package io.mszymanski.orknux.connector.model

/**
 * One piece of a stream, split into what the model said and what it thought.
 *
 * Both may be empty and both may be set: a provider can put reasoning and text
 * in the same frame, and the caller wants them in two places on the screen.
 */
data class ModelPiece(val said: String = "", val thought: String = "")

/**
 * Pulling a leading `<think>…</think>` block out of an answer as it arrives.
 *
 * ## Why this exists at all
 *
 * A reasoning model emits its thinking separately from its answer, and there
 * are two ways it reaches us. A provider that knows what it is holding sends it
 * in a field of its own — `reasoning_content` in the OpenAI shape, a `thinking`
 * block in Anthropic's — and [ModelChatClient] reads those directly. A local
 * server serving a reasoning model through a chat template does not: llama.cpp
 * and Ollama hand back the template's raw output, and the template's way of
 * marking thinking is a pair of tags inside `content`. DeepSeek-R1 and Qwen3
 * both do this, and they are the models somebody running a local provider is
 * most likely to have.
 *
 * So without this, an installation pointed at Ollama has its reasoning read out
 * as part of the answer: the tags themselves on screen, the thinking spoken by
 * text-to-speech, and the whole of it copied by the copy control. That is the
 * bug rather than a missing feature.
 *
 * ## The rule, and why it is this narrow
 *
 * **Only a block the answer opens with is recognised, and only the first one.**
 * The opener has to be the first non-whitespace text of the answer, which is
 * where a template puts it, and once its `</think>` has been seen this stops
 * looking for ever.
 *
 * The alternative — treating `<think>` as a marker wherever it appears — eats an
 * answer that is *about* the tag. A model asked how reasoning models mark their
 * thinking would have its explanation silently folded away, and somebody pasting
 * a template into a chat would watch half of it disappear. A leading block
 * cannot be that: nothing an answer is about begins before the answer does.
 *
 * `<think>` and not `<thinking>` or `<reasoning>`: it is what the templates that
 * do this actually emit. A tag added here on a guess would be a second way to
 * lose part of an answer.
 *
 * ## Feeding it
 *
 * [feed] takes the stream a piece at a time and may be handed a tag split across
 * two of them — `<thi` and `nk>` is an ordinary thing for a provider to send —
 * so it holds back any tail that could still turn out to be the start of a tag
 * and releases it once it cannot. [finish] gives back whatever is still held,
 * which is what a stream ending mid-tag leaves behind.
 *
 * Not thread-safe, and does not need to be: one of these belongs to one call.
 */
class ThinkTags {

    private var state = State.BEFORE
    private val held = StringBuilder()

    private enum class State {
        /** Nothing but whitespace seen yet, so an opener would still count. */
        BEFORE,

        /** Inside the block, looking for the closer. */
        INSIDE,

        /** The block is over, or there never was one. Everything is text now. */
        DONE,
    }

    /** The next piece of the stream, split. */
    fun feed(piece: String): ModelPiece {
        if (state == State.DONE) return ModelPiece(said = piece)

        held.append(piece)
        val said = StringBuilder()
        val thought = StringBuilder()

        while (true) {
            when (state) {
                State.BEFORE -> {
                    val at = held.indexOf(OPEN)
                    if (at >= 0 && held.substring(0, at).isBlank()) {
                        // The leading whitespace is the answer's, not the
                        // block's, so it goes out as text.
                        said.append(held, 0, at)
                        held.delete(0, at + OPEN.length)
                        state = State.INSIDE
                        continue
                    }
                    // No opener yet. Hold on while what has arrived could still
                    // become one - which is whitespace, or whitespace and the
                    // first few characters of the tag.
                    if (at < 0 && stillCouldOpen(held)) return ModelPiece()
                    // It could not, so there is no leading block and there
                    // never will be. Everything from here is the answer.
                    state = State.DONE
                    said.append(held)
                    held.setLength(0)
                    return ModelPiece(said.toString(), thought.toString())
                }

                State.INSIDE -> {
                    val at = held.indexOf(CLOSE)
                    if (at >= 0) {
                        thought.append(held, 0, at)
                        held.delete(0, at + CLOSE.length)
                        state = State.DONE
                        said.append(held)
                        held.setLength(0)
                        return ModelPiece(said.toString(), thought.toString())
                    }
                    val keep = tailOf(held, CLOSE)
                    thought.append(held, 0, held.length - keep)
                    held.delete(0, held.length - keep)
                    return ModelPiece(said.toString(), thought.toString())
                }

                State.DONE -> {
                    said.append(held)
                    held.setLength(0)
                    return ModelPiece(said.toString(), thought.toString())
                }
            }
        }
    }

    /**
     * Whatever is still held back, once the stream has ended.
     *
     * A stream that stopped in the middle of a tag, or on a `<` that was never
     * going to be one, leaves characters here. They are given back as text
     * rather than dropped: half a tag on screen is a smaller wrong than an
     * answer missing its last three characters, and nobody can tell the second
     * one happened.
     */
    fun finish(): ModelPiece {
        if (held.isEmpty()) return ModelPiece()
        val rest = held.toString()
        held.setLength(0)
        return if (state == State.INSIDE) ModelPiece(thought = rest) else ModelPiece(said = rest)
    }

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"

        /**
         * Whether what has arrived so far could still turn out to be a leading
         * opener: nothing but whitespace, or whitespace and the first few
         * characters of the tag.
         */
        fun stillCouldOpen(text: CharSequence): Boolean {
            val from = text.indexOf('<')
            if (from < 0) return text.isBlank()
            if (text.substring(0, from).isNotBlank()) return false
            val rest = text.substring(from)
            return rest.length < OPEN.length && OPEN.startsWith(rest)
        }

        /** How many trailing characters could still turn out to be [tag]. */
        fun tailOf(text: CharSequence, tag: String): Int {
            val most = minOf(text.length, tag.length - 1)
            for (length in most downTo 1) {
                if (tag.startsWith(text.substring(text.length - length))) return length
            }
            return 0
        }
    }
}
