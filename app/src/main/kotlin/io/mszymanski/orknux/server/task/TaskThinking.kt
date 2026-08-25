package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.server.chat.RoundWatch
import io.mszymanski.orknux.server.llm.LlmSessionRecorder

/**
 * What a task's turn writes down while the model is still having it.
 *
 * The chat's [RoundWatch] relays a round to somebody on the other end of an open
 * connection. A task has nobody in particular: it may be running in a Temporal
 * worker in another process, the page showing it may be closed, and whoever
 * opens it tomorrow must get the same account as whoever is watching now. So
 * this one *writes* rather than relays, into the session the turn is already
 * being recorded in, and the live view falls out of that for free - `SessionTail`
 * follows the table, so a line written here reaches every browser watching the
 * task and survives the reconnect `TaskStreamAPI` forces every four minutes.
 *
 * That is the whole reason the reasoning is not simply handed on and forgotten.
 * A delta relayed and not written down would vanish at the next stint, on a page
 * whose entire promise is that it reads the same afterwards.
 *
 * ## One line per round, not one per frame
 *
 * A reasoning model emits its thinking a few characters at a time, hundreds of
 * frames for one turn. A row each would be a transcript in which the reasoning
 * is spread over three hundred lines nobody can read, and three hundred inserts
 * for one turn. So a round's thinking is one line that grows: opened on the
 * first frame, so it is on the page within a moment of the model starting, and
 * replaced on a clock afterwards - [FLUSH_EVERY_MILLIS] rather than on every
 * frame, because what a reader can see is a block that moves rather than every
 * character of it arriving separately.
 *
 * ## When a round's thinking is over
 *
 * Two things end it, and both are the model doing something other than
 * thinking: asking for a tool, and finishing the turn. [called] closes the line
 * because a model that has decided what to look up has stopped reasoning about
 * whether to; [settle] is called by the loop when the turn ends however it
 * ended, including by an exception. Until one of those the line carries no
 * duration, which is what a page reads as "still thinking".
 *
 * ## What the duration means
 *
 * From the request going out to the last frame of reasoning, which is the wait
 * somebody actually sat through. Not between the first and last frames: a model
 * that emits its whole reasoning in one frame has those at the same instant, so
 * that measurement reports nothing on most real providers. The clock is
 * restarted as each tool returns, because the next round's request goes out the
 * moment the last result is threaded in - so a round is timed from its own
 * beginning rather than from the turn's.
 */
class TaskThinking(
    private val session: Long,
    private val agent: String,
    private val sessions: LlmSessionRecorder,
    private val clock: () -> Long = System::nanoTime,
) : RoundWatch {

    /** The round's reasoning so far. Emptied when the line is settled. */
    private val thought = StringBuilder()

    /** The line it is being written on, or null when no round is thinking. */
    private var line: Long? = null

    /** When this round's request went out, as nearly as this can tell. */
    private var began: Long = clock()

    /** When the last frame of reasoning arrived. */
    private var lastFrame: Long = began

    /** When the line was last written, so it is not written on every frame. */
    private var flushed: Long = 0

    @Synchronized
    override fun thinking(text: String) {
        // Nothing is opened on whitespace: an empty block of reasoning drawn
        // under a turn asserts there was thinking to see.
        if (thought.isEmpty() && text.isBlank()) return

        thought.append(text)
        lastFrame = clock()

        val now = System.currentTimeMillis()
        val open = line
        if (open == null) {
            line = sessions.thinking(session, agent, thought.toString())
            flushed = now
            return
        }
        if (now - flushed < FLUSH_EVERY_MILLIS) return
        flushed = now
        sessions.thinkingGrew(open, thought.toString())
    }

    /**
     * A tool was asked for, so this round has stopped thinking.
     *
     * The call itself is already being recorded by [LlmSessionRecorder]; what
     * this adds is closing the reasoning that led to it, so the two read in the
     * order they happened rather than as a block of thinking that never ended
     * sitting above a lookup.
     */
    override fun called(at: Int, tool: String, arguments: String) = settle()

    /**
     * A tool answered, which is the last thing to happen before the next round
     * is asked for. Restarts the clock, so the next round's thinking is timed
     * from its own request rather than from the turn's beginning.
     */
    @Synchronized
    override fun returned(at: Int, result: String, failed: Boolean) {
        began = clock()
    }

    /**
     * Closes whatever line is open, with how long it took.
     *
     * Safe to call when nothing is open and safe to call twice, because the
     * loop calls it in a `finally` and a round that asked for a tool has
     * already closed its own.
     */
    @Synchronized
    fun settle() {
        val open = line
        val took = (lastFrame - began) / NANOS
        began = clock()
        if (open == null) return
        sessions.thoughtFor(open, thought.toString(), took)
        line = null
        thought.setLength(0)
    }

    private companion object {
        /**
         * How often the growing line is written.
         *
         * Often enough that somebody watching sees a block that is moving, and
         * rarely enough that a turn is a handful of updates rather than one per
         * frame. The count of how long it has been thinking ticks in the
         * browser on its own clock, so this is about the words rather than
         * about the page looking alive.
         */
        const val FLUSH_EVERY_MILLIS = 800L

        const val NANOS = 1_000_000L
    }
}
