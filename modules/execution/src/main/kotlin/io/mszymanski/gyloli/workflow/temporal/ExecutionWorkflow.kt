package io.mszymanski.gyloli.workflow.temporal

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.mszymanski.gyloli.workflow.execution.ExecutionStatus
import io.mszymanski.gyloli.workflow.execution.StepStatus
import io.temporal.failure.ActivityFailure
import io.temporal.failure.ApplicationFailure
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

/**
 * The one workflow this service has: it walks a graph.
 *
 * The graph is an argument rather than a definition registered with Temporal,
 * so a workflow drawn in gyloli-ui needs nothing registering, deploying or
 * versioning — gyloli-server stays the only place a definition lives. It also
 * keeps this code still: what changes between runs is data, which is what makes
 * Temporal's determinism requirement cheap to live with here.
 */
@WorkflowInterface
interface ExecutionWorkflow {

    @WorkflowMethod
    fun run(plan: RunPlan): ExecutionStatus
}

/**
 * What the workflow is handed. Steps are node keys only — the rest is in the
 * database the activities write to, and a payload carries what it must.
 *
 * The Temporal SDK converts payloads with Jackson 2 and no Kotlin module, so
 * the creator is bound explicitly, as everywhere else in this service.
 */
data class RunPlan @JsonCreator constructor(
    @JsonProperty("executionId") val executionId: Long,
    @JsonProperty("workflowName") val workflowName: String,
    @JsonProperty("steps") val steps: List<String>,
    @JsonProperty("input") val input: String? = null,
)

data class RunStepCommand @JsonCreator constructor(
    @JsonProperty("executionId") val executionId: Long,
    @JsonProperty("nodeKey") val nodeKey: String,
    @JsonProperty("input") val input: String? = null,
)

data class StepReport @JsonCreator constructor(
    @JsonProperty("status") val status: StepStatus,
    @JsonProperty("output") val output: String? = null,
    /** True when the step decided the run has nothing further to do. */
    @JsonProperty("halt") val halt: Boolean = false,
)

/** What ended the run, when something decided there was nothing further to do. */
data class FinishRunCommand @JsonCreator constructor(
    @JsonProperty("executionId") val executionId: Long,
    @JsonProperty("stoppedAt") val stoppedAt: String? = null,
    @JsonProperty("reason") val reason: String? = null,
)

data class FailRunCommand @JsonCreator constructor(
    @JsonProperty("executionId") val executionId: Long,
    @JsonProperty("nodeKey") val nodeKey: String,
    @JsonProperty("reason") val reason: String,
    @JsonProperty("unreached") val unreached: Int,
)

/**
 * One step at a time, each one an activity, so a worker that dies takes at most
 * the step it was on with it — and Temporal hands that step to another worker
 * rather than losing the run.
 */
class ExecutionWorkflowImpl : ExecutionWorkflow {

    /** Options come from the worker's registration, so this stays free of configuration. */
    private val activities = Workflow.newActivityStub(ExecutionActivities::class.java)

    override fun run(plan: RunPlan): ExecutionStatus {
        var handOver = plan.input

        for ((index, nodeKey) in plan.steps.withIndex()) {
            val report = try {
                activities.runStep(RunStepCommand(plan.executionId, nodeKey, handOver))
            } catch (failure: ActivityFailure) {
                // Every attempt is spent by the time this is thrown.
                activities.failRun(
                    FailRunCommand(
                        executionId = plan.executionId,
                        nodeKey = nodeKey,
                        reason = failure.reason(),
                        unreached = plan.steps.size - index - 1,
                    ),
                )
                return ExecutionStatus.FAILED
            }

            // A step that did nothing passes on what it was handed, so a node
            // with no runtime yet does not cut the run in half.
            if (report.status == StepStatus.COMPLETED) handOver = report.output

            // A condition that did not hold ends the run, without failing it.
            if (report.halt) {
                activities.finishRun(FinishRunCommand(plan.executionId, nodeKey, report.output))
                return ExecutionStatus.COMPLETED
            }
        }

        activities.finishRun(FinishRunCommand(plan.executionId))
        return ExecutionStatus.COMPLETED
    }

    /** What the step said, rather than Temporal's wrapper around it. */
    private fun ActivityFailure.reason(): String =
        (cause as? ApplicationFailure)?.originalMessage ?: cause?.message ?: message ?: "the step failed"
}
