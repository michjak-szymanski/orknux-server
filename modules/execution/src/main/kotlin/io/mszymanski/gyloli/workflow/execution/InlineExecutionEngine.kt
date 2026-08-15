package io.mszymanski.gyloli.workflow.execution

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

/**
 * Runs a workflow on the calling thread, with no retries and no way to resume:
 * a restart part way through leaves the run recorded as running for ever.
 *
 * It is what runs when `gyloli.temporal.enabled` is false — a development
 * machine, or a deployment that would rather not run a Temporal service — and
 * what the tests use, since a test that needs a service to be up is a test that
 * fails for reasons of its own. Anything that has to survive anything should be
 * on Temporal.
 */
@Service
@ConditionalOnProperty(name = ["gyloli.temporal.enabled"], havingValue = "false", matchIfMissing = true)
class InlineExecutionEngine(
    private val planner: ExecutionPlanner,
    private val steps: StepRunner,
) : ExecutionEngine {

    override fun start(
        teamId: Long,
        workflowId: Long,
        trigger: ExecutionTrigger,
        input: String?,
    ): WorkflowExecution {
        val plan = planner.plan(teamId, workflowId, trigger, input)
        val executionId = requireNotNull(plan.execution.id)
        var handOver = input

        for ((index, step) in plan.steps.withIndex()) {
            val outcome = try {
                steps.runStep(executionId, step.nodeKey, handOver)
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

    private companion object {
        val log = LoggerFactory.getLogger(InlineExecutionEngine::class.java)
    }
}
