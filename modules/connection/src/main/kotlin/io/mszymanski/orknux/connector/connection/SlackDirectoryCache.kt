package io.mszymanski.orknux.connector.connection

import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * One connection's copy of one of the two Slack lists.
 *
 * Two keys per connection and never a third. A question asked without a kind
 * wants both lists and asks for both of these rather than caching their union
 * under a key of its own, which would read everything a second time and then
 * expire on its own clock. Whichever half a narrowed question already warmed is
 * a merged question's for free.
 */
internal data class SlackListingKey(val connectionId: Long, val kind: SlackTargetKind)

/**
 * How far a read of one Slack list got, and what it found.
 *
 * A read that stopped short is still worth keeping: half a member list suggests
 * half a member list. What it must never do is let anything conclude that a name
 * it does not hold is a name nobody has, which is why [complete] travels with
 * the entries rather than being worked out from how many there are.
 *
 * @property stoppedOn Slack's own error when the read ended on one -
 *   `ratelimited`, `missing_scope` - and null when it ended on the page limit or
 *   did not end short at all.
 */
internal class SlackListing(
    val entries: List<SlackSuggestion>,
    val complete: Boolean,
    val stoppedOn: String? = null,
    val readAt: Long = System.currentTimeMillis(),
)

/**
 * The Slack lists this process has already read, kept so that typing a name does
 * not read them again.
 *
 * **Why there is a cache at all.** `users.list` and `conversations.list` are
 * paginated and rate-limited, and a real Slack has thousands of members. A
 * suggestion box asks on every keystroke; without something in between, six
 * characters typed at speed are six full reads of the same list, and Slack
 * answers the seventh with `ratelimited` - for everybody, the workflow posting a
 * message included, because the limit belongs to the connection and not to this
 * feature. So the list is read once and filtered in memory, which is also the
 * only way it can be filtered: neither endpoint takes a search term.
 *
 * **How long an entry is good for.** Five minutes, and thirty seconds for one
 * that stopped short. Nothing here is a verdict - the field stays free text and
 * the check beside it asks Slack live - so a stale entry costs one name typed by
 * hand instead of picked, where no cache costs the rate limit above. Thirty
 * seconds for a partial read because "try again shortly" is the advice it comes
 * with, and advice that cannot be taken for five minutes is not advice.
 *
 * **Bounded in both directions.** [MOST_KEPT] lists at a time, the least
 * recently asked about dropped first, and the reader caps how many entries one
 * list may hold. An installation with two hundred workspaces and a Slack each
 * would otherwise grow this without limit and never give any of it back, which
 * is a leak with a schedule rather than a cache.
 *
 * **One read at a time, per list.** A person fills a box faster than Slack
 * answers, so the first call reads and the rest wait on that same read rather
 * than starting their own. A caller still waiting after [WAIT_FOR_A_READ] is
 * told the list is being read, in those words: a suggestion box that says
 * nothing yet is right, and one that says "no such channel" because the list has
 * not arrived is wrong in the one way this feature must never be wrong.
 */
internal class SlackListingCache {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Least recently read, first out. Access-ordered, so the list somebody is
     * typing against stays and the one nobody has asked about since this
     * morning goes.
     */
    private val kept = object : LinkedHashMap<SlackListingKey, SlackListing>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<SlackListingKey, SlackListing>): Boolean = size > MOST_KEPT
    }

    /** The reads happening right now, so that two callers cannot start the same one. */
    private val reading = ConcurrentHashMap<SlackListingKey, FutureTask<SlackListing>>()

    /**
     * @param read what to do on a miss. Called at most once per key at a time,
     *   on whichever caller missed first.
     * @return null when the list is being read and this caller's wait ran out -
     *   a state to report, and never an emptiness to draw a conclusion from.
     */
    fun listing(key: SlackListingKey, read: () -> SlackListing): SlackListing? {
        fresh(key)?.let { return it }

        val mine = FutureTask(read)
        val running = reading.putIfAbsent(key, mine)
        if (running != null) return waitFor(key, running)

        return try {
            mine.run()
            mine.get().also { keep(key, it) }
        } catch (failure: Exception) {
            log.warn("Could not read the {} list of connection {}", key.kind, key.connectionId, failure)
            null
        } finally {
            // Removed after the entry is kept, so a caller arriving in between
            // finds either the finished read or the kept entry, and never a gap
            // that would start a second read.
            reading.remove(key, mine)
        }
    }

    private fun waitFor(key: SlackListingKey, running: FutureTask<SlackListing>): SlackListing? = try {
        running.get(WAIT_FOR_A_READ.toMillis(), TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        null
    } catch (failure: Exception) {
        log.warn("Waiting on the {} list of connection {} came to nothing", key.kind, key.connectionId, failure)
        null
    }

    private fun fresh(key: SlackListingKey): SlackListing? {
        val held = synchronized(kept) { kept[key] } ?: return null
        val goodFor = if (held.complete) GOOD_FOR else A_PARTIAL_READ_IS_GOOD_FOR
        if (System.currentTimeMillis() - held.readAt > goodFor.toMillis()) {
            synchronized(kept) { kept.remove(key) }
            return null
        }
        return held
    }

    private fun keep(key: SlackListingKey, listing: SlackListing) {
        synchronized(kept) { kept[key] = listing }
    }

    private companion object {
        val GOOD_FOR: Duration = Duration.ofMinutes(5)
        val A_PARTIAL_READ_IS_GOOD_FOR: Duration = Duration.ofSeconds(30)
        val WAIT_FOR_A_READ: Duration = Duration.ofSeconds(5)

        /**
         * Two lists per connection, so this is sixteen Slacks being typed
         * against at once - more than one installation does, and small enough
         * that the worst case is worth writing down: this many times the
         * reader's own cap on the size of one list.
         */
        const val MOST_KEPT = 32
    }
}
