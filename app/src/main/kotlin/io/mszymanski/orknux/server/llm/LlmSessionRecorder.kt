package io.mszymanski.orknux.server.llm

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/**
 * Writes what happened into a session.
 *
 * The one door in. Nothing else saves an event, which is what keeps a session's
 * lines consistent with each other: the same rule about who the actor is, the
 * same moment written on the event and on the session, and the same refusal to
 * let a failed insert take a run down with it.
 *
 * It is called from the agent runtime rather than from a workflow, on purpose.
 * A transcript that a workflow author has to remember to write is a transcript
 * with holes exactly where somebody was busy, and the holes are invisible: a
 * session that recorded half a conversation looks like a session that had half
 * a conversation.
 *
 * No transaction is held across a turn. Each write is its own, because the
 * caller is in the middle of talking to a model and may be there for minutes -
 * a connection held for that long is one nobody else has.
 */
@Service
class LlmSessionRecorder(
    private val sessions: LlmSessionRepository,
    private val events: LlmSessionEventRepository,
) {

    /**
     * The session this key names here, made if it is not there yet.
     *
     * Found rather than created is the ordinary case and the point of the
     * feature: the second workflow to compute this key joins the conversation
     * the first one started.
     *
     * @throws LlmSessionKeyMissingException when [key] is blank.
     * @throws LlmSessionKeyTooLongException when the composed key will not fit.
     */
    fun open(workspaceId: Long, prefix: String?, key: String): Long {
        val composed = LlmSessionKey.of(prefix, key)
        sessions.findByWorkspaceIdAndSessionKey(workspaceId, composed)?.id?.let { return it }

        val front = prefix?.trim()?.ifEmpty { null }
        return try {
            requireNotNull(
                sessions.save(
                    LlmSession(workspaceId = workspaceId, sessionKey = composed, keyPrefix = front),
                ).id,
            )
        } catch (clash: DataIntegrityViolationException) {
            /*
             * Two runs reached the same session in the same instant, which is
             * a thing this feature invites rather than an accident. The unique
             * index picked a winner; the loser reads its row and carries on
             * into the conversation it meant to join.
             */
            log.debug("Session {} was opened by something else first", composed, clash)
            requireNotNull(sessions.findByWorkspaceIdAndSessionKey(workspaceId, composed)?.id) {
                "Session $composed collided on insert and is not there afterwards"
            }
        }
    }

    /** What was put to the agent: a person's question, or a node's prompt. */
    fun userSaid(session: Long, actor: String, said: String) =
        write(session, LlmSessionEventKind.USER, actor, said)

    /** What the agent finally answered, under the agent's own name. */
    fun agentSaid(session: Long, agent: String, said: String) =
        write(session, LlmSessionEventKind.AGENT, agent, said)

    /**
     * A tool the agent called, under the tool's name, with what it was passed.
     *
     * The arguments as the model sent them - unparsed and unprettied. What is
     * being recorded is what the agent asked for, and reformatting it would be
     * this deciding what the model meant.
     */
    fun toolCalled(session: Long, tool: String, arguments: String) =
        write(session, LlmSessionEventKind.TOOL, tool, arguments)

    /** Something that happened to the conversation rather than in it. */
    fun note(session: Long, said: String) =
        write(session, LlmSessionEventKind.SYSTEM, SYSTEM, said)

    /**
     * Saves the line, and moves the session's clock with it.
     *
     * It never throws. Recording is a side effect of a conversation somebody is
     * having, and losing a line of the transcript is not a reason to fail the
     * run that was having it - the answer still reaches whoever asked, and the
     * gap is in the log rather than in the work.
     */
    private fun write(session: Long, kind: LlmSessionEventKind, actor: String, content: String?) {
        try {
            val at = OffsetDateTime.now()
            events.save(
                LlmSessionEvent(
                    sessionId = session,
                    kind = kind,
                    actor = actor.trim().ifEmpty { SYSTEM }.take(ACTOR_LENGTH),
                    content = content,
                    at = at,
                ),
            )
            sessions.findByIdOrNull(session)?.let {
                it.lastEventAt = at
                sessions.save(it)
            }
        } catch (failure: Exception) {
            log.warn("A {} line could not be recorded in session {}", kind, session, failure)
        }
    }

    private companion object {
        /** What a note is signed with; nothing said it. */
        const val SYSTEM = "system"

        val log = LoggerFactory.getLogger(LlmSessionRecorder::class.java)
    }
}
