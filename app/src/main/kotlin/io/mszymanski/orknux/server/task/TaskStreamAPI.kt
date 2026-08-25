package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.server.llm.LlmSessionEvent
import io.mszymanski.orknux.server.llm.LlmSessionEventKind
import io.mszymanski.orknux.server.llm.SessionTail
import io.mszymanski.orknux.server.llm.SessionWatch
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.stream.ServerSentEvents
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.MediaType
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * One line of a task's session, as it is sent to somebody watching.
 *
 * The same five fields `llmSessionEvents` returns, deliberately: a page that
 * has just loaded the last two hundred lines and a page being handed the next
 * one must be holding the same kind of thing, or the merge between them is a
 * conversion nobody remembers to update.
 */
data class TaskStepView(
    val id: Long,
    val kind: LlmSessionEventKind,
    val actor: String,
    val content: String?,
    /** What a call gave back. Null while its tool has not answered yet. */
    val result: String?,
    val at: String,
)

/**
 * Watching a task work.
 *
 * The task detail page is a session view rather than a table that reloads: a
 * step appears when it happens, a tool call is named the moment it is made and
 * fills in with what came back when the tool answers, and the state - working,
 * needs you, done, failed - is on the page the instant it changes. What that
 * replaced was `AutoRefresh`, which is the right control for a list of runs and
 * the wrong one for watching somebody work: an interval somebody has to choose
 * is either too slow to feel live or a query every five seconds for an hour.
 *
 * ## What carries it, and what happens when it drops
 *
 * Server-sent events, like the chat, and read by the same code in the browser.
 * What is different is the lifetime and therefore the shape. A chat's stream is
 * one answer being composed inside one request: it opens, the model writes, it
 * closes, and if it drops the answer is lost and asked for again. A task runs
 * for hours in a thread or a Temporal worker that has nothing to do with any
 * HTTP request, so this stream does not *carry* the work - it *follows* what the
 * work wrote down.
 *
 * That is what makes a dropped connection uninteresting. Every frame carries the
 * id of the event it is about, the browser keeps the highest it has seen, and a
 * reconnect asks for everything after it. Nothing is replayed from the beginning
 * and nothing is missed, whether the gap was a second or a night.
 *
 * **And the connection is dropped on purpose, every few minutes.** A stream held
 * open for an hour is a container thread held for an hour, and every proxy
 * between here and a browser has an idle timeout of its own that nobody here
 * controls. So a stint is bounded: when it is up the server says `again` and
 * closes, and the browser comes straight back with its cursor. The reason to do
 * it this way rather than to hold on and hope is that it makes the recovery path
 * the *ordinary* path - a reconnect that only ever runs when something has gone
 * wrong is a reconnect that does not work, and nobody finds out until the night
 * it is needed.
 *
 * ## Two people watching, and somebody opening an hour in
 *
 * Neither can get a partial account, because there is no live state to join
 * halfway through. What a viewer sees is a cursor over a durable log: the page
 * reads the tail it wants with `llmSessionEvents` and then follows on from the
 * newest line it holds. Somebody opening an hour in reads the hour and follows
 * from there; a second person watching alongside the first is a second cursor
 * over the same rows. There is nothing either of them can be too late for.
 *
 * ## Both engines
 *
 * Nothing here knows which engine took the turn, and that is the point. It
 * follows `llm_session_event`, which `LlmSessionRecorder` is the single door
 * into, and both `InlineTaskEngine` and `TemporalTaskEngine` run the same
 * [TaskLoop] through the same recorder. The all-in-one image runs inline and
 * gets exactly this; an installation on Temporal gets exactly this. See
 * [SessionTail] for why the poll underneath is what makes that true even when
 * the turn was taken in another process.
 */
@RestController
class TaskStreamAPI(
    private val tasks: TaskRepository,
    private val access: WorkspaceAccess,
    private val tail: SessionTail,
    private val views: TaskViews,
    private val mapper: ObjectMapper,
) {

    /**
     * The last state read for a task, shared by everybody watching it.
     *
     * The one thing on this page that is not in the session: a task moving from
     * queued to working writes no line, and "what it is waiting for" is a row in
     * `task_request` rather than something anybody said. So it has to be read,
     * and read on a timer - which is the query a live view is not supposed to
     * cost.
     *
     * It costs one per task rather than one per viewer. Whichever connection
     * notices the snapshot has gone stale reads it, under the map's own lock for
     * that key, and everybody else watching the same task is handed what it
     * found. Ten people watching one task is one query every two seconds, the
     * same as one person; nobody watching is none, because the entry is dropped
     * with the last watcher.
     */
    private val snapshots = ConcurrentHashMap<Long, Snapshot>()

    private data class Snapshot(val at: Long, val view: TaskView)

    /**
     * Follows one task.
     *
     * @param after the id of the newest line the caller already holds, so a page
     *   that has drawn the log so far is given only what came after it. Absent,
     *   or nought, asks for the session from its first line - which is what a
     *   reader with nothing drawn wants.
     * @param lastEventId the same thing under the name the protocol gives it. A
     *   browser's own `EventSource` sends this header on a reconnect without
     *   being asked; this client reconnects by hand because it wants the query
     *   parameter's clarity in a log, and both are honoured so that the endpoint
     *   is correct for either.
     *
     * Access is checked before the first byte. After it the status code has gone
     * and there is no way left to say no.
     */
    @GetMapping("/api/tasks/{id}/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Transactional(readOnly = true)
    fun stream(
        @PathVariable id: Long,
        @RequestParam(required = false) after: Long?,
        @RequestHeader(name = "Last-Event-ID", required = false) lastEventId: Long?,
        response: HttpServletResponse,
    ): StreamingResponseBody {
        val task = tasks.findByIdOrNull(id) ?: throw TaskNotFoundException(id)
        access.requireVisible(task.workspaceId)

        val session = task.sessionId
        val from = (after ?: lastEventId ?: 0L).coerceAtLeast(0L)
        val opening = views.of(task)

        /*
         * Nothing between here and the browser may hold a frame back.
         *
         * `no-cache` because a proxy that caches a stream serves the first
         * reader's account to the second one for ever, and `no` to nginx's
         * buffering because the deployed interface is served through nginx -
         * which, left to itself, collects a response until it has enough of it
         * to be worth forwarding. That is the correct thing to do to a page and
         * the wrong thing to do to this.
         */
        response.setHeader("Cache-Control", "no-cache, no-transform")
        response.setHeader("X-Accel-Buffering", "no")

        return StreamingResponseBody { _ ->
            val sse = ServerSentEvents(response, mapper)
            /*
             * The state first, always, and before anything is followed.
             *
             * A page that reconnects to a task which ended while it was away
             * would otherwise sit on "Working" until something else happened -
             * and on a finished task nothing else ever happens, so it would sit
             * there for ever. The state is the one thing that is sent whether or
             * not there is any news.
             */
            sse.send("state", opening)

            if (session == null) {
                // Its log was thrown away, so there is nothing to follow. Said
                // rather than left hanging: a stream that opens and stays silent
                // is indistinguishable from one that is working.
                sse.send("end", mapOf("reason" to "gone"))
                return@StreamingResponseBody
            }

            val watch = tail.follow(session, from)
            try {
                relay(id, watch, sse, opening)
            } catch (closed: Exception) {
                // The reader went away, or a write failed. There is nobody left
                // to report it to; the only thing that would be wrong is losing
                // it in silence.
                log.debug("Task {} stream ended early", id, closed)
            } finally {
                tail.unfollow(watch)
                // The shared snapshot belongs to whoever is watching; the last
                // one out takes it with them rather than leaving a task's state
                // in a map for the life of the process.
                if (!tail.followed(session)) snapshots.remove(id)
            }
        }
    }

    /**
     * The stint: hand over lines as they arrive, and keep the state honest.
     *
     * The loop wakes on a line or on [SLICE_MILLIS], whichever comes first, so
     * that a task which is thinking still gets its state checked and its
     * connection kept alive. It never queries on its own account - [stateOf] is
     * what decides whether anything is read, and it is shared.
     */
    private fun relay(id: Long, watch: SessionWatch, sse: ServerSentEvents, opening: TaskView) {
        val until = System.currentTimeMillis() + STINT.toMillis()
        var shown = opening.status
        var waiting = openRequestOf(opening)
        var lastWord = System.currentTimeMillis()

        while (System.currentTimeMillis() < until) {
            val line = watch.next(SLICE_MILLIS)
            if (line != null) {
                sse.send(requireNotNull(line.id), "step", describe(line))
                lastWord = System.currentTimeMillis()
                // Another line may already be behind it; take them all before
                // going back to the clock.
                continue
            }

            if (watch.overrun) {
                // It fell behind and was let go. `again` rather than `end`: the
                // cursor it holds is still good and the reconnect fills the gap.
                sse.send("again", mapOf("reason" to "behind"))
                return
            }

            val state = stateOf(id) ?: run {
                sse.send("end", mapOf("reason" to "gone"))
                return
            }
            if (state.status != shown || openRequestOf(state) != waiting) {
                sse.send("state", state)
                shown = state.status
                waiting = openRequestOf(state)
                lastWord = System.currentTimeMillis()
            }

            /*
             * A finished task is not followed. Everything it wrote is in the
             * database and nothing further will be written, so holding the
             * connection open would be a thread spent on a page that will never
             * change - and the browser, told `end`, stops asking.
             */
            if (state.status.over) {
                sse.send("end", mapOf("reason" to "over"))
                return
            }

            if (System.currentTimeMillis() - lastWord > QUIET_MILLIS) {
                sse.keepAlive()
                lastWord = System.currentTimeMillis()
            }
        }

        // The stint is up. Said rather than simply closed, so the browser knows
        // this was the arrangement rather than a failure and comes back without
        // backing off.
        sse.send("again", mapOf("reason" to "stint"))
    }

    /**
     * The task's state, read at most once per [STATE_EVERY] however many people
     * are watching it.
     *
     * `compute` is what makes that true rather than merely likely: it holds the
     * map's lock for this key while it runs, so of ten connections arriving at
     * the same instant one reads and nine are handed what it read. Written that
     * way rather than with a check-then-read, which is the same code with a race
     * in it and ten queries in the worst case - which is precisely the worst case
     * this exists to prevent.
     */
    private fun stateOf(id: Long): TaskView? {
        val now = System.currentTimeMillis()
        return snapshots.compute(id) { _, held ->
            if (held != null && now - held.at < STATE_EVERY.toMillis()) {
                held
            } else {
                tasks.findByIdOrNull(id)?.let { Snapshot(now, views.of(it)) }
            }
        }?.view
    }

    /** What it is standing still for, or null. Compared to notice an answer landing. */
    private fun openRequestOf(task: TaskView): TaskRequestView? =
        task.requests.lastOrNull { it.decision == null }

    private fun describe(event: LlmSessionEvent) = TaskStepView(
        id = requireNotNull(event.id),
        kind = event.kind,
        actor = event.actor,
        content = event.content,
        result = event.result,
        at = event.at.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    )

    private companion object {
        /**
         * How long one connection is held before it is handed back.
         *
         * Under every idle timeout worth worrying about, and long enough that a
         * reconnect is a thing that happens a dozen times an hour rather than
         * constantly. Not configurable: an operator has no way to know a better
         * number than this, and the cost of getting it wrong is paid by the
         * reconnect that works anyway.
         */
        val STINT: Duration = Duration.ofMinutes(4)

        /** How stale a shared state snapshot may be before somebody re-reads it. */
        val STATE_EVERY: Duration = Duration.ofSeconds(2)

        /** How long the loop waits on a line before it looks at the clock. */
        const val SLICE_MILLIS = 500L

        /** How long a connection may say nothing before it says nothing loudly. */
        const val QUIET_MILLIS = 20_000L

        val log = LoggerFactory.getLogger(TaskStreamAPI::class.java)
    }
}
