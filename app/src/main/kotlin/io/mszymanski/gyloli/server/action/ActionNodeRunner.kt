package io.mszymanski.gyloli.server.action

import io.mszymanski.gyloli.server.condition.ConditionEvaluator
import io.mszymanski.gyloli.server.condition.ConditionNotDecidableException
import io.mszymanski.gyloli.server.condition.WorkflowConditionRepository
import io.mszymanski.gyloli.workflow.execution.ExecutionStep
import io.mszymanski.gyloli.workflow.execution.NodeKind
import io.mszymanski.gyloli.workflow.execution.NodeRunner
import io.mszymanski.gyloli.workflow.execution.StepResult
import io.mszymanski.gyloli.workflow.execution.StepStatus
import io.mszymanski.gyloli.workflow.script.ScriptResult
import io.mszymanski.gyloli.workflow.script.ScriptRunner
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
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
 * runtime today: a function call, which runs the team's JavaScript in the
 * sandbox. A wait holds the run until its condition holds or its time passes.
 * The rest — sending through a connection, calling an HTTP endpoint — report
 * that they were not performed rather than pretending they were.
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
    private val properties: ActionProperties,
) : NodeRunner {

    override fun supports(kind: NodeKind): Boolean = kind == NodeKind.ACTION

    override fun run(step: ExecutionStep, input: String?): StepResult {
        val actionId = step.actionId
            ?: return StepResult(StepStatus.SKIPPED, "${step.name} names no action, so there was nothing to run.")
        val action = actions.findByIdOrNull(actionId)
            ?: return StepResult(StepStatus.SKIPPED, "The action ${step.name} ran has been deleted.")

        return when (action.subtype) {
            ActionSubtype.FUNCTION -> callFunction(action, input)
            ActionSubtype.INLINE_CONDITION -> waitUntil(action, input) { holds(it, input) }
            ActionSubtype.CONDITION -> waitForSavedCondition(action, input)
            ActionSubtype.TIME -> waitForTime(action, input)
            ActionSubtype.OUTGOING_CONNECTION, ActionSubtype.HTTP_REQUEST ->
                StepResult(
                    StepStatus.SKIPPED,
                    "${label(action.subtype)} actions have no runtime yet; nothing was sent.",
                )
        }
    }

    /**
     * Calls the team's function with the arguments the action maps to it.
     *
     * Everything crossing into the script is JSON: an argument is a placeholder
     * resolved against what the previous node produced, or the literal text as
     * written. What comes back is the JSON the script returned, which is what
     * the next node is handed.
     */
    private fun callFunction(action: WorkflowAction, input: String?): StepResult {
        val function = action.functionId?.let { functions.findByIdOrNull(it) }
            ?: return StepResult(StepStatus.SKIPPED, "The function ${action.name} calls has been deleted.")

        val given = parse(input)
        val byName = action.mappings.associate { it.argument to resolve(it.expression, given) }
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
            "teamId" to action.teamId,
            "action" to action.name,
        ),
    )

    /**
     * Holds the run until the condition holds.
     *
     * The condition is JavaScript over what the previous node produced, so it is
     * evaluated in the same sandbox. Nothing else changes while this waits — the
     * inline engine is one thread — so the retries only make sense once the
     * engine can be resumed; until then the wait is bounded hard, so a workflow
     * cannot pin a thread for an hour.
     */
    private fun waitUntil(
        action: WorkflowAction,
        input: String?,
        answer: (WorkflowAction) -> Boolean,
    ): StepResult {
        val retry = Duration.ofSeconds((action.retryIntervalSeconds ?: 30).toLong())
        val until = System.nanoTime() + cappedWait(action.timeoutSeconds).toNanos()

        while (true) {
            if (answer(action)) return StepResult(StepStatus.COMPLETED, input ?: "null")

            val left = until - System.nanoTime()
            if (left <= 0) throw ActionFailedException("${action.name} was still waiting when it ran out of time")
            Thread.sleep(minOf(retry.toMillis(), left / 1_000_000).coerceAtLeast(1))
        }
    }

    /** Waits on one of the team's conditions, asked again on every retry. */
    private fun waitForSavedCondition(action: WorkflowAction, input: String?): StepResult {
        val condition = action.conditionId?.let { conditions.findByIdOrNull(it) }
            ?: return StepResult(StepStatus.SKIPPED, "The condition ${action.name} waits on has been deleted.")

        return waitUntil(action, input) {
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

    private fun waitForTime(action: WorkflowAction, input: String?): StepResult {
        val asked = Duration.ofSeconds((action.durationSeconds ?: 0).toLong())
        val waited = minOf(asked, properties.maxWait)
        Thread.sleep(waited.toMillis())

        val note = if (waited < asked) " (capped from ${asked.toSeconds()}s)" else ""
        log.info("{} waited {}s{}", action.name, waited.toSeconds(), note)
        return StepResult(StepStatus.COMPLETED, input ?: "null")
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

    /** A wait is bounded by configuration, whatever the action asks for. */
    private fun cappedWait(seconds: Int?): Duration =
        minOf(Duration.ofSeconds((seconds ?: 0).toLong()), properties.maxWait)

    private companion object {
        val log = LoggerFactory.getLogger(ActionNodeRunner::class.java)

        val PLACEHOLDER = Regex("""\{\{\s*input\.([A-Za-z_][A-Za-z0-9_]*)\s*}}""")
        val WHOLE_PLACEHOLDER = Regex("""\{\{\s*input\.([A-Za-z_][A-Za-z0-9_]*)\s*}}""")
    }
}

/** Raised when an action could not do its work; the step fails with this message. */
class ActionFailedException(message: String) : RuntimeException(message)

@ConfigurationProperties(prefix = "gyloli.action")
data class ActionProperties(
    /**
     * The longest a wait may hold a run. The inline engine waits on the thread
     * carrying the run, so an unbounded wait is an unbounded thread; a workflow
     * that needs to wait for hours needs the Temporal engine and a timer.
     */
    val maxWait: Duration = Duration.ofMinutes(5),
)
