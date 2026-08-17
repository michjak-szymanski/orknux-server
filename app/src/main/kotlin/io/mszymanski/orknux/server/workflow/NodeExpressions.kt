package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.workflow.execution.ExecutionStep
import org.springframework.stereotype.Component
import io.mszymanski.orknux.workflow.execution.NodeBinding
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * What a node was told to pass on, and what that comes to once the run is
 * carrying something.
 *
 * A parameter is either a value, used exactly as written, or a reference to a
 * field the run is carrying. There is no substitution and no syntax: text that
 * looks like a placeholder is text.
 *
 * There are two ways to want the answer, and the difference matters. An action
 * argument is JSON — a string argument has to arrive quoted, or the function
 * receives something that is not a string. A prompt is read by a model, and
 * quoting it would put the quote marks in the sentence. So the caller says which
 * it needs.
 */
@Component
class NodeExpressions(private val mapper: ObjectMapper) {

    /** The run's payload, or null when there is nothing to read from. */
    fun parse(input: String?): JsonNode? = input
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { mapper.readTree(it) }.getOrNull() }

    /**
     * What a step hands on, under the name its node gave it.
     *
     * Without a name the value goes on as it is, which is what every node did
     * before names existed and what a node still does when it has none. With
     * one, it becomes `{"reply": "…"}` — the smallest thing a later node can
     * address a field of.
     */
    fun named(outputName: String?, value: String): String {
        val name = outputName?.trim().orEmpty()
        if (name.isEmpty()) return value

        return mapper.writeValueAsString(mapOf(name to value))
    }

    /**
     * The same, for a value that is already JSON.
     *
     * A function returns JSON, so wrapping it as text would hand the next node
     * the *source* of an object rather than the object — `{"result":"{\"id\":1}"}`
     * where `{"result":{"id":1}}` was meant, and a reference to `result.id` would
     * have nothing to read.
     */
    fun namedJson(outputName: String?, json: String): String {
        val name = outputName?.trim().orEmpty()
        if (name.isEmpty()) return json

        val parsed = runCatching { mapper.readTree(json) }.getOrNull()
            // Not JSON after all: treat it as the text it is rather than failing
            // a step over the shape of its own output.
            ?: return named(name, json)

        return mapper.writeValueAsString(mapOf(name to parsed))
    }

    /**
     * What this step was told to pass, as the planner wrote it down.
     *
     * Steps planned before parameters knew whether they were a value or a
     * reference wrote a plain name-to-text map. Those are read as written
     * values, which is what they were.
     */
    fun mappingsOf(step: ExecutionStep): Map<String, NodeBinding> = step.mappings
        ?.let { runCatching { mapper.readValue(it, Map::class.java) }.getOrNull() }
        ?.entries
        ?.mapNotNull { (name, held) ->
            val binding = when (held) {
                is Map<*, *> -> NodeBinding(
                    expression = held["expression"]?.toString().orEmpty(),
                    reference = held["reference"] == true,
                    from = held["from"]?.toString(),
                )

                else -> NodeBinding(expression = held?.toString() ?: return@mapNotNull null)
            }
            name.toString() to binding
        }
        ?.toMap()
        .orEmpty()

    /**
     * What a parameter comes to, as text.
     *
     * A value is the text itself — all of it, whatever it looks like. A
     * reference reads the field it names, out of what the run is carrying or out
     * of the event that started it.
     *
     * There is no third thing. A value that happens to contain braces is a value
     * containing braces, which is why the editor refuses to save one: text that
     * looks like it should be substituted, and is not, is the failure that sent
     * `{{llmResult}}` to Slack as those eleven characters.
     */
    fun textOf(binding: NodeBinding, input: JsonNode?, trigger: JsonNode? = null): String {
        if (!binding.reference) return binding.expression

        val (source, path) = read(binding.expression, input, trigger)
        val value = path.fold(source) { node, step -> node?.get(step) } ?: return ""
        return if (value.isTextual) value.stringValue() else value.toString()
    }

    /** The same, as JSON, for something being handed to a function. */
    fun jsonOf(binding: NodeBinding, input: JsonNode?, trigger: JsonNode? = null): String {
        if (!binding.reference) return mapper.writeValueAsString(binding.expression)

        val (source, path) = read(binding.expression, input, trigger)
        return path.fold(source) { node, step -> node?.get(step) }?.toString() ?: "null"
    }

    /**
     * Which payload a reference reads from, and the path into it.
     *
     * `trigger.channel` reads the event that started the run; anything else
     * reads what the run is carrying now, which is where every named output
     * ends up.
     */
    private fun read(field: String, input: JsonNode?, trigger: JsonNode?): Pair<JsonNode?, List<String>> {
        val steps = field.trim().split('.').filter { it.isNotEmpty() }
        return if (steps.firstOrNull() == TRIGGER) trigger to steps.drop(1) else input to steps
    }

    private companion object {
        const val TRIGGER = "trigger"
    }
}
