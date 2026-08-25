package io.mszymanski.orknux.server.llm

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One reader following a session as it is written.
 *
 * A cursor and a queue, and the cursor is the important half. Everything a
 * follower has seen is identified by the id of the last event it took, so a
 * connection that drops says where it had got to and is given the rest - rather
 * than being given the session again from the beginning, which for a task that
 * ran overnight is a page rebuilt out of an hour of transcript every time a
 * proxy times out.
 *
 * The queue is bounded, and overrunning it is not an error. A reader that cannot
 * keep up is dropped and reconnects at its cursor, which loses nothing: the
 * events are in the database and the cursor says which of them are still owed.
 * The alternative - an unbounded queue - is one slow browser holding a session's
 * entire history in this process's heap.
 */
class SessionWatch(val session: Long, from: Long) {

    private val waiting = ArrayBlockingQueue<LlmSessionEvent>(DEPTH)

    /**
     * The highest event id this follower has been handed.
     *
     * Read by the pump to work out what to fetch, and written only as events are
     * queued, so a follower that is dropped mid-batch reconnects at the last line
     * it actually got rather than at the last line somebody tried to give it.
     */
    @Volatile
    var cursor: Long = from
        private set

    /** Set when the queue overflowed. The connection ends and the browser returns. */
    @Volatile
    var overrun: Boolean = false
        private set

    /**
     * Calls handed over with nothing back from them yet.
     *
     * A call is recorded before its tool runs, so the line arrives with
     * arguments and a null result and is filled in afterwards - see
     * [LlmSessionRecorder.toolReturned]. A tail that only ever moved forwards
     * would therefore show every lookup as permanently unanswered, which is the
     * one thing somebody watching an agent work most wants to see. These ids are
     * looked at again on each pass until they answer.
     *
     * Bounded by [OUTSTANDING]: an agent that made a thousand calls that never
     * returned is a broken tool, not a reason to re-read a thousand rows a
     * second.
     */
    val outstanding: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /**
     * Hands one line over, or gives up on this follower.
     *
     * The cursor moves only forwards. A result arriving on a line that was
     * already sent is offered again under the same id - the reader merges by id -
     * and must not drag the cursor back to it.
     */
    fun offer(event: LlmSessionEvent): Boolean {
        val id = event.id ?: return true
        if (!waiting.offer(event)) {
            overrun = true
            return false
        }
        if (id > cursor) cursor = id
        if (event.kind == LlmSessionEventKind.TOOL && event.result == null) {
            if (outstanding.size < OUTSTANDING) outstanding.add(id)
        } else {
            outstanding.remove(id)
        }
        return true
    }

    /** The next line owed, or null when nothing arrived in that long. */
    fun next(millis: Long): LlmSessionEvent? = waiting.poll(millis, TimeUnit.MILLISECONDS)

    private companion object {
        /**
         * How far a follower may fall behind.
         *
         * Generous enough that an agent making a round of parallel calls does
         * not overrun a browser that is merely painting, and small enough that a
         * hundred abandoned connections are a hundred small queues.
         */
        const val DEPTH = 512

        const val OUTSTANDING = 64
    }
}

/**
 * Following sessions as they are written, for however many people are watching.
 *
 * This is the whole of what makes a task's page live, and the reason it works is
 * that **it does not watch the task**. It watches `llm_session_event`, which is
 * the one table every agent in this application writes what it did into, through
 * the one door [LlmSessionRecorder] is. So the inline engine and the Temporal
 * engine feed it identically and neither knows it exists: whichever of them took
 * the turn, the turn was recorded, and recording is what this hears. A live view
 * built on the engines instead would have been two of them, and the one that is
 * not on the machine you happen to be running would be the one that rotted.
 *
 * **The poll is the mechanism and the signal is the accelerant.** That is the
 * same bargain [io.mszymanski.orknux.server.task.TaskEngine.nudge] makes and it
 * is made here for the same reason: the recorder tells this that something was
 * written, which is what makes a step appear in the moment rather than a second
 * later, but nothing about correctness depends on that arriving. A turn taken by
 * a Temporal worker in another process, or by another replica of this server,
 * writes the row and signals a tail this JVM does not have - and the periodic
 * pass finds it anyway. Building only the signal would have been a live view
 * that quietly stopped working the day somebody ran two servers.
 *
 * **What it costs is a query per watched session, not per watcher.** Every
 * follower of one session is served from one read: the pump asks for everything
 * past the *lowest* cursor among them and hands each follower the part it has
 * not seen. Two people watching the same task cost what one does, and a session
 * nobody is watching costs nothing at all - there is no timer running for it,
 * because the map it would be in is empty.
 */
@Service
class SessionTail(private val events: LlmSessionEventRepository) : DisposableBean {

    private val watched = ConcurrentHashMap<Long, MutableSet<SessionWatch>>()

    /** One pass at a time per session, so a stir during a read does not stack up. */
    private val passing = ConcurrentHashMap<Long, AtomicBoolean>()

    private val pump: ScheduledExecutorService =
        Executors.newScheduledThreadPool(THREADS, named("orknux-tail"))

    init {
        pump.scheduleWithFixedDelay(::sweep, BACKSTOP_MILLIS, BACKSTOP_MILLIS, TimeUnit.MILLISECONDS)
    }

    /**
     * Starts following, from the last event the caller already holds.
     *
     * @param from the id of the newest event the reader has. Nought for a reader
     *   holding none, which asks for the session from the beginning - so a
     *   caller that has already drawn the last two hundred lines passes the
     *   highest of them and is given only what came after.
     */
    fun follow(session: Long, from: Long): SessionWatch {
        val watch = SessionWatch(session, from)
        watched.computeIfAbsent(session) { ConcurrentHashMap.newKeySet() }.add(watch)
        stirred(session)
        return watch
    }

    fun unfollow(watch: SessionWatch) {
        watched.computeIfPresent(watch.session) { _, held ->
            held.remove(watch)
            // Dropped rather than left empty, so the sweep walks what is being
            // watched rather than everything that ever was.
            held.ifEmpty { null }
        }
    }

    /** Whether anybody is following this one, which is what decides if a stir is worth anything. */
    fun followed(session: Long): Boolean = watched.containsKey(session)

    /**
     * Something was written here.
     *
     * A hint, and cheap to ignore: a session nobody is watching returns without
     * touching anything, which is the ordinary case - most of what this
     * application records is never watched live by anybody.
     */
    fun stirred(session: Long) {
        if (!watched.containsKey(session)) return
        try {
            pump.execute { pass(session) }
        } catch (refused: RuntimeException) {
            // Shutting down, or the pump is saturated. The sweep will find it.
            log.debug("Session {} could not be looked at now", session, refused)
        }
    }

    private fun sweep() {
        watched.keys.forEach { session ->
            runCatching { pass(session) }
                .onFailure { log.warn("Session {} could not be swept", session, it) }
        }
    }

    /**
     * One read of what is new, handed to everybody who has not seen it.
     *
     * Guarded so that only one pass per session runs at a time. Two would be two
     * reads of the same rows and, worse, two threads racing to move one
     * follower's cursor - which is how a line gets skipped.
     */
    private fun pass(session: Long) {
        val gate = passing.computeIfAbsent(session) { AtomicBoolean() }
        if (!gate.compareAndSet(false, true)) return
        try {
            val watchers = watched[session] ?: return
            if (watchers.isEmpty()) return

            // The lowest cursor among them, so one read serves the person who
            // joined an hour in and the person who joined a moment ago alike.
            var at = watchers.minOf { it.cursor }
            while (true) {
                val batch = events.after(session, at, PageRequest.of(0, BATCH))
                if (batch.isEmpty()) break
                batch.forEach { event ->
                    val id = event.id ?: return@forEach
                    watchers.forEach { watcher -> if (id > watcher.cursor) hand(watcher, event) }
                }
                at = batch.last().id ?: break
                if (batch.size < BATCH) break
            }

            fillIn(watchers)
        } catch (failure: Exception) {
            log.warn("Session {} could not be read", session, failure)
        } finally {
            gate.set(false)
            if (!watched.containsKey(session)) passing.remove(session)
        }
    }

    /**
     * The calls that have answered since anybody last looked.
     *
     * Read by id rather than by scanning the tail again, because these are lines
     * *behind* every follower's cursor: a lookup dispatched thirty seconds ago
     * has long since been passed by everything said after it. Nothing is read at
     * all while no call is outstanding, which is most of the time - an agent is
     * either between calls or in one, and a call takes as long as the tool does.
     */
    private fun fillIn(watchers: Set<SessionWatch>) {
        val open = watchers.flatMapTo(mutableSetOf()) { it.outstanding }
        if (open.isEmpty()) return
        events.findAllById(open)
            .filter { it.result != null }
            .forEach { answered ->
                val id = answered.id ?: return@forEach
                watchers.forEach { watcher -> if (watcher.outstanding.remove(id)) hand(watcher, answered) }
            }
    }

    /** Hands a line over, and lets go of a follower that could not take it. */
    private fun hand(watcher: SessionWatch, event: LlmSessionEvent) {
        if (!watcher.offer(event)) {
            log.debug("A reader of session {} fell behind and was let go", watcher.session)
            unfollow(watcher)
        }
    }

    override fun destroy() {
        pump.shutdownNow()
    }

    private fun named(prefix: String) = object : ThreadFactory {
        private val next = AtomicInteger(1)
        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "$prefix-${next.getAndIncrement()}").apply { isDaemon = true }
    }

    private companion object {
        /**
         * How often a watched session is read whether or not anything said so.
         *
         * This is the number that decides what a live view costs, and it is a
         * per-session number rather than a per-viewer one. Two seconds is what a
         * turn taken in another process is behind by in the worst case; in this
         * process the signal has already delivered it and this finds nothing.
         */
        const val BACKSTOP_MILLIS = 2_000L

        /** Enough to keep a stir prompt while a slow read is in flight elsewhere. */
        const val THREADS = 2

        /**
         * How many lines one read may bring back.
         *
         * Capped so that a follower joining a session with ten thousand events
         * in it does not pull all of them into memory in one go; the loop asks
         * again while a batch comes back full.
         */
        const val BATCH = 200

        val log = LoggerFactory.getLogger(SessionTail::class.java)
    }
}
