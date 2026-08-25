package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ModelPiece
import io.mszymanski.orknux.connector.model.ThinkTags
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pulling a reasoning model's thinking out of the answer it was wrapped in.
 *
 * The shape being handled is what llama.cpp and Ollama hand back for
 * DeepSeek-R1 and Qwen3: the chat template writes `<think>…</think>` into
 * `content`, and no field anywhere says that is what it is. Before this the
 * whole of it was the answer — the tags on screen, the reasoning copied by the
 * copy control and read out by the speech model.
 *
 * Two things are asserted here that are easy to get wrong and impossible to see
 * once they are. **A tag arrives in pieces**, because a provider splits its
 * frames wherever it likes and `<thi` then `nk>` is an ordinary thing to
 * receive; a splitter that looked at one frame at a time would put both halves
 * on the screen and then hunt for a tag that had already gone past. And **only
 * a leading block counts**, so an answer that is *about* the tag keeps its
 * words — which is the failure mode of the obvious implementation and the one
 * nobody would ever be told about, because the words simply are not there.
 */
class ThinkTagsTest {

    /** Everything the splitter gave back over a whole stream, joined. */
    private fun whole(vararg pieces: String): ModelPiece {
        val tags = ThinkTags()
        val said = StringBuilder()
        val thought = StringBuilder()
        (pieces.toList() + null).forEach { piece ->
            val out = if (piece == null) tags.finish() else tags.feed(piece)
            said.append(out.said)
            thought.append(out.thought)
        }
        return ModelPiece(said.toString(), thought.toString())
    }

    @Test
    fun `a leading block is thinking and the rest is the answer`() {
        val split = whole("<think>The user wants a number.</think>Forty-two.")

        assertThat(split.thought).isEqualTo("The user wants a number.")
        assertThat(split.said).isEqualTo("Forty-two.")
    }

    @Test
    fun `a tag split across frames is still one tag`() {
        // Every boundary a provider could choose, including inside both tags.
        val split = whole("<thi", "nk>", "Two ", "and two.", "</thi", "nk>", "Four.")

        assertThat(split.thought).isEqualTo("Two and two.")
        assertThat(split.said).isEqualTo("Four.")
        // And nothing of either tag reached the answer, which is the visible
        // half of the bug this is about.
        assertThat(split.said).doesNotContain("<", ">")
    }

    @Test
    fun `an answer with no block is untouched`() {
        val split = whole("Forty", "-two.")

        assertThat(split.said).isEqualTo("Forty-two.")
        assertThat(split.thought).isEmpty()
    }

    /**
     * The rule that stops this eating an answer.
     *
     * A model explaining how reasoning models mark their thinking writes the
     * tag in the middle of a sentence. Treated as a marker it would swallow the
     * explanation, silently, and the reader would have no way to know a
     * paragraph was missing - which is worse than showing tags, because showing
     * tags is at least visible.
     */
    @Test
    fun `a tag in the middle of an answer is left alone`() {
        val split = whole("Reasoning models write <think>like this</think> before answering.")

        assertThat(split.thought).isEmpty()
        assertThat(split.said).isEqualTo("Reasoning models write <think>like this</think> before answering.")
    }

    @Test
    fun `leading whitespace belongs to the answer, not to the block`() {
        val split = whole("\n\n<think>Hmm.</think>Yes.")

        assertThat(split.thought).isEqualTo("Hmm.")
        assertThat(split.said).isEqualTo("\n\nYes.")
    }

    /**
     * A stream cut off inside the block. What was thought is what was thought;
     * there is simply no answer yet, and the caller reads that as a round that
     * produced nothing rather than as an answer.
     */
    @Test
    fun `a block that never closes is all thinking`() {
        val split = whole("<think>Still working on ")

        assertThat(split.thought).isEqualTo("Still working on ")
        assertThat(split.said).isEmpty()
    }

    /**
     * A lone `<` that was never going to be a tag has to come back out.
     *
     * The splitter holds characters back while they could still grow into an
     * opener, and a stream that ends on one would otherwise lose them. Three
     * missing characters at the end of an answer is a fault nobody would report
     * and nobody could find.
     */
    @Test
    fun `characters held back while they might be a tag are given back`() {
        assertThat(whole("<th").said).isEqualTo("<th")
        assertThat(whole("<b>bold</b> text").said).isEqualTo("<b>bold</b> text")
    }

    @Test
    fun `a second block later in the answer is the answer's own`() {
        val split = whole("<think>One.</think>First. <think>Two.</think> Second.")

        assertThat(split.thought).isEqualTo("One.")
        assertThat(split.said).isEqualTo("First. <think>Two.</think> Second.")
    }
}
