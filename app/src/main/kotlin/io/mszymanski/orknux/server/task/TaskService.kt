package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.connector.model.ModelKind
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * What is being asked of an agent, as anything that starts a task supplies it.
 *
 * A value rather than a set of arguments because this is the API another feature
 * calls. "Start by AI" on an issue fills it in from the issue, a Designer module
 * will fill it in from a design, and the Tasks page fills it in from a box
 * somebody typed in - and none of those should have to know the order of six
 * parameters or which of them are optional.
 */
data class NewTask(
    val workspaceId: Long,
    val prompt: String,
    /** Null takes the first line of the prompt, which is what somebody typing one means. */
    val title: String? = null,
    /** The agent to do it. Null means a bare model, which starts with no grants. */
    val agentId: Long? = null,
    /** Null takes the agent's own model. */
    val modelId: Long? = null,
    /** What it came from, where something started it. The link back, and the audience. */
    val issueId: Long? = null,
    /** Whoever asked for it, by username. They hear about it. */
    val createdBy: String,
)

/**
 * Starting a task, and everything a person does to one afterwards.
 *
 * This is the door, and it is a service rather than something only the page can
 * reach on purpose: #230 puts a "Start by AI" button on an issue and a Designer
 * module will start tasks of its own, so creating one has to be a call somebody
 * else can make with a prompt, who is to do it and a link back. The page is one
 * caller of this and not the definition of it.
 *
 * Nothing here decides who may do anything. Access is the controller's, in the
 * order this codebase always does it: check, act, record.
 */
@Service
class TaskService(
    private val tasks: TaskRepository,
    private val requests: TaskRequestRepository,
    private val grants: TaskGrantRepository,
    private val agents: AgentRepository,
    private val models: ModelService,
    private val sessions: LlmSessionRecorder,
    private val engine: TaskEngine,
    private val properties: TaskProperties,
    /** Where the turn count is decided now: the workspace, falling back to the file. */
    private val workspaces: WorkspaceRepository,
) {

    /**
     * Records the task, opens its log and sets it going.
     *
     * The session is opened and the prompt written into it before anything is
     * started, so a task opened a second later shows what was asked even though
     * no turn has been taken. A page that draws an empty log for the first
     * minute is one nobody can tell from a broken one.
     */
    /**
     * How many turns this workspace's next task gets.
     *
     * The workspace where it has said, and the installation's own number where
     * it has not - which is every workspace until somebody fills it in, so
     * nothing changed for anybody by this becoming a setting. Read here and
     * copied onto the row below, the same as the working time beside it:
     * raising it does not extend a task already going.
     */
    private fun turnsFor(workspaceId: Long): Int =
        workspaces.findByIdOrNull(workspaceId)?.taskMaxTurns ?: properties.maxTurns

    @Transactional
    fun start(input: NewTask): Task {
        val prompt = input.prompt.trim()
        if (prompt.isEmpty()) throw TaskPromptMissingException()

        val agent = input.agentId?.let { id ->
            agents.findByIdOrNull(id)?.takeIf { it.workspaceId == input.workspaceId }
                ?: throw TaskNotRunnableException("That agent is not one of this workspace's")
        }
        if (agent != null && !agent.enabled) throw TaskNotRunnableException("${agent.name} is switched off")

        val modelId = input.modelId ?: agent?.modelId ?: throw TaskWorkerMissingException()
        val model = models.models(input.workspaceId).firstOrNull { it.id == modelId }
            ?: throw TaskNotRunnableException("That model is not one this workspace can reach")
        if (!model.enabled) throw TaskNotRunnableException("${model.name} is switched off")
        if (model.kind != ModelKind.CHAT) throw TaskNotRunnableException("${model.name} does not answer questions")

        val task = tasks.save(
            Task(
                workspaceId = input.workspaceId,
                title = titleFor(input.title, prompt),
                prompt = prompt,
                agentId = agent?.id,
                modelId = modelId,
                issueId = input.issueId,
                createdBy = input.createdBy,
                turnsAllowed = turnsFor(input.workspaceId),
                secondsAllowed = properties.workingTime.toSeconds(),
            ),
        )
        val taskId = requireNotNull(task.id)

        task.sessionId = sessions.open(input.workspaceId, SESSION_PREFIX, taskId.toString())
        sessions.userSaid(requireNotNull(task.sessionId), input.createdBy, prompt)
        tasks.save(task)

        engine.begin(taskId)
        return task
    }

    /**
     * Gives the task the one thing it asked for, and lets it carry on.
     *
     * The grant is written against the task and not against the agent, which is
     * the whole of the difference between this and ticking a box on an agent's
     * page. It is also why there is no "approve everything": what is written
     * down is the capability that was asked for, the thing it names, and who
     * said yes.
     */
    @Transactional
    fun approve(requestId: Long, by: String): Task {
        val (request, task) = settling(requestId)
        if (request.kind != TaskRequestKind.PERMISSION) {
            throw TaskRequestSettledException()
        }

        grants.save(
            TaskGrant(
                taskId = task.id!!,
                requestId = request.id,
                capability = requireNotNull(request.capability),
                subject = request.subject,
                grantedBy = by,
            ),
        )
        settle(request, TaskDecision.GRANTED, by, answer = null)
        return resume(task, told(request, granted = true, by = by))
    }

    /** Says no, and lets the task carry on without it or give up saying so. */
    @Transactional
    fun refuse(requestId: Long, by: String): Task {
        val (request, task) = settling(requestId)
        if (request.kind != TaskRequestKind.PERMISSION) throw TaskRequestSettledException()
        settle(request, TaskDecision.REFUSED, by, answer = null)
        return resume(task, told(request, granted = false, by = by))
    }

    /** Answers the question it stopped to ask. */
    @Transactional
    fun answer(requestId: Long, said: String, by: String): Task {
        val (request, task) = settling(requestId)
        if (request.kind != TaskRequestKind.QUESTION) throw TaskRequestSettledException()
        val words = said.trim()
        if (words.isEmpty()) throw TaskRequestSettledException()
        settle(request, TaskDecision.ANSWERED, by, answer = words)
        return resume(task, words)
    }

    /**
     * Stops it.
     *
     * A task that is running is stopped where it stands: the turn in flight
     * finishes and the loop sees the status and does not take another. Nothing
     * a turn already did is undone, which is the honest answer - a command that
     * has run on a machine has run.
     */
    @Transactional
    fun stop(taskId: Long, by: String): Task {
        val task = tasks.findByIdOrNull(taskId) ?: throw TaskNotFoundException(taskId)
        if (task.status.over) return task

        requests.findFirstByTaskIdAndDecisionIsNullOrderByAskedAtAscIdAsc(taskId)?.let {
            settle(it, TaskDecision.REFUSED, by, answer = null)
        }
        task.status = TaskStatus.STOPPED
        task.waitingUntil = null
        task.endedBecause = "stopped by $by"
        task.finishedAt = OffsetDateTime.now()
        tasks.save(task)
        task.sessionId?.let { sessions.note(it, "The task was stopped by $by.") }
        return task
    }

    /** The request and its task, or why neither can be decided on. */
    private fun settling(requestId: Long): Pair<TaskRequest, Task> {
        val request = requests.findByIdOrNull(requestId) ?: throw TaskRequestNotFoundException(requestId)
        if (!request.open) throw TaskRequestSettledException()
        val task = tasks.findByIdOrNull(request.taskId) ?: throw TaskNotFoundException(request.taskId)
        if (task.status != TaskStatus.WAITING) throw TaskRequestSettledException()
        return request to task
    }

    private fun settle(request: TaskRequest, decision: TaskDecision, by: String, answer: String?) {
        request.decision = decision
        request.decidedBy = by
        request.decidedAt = OffsetDateTime.now()
        request.answer = answer
        requests.save(request)
    }

    /**
     * Puts the task back to work with what it was told.
     *
     * The words go into the session as a turn from the person, so the next turn
     * reads them exactly the way it reads everything else - there is no second
     * channel by which a task learns something, and no state carried anywhere
     * but the transcript.
     */
    private fun resume(task: Task, said: String): Task {
        task.status = TaskStatus.RUNNING
        task.waitingUntil = null
        tasks.save(task)
        task.sessionId?.let { sessions.userSaid(it, TASK, said) }
        engine.nudge(requireNotNull(task.id))
        return task
    }

    /** What the agent is told about a decision, in words it can act on. */
    private fun told(request: TaskRequest, granted: Boolean, by: String): String {
        val what = buildString {
            append(request.capability?.name?.lowercase() ?: "what you asked for")
            request.subject?.let { append(' ').append(it) }
        }
        return if (granted) {
            "$by has given you $what for this task. Carry on, and use it only for this."
        } else {
            "$by has refused you $what. Carry on without it, or call task_done and say what you could not do."
        }
    }

    /**
     * A line to find it by.
     *
     * The first line of the prompt, cut to what the column holds. Somebody
     * typing a task writes what they want first and the detail after it, so the
     * first line is the name they would have given it anyway.
     */
    private fun titleFor(given: String?, prompt: String): String {
        val chosen = given?.trim()?.takeIf { it.isNotEmpty() }
            ?: prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: prompt
        return chosen.take(Task.TITLE_LENGTH)
    }

    private companion object {
        /** What a task's sessions are keyed under, so they are findable as a family. */
        const val SESSION_PREFIX = "task"

        /** How the machinery signs a line it wrote in the transcript. */
        const val TASK = "task"
    }
}
