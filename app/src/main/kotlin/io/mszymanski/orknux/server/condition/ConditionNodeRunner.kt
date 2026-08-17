package io.mszymanski.orknux.server.condition

import io.mszymanski.orknux.workflow.execution.ExecutionStep
import io.mszymanski.orknux.workflow.execution.NodeKind
import io.mszymanski.orknux.workflow.execution.NodeRunner
import io.mszymanski.orknux.workflow.execution.StepResult
import io.mszymanski.orknux.workflow.execution.StepStatus
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/**
 * Asks a condition of the run it is in, and stops the run when the answer is no.
 *
 * The graph has no branches — an edge carries no label — so what a condition
 * node can say is "carry on" or "there is nothing further to do". The run ends
 * completed rather than failed in the second case, because nothing went wrong:
 * the workflow asked a question and acted on the answer.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ConditionNodeRunner(
    private val conditions: WorkflowConditionRepository,
    private val evaluator: ConditionEvaluator,
) : NodeRunner {

    override fun supports(kind: NodeKind): Boolean = kind == NodeKind.CONDITION

    override fun run(step: ExecutionStep, input: String?, trigger: String?): StepResult {
        val conditionId = step.conditionId
            ?: return StepResult(StepStatus.SKIPPED, "${step.name} asks no condition, so the run carried on.")
        val condition = conditions.findByIdOrNull(conditionId)
            ?: return StepResult(StepStatus.SKIPPED, "The condition ${step.name} asks has been deleted.")

        val holds = try {
            evaluator.holds(condition, input)
        } catch (failure: ConditionNotDecidableException) {
            // A question that cannot be answered is not a run that failed; it is
            // a run with nothing further to do, and a reason worth reading.
            return StepResult(
                StepStatus.COMPLETED,
                "${condition.name} could not be answered: ${failure.message}",
                halt = true,
            )
        }

        return if (holds) {
            StepResult(StepStatus.COMPLETED, input ?: "null")
        } else {
            StepResult(StepStatus.COMPLETED, "${condition.name} did not hold, so the run stopped here.", halt = true)
        }
    }
}
