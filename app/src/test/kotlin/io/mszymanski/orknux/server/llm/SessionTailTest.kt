package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * Following a session as it is written.
 *
 * This is the mechanism the task page's live view is, and every one of these is
 * about a promise that page makes rather than about the class in the abstract.
 *
 * The one to read first is [twoReadersOneQuery]. What makes a live view
 * affordable is that watching costs a query per *session* and not per viewer,
 * and it is the kind of property that is true when it is written and quietly
 * false a year later - so it is asserted against a repository that counts what
 * it was asked, rather than left as a paragraph of intent.
 */
@SpringBootTest
class SessionTailTest(
    @Autowired val tail: SessionTail,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val events: LlmSessionEventRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private var session: Long = 0

    @BeforeEach
    fun openASession() {
        workspaceId = requireNotNull(
            workspaces.save(Workspace(name = "Tail ${System.nanoTime()}")).id,
        )
        session = recorder.open(workspaceId, "test", System.nanoTime().toString())
    }

    /**
     * A line written reaches somebody already watching, without anybody asking.
     *
     * The whole feature in one assertion: the recorder is the only thing that
     * was called, and a follower that was attached before it had a line.
     */
    @Test
    fun `a line written reaches a reader that was already watching`() {
        val watch = tail.follow(session, 0)
        try {
            recorder.agentSaid(session, "Ada", "I looked at the tracker")

            assertThat(waitFor(watch)?.content).isEqualTo("I looked at the tracker")
        } finally {
            tail.unfollow(watch)
        }
    }

    /**
     * Somebody joining an hour in is given the hour and nothing after it twice.
     *
     * The cursor is what a reconnect and a late arrival have in common, and this
     * is the late arrival: follow from nought and everything already written
     * arrives, in the order it was written.
     */
    @Test
    fun `a reader joining late is given what it missed`() {
        recorder.userSaid(session, "alice", "find out why it is slow")
        recorder.agentSaid(session, "Ada", "looking")

        val watch = tail.follow(session, 0)
        try {
            val first = waitFor(watch)
            val second = waitFor(watch)
            assertThat(listOfNotNull(first?.content, second?.content))
                .containsExactly("find out why it is slow", "looking")
        } finally {
            tail.unfollow(watch)
        }
    }

    /**
     * And a reader that says where it got to is given only the rest.
     *
     * This is what makes a dropped connection cheap. Without it a page that had
     * been open all night would rebuild itself out of the whole night's
     * transcript every time a proxy timed one out.
     */
    @Test
    fun `a reader that says where it got to is not sent the beginning again`() {
        recorder.userSaid(session, "alice", "the first thing")
        val held = requireNotNull(recorder.agentSaid(session, "Ada", "the second thing"))
        recorder.agentSaid(session, "Ada", "the third thing")

        val watch = tail.follow(session, held)
        try {
            assertThat(waitFor(watch)?.content).isEqualTo("the third thing")
            // And nothing else: what came before the cursor is not owed.
            assertThat(watch.next(300)).isNull()
        } finally {
            tail.unfollow(watch)
        }
    }

    /**
     * A call is handed over twice: when it is made, and when it answers.
     *
     * The reason this is not simply a forward-only tail. A call is recorded
     * before its tool runs, so a page that only ever saw ids it had not seen
     * would show every lookup as permanently running - which is the one state on
     * that page somebody is actually watching for.
     */
    @Test
    fun `a call arrives when it is made and again when it answers`() {
        val watch = tail.follow(session, 0)
        try {
            val line = recorder.toolCalled(session, "orknux_issues", """{"status":"OPEN"}""")

            val made = waitFor(watch)
            assertThat(made?.kind).isEqualTo(LlmSessionEventKind.TOOL)
            assertThat(made?.result).isNull()

            recorder.toolReturned(line, "four open issues")

            val answered = waitFor(watch)
            assertThat(answered?.id).isEqualTo(line)
            assertThat(answered?.result).isEqualTo("four open issues")
        } finally {
            tail.unfollow(watch)
        }
    }

    /**
     * Two people watching one task cost what one does.
     *
     * The cost decision, pinned. Both followers are attached at different
     * cursors on purpose - one that has seen everything and one that has seen
     * nothing - because the coalescing has to survive that or it is not
     * coalescing, it is a coincidence.
     *
     * Counted through the repository rather than argued about: the pass reads
     * once and hands each follower the part it has not seen.
     */
    @Test
    fun twoReadersOneQuery() {
        val caughtUp = requireNotNull(recorder.userSaid(session, "alice", "already read this"))

        val counting = CountingEvents(events)
        val counted = SessionTail(counting)
        val behind = counted.follow(session, 0)
        val ahead = counted.follow(session, caughtUp)
        try {
            // Both followers are attached, and both were stirred on the way in.
            counting.reads.set(0)

            recorder.agentSaid(session, "Ada", "the new line")
            counted.stirred(session)

            assertThat(waitFor(behind, expecting = "the new line")?.content).isEqualTo("the new line")
            assertThat(waitFor(ahead, expecting = "the new line")?.content).isEqualTo("the new line")

            /*
             * One read, or at most the handful a couple of stirs and a backstop
             * tick produce - and crucially not one *per follower*, which is what
             * the number would grow with if every connection were reading for
             * itself. Bounded rather than fixed because the backstop is a timer
             * and a test is not entitled to know how many times it fired.
             */
            assertThat(counting.reads.get()).isLessThanOrEqualTo(READS_ALLOWED)
        } finally {
            counted.unfollow(behind)
            counted.unfollow(ahead)
            counted.destroy()
        }
    }

    /**
     * A session nobody is watching costs nothing at all.
     *
     * The other half of the cost, and the half that is true of nearly every
     * session in an installation: most of what is recorded here is never watched
     * live by anybody, and a stir for one of those must not read a row.
     */
    @Test
    fun `a session nobody is watching is not read`() {
        val counting = CountingEvents(events)
        val counted = SessionTail(counting)
        try {
            counting.reads.set(0)
            counted.stirred(session)
            Thread.sleep(QUIET_MILLIS)

            assertThat(counting.reads.get()).isZero()
            assertThat(counted.followed(session)).isFalse()
        } finally {
            counted.destroy()
        }
    }

    /** The next line owed, waited on for long enough to be a real answer. */
    private fun waitFor(watch: SessionWatch, expecting: String? = null): LlmSessionEvent? {
        val until = System.currentTimeMillis() + WAIT_MILLIS
        while (System.currentTimeMillis() < until) {
            val line = watch.next(WAIT_SLICE) ?: continue
            if (expecting == null || line.content == expecting) return line
        }
        return null
    }

    private companion object {
        /**
         * Long enough for a stir and for the backstop behind it, which is what
         * decides the worst case. Short enough that a broken tail fails the
         * suite in seconds rather than holding it up.
         */
        const val WAIT_MILLIS = 6_000L

        const val WAIT_SLICE = 250L

        /** Long enough that a tail which was going to read would have. */
        const val QUIET_MILLIS = 3_000L

        /**
         * What two followers may cost between them.
         *
         * Generous, and the generosity is the point: what is being caught is the
         * number growing with the *number of viewers*, so anything that does not
         * scale with followers passes and a read-per-follower does not.
         */
        const val READS_ALLOWED = 6
    }
}

/**
 * The real repository, counting the one call the cost argument is about.
 *
 * Delegated rather than mocked, so everything a tail does other than tailing
 * still happens against a real database - what is being measured is how often
 * the tail asks for the next lines, and a stand-in that answered differently
 * would measure a different thing.
 */
private class CountingEvents(
    private val real: LlmSessionEventRepository,
) : LlmSessionEventRepository by real {

    val reads = java.util.concurrent.atomic.AtomicInteger()

    override fun after(
        sessionId: Long,
        after: Long,
        page: org.springframework.data.domain.Pageable,
    ): List<LlmSessionEvent> {
        reads.incrementAndGet()
        return real.after(sessionId, after, page)
    }
}
