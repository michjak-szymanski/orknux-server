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
    /**
     * Set on [StepStatus.WAITING]: how long to leave the step alone before
     * running it again. Whatever is carrying the run spends it.
     */
    val resumeAfter: Duration? = null,
)

/** Raised when a step could not be carried out; carries a message worth showing. */
class StepFailedException(val nodeKey: String, reason: String) : RuntimeException(reason)

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
    fun runStep(executionId: Long, nodeKey: String, input: String?): StepOutcome {
        val step = stepOf(executionId, nodeKey)

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
            runnerFor(step.kind).run(step, input)
        } catch (failure: Exception) {
            failStep(executionId, step, failure.message ?: failure::class.simpleName ?: "the step failed")
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
        step.finishedAt = OffsetDateTime.now()
        steps.save(step)

        log.write(
            executionId,
            nodeKey,
            if (result.status == StepStatus.COMPLETED) LogLevel.SUCCESS else LogLevel.INFO,
            result.output ?: "${step.name} ${result.status.name.lowercase()}",
        )
        return StepOutcome(result.status, result.output, result.halt)
    }

    /**
     * Records a step the engine gave up on, the way a thrown runner is recorded.
     *
     * An engine that will not wait as long as a step asked ends up here, so a
     * step that failed looks the same whichever side gave up on it.
     */
    fun failStep(executionId: Long, nodeKey: String, reason: String): Nothing =
        failStep(executionId, stepOf(executionId, nodeKey), reason)

    private fun failStep(executionId: Long, step: ExecutionStep, reason: String): Nothing {
        step.status = StepStatus.FAILED
        step.error = reason.take(ERROR_LENGTH)
        step.finishedAt = OffsetDateTime.now()
        steps.save(step)
        log.write(executionId, step.nodeKey, LogLevel.ERROR, "${step.name} failed: $reason")
        throw StepFailedException(step.nodeKey, reason)
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
