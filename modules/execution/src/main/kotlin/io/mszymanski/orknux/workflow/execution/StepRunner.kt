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
 * How many attempts a node is allowed, and how long it waits between them.
 *
 * Visible, and with the arithmetic on it rather than inline where it is spent,
 * so the curve can be asserted without a test that actually sits out the waits
 * it is checking.
 */
data class RetryPolicy(
    val attempts: Int,
    /** The wait before the first retry, and the whole of it under [RetryBackoff.FIXED]. */
    val backoff: Duration,
    val curve: RetryBackoff = RetryBackoff.FIXED,
) {

    /**
     * How long to leave the step alone, having spent [attemptsSpent] attempts.
     *
     * Doubling counts from the first retry, so the number written on the node is
     * what it waits before its second attempt on either curve; only the waits
     * after that one differ. Which also means switching a node to doubling never
     * makes its first retry later than it was.
     */
    fun waitAfter(attemptsSpent: Int): Duration {
        if (curve != RetryBackoff.EXPONENTIAL) return backoff
        val doublings = (attemptsSpent - 1).coerceIn(0, MAX_DOUBLINGS)
        val grown = backoff.multipliedBy(1L shl doublings)
        return if (grown > MAX_WAIT) MAX_WAIT else grown
    }

    companion object {

        /**
         * The longest a single wait may come to, whatever the curve.
         *
         * The same hour the editor already refuses to take more than for a fixed
         * wait: an hour is what one wait is allowed to cost the person waiting,
         * and which curve arrived at it does not change that. Left uncapped, ten
         * attempts doubling off that hour is three weeks of run - and the two
         * numbers that produced it are "3600" and "10", neither of which looks
         * like three weeks to whoever typed them.
         */
        val MAX_WAIT: Duration = Duration.ofHours(1)

        /**
         * Enough doublings to reach the cap from a wait of one second, and few
         * enough that the shift stays a number.
         */
        private const val MAX_DOUBLINGS = 30
    }
}

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
 *   that dropped might well work. Read twice: by a node's own retry policy,
 *   which will not spend an attempt on something already settled, and by
 *   Temporal, which will not retry an activity that says so. A policy the step
 *   has already exhausted is settled by the same definition, which is what
 *   stops the two layers being multiplied together.
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
    /**
     * Counted here because this is where both engines end a run: the inline one
     * calls [failRun] and [finishRun] directly, and Temporal reaches them through
     * an activity. Counting in either engine would count one deployment's runs.
     */
    private val metrics: WorkflowRunMetrics,
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
            // A resumed wait is the same attempt asking again; anything else is
            // a new one, which is what a retry policy is counting.
            step.attempts += 1
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
            val reason = failure.message ?: failure::class.simpleName ?: "the step failed"

            val policy = retryOf(step)
            if (policy != null && !permanent && step.attempts < policy.attempts) {
                return parkForRetry(executionId, step, input, reason, policy)
            }

            /*
             * A node's own policy is the whole of its retries.
             *
             * Temporal retries an activity three times of its own accord, so a
             * policy left to throw would be multiplied by three - five attempts
             * asked for and fifteen performed. Exhausting the policy settles the
             * failure by definition: there is nothing left to try, which is
             * exactly what `permanent` means to the activity that reads it.
             * A node with no policy keeps the arrangement it has always had.
             */
            failStep(executionId, step, reason, permanent || policy != null)
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
     * The node's retry policy, or null where it has none.
     *
     * One attempt is no policy at all rather than a policy of one, so a node
     * carrying the number the editor shows by default costs nothing.
     */
    private fun retryOf(step: ExecutionStep): RetryPolicy? = step.retryAttempts
        ?.takeIf { it > 1 }
        ?.let {
            RetryPolicy(
                attempts = it,
                backoff = Duration.ofSeconds((step.retryBackoffSeconds ?: 0).coerceAtLeast(0).toLong()),
                // Null is the fixed wait every step had before a node could ask
                // for anything else.
                curve = step.retryBackoff ?: RetryBackoff.FIXED,
            )
        }

    /**
     * Parks a failed attempt instead of throwing, so the next one is asked for
     * the way a wait is.
     *
     * Retrying through the wait both engines already honour is what keeps the
     * policy from being applied twice: the activity answers normally, so
     * Temporal has no failure to retry on top, and the backoff runs down on a
     * durable timer rather than inside an activity holding a worker and burning
     * its start-to-close timeout.
     */
    private fun parkForRetry(
        executionId: Long,
        step: ExecutionStep,
        input: String?,
        reason: String,
        policy: RetryPolicy,
    ): StepOutcome {
        step.status = StepStatus.WAITING
        step.error = reason.take(ERROR_LENGTH)
        // The deadline a parked runner wrote belongs to the attempt that wrote
        // it; the next attempt starts its own clock.
        step.waitUntil = null
        steps.save(step)

        // What this attempt in particular earns, which on a doubling curve is
        // not what the last one waited - so it is worked out here and then both
        // written down and spent, rather than the log quoting the node's number
        // while the run waits some other length of time.
        val wait = policy.waitAfter(step.attempts)
        log.write(
            executionId,
            step.nodeKey,
            LogLevel.INFO,
            "${step.name} failed on attempt ${step.attempts} of ${policy.attempts}: $reason. " +
                "Trying again in ${wait.toSeconds()}s",
        )
        // It produced nothing, so the next attempt is handed what this one was.
        return StepOutcome(StepStatus.WAITING, input, resumeAfter = wait)
    }

    /**
     * Records that a failed step's failure edge is the way the run went on.
     *
     * Written down for the same reason a condition's answer is: a later run
     * starting partway down cannot otherwise tell a step whose failure was
     * handled from one that stopped the run where it stood.
     */
    fun recordFailureExit(executionId: Long, nodeKey: String): ExecutionStep {
        val step = stepOf(executionId, nodeKey)
        step.branch = EdgeBranch.FAILURE
        log.write(executionId, nodeKey, LogLevel.INFO, "${step.name} failed; the run is taking its failure branch")
        return steps.save(step)
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
        /*
         * Read before the status is changed, because a Temporal activity is
         * delivered at least once: an attempt that ended the run and then died
         * before saying so arrives again, and the second one must not be a
         * second failure. Writing the same ending twice is harmless; counting
         * it twice is a failure rate nobody can trust.
         */
        val counts = execution.status == ExecutionStatus.RUNNING
        execution.status = ExecutionStatus.FAILED
        execution.error = reason.take(ERROR_LENGTH)
        execution.finishedAt = OffsetDateTime.now()

        log.write(
            executionId,
            null,
            LogLevel.ERROR,
            "${execution.workflowName} stopped at $nodeKey with $unreached steps unreached",
        )
        if (counts) metrics.runFailed()
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
        // See [failRun]: an activity that is delivered twice ends the run twice.
        val counts = execution.status == ExecutionStatus.RUNNING
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
        if (counts) metrics.runCompleted()
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
