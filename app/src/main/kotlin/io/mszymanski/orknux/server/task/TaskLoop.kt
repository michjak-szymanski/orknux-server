package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.server.chat.AgentBriefing
import io.mszymanski.orknux.server.chat.AgentConversation
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.SessionMemoryBudgets
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.OffsetDateTime

/**
 * How far a task got in one go, as whatever is carrying it needs to know.
 *
 * The same three answers a workflow step gives — it did some work, it is parked
 * and here is when to come back, or there is nothing further to do — because
 * they are the same three answers, and the engines above this were written
 * against them.
 */
sealed interface TaskTurn {

    /** A turn was taken and the task is still going. Ask again straight away. */
    data object Working : TaskTurn

    /** Stopped for a person. Ask again after this, and not before. */
    data class Parked(val after: Duration) : TaskTurn

    /** Finished, failed, given up on or stopped. Nothing further to ask. */
    data object Over : TaskTurn
}

/**
 * One turn of a task, and everything that decides whether there is another.
 *
 * A turn is one round of the ordinary agent conversation — the same
 * [AgentConversation] a chat and a workflow's agent node go through, with the
 * same tool loop inside it, writing into the same LLM session. What this adds is
 * the outer loop: it decides what to put in front of the model, what the answer
 * meant, whether the task may have another go, and what to do when the agent
 * stops to ask a person something.
 *
 * **The task's memory is the session, not this class.** Nothing is carried in a
 * field between two turns; each one rebuilds what the model sees out of
 * `llm_session_event`. That is what lets a task be picked up by another process
 * after a restart with nothing lost, and it is why the loop can be re-entered by
 * a Temporal activity that may be delivered twice.
 *
 * It holds no transaction. A turn calls a model and then whatever tools that
 * model asks for, which can be minutes; a database connection held for that long
 * is one nobody else has. Every write here is its own.
 */
@Service
class TaskLoop(
    private val tasks: TaskRepository,
    private val requests: TaskRequestRepository,
    private val conversation: AgentConversation,
    private val briefings: AgentBriefing,
    private val sessions: LlmSessionRecorder,
    private val budgets: SessionMemoryBudgets,
    private val worker: TaskWorker,
    private val tools: TaskTools,
    private val news: TaskNewsDesk,
    private val properties: TaskProperties,
) {

    /**
     * Takes the task one turn further, or says why it did not.
     *
     * Safe to call on a task in any state, including one that is already over:
     * a Temporal activity is delivered at least once, and a second delivery must
     * not start a finished task again.
     */
    fun advance(taskId: Long): TaskTurn {
        val task = tasks.findByIdOrNull(taskId) ?: return TaskTurn.Over

        when (task.status) {
            TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.STOPPED -> return TaskTurn.Over

            TaskStatus.WAITING -> return waiting(task)

            TaskStatus.QUEUED -> {
                task.status = TaskStatus.RUNNING
                task.startedAt = OffsetDateTime.now()
                tasks.save(task)
            }

            TaskStatus.RUNNING -> Unit
        }

        spent(task)?.let { return end(task, TaskStatus.FAILED, it, said = null) }

        return try {
            take(task)
        } catch (parked: TaskParked) {
            park(task, parked)
        } catch (finished: TaskFinished) {
            end(task, TaskStatus.DONE, "finished", finished.summary)
        } catch (unrunnable: TaskNotRunnableException) {
            // The agent was deleted, switched off, or its model is gone. Not
            // something another turn will fix, and not a failure of the model.
            end(task, TaskStatus.FAILED, unrunnable.message ?: "it could not be run", said = null)
        }
    }

    /** One round of the agent's own conversation, and what its answer meant. */
    private fun take(task: Task): TaskTurn {
        val taskId = requireNotNull(task.id)
        val session = task.sessionId ?: return end(task, TaskStatus.FAILED, "its log is gone", said = null)
        val working = worker.of(task)
        val agent = working.agent
        val budget = budgets.budget(agent.memoryShare, task.workspaceId, working.modelId)

        val turns = buildList {
            add(ChatTurn("system", briefing(agent.let(briefings::of), task)))
            addAll(sessions.remembered(session, budget))
            addAll(sessions.recalled(session, budget))
        }

        val begun = System.nanoTime()
        val answer = try {
            conversation.answer(working.modelId, agent, turns, session, tools.shed())
        } finally {
            record(task, Duration.ofNanos(System.nanoTime() - begun))
        }

        return when (answer) {
            /*
             * Text and no `task_done`, which is the agent reporting progress.
             * AgentConversation has already written what it said into the
             * session, so all that is left is to ask it to carry on - written
             * down as well, because a transcript in which the agent answers
             * twice in a row with nothing in between reads as a model talking
             * to itself, and because two turns of one role running together is
             * a shape some providers refuse.
             */
            is ChatCompletion.Answered -> {
                sessions.userSaid(session, TASK, CARRY_ON)
                log.debug("Task {} took turn {}", taskId, task.turnsSpent)
                TaskTurn.Working
            }

            /*
             * The model could not answer. The task ends here rather than trying
             * again: what the loop would put in front of it next time is what it
             * has just refused, and a task that quietly retries an outage for
             * forty turns is the bill this feature exists to bound. The reason
             * is the model's own words, in the log, for whoever reads it.
             */
            is ChatCompletion.Failed ->
                end(task, TaskStatus.FAILED, "the model could not answer: ${answer.reason}", said = null)

            // The loop inside runs tools to a conclusion, so nothing here is
            // still asking for one.
            is ChatCompletion.CalledTools ->
                end(task, TaskStatus.FAILED, "the model asked for a tool that could not be run", said = null)
        }
    }

    /**
     * What the model is told about being a task, on top of its agent's briefing.
     *
     * Said every turn rather than once at the start, because it is a system turn
     * and system turns are not remembered - only what was *said* is. It is also
     * where the bounds are named: a model that knows it has forty turns spends
     * them differently from one that thinks it has for ever.
     */
    private fun briefing(agentBriefing: String?, task: Task): String = buildString {
        agentBriefing?.let { appendLine(it).appendLine() }
        appendLine(
            "You are working on a task on your own. Nobody is watching between your turns, so do the work " +
                "rather than describing what you would do, and use your tools to actually carry it out.",
        )
        appendLine(
            "When the work is finished, call task_done with a summary. Until you do, whatever you write is " +
                "recorded as progress and you will be asked to carry on.",
        )
        appendLine(
            "If you need something you have not been given, call task_request_permission. If you cannot sensibly " +
                "go on without knowing something - most often how what you are producing should be delivered - " +
                "call task_ask. Both stop the task until a person answers, so use them when you mean them.",
        )
        appendLine(
            "You have taken ${task.turnsSpent} of ${task.turnsAllowed} turns. When they run out the task stops " +
                "unfinished, so if you are running short, finish what you can and say so.",
        )
    }

    /** What a task that is standing still should do next. */
    private fun waiting(task: Task): TaskTurn {
        val patience = task.waitingUntil
        if (patience != null && OffsetDateTime.now().isAfter(patience)) {
            val asked = requests.findFirstByTaskIdAndDecisionIsNullOrderByAskedAtAscIdAsc(requireNotNull(task.id))
            asked?.let {
                it.decision = TaskDecision.REFUSED
                it.decidedBy = TASK
                it.decidedAt = OffsetDateTime.now()
                requests.save(it)
            }
            return end(task, TaskStatus.FAILED, "nobody answered", said = null)
        }
        return TaskTurn.Parked(pollFor(task))
    }

    /**
     * How long to leave a parked task before looking again.
     *
     * Short at first and long afterwards, because the two are answering
     * different questions. Somebody who approves a request while looking at the
     * page expects the task to move, so the first few minutes are polled
     * closely; a task nobody has come back to in an hour is one that will be
     * answered tomorrow, and asking every thirty seconds until then writes a
     * week of history for a fact that has not changed.
     */
    private fun pollFor(task: Task): Duration {
        val since = task.waitingUntil?.minus(properties.patience) ?: return properties.pollWhileWaiting
        val waited = Duration.between(since, OffsetDateTime.now())
        return if (waited < properties.pollWhileWaiting.multipliedBy(CLOSE_POLLS)) {
            properties.pollWhileWaiting
        } else {
            properties.pollWhileWaiting.multipliedBy(CLOSE_POLLS)
        }
    }

    /** Writes down what the agent stopped for, and tells whoever should hear. */
    private fun park(task: Task, parked: TaskParked): TaskTurn {
        val taskId = requireNotNull(task.id)
        val written = requests.save(
            TaskRequest(
                taskId = taskId,
                kind = parked.kind,
                capability = parked.capability,
                subject = parked.subject,
                asks = parked.asks,
            ),
        )

        task.status = TaskStatus.WAITING
        task.waitingUntil = OffsetDateTime.now().plus(properties.patience)
        tasks.save(task)

        task.sessionId?.let { sessions.note(it, parked.message.orEmpty()) }
        news.waiting(task, written)
        return TaskTurn.Parked(properties.pollWhileWaiting)
    }

    /** Adds what this turn cost, whether or not it produced anything. */
    private fun record(task: Task, took: Duration) {
        task.turnsSpent += 1
        task.workedSeconds += took.toSeconds()
        tasks.save(task)
    }

    /** Whether the task has run out of something, in the words the page shows. */
    private fun spent(task: Task): String? = when {
        task.turnsSpent >= task.turnsAllowed -> "out of turns after ${task.turnsSpent}"
        task.workedSeconds >= task.secondsAllowed -> "out of time after ${task.workedSeconds}s of work"
        else -> null
    }

    /**
     * Ends the task, one way or another.
     *
     * The one place a task stops, so what is written down when it does cannot
     * differ between the six ways of getting here.
     */
    private fun end(task: Task, status: TaskStatus, because: String, said: String?): TaskTurn {
        task.status = status
        task.endedBecause = because.take(Task.TITLE_LENGTH)
        task.outcome = said
        task.finishedAt = OffsetDateTime.now()
        tasks.save(task)

        task.sessionId?.let {
            sessions.note(it, said?.let { summary -> "The task is finished. $summary" } ?: "The task stopped: $because")
        }
        news.finished(task)
        return TaskTurn.Over
    }

    private companion object {
        /** Who the machinery speaks as in a transcript, beside the agent and the person. */
        const val TASK = "task"

        const val CARRY_ON = "Carry on with the task. Call task_done when it is finished."

        /** How many close polls a freshly parked task gets before they lengthen. */
        const val CLOSE_POLLS = 10L

        val log = LoggerFactory.getLogger(TaskLoop::class.java)
    }
}
