package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.server.condition.ConditionEvaluator
import io.mszymanski.orknux.server.condition.ConditionNotDecidableException
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStep
import io.mszymanski.orknux.workflow.execution.NodeKind
import io.mszymanski.orknux.workflow.execution.NodeRunner
import io.mszymanski.orknux.workflow.execution.StepResult
import io.mszymanski.orknux.workflow.execution.StepStatus
import io.mszymanski.orknux.workflow.script.ScriptResult
import io.mszymanski.orknux.workflow.script.ScriptRunner
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Runs an action node.
 *
 * What it does is the action's business, and only one kind of action has a
 * runtime today: a function call, which runs the workspace's JavaScript in the
 * sandbox. A wait holds the run until its condition holds or its time passes.
 * The rest — sending through a connection, calling an HTTP endpoint — report
 * that they were not performed rather than pretending they were.
 *
 * A wait holds nothing while it waits. It answers the question it was given,
 * and if the answer is not yet it parks the step and says when to come back;
 * the delay belongs to whatever is carrying the run. So a wait costs no thread,
 * survives a restart, and may be as long as the run itself is allowed to be.
 *
 * This lives in `app` because it needs the catalogue, and it is a `NodeRunner`
 * because that is the seam the execution module leaves for exactly this.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ActionNodeRunner(
    private val actions: WorkflowActionRepository,
    private val functions: WorkflowFunctionRepository,
    private val scripts: ScriptRunner,
    private val conditions: WorkflowConditionRepository,
    private val evaluator: ConditionEvaluator,
    private val mapper: ObjectMapper,
) : NodeRunner {

    override fun supports(kind: NodeKind): Boolean = kind == NodeKind.ACTION

    override fun run(step: ExecutionStep, input: String?): StepResult {
        val actionId = step.actionId
            ?: return StepResult(StepStatus.SKIPPED, "${step.name} names no action, so there was nothing to run.")
        val action = actions.findByIdOrNull(actionId)
            ?: return StepResult(StepStatus.SKIPPED, "The action ${step.name} ran has been deleted.")

        return when (action.subtype) {
            ActionSubtype.FUNCTION -> callFunction(action, step, input)
            ActionSubtype.INLINE_CONDITION -> waitFor(step, action, input) { holds(action, input) }
            ActionSubtype.CONDITION -> waitForSavedCondition(step, action, input)
            ActionSubtype.TIME -> waitForTime(step, action, input)
            ActionSubtype.OUTGOING_CONNECTION, ActionSubtype.HTTP_REQUEST ->
                StepResult(
                    StepStatus.SKIPPED,
                    "${label(action.subtype)} actions have no runtime yet; nothing was sent.",
                )
        }
    }

    /**
     * Calls the workspace's function with the arguments the action maps to it.
     *
     * Everything crossing into the script is JSON: an argument is a placeholder
     * resolved against what the previous node produced, or the literal text as
     * written. What comes back is the JSON the script returned, which is what
     * the next node is handed.
     */
    private fun callFunction(action: WorkflowAction, step: ExecutionStep, input: String?): StepResult {
        val function = action.functionId?.let { functions.findByIdOrNull(it) }
            ?: return StepResult(StepStatus.SKIPPED, "The function ${action.name} calls has been deleted.")

        val given = parse(input)
        // The node's mappings, carried onto the step when the run started. The
        // action's own are only ever a seed for a node, so reading them here
        // would run a binding nobody chose.
        val byName = mappingsOf(step).mapValues { (_, expression) -> resolve(expression, given) }
        // The order the function takes them, not the order they were mapped.
        val arguments = function.params.map { byName[it.name] ?: "null" }

        return when (val result = scripts.call(function.source, function.name, arguments, contextFor(action))) {
            is ScriptResult.Returned -> StepResult(
                StepStatus.COMPLETED,
                result.json ?: "null",
            )

            is ScriptResult.Failed -> throw ActionFailedException("${function.name} ${result.reason}")
        }
    }

    /** What this step was told to pass, as the planner wrote it down. */
    private fun mappingsOf(step: ExecutionStep): Map<String, String> = step.mappings
        ?.let { runCatching { mapper.readValue(it, Map::class.java) }.getOrNull() }
        ?.entries
        ?.associate { (name, expression) -> name.toString() to expression.toString() }
        .orEmpty()

    /**
     * What a script is told about where it is running.
     *
     * The clock is the reason this exists: a sandbox with no host and no I/O
     * still has `Date`, but a run should agree with the server about when it
     * started rather than each script asking separately.
     */
    private fun contextFor(action: WorkflowAction): String = mapper.writeValueAsString(
        mapOf(
            "now" to OffsetDateTime.now().toString(),
            "timestamp" to System.currentTimeMillis(),
            "workspaceId" to action.workspaceId,
            "action" to action.name,
        ),
    )

    /**
     * Holds the run until the condition holds, without holding anything to do it.
     *
     * The condition is asked once per visit, and one of three things is true:
     * it holds and the run carries on, it has run out of time and the step
     * fails, or neither, and the step parks until the retry interval is up.
     * Since it is the engine that waits, asking every thirty seconds for a day
     * costs a day of timers rather than a day of thread.
     */
    private fun waitFor(
        step: ExecutionStep,
        action: WorkflowAction,
        input: String?,
        answer: () -> Boolean,
    ): StepResult {
        if (answer()) return StepResult(StepStatus.COMPLETED, input ?: "null")

        val until = deadline(step, action.timeoutSeconds)
        val left = Duration.between(OffsetDateTime.now(), until)
        if (left <= Duration.ZERO) {
            throw ActionFailedException("${action.name} was still waiting when it ran out of time")
        }

        val retry = Duration.ofSeconds((action.retryIntervalSeconds ?: RETRY_SECONDS).toLong())
        val after = minOf(retry, left)
        return park(step, until, after, "${action.name} is waiting; asked again in ${after.toSeconds()}s")
    }

    /** Waits on one of the workspace's conditions, asked again on every retry. */
    private fun waitForSavedCondition(step: ExecutionStep, action: WorkflowAction, input: String?): StepResult {
        val condition = action.conditionId?.let { conditions.findByIdOrNull(it) }
            ?: return StepResult(StepStatus.SKIPPED, "The condition ${action.name} waits on has been deleted.")

        return waitFor(step, action, input) {
            try {
                evaluator.holds(condition, input)
            } catch (failure: ConditionNotDecidableException) {
                // Not yet: the run may still be given what the condition asks
                // about, and the timeout is what decides when to give up.
                log.debug("{} is not decidable yet: {}", condition.name, failure.message)
                false
            }
        }
    }

    /**
     * Waits for a fixed time: the step parks once and is asked again when the
     * time is up, so the duration is bounded by what the run itself may take
     * rather than by anything being held while it passes.
     */
    private fun waitForTime(step: ExecutionStep, action: WorkflowAction, input: String?): StepResult {
        val until = deadline(step, action.durationSeconds)
        val left = Duration.between(OffsetDateTime.now(), until)

        if (left <= Duration.ZERO) {
            log.info("{} waited its {}s", action.name, action.durationSeconds ?: 0)
            return StepResult(StepStatus.COMPLETED, input ?: "null")
        }
        return park(step, until, left, "${action.name} is waiting ${left.toSeconds()}s")
    }

    /**
     * The moment this wait gives up, taken from the step if it has parked before.
     *
     * A resumed wait is the same wait: it counts from when it first parked, not
     * from when whatever is carrying the run came back to it.
     */
    private fun deadline(step: ExecutionStep, seconds: Int?): OffsetDateTime =
        step.waitUntil ?: OffsetDateTime.now().plusSeconds((seconds ?: 0).toLong())

    /** Parks the step, writing down the deadline so the next visit knows it. */
    private fun park(step: ExecutionStep, until: OffsetDateTime, after: Duration, note: String): StepResult {
        step.waitUntil = until
        return StepResult.waiting(after, note)
    }

    /** Evaluates an inline expression in the sandbox, with the step's input as `input`. */
    private fun holds(action: WorkflowAction, input: String?): Boolean {
        val expression = action.conditionExpression ?: return false
        return evaluate(expression, input)
    }

    private fun evaluate(expression: String, input: String?): Boolean {
        val source = """
            export default function condition(input) {
              return Boolean($expression);
            }
        """.trimIndent()

        return when (val result = scripts.call(source, "condition", listOf(input ?: "null"))) {
            is ScriptResult.Returned -> result.json == "true"
            is ScriptResult.Failed -> throw ActionFailedException("the condition ${result.reason}")
        }
    }

    /**
     * `{{input.x}}` becomes what the previous node produced under `x`, as JSON.
     * Anything else is taken literally, so a mapping can be a constant.
     */
    private fun resolve(expression: String, input: JsonNode?): String {
        val whole = WHOLE_PLACEHOLDER.matchEntire(expression.trim())
        if (whole != null) {
            val value = input?.get(whole.groupValues[1])
            return value?.toString() ?: "null"
        }

        val filled = PLACEHOLDER.replace(expression) { match ->
            val value = input?.get(match.groupValues[1])
            if (value == null || value.isNull) "" else if (value.isTextual) value.stringValue() else value.toString()
        }
        return mapper.writeValueAsString(filled)
    }

    private fun parse(input: String?): JsonNode? = input
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { mapper.readTree(it) }.getOrNull() }

    private companion object {
        val log = LoggerFactory.getLogger(ActionNodeRunner::class.java)

        /** How often a wait asks again, when the action does not say. */
        const val RETRY_SECONDS = 30

        val PLACEHOLDER = Regex("""\{\{\s*input\.([A-Za-z_][A-Za-z0-9_]*)\s*}}""")
        val WHOLE_PLACEHOLDER = Regex("""\{\{\s*input\.([A-Za-z_][A-Za-z0-9_]*)\s*}}""")
    }
}

/** Raised when an action could not do its work; the step fails with this message. */
class ActionFailedException(message: String) : RuntimeException(message)
