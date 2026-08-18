package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.server.condition.ConditionEvaluator
import io.mszymanski.orknux.connector.connection.Delivery
import io.mszymanski.orknux.connector.connection.HttpAnswer
import io.mszymanski.orknux.connector.connection.OutgoingHttp
import io.mszymanski.orknux.connector.connection.OutgoingMessages
import io.mszymanski.orknux.server.variable.VariableArguments
import io.mszymanski.orknux.server.workflow.NodeExpressions
import io.mszymanski.orknux.server.condition.ConditionNotDecidableException
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStep
import io.mszymanski.orknux.workflow.execution.KIND_RUNNER_ORDER
import io.mszymanski.orknux.workflow.execution.NodeKind
import io.mszymanski.orknux.workflow.execution.NodeRunner
import io.mszymanski.orknux.workflow.execution.PermanentFailure
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
@Order(KIND_RUNNER_ORDER)
class ActionNodeRunner(
    private val actions: WorkflowActionRepository,
    private val functions: WorkflowFunctionRepository,
    private val scripts: ScriptRunner,
    private val conditions: WorkflowConditionRepository,
    private val evaluator: ConditionEvaluator,
    private val mapper: ObjectMapper,
    private val expressions: NodeExpressions,
    private val messages: OutgoingMessages,
    private val externals: VariableArguments,
    private val http: OutgoingHttp,
) : NodeRunner {

    override fun supports(kind: NodeKind): Boolean = kind == NodeKind.ACTION

    override fun run(step: ExecutionStep, input: String?, trigger: String?): StepResult {
        val actionId = step.actionId
            ?: return StepResult(StepStatus.SKIPPED, "${step.name} names no action, so there was nothing to run.")
        val action = actions.findByIdOrNull(actionId)
            ?: return StepResult(StepStatus.SKIPPED, "The action ${step.name} ran has been deleted.")

        return when (action.subtype) {
            ActionSubtype.FUNCTION -> callFunction(action, step, input, trigger)
            ActionSubtype.INLINE_CONDITION -> waitFor(step, action, input) { holds(action, input) }
            ActionSubtype.CONDITION -> waitForSavedCondition(step, action, input)
            ActionSubtype.TIME -> waitForTime(step, action, input)
            ActionSubtype.OUTGOING_CONNECTION -> send(action, step, input, trigger)

            ActionSubtype.HTTP_REQUEST -> request(action, step, input, trigger)
        }
    }

    /**
     * Sends what the node says, through the connection the action points at.
     *
     * The node's own parameters decide where it goes and what it says, so two
     * nodes running the same action can answer two different people. Nothing
     * here touches a credential: [OutgoingMessages] holds the connection and
     * reports what happened.
     */
    private fun send(action: WorkflowAction, step: ExecutionStep, input: String?, trigger: String?): StepResult {
        val connectionId = action.connectionId
            ?: return StepResult(StepStatus.SKIPPED, "${action.name} names no connection to send through.")

        val given = expressions.parse(input)
        val started = expressions.parse(trigger)
        val byName = expressions.mappingsOf(step)
        fun resolved(name: String) = byName[name]?.let { expressions.textOf(it, given, started) }?.trim().orEmpty()

        val target = resolved(ActionParameters.TARGET).ifEmpty { action.targetName.orEmpty() }
        val content = resolved(ActionParameters.CONTENT).ifEmpty { action.content.orEmpty() }

        // Neither is a failure worth stopping a run over: a node still being
        // drawn has blanks, and saying what is missing beats a Slack error.
        if (target.isEmpty()) return StepResult(StepStatus.SKIPPED, "${step.name} has nobody to send to.")
        if (content.isEmpty()) return StepResult(StepStatus.SKIPPED, "${step.name} has nothing to say.")

        // Replying in a thread needs the message being replied to. Blank sends
        // to the channel instead, which is what a plain send has always meant.
        val threadTs = resolved(ActionParameters.THREAD_TS).takeIf { it.isNotEmpty() }

        return when (val delivery = messages.send(connectionId, target, content, threadTs)) {
            is Delivery.Sent -> StepResult(
                StepStatus.COMPLETED,
                expressions.namedJson(
                    step.outputName,
                    mapper.writeValueAsString(mapOf("channel" to delivery.channel, "ts" to delivery.ts)),
                ),
            )

            is Delivery.NotPossible -> StepResult(StepStatus.SKIPPED, "${step.name} sent nothing: ${delivery.reason}.")

            // Somebody meant this to send, and it did not.
            // What it tried, not only what went wrong. `channel_not_found` says
            // nothing about the fact that `target` was wired to the message text
            // rather than to a channel — and that is the mistake it usually is.
            is Delivery.Refused -> throw ActionFailedException(
                "${step.name} could not send to \"${target.take(TARGET_IN_ERROR)}\": ${delivery.reason}",
                // Slack answering is an answer: a channel that does not exist
                // will not exist on the third attempt either.
                permanent = true,
            )
        }
    }

    /**
     * Calls whatever the node points at, and hands the answer on.
     *
     * The URL and the body are the node's to vary, the same way a send's target and
     * content are: two nodes running one action can call two addresses. Method and
     * headers stay the definition's — they say what kind of call this is, which is a
     * property of the action rather than of one use of it.
     *
     * What comes back is a map: status, whether it was in the 200s, and the body —
     * parsed when it is JSON, so a later node reads fields off it rather than a
     * string it has to parse itself.
     */
    private fun request(action: WorkflowAction, step: ExecutionStep, input: String?, trigger: String?): StepResult {
        val given = expressions.parse(input)
        val started = expressions.parse(trigger)
        val byName = expressions.mappingsOf(step)
        fun resolved(name: String) = byName[name]?.let { expressions.textOf(it, given, started) }?.trim().orEmpty()

        val url = resolved(ActionParameters.URL).ifEmpty { action.url.orEmpty() }
        if (url.isEmpty()) return StepResult(StepStatus.SKIPPED, "${step.name} has no URL to call.")

        val body = resolved(ActionParameters.BODY).ifEmpty { action.content.orEmpty() }
        val method = action.method?.trim()?.ifEmpty { null } ?: "GET"

        return when (val answer = http.call(url, method, headersOf(action), body)) {
            is HttpAnswer.Answered -> answered(action, step, answer, method, url)

            /*
             * The call was not made and would not be worth making again — a URL that
             * is not one, or a host this must not reach. Permanent, so the run stops
             * here instead of trying twice more at something that has to be edited.
             */
            is HttpAnswer.Refused -> throw ActionFailedException(
                "${step.name} did not call \"${url.take(URL_IN_ERROR)}\": ${answer.reason}",
                permanent = true,
            )

            // Nothing came back. That is the kind of thing that works on the retry.
            is HttpAnswer.Unreachable -> throw ActionFailedException(
                "${step.name} could not reach \"${url.take(URL_IN_ERROR)}\": ${answer.reason}",
                permanent = false,
            )
        }
    }

    /**
     * What to make of an answer.
     *
     * A 2xx is the step's result. Anything else is a failure, and which kind matters:
     * a 4xx is the request being wrong — the same request will be just as wrong in
     * thirty seconds — while a 5xx is the other end having a bad moment, which is
     * exactly what retrying is for.
     */
    private fun answered(
        action: WorkflowAction,
        step: ExecutionStep,
        answer: HttpAnswer.Answered,
        method: String,
        url: String,
    ): StepResult {
        val summary = "$method ${url.take(URL_IN_ERROR)} answered ${answer.status}"

        return when (answer.status) {
            in 200..299 -> StepResult(
                StepStatus.COMPLETED,
                expressions.namedJson(step.outputName, mapper.writeValueAsString(shapeOf(answer))),
            )

            in 400..499 -> throw ActionFailedException("${step.name}: $summary. ${detail(answer)}", permanent = true)
            else -> throw ActionFailedException("${step.name}: $summary. ${detail(answer)}", permanent = false)
        }
    }

    /** The answer as the rest of the run sees it. */
    private fun shapeOf(answer: HttpAnswer.Answered): Map<String, Any?> = mapOf(
        "status" to answer.status,
        "ok" to (answer.status in 200..299),
        /*
         * Parsed where it is JSON, and left as text where it is not. A workflow that
         * asked for JSON should be able to point at a field; one that fetched a CSV
         * should still get its CSV rather than an error about it not being JSON.
         */
        "body" to jsonOrText(answer),
    )

    private fun jsonOrText(answer: HttpAnswer.Answered): Any? {
        val looksJson = answer.contentType?.contains("json", ignoreCase = true) == true
        if (!looksJson) return answer.body
        return runCatching { mapper.readTree(answer.body) }.getOrDefault(answer.body)
    }

    /** Enough of a failing body to see what was wrong, without pasting a web page into a log. */
    private fun detail(answer: HttpAnswer.Answered): String =
        answer.body.trim().replace(WHITESPACE, " ").take(BODY_IN_ERROR).ifEmpty { "It said nothing." }

    /** The headers the action defines, as they were typed. Unreadable JSON sends none. */
    private fun headersOf(action: WorkflowAction): Map<String, String> {
        val written = action.headers?.trim()?.ifEmpty { null } ?: return emptyMap()
        return runCatching {
            val tree = mapper.readTree(written)
            tree.properties().associate { (name, value) -> name to value.asString() }
        }.getOrDefault(emptyMap())
    }

    /**
     * Calls the workspace's function with the arguments the node passes it.
     *
     * Everything crossing into the script is JSON: an argument is either the
     * value the node holds, quoted as the string it is, or the field it refers
     * to, taken from what the run is carrying with its own shape intact. What
     * comes back is the JSON the script returned, which is what the next node
     * is handed.
     */
    private fun callFunction(action: WorkflowAction, step: ExecutionStep, input: String?, trigger: String?): StepResult {
        val function = action.functionId?.let { functions.findByIdOrNull(it) }
            ?: return StepResult(StepStatus.SKIPPED, "The function ${action.name} calls has been deleted.")

        val given = expressions.parse(input)
        val started = expressions.parse(trigger)
        // The node's mappings, carried onto the step when the run started. The
        // action's own are only ever a seed for a node, so reading them here
        // would run a binding nobody chose.
        val byName = expressions.mappingsOf(step).mapValues { (_, binding) -> expressions.jsonOf(binding, given, started) }
        // The order the function takes them, not the order they were mapped.
        // What the node passes, then what the workspace does: an external
        // parameter is not the caller's to fill, so it is appended rather than
        // looked for among the mappings.
        val arguments = function.params.map { byName[it.name] ?: "null" } + externals.of(function)

        return when (val result = scripts.call(function.source, function.name, arguments, contextFor(action))) {
            is ScriptResult.Returned -> StepResult(
                StepStatus.COMPLETED,
                // Under the name the node gave it, if it gave one. Unnamed, the
                // return value is handed on as it is — which is what every node
                // did before names existed, and why the `result` port the action
                // declares was not something a later node could actually read.
                expressions.namedJson(step.outputName, result.json ?: "null"),
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

    private companion object {
        val log = LoggerFactory.getLogger(ActionNodeRunner::class.java)

        /** How often a wait asks again, when the action does not say. */
        const val RETRY_SECONDS = 30

        /** Enough of the target to recognise what was wired into it, not a message. */
        const val TARGET_IN_ERROR = 60

        /** Enough of a URL to recognise it in a failure, without a query string filling the log. */
        const val URL_IN_ERROR = 80

        /** Enough of a failing body to see what was wrong, without pasting a web page into it. */
        const val BODY_IN_ERROR = 200

        val WHITESPACE = Regex("""\s+""")
    }
}

/**
 * Raised when an action could not do its work; the step fails with this message.
 *
 *  permanent set when trying again could not give a different answer — a
 *   channel that does not exist, a credential the service rejected. Retrying
 *   those only takes longer to reach the same conclusion.
 */
class ActionFailedException(message: String, override val permanent: Boolean = false) :
    RuntimeException(message), PermanentFailure
