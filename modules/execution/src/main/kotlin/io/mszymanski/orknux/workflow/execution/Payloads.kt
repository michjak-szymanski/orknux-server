package io.mszymanski.orknux.workflow.execution

import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * What a step hands to the next one.
 *
 * A run used to carry only the last step's output: each step replaced what it
 * was given. That is why a workflow could not answer the person who started it —
 * by the time an agent had written a reply, the event holding the channel to
 * send it to was gone, and no expression could reach back to it.
 *
 * So a step adds to what it received instead of replacing it. The trigger's
 * fields are still there at the end of the graph, and a node halfway down can
 * read something produced three nodes earlier. It is the difference between a
 * conveyor belt and a relay.
 */
object Payloads {

    private val mapper = ObjectMapper()

    /**
     * Both are objects: the new keys are laid over the old ones, so a step that
     * produced `reply` adds `reply` and leaves `channel` where it was. A name
     * used twice means the later step wins — which the editor refuses to save,
     * because silently losing the first one is worse than being told.
     *
     * Anything that is not an object cannot be merged into one. Prose from an
     * unnamed agent replaces what came before, exactly as it did before this
     * existed; naming the output is what turns it into something that can be
     * carried alongside.
     */
    fun carry(previous: String?, produced: String?): String? {
        // A step that produced nothing does not erase what the run is holding.
        if (produced.isNullOrBlank()) return previous
        if (previous.isNullOrBlank()) return produced

        val before = parse(previous) ?: return produced
        val after = parse(produced) ?: return produced

        val merged = before.deepCopy()
        after.propertyNames().forEach { name -> merged.set(name, after.get(name)) }
        return mapper.writeValueAsString(merged)
    }

    /** Null unless the text is a JSON object; an array or a number is neither mergeable nor an error. */
    private fun parse(json: String): ObjectNode? =
        runCatching { mapper.readTree(json) as? ObjectNode }.getOrNull()
}
