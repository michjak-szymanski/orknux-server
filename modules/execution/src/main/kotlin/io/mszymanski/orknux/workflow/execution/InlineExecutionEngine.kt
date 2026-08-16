package io.mszymanski.orknux.workflow.execution

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Runs a workflow on the calling thread, with no retries and no way to resume:
 * a restart part way through leaves the run recorded as running for ever.
 *
 * It is what runs when `orknux.temporal.enabled` is false — a development
 * machine, or a deployment that would rather not run a Temporal service — and
 * what the tests use, since a test that needs a service to be up is a test that
 * fails for reasons of its own. Anything that has to survive anything should be
 * on Temporal.
 *
 * A step that parks is waited out here on the calling thread rather than on a
 * timer, so what a run may wait for in total is bounded by
 * [InlineExecutionProperties.maxWait]. A workflow that has to wait for an hour
 * works on Temporal and fails here, which is the honest answer.
 */
@Service
@ConditionalOnProperty(name = ["orknux.temporal.enabled"], havingValue = "false", matchIfMissing = true)
@EnableConfigurationProperties(InlineExecutionProperties::class)
class InlineExecutionEngine(
    private val planner: ExecutionPlanner,
    private val steps: StepRunner,
    private val properties: InlineExecutionProperties,
) : ExecutionEngine {

    override fun start(
        workspaceId: Long,
        workflowId: Long,
        trigger: ExecutionTrigger,
        input: String?,
    ): WorkflowExecution {
        val plan = planner.plan(workspaceId, workflowId, trigger, input)
        val executionId = requireNotNull(plan.execution.id)
        var handOver = input

        for ((index, step) in plan.steps.withIndex()) {
            val outcome = try {
                runToDecision(executionId, step.nodeKey, handOver)
            } catch (failure: StepFailedException) {
                log.warn("Execution {} failed at {}", executionId, step.nodeKey, failure)
                return steps.failRun(
                    executionId = executionId,
                    nodeKey = step.nodeKey,
                    reason = failure.message ?: "the step failed",
                    unreached = plan.steps.size - index - 1,
                )
            }

            // A step that did nothing passes on what it was handed, so a node
            // with no runtime yet does not cut the run in half.
            if (outcome.status == StepStatus.COMPLETED) handOver = outcome.output

            // A condition that did not hold ends the run: what is left has no
            // reason to happen, and the run did not fail.
            if (outcome.halt) {
                log.info("Execution {} stopped at {}: the run has nothing further to do", executionId, step.nodeKey)
                return steps.finishRun(executionId, stoppedAt = step.nodeKey, reason = outcome.output)
            }
        }

        return steps.finishRun(executionId)
    }

    /**
     * Runs one step, and keeps running it for as long as it parks.
     *
     * The delay is spent on this thread, which is the whole of the difference
     * between this engine and the Temporal one: a durable timer costs nothing
     * while it runs down, and this costs a thread for every second of it. So it
     * is bounded — a run that wants to wait longer than the engine allows fails
     * where it waited, and says what would have carried it.
     */
    private fun runToDecision(executionId: Long, nodeKey: String, input: String?): StepOutcome {
        var waited = Duration.ZERO

        while (true) {
            val outcome = steps.runStep(executionId, nodeKey, input)
            if (outcome.status != StepStatus.WAITING) return outcome

            // Unreachable — a parked node says when to come back — but leaving
            // the step open and carrying on would be worse than stopping.
            val pause = outcome.resumeAfter
                ?: steps.failStep(executionId, nodeKey, "$nodeKey parked without saying when to come back")

            waited += pause
            if (waited > properties.maxWait) {
                steps.failStep(
                    executionId,
                    nodeKey,
                    "$nodeKey asked to wait longer than the inline engine allows (${properties.maxWait}); " +
                        "a wait that long needs orknux.temporal.enabled",
                )
            }
            log.debug("Execution {} is waiting {}s at {}", executionId, pause.toSeconds(), nodeKey)
            Thread.sleep(pause.toMillis())
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(InlineExecutionEngine::class.java)
    }
}

/** How patient the inline engine is, which is only ever a development concern. */
@ConfigurationProperties(prefix = "orknux.execution.inline")
data class InlineExecutionProperties(
    /**
     * The longest one run may spend parked, added up over all its waits.
     *
     * Temporal waits with a timer and needs no such bound — a run there waits
     * for as long as the run timeout allows. This engine waits with the thread
     * carrying the run, so an unbounded wait is an unbounded thread.
     */
    val maxWait: Duration = Duration.ofMinutes(5),
)
