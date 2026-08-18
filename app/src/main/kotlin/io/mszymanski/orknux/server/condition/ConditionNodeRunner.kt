package io.mszymanski.orknux.server.condition

import io.mszymanski.orknux.workflow.execution.EdgeBranch
import io.mszymanski.orknux.workflow.execution.ExecutionStep
import io.mszymanski.orknux.workflow.execution.KIND_RUNNER_ORDER
import io.mszymanski.orknux.workflow.execution.NodeKind
import io.mszymanski.orknux.workflow.execution.NodeRunner
import io.mszymanski.orknux.workflow.execution.StepResult
import io.mszymanski.orknux.workflow.execution.StepStatus
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/**
 * Asks a condition of the run it is in, and says which way the answer goes.
 *
 * Every answer carries a branch, YES or NO, and the engine decides what that
 * means: where the node has edges labelled with them, the run follows the
 * matching ones and skips whatever only the other reached; where it has none -
 * which is every graph drawn before branches existed - `halt` still ends the
 * run on a no.
 *
 * Both are the same sentence said twice, deliberately: the runner reports what
 * the condition answered, and how a graph is drawn decides whether that is a
 * fork or an ending. A runner that had to know which would be a runner that
 * had to read the graph.
 */
@Component
@Order(KIND_RUNNER_ORDER)
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
            /*
             * A question that cannot be answered is not a run that failed; it
             * is a run with nothing further to do, and a reason worth reading.
             * It takes neither branch: an unanswerable question has no "no"
             * side to follow, and guessing one would send the run down a path
             * on the strength of an error.
             */
            return StepResult(
                StepStatus.COMPLETED,
                "${condition.name} could not be answered: ${failure.message}",
                halt = true,
            )
        }

        return if (holds) {
            StepResult(StepStatus.COMPLETED, input ?: "null", branch = EdgeBranch.YES)
        } else {
            /*
             * `halt` and a branch together: the engine takes the branch where
             * the graph offers one, and reads the halt where it does not.
             */
            StepResult(
                StepStatus.COMPLETED,
                "${condition.name} did not hold.",
                halt = true,
                branch = EdgeBranch.NO,
            )
        }
    }
}
