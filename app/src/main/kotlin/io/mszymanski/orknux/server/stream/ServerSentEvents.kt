package io.mszymanski.orknux.server.stream

import tools.jackson.databind.ObjectMapper
import java.io.OutputStream

/**
 * Writing server-sent events, in the one place that knows the wire format.
 *
 * There are two streams in this application - a chat's answer arriving as the
 * model writes it, and a task's session arriving as the agent works - and they
 * have nothing in common except this: an event name, a JSON payload, a blank
 * line, and a flush. That was written out by hand inside the chat's endpoint,
 * and a second copy inside the task's would have been the second place for the
 * flush to be forgotten. It is one line of code and it is still worth one class,
 * because the failure it prevents is silent: without the flush the whole
 * exercise still works, it simply arrives all at once at the end.
 *
 * What is *not* shared is the vocabulary. A chat sends `chunk`, `done` and
 * `error`; a task sends `step`, `state`, `again` and `end`. Those are two
 * conversations about two different things, and folding them into one set of
 * names would mean a reader having to know which endpoint it was talking to in
 * order to know what a frame meant - which is the opposite of what a shared
 * format buys.
 */
class ServerSentEvents(private val out: OutputStream, private val mapper: ObjectMapper) {

    /**
     * One frame: the name, the JSON, and the blank line that ends it.
     *
     * Flushed every time. A servlet container buffers, so an unflushed stream
     * delivers the whole conversation in one piece when the response closes -
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
     */
    fun keepAlive() {
        out.write(":\n\n".toByteArray())
        out.flush()
    }

    private fun write(id: Long?, event: String, payload: Any) {
        val frame = buildString {
            if (id != null) append("id: ").append(id).append('\n')
            append("event: ").append(event).append('\n')
            append("data: ").append(mapper.writeValueAsString(payload)).append("\n\n")
        }
        out.write(frame.toByteArray())
        out.flush()
    }
}
