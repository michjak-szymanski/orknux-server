package io.mszymanski.orknux.server.stream

import jakarta.servlet.http.HttpServletResponse
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.locks.ReentrantLock

/**
 * Writing server-sent events, in the one place that knows the wire format.
 *
 * There are two streams in this application - a chat's answer arriving as the
 * model writes it, and a task's session arriving as the agent works - and they
 * have nothing in common except this: an event name, a JSON payload, a blank
 * line, and getting the bytes onto the wire. That was written out by hand inside
 * the chat's endpoint, and a second copy inside the task's would have been the
 * second place to get it wrong.
 *
 * **It writes through the response rather than through the stream it is handed,
 * and that is not a detail.** Spring gives a `StreamingResponseBody` a
 * `StreamUtils.NonFlushingOutputStream`, whose `flush()` does nothing at all -
 * so the obvious spelling of this, `out.write(frame); out.flush()`, produces a
 * stream that does not stream. Nothing fails: the bytes sit in the container's
 * buffer until it fills at eight kilobytes or the response ends, so a short
 * answer arrives in one piece at the end and a long one arrives in lurches. The
 * chat did exactly that and nobody could see it, because a model writing prose
 * eventually fills eight kilobytes and the last part of a long answer does
 * appear to stream. `HttpServletResponse.flushBuffer` is what actually commits
 * the response and pushes, and it is the reason this class takes a response and
 * not an output stream.
 *
 * What is *not* shared is the vocabulary. A chat sends `chunk`, `done` and
 * `error`; a task sends `step`, `state`, `again` and `end`. Those are two
 * conversations about two different things, and folding them into one set of
 * names would mean a reader having to know which endpoint it was talking to in
 * order to know what a frame meant - which is the opposite of what a shared
 * format buys.
 */
class ServerSentEvents(private val response: HttpServletResponse, private val mapper: ObjectMapper) {

    private val out = response.outputStream

    /**
     * One frame at a time, because a chat's stream is written by two threads.
     *
     * The thread composing the answer writes the frames and a second one pings
     * the reader while the model is silent - see [ReaderWatch], which is how a
     * browser that has gone away is noticed at all. A frame is several writes
     * and a flush, so two threads writing without this would put a keep-alive
     * through the middle of an event, and the browser's parser would drop the
     * frame it landed in.
     *
     * A lock rather than `@Synchronized` so that a ping can decline to wait for
     * it. See [keepAlive].
     */
    private val writing = ReentrantLock()

    /**
     * One frame: the name, the JSON, and the blank line that ends it.
     *
     * Pushed every time. A servlet container buffers, so a frame that is only
     * written is a frame that arrives whenever the buffer happens to fill -
     * which is exactly the behaviour streaming exists to replace, and it looks
     * like a slow server rather than like a bug.
     */
    fun send(event: String, payload: Any) = write(null, event, payload)

    /**
     * The same, carrying the id of the thing it is about.
     *
     * `id:` is not decoration. It is what lets a reader that was cut off say
     * where it had got to, and it is the whole of how a task's page catches up
     * without replaying an hour of work - see `TaskStreamAPI` for the cursor
     * this id becomes.
     */
    fun send(id: Long, event: String, payload: Any) = write(id, event, payload)

    /**
     * A comment, which is a frame that says nothing.
     *
     * Every proxy and load balancer between here and a browser closes a
     * connection that has been idle for long enough, and a task that is thinking
     * is a connection with nothing on it. A colon and a newline is the protocol's
     * own way of saying "still here"; the browser's parser ignores it, and every
     * hop in between sees traffic.
     *
     * **It gives up rather than queue behind a frame.** On a chat's stream a
     * ping is also how a reader that has gone is discovered, and the thread
     * asking is shared between every stream in the process - so one congested
     * socket must not hold it while every other conversation waits to find out
     * whether anybody is still listening. Where another thread is already
     * writing there is nothing to discover anyway: that write is the same
     * question, asked by whoever got there first.
     */
    fun keepAlive() {
        if (!writing.tryLock()) return
        try {
            onto(":\n\n")
        } finally {
            writing.unlock()
        }
    }

    private fun write(id: Long?, event: String, payload: Any) = push(
        buildString {
            if (id != null) append("id: ").append(id).append('\n')
            append("event: ").append(event).append('\n')
            append("data: ").append(mapper.writeValueAsString(payload)).append("\n\n")
        },
    )

    /** Under the lock, so nothing is written through the middle of a frame. */
    private fun push(frame: String) {
        writing.lock()
        try {
            onto(frame)
        } finally {
            writing.unlock()
        }
    }

    private fun onto(frame: String) {
        out.write(frame.toByteArray())
        /*
         * Both, and in this order. The stream's own flush is what a wrapper
         * further down might be waiting for, and `flushBuffer` is what commits
         * the response and puts the bytes on the socket - which the stream
         * Spring hands a `StreamingResponseBody` will not do, whatever it is
         * asked. See the note on this class.
         */
        out.flush()
        response.flushBuffer()
    }
}
