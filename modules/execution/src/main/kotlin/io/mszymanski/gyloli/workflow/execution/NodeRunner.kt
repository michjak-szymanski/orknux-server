package io.mszymanski.gyloli.workflow.execution

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * What a runner made of one node. A runner that could not do its work throws;
 * the engine turns that into a failed step and a stopped run.
 */
data class StepResult(
    /** Either [StepStatus.COMPLETED] or [StepStatus.SKIPPED]. */
    val status: StepStatus,
    /** What the node produced, and what the next node is handed. */
    val output: String? = null,
    /**
     * Ends the run here, without failing it.
     *
     * A condition node that does not hold is the reason this exists: the run did
     * what it was asked and there is nothing further to do, which is a different
     * outcome from a step that could not be carried out.
     */
    val halt: Boolean = false,
) {
    init {
        require(status == StepStatus.COMPLETED || status == StepStatus.SKIPPED) {
            "A runner reports what it did, not that it failed: $status"
        }
    }
}

/**
 * Carries out one node of a graph.
 *
 * This is the seam the node kinds land on: an agent runner will call the model,
 * a publish runner will call gyloli-connector. The engine picks the first
 * runner that claims the kind, so a new one is a `@Component` and nothing else.
 */
interface NodeRunner {

    fun supports(kind: NodeKind): Boolean

    /**
     * @param input what the node before it produced, or the run's own input for
     *   the first node.
     */
    fun run(step: ExecutionStep, input: String?): StepResult
}

/**
 * Claims every kind, last, so a graph can be run before any kind has a runtime.
 * It performs nothing and says so: the step is skipped rather than completed,
 * because a run that reports success without doing the work is worse than one
 * that reports it did not.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class UnimplementedNodeRunner : NodeRunner {

    override fun supports(kind: NodeKind): Boolean = true

    override fun run(step: ExecutionStep, input: String?): StepResult =
        StepResult(StepStatus.SKIPPED, "${step.kind} nodes have no runtime yet; nothing was performed.")
}
