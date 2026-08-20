package io.mszymanski.orknux.server.llm

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
     * A tool the agent called, and what it passed.
     *
     * The call and not its result. What a tool returned can be the whole of a
     * file or a page of JSON, and it is already threaded back into the
     * conversation the model sees; what a reader of the transcript wants to
     * know is that the agent went and looked, and what it asked for.
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
 * one is ever filled. [actor] is who produced it, always, and it is a name
 * rather than an id so the line still reads after the agent has been renamed.
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

class LlmSessionKeyTooLongException(length: Int) :
    RuntimeException(
        "A session key is at most ${LlmSessionKey.LONGEST} characters, prefix included, and this one is $length",
    )

class LlmSessionNotFoundException(id: Long) : RuntimeException("No LLM session with id $id")
