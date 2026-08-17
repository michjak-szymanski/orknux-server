package io.mszymanski.orknux.server.condition

import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.variable.VariableArguments
import io.mszymanski.orknux.workflow.script.ScriptResult
import io.mszymanski.orknux.workflow.script.ScriptRunner
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.OffsetDateTime
import java.time.LocalTime

/**
 * Decides one of a workspace's conditions against what a run is carrying.
 *
 * A condition is data rather than code — a property, a check and what to check
 * against — so this is where that data becomes an answer. It is deliberately not
 * the script sandbox: a condition the UI built should not be able to run
 * anything, and a wait that asks the same question every thirty seconds should
 * not compile a script each time.
 */
@Service
class ConditionEvaluator(
    private val conditions: WorkflowConditionRepository,
    private val functions: WorkflowFunctionRepository,
    private val scripts: ScriptRunner,
    private val externals: VariableArguments,
    private val mapper: ObjectMapper,
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    /**
     * @param input the JSON the previous node produced, or the run's own input.
     * @throws ConditionNotDecidableException when the input does not carry what
     *   the condition asks about; a wait treats that as "not yet", and a
     *   condition node as a reason to stop.
     */
    fun holds(condition: WorkflowCondition, input: String?): Boolean =
        decide(condition, parse(input), raw = input, depth = 0)

    private fun decide(condition: WorkflowCondition, input: JsonNode?, raw: String?, depth: Int): Boolean {
        if (depth > MAX_DEPTH) throw ConditionNotDecidableException("${condition.name} nests too deeply")

        val answer = when (condition.type) {
            ConditionType.ANY_OF -> members(condition).any { decide(it, input, raw, depth + 1) }
            ConditionType.ALL_OF -> members(condition).all { decide(it, input, raw, depth + 1) }
            ConditionType.FUNCTION -> ask(condition, raw)
            else -> test(condition, input)
        }
        return answer != condition.negate
    }

    /**
     * Runs the workspace's function in the sandbox and takes its answer.
     *
     * The function is handed what the run is carrying, and has to say true or
     * false: a condition that answered `"maybe"` is a condition nobody can act
     * on, so anything else is refused rather than guessed at.
     */
    private fun ask(condition: WorkflowCondition, input: String?): Boolean {
        val function = condition.functionId?.let { functions.findByIdOrNull(it) }
            ?: throw ConditionNotDecidableException("${condition.name} names a function that has been deleted")

        // What the run is carrying, then the workspace's own values: a condition
        // checking something against a stored secret is the same shape as an
        // action doing it.
        val arguments = listOf(input ?: "null") + externals.of(function)
        val call = scripts.call(function.source, function.name, arguments, contextFor(condition))
        return when (val result = call) {
            is ScriptResult.Returned -> when (result.json) {
                "true" -> true
                "false" -> false
                else -> throw ConditionNotDecidableException(
                    "${function.name} answered ${result.json ?: "nothing"}, which is not true or false",
                )
            }

            is ScriptResult.Failed -> throw ConditionNotDecidableException("${function.name} ${result.reason}")
        }
    }

    /**
     * What the asking function is told about where it is running.
     *
     * The clock is the reason this exists: the sandbox has no host and no I/O,
     * so a question about the date has to be answered from here — and a
     * condition should agree with the run about when "now" is, the same way an
     * action's function does.
     */
    private fun contextFor(condition: WorkflowCondition): String = mapper.writeValueAsString(
        mapOf(
            "now" to OffsetDateTime.now(clock).toString(),
            "timestamp" to clock.millis(),
            "workspaceId" to condition.workspaceId,
            "condition" to condition.name,
        ),
    )

    private fun members(condition: WorkflowCondition): List<WorkflowCondition> = condition.members.map { id ->
        conditions.findByIdOrNull(id)
            ?: throw ConditionNotDecidableException("${condition.name} names a condition that has been deleted")
    }

    private fun test(condition: WorkflowCondition, input: JsonNode?): Boolean {
        val check = condition.check
            ?: throw ConditionNotDecidableException("${condition.name} has no check to make")
        val property = condition.property
            ?: throw ConditionNotDecidableException("${condition.name} has nothing to check")

        if (check == ConditionCheck.BETWEEN) return withinTime(condition)

        val value = valueOf(property, input)
            ?: throw ConditionNotDecidableException(
                "${condition.name} asks about ${label(property)}, which this run is not carrying",
            )

        return when (check) {
            ConditionCheck.IN_LIST, ConditionCheck.WORKSPACEMATE -> condition.values.any { it.equalsIgnoringCase(value) }
            ConditionCheck.EQUALS -> condition.values.firstOrNull()?.equalsIgnoringCase(value) == true
            ConditionCheck.CONTAINS -> condition.values.any { value.contains(it, ignoreCase = true) }
            ConditionCheck.MATCHES -> condition.values.firstOrNull()
                ?.let { pattern -> runCatching { Regex(pattern).containsMatchIn(value) }.getOrElse { false } } == true

            ConditionCheck.BETWEEN -> false
        }
    }

    /** Whether the clock is between the two listed times, as HH:mm. */
    private fun withinTime(condition: WorkflowCondition): Boolean {
        val from = condition.values.getOrNull(0)?.let(::parseTime)
        val until = condition.values.getOrNull(1)?.let(::parseTime)
        if (from == null || until == null) {
            throw ConditionNotDecidableException("${condition.name} needs two times to sit between")
        }

        val now = LocalTime.now(clock)
        // A range that ends before it starts runs over midnight.
        return if (from <= until) now >= from && now <= until else now >= from || now <= until
    }

    private fun parseTime(text: String): LocalTime? = runCatching { LocalTime.parse(text.trim()) }.getOrNull()

    /**
     * Where each property is read from. These are the field names a trigger
     * puts in the run's input — `SlackListener` writes `user`, `channel` and
     * `text` — with a couple of spellings allowed for what someone might map by
     * hand.
     */
    private fun valueOf(property: ConditionProperty, input: JsonNode?): String? {
        if (property == ConditionProperty.CURRENT_TIME) return LocalTime.now(clock).toString()
        val names = when (property) {
            ConditionProperty.MESSAGE_AUTHOR -> listOf("user", "author", "messageAuthor")
            ConditionProperty.MESSAGE_CHANNEL -> listOf("channel", "messageChannel")
            ConditionProperty.MESSAGE_TEXT -> listOf("text", "message", "messageText")
            ConditionProperty.ISSUE_PRIORITY -> listOf("priority", "issuePriority")
            ConditionProperty.ISSUE_STATUS -> listOf("status", "issueStatus")
            ConditionProperty.ISSUE_TYPE -> listOf("issueType", "type")
            ConditionProperty.CURRENT_TIME -> emptyList()
        }

        return names
            .firstNotNullOfOrNull { name -> input?.get(name)?.takeIf { !it.isNull } }
            ?.let { if (it.isTextual) it.stringValue() else it.toString() }
    }

    private fun parse(input: String?): JsonNode? = input
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { mapper.readTree(it) }.getOrNull() }

    private fun String.equalsIgnoringCase(other: String) = equals(other, ignoreCase = true)

    private companion object {
        val log = LoggerFactory.getLogger(ConditionEvaluator::class.java)

        /** A composite of composites is fine; a hundred of them is a mistake. */
        const val MAX_DEPTH = 10
    }
}

/** What each property is called where a person reads it. */
fun label(property: ConditionProperty): String = property.name
    .split('_')
    .joinToString(" ") { it.lowercase() }

/** Raised when the condition cannot be answered from what the run is carrying. */
class ConditionNotDecidableException(message: String) : RuntimeException(message)
