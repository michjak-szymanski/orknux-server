package io.mszymanski.orknux.connector.shell

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The buffer that decides what survives a command that says too much.
 *
 * Reached directly rather than through a shell, which is why [BoundedBuffer] is
 * internal rather than private. What it does is arithmetic over a wrapping
 * array, and the cases that break such a thing - a write that lands exactly on
 * the wrap, a single write bigger than the whole ring, a character cut in half
 * at the leading edge - are ones it takes a container, an SSH server and a
 * command printing a megabyte to reach from outside, and which no failure
 * message from out there would name. `ShellSessionTest` still drives the same
 * behaviour over a real connection; this says which line of arithmetic is wrong
 * when it does.
 *
 * The measurement that matters in every one of these is the same: the *last*
 * bytes written are still there. That is the whole of issue #287 - a build
 * prints its error last, the old buffer kept only the beginning, and a model
 * handed the beginning could not tell a failure from a cut.
 */
class BoundedBufferTest {

    @Test
    fun `output under the limit comes back exactly as it was written`() {
        val buffer = BoundedBuffer(1024)
        buffer.write("hello\nworld\n".toByteArray())

        // Byte for byte, and no marker anywhere in it. The ordinary command that
        // prints two lines must not be made to look like one that was cut, or
        // every reader of this output has to wonder about every short answer.
        assertThat(buffer.text()).isEqualTo("hello\nworld\n")
        assertThat(buffer.truncated).isFalse()
    }

    @Test
    fun `output filling the limit exactly is still untouched`() {
        // The boundary, because "under the limit" and "at the limit" are one
        // off from each other and this is where a fence gets posted wrong.
        val buffer = BoundedBuffer(300)
        val written = "x".repeat(300)
        buffer.write(written.toByteArray())

        assertThat(buffer.text()).isEqualTo(written)
        assertThat(buffer.truncated).isFalse()
    }

    @Test
    fun `the last thing printed survives, which is the whole point`() {
        val buffer = BoundedBuffer(1024)
        buffer.write("THE-BEGINNING\n".toByteArray())
        buffer.write("filler\n".repeat(5_000).toByteArray())
        buffer.write("THE-ANSWER-IS-HERE\n".toByteArray())

        val text = buffer.text()

        // The old buffer passed the first of these and failed the second, which
        // is exactly the wrong way round for anything that prints its verdict
        // last.
        assertThat(text).contains("THE-BEGINNING")
        assertThat(text).endsWith("THE-ANSWER-IS-HERE\n")
        assertThat(buffer.truncated).isTrue()
    }

    @Test
    fun `the marker says how much went and where the gap is`() {
        val buffer = BoundedBuffer(4096)
        buffer.write("start\n".toByteArray())
        buffer.write("y".repeat(2 * 1024 * 1024).toByteArray())
        buffer.write("\nend\n".toByteArray())

        val text = buffer.text()

        // A number, because "the output was cut" and "the output ended" are the
        // two readings this exists to separate and only a number separates them.
        assertThat(text).containsPattern("… [0-9.]+ MiB of output removed from the middle\\.")
        assertThat(text).contains("what is below is where it ended, complete")
        assertThat(text).startsWith("start\n")
        assertThat(text).endsWith("\nend\n")
    }

    @Test
    fun `what is kept from the command never exceeds the limit`() {
        val limit = 8192
        val buffer = BoundedBuffer(limit)
        buffer.write("z".repeat(5 * 1024 * 1024).toByteArray())

        val bytes = buffer.text().toByteArray(Charsets.UTF_8)
        val marker = MARKER.toRegex().find(buffer.text())?.value.orEmpty()

        // The marker is this application's words rather than the command's, so
        // it sits on top of the allowance; what came from the far side is what
        // the allowance governs, and that is what is measured.
        assertThat(marker).isNotEmpty()
        assertThat(bytes.size - marker.toByteArray(Charsets.UTF_8).size).isEqualTo(limit)
    }

    @Test
    fun `a single write larger than the whole buffer keeps its own last bytes`() {
        // One `write` bigger than the ring is the case where the loop could
        // copy a megabyte in order to overwrite it, and the case where a naive
        // wrap keeps the wrong end of the call.
        val buffer = BoundedBuffer(600)
        buffer.write(("a".repeat(100_000) + "TAIL").toByteArray())

        assertThat(buffer.text()).endsWith("TAIL")
        assertThat(buffer.truncated).isTrue()
    }

    @Test
    fun `bytes written one at a time land in the same place as bytes written in blocks`() {
        // Two entry points onto one ring. MINA uses both, and a ring that
        // advances differently on the single-byte path is a corruption that only
        // shows up under whichever stream happened to be written that way.
        val blocks = BoundedBuffer(300)
        val singles = BoundedBuffer(300)

        val written = ("q".repeat(2000) + "LAST").toByteArray()
        blocks.write(written)
        written.forEach { singles.write(it.toInt()) }

        assertThat(singles.text()).isEqualTo(blocks.text())
        assertThat(singles.text()).endsWith("LAST")
    }

    @Test
    fun `many small writes wrap the ring without losing the end`() {
        // The ring wraps here rather than being filled once, and it wraps at an
        // offset that is not a multiple of the write size, so a run that spans
        // the wrap is copied in two pieces.
        val buffer = BoundedBuffer(310)
        repeat(1_000) { buffer.write("line $it\n".toByteArray()) }

        assertThat(buffer.text()).endsWith("line 999\n")
        assertThat(buffer.truncated).isTrue()
    }

    @Test
    fun `a character cut in half at the seam costs one replacement and not an exception`() {
        /*
         * The ring's leading edge falls wherever the arithmetic puts it, which
         * is inside a multi-byte character sooner or later. Decoding with
         * REPLACE is what makes that one U+FFFD rather than a result thrown away
         * half way through being reported - the same property that lets somebody
         * `cat` a binary through this.
         *
         * Driven with a limit that is not a multiple of three against
         * three-byte characters, so the cut is certain rather than hoped for.
         */
        val buffer = BoundedBuffer(100)
        buffer.write("€".repeat(500).toByteArray(Charsets.UTF_8))

        val text = buffer.text()

        assertThat(text).endsWith("€")
        assertThat(text).contains("�")
        assertThat(buffer.truncated).isTrue()
    }

    @Test
    fun `a buffer nothing was written to is empty rather than marked`() {
        val buffer = BoundedBuffer(1024)

        assertThat(buffer.text()).isEmpty()
        assertThat(buffer.truncated).isFalse()
    }

    private companion object {
        /** The marker as a pattern, so a test can measure it without repeating its wording. */
        const val MARKER = "\n… .+ …\n"
    }
}
