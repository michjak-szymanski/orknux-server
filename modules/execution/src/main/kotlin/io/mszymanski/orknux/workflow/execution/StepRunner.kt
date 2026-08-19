package io.mszymanski.orknux.workflow.execution

import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.OffsetDateTime

/** What one step did, as the thing carrying the run needs to know it. */
data class StepOutcome(
    val status: StepStatus,
    val output: String? = null,
    val halt: Boolean = false,
    /** Which way out of a condition the run went; null for every other node. */
    val branch: EdgeBranch? = null,
    /**
     * Set on [StepStatus.WAITING]: how long to leave the step alone before
     * running it again. Whatever is carrying the run spends it.
     */
    val resumeAfter: Duration? = null,
)

/**
 * Implemented by a failure that knows whether it is worth trying again.
 *
 * Declared here so a runner in another module can say so without the execution
 * module knowing what a Slack channel is.
 */
interface PermanentFailure {
    val permanent: Boolean
}

/**
 * Raised when a step could not be carried out; carries a message worth showing.
 *
 * @param permanent whether trying again could ever give a different answer. A
 *   channel that does not exist will not start existing on the second attempt,
 *   and a run that retries it three times only takes longer to say so; a network
 *   that dropped might well work. Temporal reads this to decide whether to
 *   retry, and the inline engine has no retries to decide about.
 */
class StepFailedException(
    val nodeKey: String,
    reason: String,
    override val permanent: Boolean = false,
) : RuntimeException(reason), PermanentFailure

/**
 * The work of one step, and the two ways a run ends.
 *
 * Everything here is a single step's worth of work with its own writes, so it
 * can be called by the inline engine in a loop or by a Temporal activity one
 * call at a time — including a second time, if the first attempt died before
 * saying what happened.
 */
@Service
class StepRunner(
    private val executions: WorkflowExecutionRepository,
    private val steps: ExecutionStepRepository,
    private val log: RunLogger,
    /** Ordered by `@Order`; the first that claims a kind runs it. */
    private val runners: List<NodeRunner>,
) {

    /**
     * Carries out one step and records what it did.
     *
     * A step that parks is not finished, so this is also how a parked step is
     * picked back up: the same call, with the same input, once the caller has
     * waited as long as the last outcome asked for.
     *
     * @throws StepFailedException if the runner could not; the step is left
     *   failed and the caller decides whether to try again.
     */
    fun runStep(executionId: Long, nodeKey: String): StepOutcome {
        val step = stepOf(executionId, nodeKey)
        val execution = executionOf(executionId)

        // Read here rather than handed in: the payload belongs to the run, not
        // to the message that asked for the step. A parked step picked up by
        // another worker reads the same thing this one did.
        val input = execution.carried ?: execution.input

        // A step that parked keeps the deadline it recorded, so a wait resumed
        // on another worker does not start its clock again. RUNNING with a
        // deadline is a wait whose worker died mid-question, which is the same
        // wait; anything else is this step starting.
        val resuming = step.waitUntil != null &&
            (step.status == StepStatus.WAITING || step.status == StepStatus.RUNNING)

        if (!resuming) {
            step.startedAt = OffsetDateTime.now()
            step.waitUntil = null
        }
        step.status = StepStatus.RUNNING
        step.finishedAt = null
        step.error = null
        step.input = input
        steps.save(step)

        val result = try {
            // What began the run, alongside what it is carrying now. The second
            // is what lets a step deep in the graph still ask about the event
            // that started everything.
            runnerFor(step.kind).run(step, input, execution.input)
        } catch (failure: Exception) {
            // A runner that knows its failure is final says so, and that travels
            // with the step: Temporal reads it and stops retrying something that
            // cannot come out differently.
            val permanent = (failure as? PermanentFailure)?.permanent == true
            failStep(executionId, step, failure.message ?: failure::class.simpleName ?: "the step failed", permanent)
        }

        if (result.status == StepStatus.WAITING) {
            step.status = StepStatus.WAITING
            steps.save(step)

            // Once, on the way in: a wait that asks the same question every
            // thirty seconds should not fill the log with what it is still doing.
            if (!resuming) {
                log.write(executionId, nodeKey, LogLevel.INFO, result.output ?: "${step.name} is waiting")
            }
            // It has produced nothing, so the next attempt is handed what this
            // one was.
            return StepOutcome(StepStatus.WAITING, input, resumeAfter = result.resumeAfter)
        }

        step.status = result.status
        step.output = result.output
        // Which way out of a condition the run went, written down rather than
        // only acted on: a later run that starts partway down this graph has to
        // know which edges this one took, and the answer is not recoverable
        // from the statuses alone - a node with no runtime is skipped too.
        step.branch = result.branch
        step.finishedAt = OffsetDateTime.now()
        steps.save(step)

        // What the run carries from here on. Written once, where it is read
        // from, so both engines carry the same thing and neither has to hand it
        // to the other.
        if (result.status == StepStatus.COMPLETED) {
            execution.carried = Payloads.carry(input, result.output)
            executions.save(execution)
        }

        log.write(
            executionId,
            nodeKey,
            if (result.status == StepStatus.COMPLETED) LogLevel.SUCCESS else LogLevel.INFO,
            result.output ?: "${step.name} ${result.status.name.lowercase()}",
        )
        return StepOutcome(result.status, result.output, result.halt, result.branch)
    }

    /**
     * Records a step the engine gave up on, the way a thrown runner is recorded.
     *
     * An engine that will not wait as long as a step asked ends up here, so a
     * step that failed looks the same whichever side gave up on it.
     */
    fun failStep(executionId: Long, nodeKey: String, reason: String, permanent: Boolean = false): Nothing =
        failStep(executionId, stepOf(executionId, nodeKey), reason, permanent)

    private fun failStep(
        executionId: Long,
        step: ExecutionStep,
        reason: String,
        permanent: Boolean = false,
    ): Nothing {
        step.status = StepStatus.FAILED
        step.error = reason.take(ERROR_LENGTH)
        step.finishedAt = OffsetDateTime.now()
        steps.save(step)
        log.write(executionId, step.nodeKey, LogLevel.ERROR, "${step.name} failed: $reason")
        throw StepFailedException(step.nodeKey, reason, permanent)
    }

    /**
     * Records a step the run went past, because the branch that reaches it was
     * not the one taken.
     *
     * Written down rather than left pending: "skipped, the condition went the
     * other way" is a fact about what happened, and a step silently absent from
     * a run is the kind of gap somebody debugging spends an afternoon on.
     */
    fun skipStep(executionId: Long, nodeKey: String, reason: String): ExecutionStep {
        val step = stepOf(executionId, nodeKey)
        step.status = StepStatus.SKIPPED
        step.output = reason.take(ERROR_LENGTH)
        step.startedAt = OffsetDateTime.now()
        step.finishedAt = OffsetDateTime.now()
        log.write(executionId, nodeKey, LogLevel.INFO, "${step.name} skipped: $reason")
        return steps.save(step)
    }

    /** Stops the run. The steps it never reached stay pending, because they were. */
    fun failRun(executionId: Long, nodeKey: String, reason: String, unreached: Int): WorkflowExecution {
        val execution = executionOf(executionId)
        execution.status = ExecutionStatus.FAILED
        execution.error = reason.take(ERROR_LENGTH)
        execution.finishedAt = OffsetDateTime.now()

        log.write(
            executionId,
            null,
            LogLevel.ERROR,
            "${execution.workflowName} stopped at $nodeKey with $unreached steps unreached",
        )
        return executions.save(execution)
    }

    /**
     * Ends the run as completed.
     *
     * @param stoppedAt the node that decided there was nothing further to do,
     *   and [reason] what it said. Both null when the run simply reached the
     *   end, which is the ordinary case.
     */
    fun finishRun(executionId: Long, stoppedAt: String? = null, reason: String? = null): WorkflowExecution {
        val execution = executionOf(executionId)
        execution.status = ExecutionStatus.COMPLETED
        execution.finishedAt = OffsetDateTime.now()
        execution.stoppedAtNodeKey = stoppedAt
        execution.stoppedReason = reason?.take(ERROR_LENGTH)

        val ending = if (reason == null) {
            "${execution.workflowName} finished"
        } else {
            "${execution.workflowName} stopped after $stoppedAt: $reason"
        }
        log.write(executionId, null, if (reason == null) LogLevel.SUCCESS else LogLevel.INFO, ending)
        return executions.save(execution)
    }

    private fun stepOf(executionId: Long, nodeKey: String) =
        steps.findByExecutionIdAndNodeKey(executionId, nodeKey)
            ?: error("Execution $executionId has no step $nodeKey")

    private fun executionOf(id: Long) = executions.findByIdOrNull(id) ?: throw ExecutionNotFoundException(id)

    private fun runnerFor(kind: NodeKind): NodeRunner =
        runners.firstOrNull { it.supports(kind) } ?: error("No runner claims $kind nodes")

    private companion object {
        /** Matches the column. */
        const val ERROR_LENGTH = 1000
    }
}
