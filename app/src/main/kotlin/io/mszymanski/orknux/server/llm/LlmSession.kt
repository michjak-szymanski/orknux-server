package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.server.graphql.Refusal
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime

/**
 * What one line of a session is.
 *
 * Four, taken from the four things there are to record and stopping there. A
 * fifth would have to be something that is neither the agent, a tool, whoever
 * asked, nor the machinery, and there is no such speaker.
 */
enum class LlmSessionEventKind {
    /** The agent's answer, as the model finally gave it. */
    AGENT,

    /**
     * A tool the agent called, what it passed, and what came back.
     *
     * Both halves on one line, because they are one thing that happened and
     * because the pairing is then structural: written as two events they could
     * only be matched by order, and a round of parallel calls is written inside
     * a single millisecond.
     *
     * It used to be the call alone, on the ground that what a tool returned was
     * already threaded back into the conversation the model sees. That holds
     * inside one exchange and fails across two - the provider's loop resolves
     * the calls and only the assistant text it produced is kept - so the next
     * turn was answered out of the model's own summary of the data rather than
     * out of the data.
     */
    TOOL,

    /** What was put to the agent - a question, or the node's prompt. */
    USER,

    /**
     * A note from the machinery about the conversation itself.
     *
     * Not something anybody said. An agent that could not answer leaves one of
     * these, so a transcript that goes quiet says why rather than simply
     * stopping.
     */
    SYSTEM,
}

/**
 * How a session's identity is spelled, in the one place that spells it.
 *
 * A caller supplies two halves - an optional prefix and a key - and this is the
 * only thing that joins them. Everything downstream, the runner included, holds
 * the composed key and never the halves, so there is no second rule anywhere
 * that could disagree with this one.
 */
object LlmSessionKey {

    /**
     * What separates the halves.
     *
     * Not nothing, which is what "prefix + key" would literally mean. Joined
     * bare, prefix "issue-" with key "42" is the same string as no prefix with
     * key "issue-42" - two callers who meant different sessions, silently put
     * in one. Two callers landing on one session is the point of this feature
     * when they both computed the same key; it is not the point when the
     * boundary between the halves moved.
     *
     * A prefix containing a colon of its own is left alone: that is somebody
     * naming their own namespace, and it is theirs to name.
     */
    const val SEPARATOR = ":"

    /** As long as the column, so what is composed is what can be stored. */
    const val LONGEST = 300

    /**
     * The composed key, or an exception saying why there is not one.
     *
     * @throws LlmSessionKeyMissingException when there is no key. A prefix on
     *   its own identifies a family of sessions rather than a session.
     * @throws LlmSessionKeyTooLongException when the two together will not fit.
     *   Refused rather than truncated: two keys cut to the same three hundred
     *   characters would be one session, and merging conversations by accident
     *   is worse than saying the key is too long.
     */
    fun of(prefix: String?, key: String): String {
        val held = key.trim()
        if (held.isEmpty()) throw LlmSessionKeyMissingException()

        val front = prefix?.trim().orEmpty()
        val composed = if (front.isEmpty()) held else front + SEPARATOR + held
        if (composed.length > LONGEST) throw LlmSessionKeyTooLongException(composed.length)
        return composed
    }
}

/**
 * A conversation with an agent that no single run owns.
 *
 * Everything an agent said used to belong to whatever asked it: a chat's turns
 * end with the chat, a run's live in that run's log. A session is keyed by
 * something the caller computes instead, so two workflows that arrive at the
 * same key are writing into the same conversation - which is what lets it
 * outlive the run that opened it, and is why nothing here has a workflow or an
 * execution on it.
 *
 * Nobody creates one from a screen. It appears the first time something records
 * into it and is found by its key afterwards, so the interface only ever reads.
 */
@Entity
@Table(name = "llm_session")
class LlmSession(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /**
     * Whose it is.
     *
     * Also what bounds the key: an agent belongs to a workspace, so two teams
     * that both keyed a session on "standup" are not in one conversation.
     */
    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    /** The identity, as [LlmSessionKey] composed it. Never assembled anywhere else. */
    @Column(name = "session_key", nullable = false, length = LlmSessionKey.LONGEST)
    val sessionKey: String,

    /**
     * The prefix as it was given, or null where there was none.
     *
     * Kept rather than read back out of [sessionKey], because a prefix may
     * contain the separator itself and there would be no telling which
     * occurrence was ours.
     */
    @Column(name = "key_prefix", length = 120)
    val keyPrefix: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    /**
     * When anything was last said here, or null on a session opened and not yet
     * written to.
     *
     * Kept on the row because the list is read by recency: worked out from the
     * events instead, ordering one page would mean joining every event of every
     * session in the workspace.
     */
    @Column(name = "last_event_at")
    var lastEventAt: OffsetDateTime? = null,
)

/** Matches the column; a tool with a long name must not fail the insert. */
internal const val ACTOR_LENGTH = 200

/**
 * One thing that happened in a session.
 *
 * What [content] holds depends on [kind] - the words, the arguments of a call,
 * the text of a note - and one column says so rather than four of which exactly
 * one is ever filled. [result] is the exception and is not one of those four: a
 * call and what it returned are two halves of one thing that happened, not two
 * spellings of the payload, and keeping them on one row is what makes the
 * pairing structural rather than something a reader has to reconstruct from the
 * order. [actor] is who produced it, always, and it is a name rather than an id
 * so the line still reads after the agent has been renamed.
 */
@Entity
@Table(name = "llm_session_event")
class LlmSessionEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "session_id", nullable = false)
    val sessionId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val kind: LlmSessionEventKind,

    /** The agent, the tool, the node or person that asked, or "system". */
    @Column(nullable = false, length = ACTOR_LENGTH)
    val actor: String,

    /** The words, the call's arguments, or the note. */
    @Column(columnDefinition = "text")
    val content: String? = null,

    /**
     * What a tool gave back, and null on every line that is not a call.
     *
     * Filled in after the fact: the call is written before the tool runs, so
     * one that hangs still leaves the transcript saying what was asked of it,
     * and this arrives when there is an answer to write. A call that is still
     * null long after it was made is a tool that never returned, which is worth
     * being able to see.
     *
     * Unbounded here on purpose. How much of it a model may be shown again is
     * [LlmSessionRecorder]'s to decide, because that is a question about a
     * prompt; what the record holds is what actually came back.
     */
    @Column(columnDefinition = "text")
    var result: String? = null,

    @Column(nullable = false)
    val at: OffsetDateTime = OffsetDateTime.now(),
)

interface LlmSessionRepository : JpaRepository<LlmSession, Long> {

    /** The one this key names here, which is what "the same session" means. */
    fun findByWorkspaceIdAndSessionKey(workspaceId: Long, sessionKey: String): LlmSession?

    /**
     * The list, filtered the way the page asks.
     *
     * One search across the key and the prefix, because those are the only two
     * things a session is described by and asking which of them somebody meant
     * is asking them to know the schema. The search is never null: Postgres
     * cannot type a null parameter inside `lower()` - it guesses bytea and
     * refuses the function - so "no filter" is the empty string.
     */
    @Query(
        """
        select s from LlmSession s
        where s.workspaceId = :workspaceId
          and (
            :search = ''
            or lower(s.sessionKey) like lower(concat('%', :search, '%'))
            or lower(coalesce(s.keyPrefix, '')) like lower(concat('%', :search, '%'))
          )
        """,
        countQuery = """
        select count(s) from LlmSession s
        where s.workspaceId = :workspaceId
          and (
            :search = ''
            or lower(s.sessionKey) like lower(concat('%', :search, '%'))
            or lower(coalesce(s.keyPrefix, '')) like lower(concat('%', :search, '%'))
          )
        """,
    )
    fun search(workspaceId: Long, search: String, pageable: Pageable): Page<LlmSession>
}

/** How many events one session holds, for a page of sessions. */
interface LlmSessionEventCount {
    val sessionId: Long
    val total: Long
}

interface LlmSessionEventRepository : JpaRepository<LlmSessionEvent, Long> {

    fun countBySessionId(sessionId: Long): Long

    /** Everything said in one session, thrown away with it. */
    fun deleteBySessionId(sessionId: Long)

    /**
     * The tail of a session, oldest first, for putting back in front of a model.
     *
     * Ordered newest-first here and reversed by the caller, because what a
     * limited read wants is the *most recent* N and a database cannot take the
     * last N of an ascending order without reading all of it. The id joins the
     * sort for the reason the transcript's does: a turn writes its question, its
     * tool calls and its answer inside the same millisecond, and a memory that
     * reorders itself between two runs is worse than no memory.
     */
    @Query(
        """
        select e from LlmSessionEvent e
        where e.sessionId = :sessionId
          and e.kind in :kinds
        order by e.at desc, e.id desc
        """,
    )
    fun latest(sessionId: Long, kinds: Collection<LlmSessionEventKind>, page: Pageable): List<LlmSessionEvent>

    /**
     * The same tail, as it stood before a moment.
     *
     * For reading a session the way something else read it earlier. A chat
     * opened from a session copies [latest] into its own thread and then writes
     * back into the session, so asking again afterwards returns a different
     * tail - the chat's own turns included. Bounded by when the reader read, it
     * returns what was there to be copied and nothing the copy caused.
     *
     * Strictly before, so nothing the reader itself went on to write can come
     * back as something it had read.
     */
    @Query(
        """
        select e from LlmSessionEvent e
        where e.sessionId = :sessionId
          and e.kind in :kinds
          and e.at < :before
        order by e.at desc, e.id desc
        """,
    )
    fun latestBefore(
        sessionId: Long,
        kinds: Collection<LlmSessionEventKind>,
        before: OffsetDateTime,
        page: Pageable,
    ): List<LlmSessionEvent>

    /**
     * The calls that returned something, newest first, for putting the data
     * back in front of the model.
     *
     * The other half of what [latest] does for what was said. A model asked a
     * follow-up has its own summary of a lookup in front of it and not the
     * lookup, so "check that again" is answered out of prose; this is what it
     * is answered out of instead.
     *
     * Newest first and reversed by the caller, for the reason [latest] gives.
     * Calls that never returned are left out rather than shown as empty: a tool
     * that hung is a line for the transcript and nothing for a prompt.
     */
    @Query(
        """
        select e from LlmSessionEvent e
        where e.sessionId = :sessionId
          and e.kind = :kind
          and e.result is not null
        order by e.at desc, e.id desc
        """,
    )
    fun latestResults(sessionId: Long, kind: LlmSessionEventKind, page: Pageable): List<LlmSessionEvent>

    /**
     * The calls made inside a stretch of a session, oldest first.
     *
     * For putting a tail back together as it actually read. [latestBefore]
     * answers what was *said*, because that is what a thread was seeded from
     * and what a prompt may hold; this is the working that went on between
     * those turns, which the page it is shown on wants and the model must not
     * be given.
     *
     * Bounded by the two ends of that tail rather than by a count of turns, so
     * what comes back is what happened inside it and nothing on either side.
     * Both ends are inclusive here and narrowed by the caller, because two
     * lines of one exchange are written in the same instant and only the id
     * tells them apart.
     */
    @Query(
        """
        select e from LlmSessionEvent e
        where e.sessionId = :sessionId
          and e.kind = :kind
          and e.at >= :from
          and e.at <= :to
        order by e.at asc, e.id asc
        """,
    )
    fun calledBetween(
        sessionId: Long,
        kind: LlmSessionEventKind,
        from: OffsetDateTime,
        to: OffsetDateTime,
        page: Pageable,
    ): List<LlmSessionEvent>

    /**
     * What was written after a line, oldest first.
     *
     * The tail a live reader follows, and the only query here ordered by id
     * alone. Everything else in this repository reads a session by *when* things
     * were said, because that is how a person reads one; this reads it by the
     * order it was written in, because that is what a cursor can be. A moment is
     * not one: a turn writes its question, its calls and its answer inside the
     * same millisecond, so "everything since 10:04:31.226" is a boundary that
     * falls in the middle of an exchange and cannot be resumed from without
     * either losing a line or sending it twice.
     *
     * `llm_session_event_tail_idx` is what makes it a seek rather than a walk of
     * a session that has been running all night - see V205 for why the index
     * that was already there is not the one this wants.
     */
    @Query(
        """
        select e from LlmSessionEvent e
        where e.sessionId = :sessionId
          and e.id > :after
        order by e.id asc
        """,
    )
    fun after(sessionId: Long, after: Long, page: Pageable): List<LlmSessionEvent>

    /**
     * The counts for a whole page at once.
     *
     * One query rather than one per row: a list of twenty sessions that each
     * asked separately is twenty round trips to draw one column.
     */
    @Query(
        """
        select e.sessionId as sessionId, count(e) as total from LlmSessionEvent e
        where e.sessionId in :ids
        group by e.sessionId
        """,
    )
    fun countsFor(ids: Collection<Long>): List<LlmSessionEventCount>

    /**
     * One session's transcript, searched.
     *
     * The actor is searched alongside the payload so that "skill_load" finds
     * the calls to it, which is the question somebody scanning a long session
     * actually asks. An empty search matches everything, for the reason
     * [LlmSessionRepository.search] explains.
     */
    @Query(
        """
        select e from LlmSessionEvent e
        where e.sessionId = :sessionId
          and (
            :search = ''
            or lower(coalesce(e.content, '')) like lower(concat('%', :search, '%'))
            or lower(e.actor) like lower(concat('%', :search, '%'))
          )
        """,
        countQuery = """
        select count(e) from LlmSessionEvent e
        where e.sessionId = :sessionId
          and (
            :search = ''
            or lower(coalesce(e.content, '')) like lower(concat('%', :search, '%'))
            or lower(e.actor) like lower(concat('%', :search, '%'))
          )
        """,
    )
    fun search(sessionId: Long, search: String, pageable: Pageable): Page<LlmSessionEvent>

    /**
     * The same, narrowed to some of the kinds.
     *
     * A second method rather than a nullable collection parameter, the way the
     * tracker's status filter is two methods: an empty `in` list is not
     * something every database will accept, and "no filter" is the caller's
     * decision rather than a value to pass down.
     */
    @Query(
        """
        select e from LlmSessionEvent e
        where e.sessionId = :sessionId
          and e.kind in :kinds
          and (
            :search = ''
            or lower(coalesce(e.content, '')) like lower(concat('%', :search, '%'))
            or lower(e.actor) like lower(concat('%', :search, '%'))
          )
        """,
        countQuery = """
        select count(e) from LlmSessionEvent e
        where e.sessionId = :sessionId
          and e.kind in :kinds
          and (
            :search = ''
            or lower(coalesce(e.content, '')) like lower(concat('%', :search, '%'))
            or lower(e.actor) like lower(concat('%', :search, '%'))
          )
        """,
    )
    fun searchByKinds(
        sessionId: Long,
        kinds: Collection<LlmSessionEventKind>,
        search: String,
        pageable: Pageable,
    ): Page<LlmSessionEvent>
}

/**
 * A session was asked for with a prefix and nothing else.
 *
 * A prefix names a family of sessions, not a session: with no key there is
 * nothing to tell one member of that family from another, and everything the
 * prefix covers would be one conversation.
 */
class LlmSessionKeyMissingException :
    RuntimeException("A session needs a key; a prefix on its own does not name one")

class LlmSessionKeyTooLongException(val length: Int) :
    RuntimeException(
        "A session key is at most ${LlmSessionKey.LONGEST} characters, prefix included, and this one is $length",
    ), Refusal {

    override val arguments get() = mapOf("length" to length)
}

class LlmSessionNotFoundException(val id: Long) : RuntimeException("No LLM session with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

