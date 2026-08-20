package io.mszymanski.orknux.server.llm

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import io.mszymanski.orknux.connector.model.ChatTurn
import org.springframework.data.domain.PageRequest

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
     * What was said before, as turns to put in front of the model.
     *
     * This is the half that makes a session memory rather than a log. Without
     * it an agent writes everything down and reads none of it back, so the
     * second run asks its question of a model that has never heard of the
     * first - which is a transcript, not a conversation.
     *
     * Only what was *said* comes back. A tool call is recorded for somebody
     * reading the session, but replaying one across turns would hand the model
     * a call it never made in this exchange, with no answer threaded to it, and
     * a system note is this application talking about the conversation rather
     * than in it. Both belong in the transcript and neither belongs in the
     * prompt.
     *
     * Bounded twice, because a session is designed to outlive things and will
     * eventually be longer than any context window. The most recent turns are
     * the ones kept: [MEMORY_TURNS] of them at most, and only while they fit in
     * [MEMORY_CHARS] - counted from the newest backwards, so the cut falls at
     * the oldest turn rather than in the middle of the newest.
     */
    fun remembered(session: Long): List<ChatTurn> =
        tail(session) { events.latest(session, SAID, PageRequest.of(0, MEMORY_TURNS)) }
            .map { ChatTurn(it.role, it.content) }

    /**
     * The same tail as it stood before a moment, and who said each line of it.
     *
     * For reading back what something else copied out of here. [remembered]
     * answers a model, which only needs the words; this answers whoever wants
     * to know who spoke them - which the copy could not keep, because the place
     * it was copied into stores a role and some text and has nowhere to put a
     * name.
     *
     * Bounded by when the copy was taken, because a chat continuing a session
     * writes back into it: asked without the bound, a session would hand back
     * the chat's own turns as though they had been there to be copied.
     *
     * Shaped by exactly the same rules as [remembered] - same kinds, same
     * counts, same order - so what comes back is the tail that was copied
     * rather than something merely like it.
     */
    fun saidBefore(session: Long, before: OffsetDateTime): List<RememberedTurn> =
        tail(session) { events.latestBefore(session, SAID, before, PageRequest.of(0, MEMORY_TURNS)) }

    /**
     * The bounding and the ordering, in one place.
     *
     * Both readers want the same tail and differ only in where they stop, so
     * the rules that decide how much of a session is memory are written once.
     * Two copies of them would be two answers to "what was said", and the
     * second one to drift would be a chat labelling turns it did not carry.
     */
    private fun tail(session: Long, read: () -> List<LlmSessionEvent>): List<RememberedTurn> {
        val recent = try {
            read()
        } catch (failure: Exception) {
            // Same bargain as writing: an unreadable memory is a worse answer,
            // not a failed run.
            log.warn("Session {} could not be read back", session, failure)
            return emptyList()
        }

        var room = MEMORY_CHARS
        return recent
            .asSequence()
            .mapNotNull { event -> event.content?.takeIf { it.isNotBlank() }?.let { event to it } }
            .takeWhile { (_, said) ->
                room -= said.length
                room >= 0
            }
            .map { (event, said) ->
                RememberedTurn(
                    role = if (event.kind == LlmSessionEventKind.USER) "user" else "assistant",
                    content = said,
                    actor = event.actor,
                )
            }
            .toList()
            .asReversed()
    }

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

        /** How many said turns come back at most. */
        const val MEMORY_TURNS = 40

        /**
         * And how much of them, in characters.
         *
         * A rough stand-in for a token budget: the count is the thing every
         * model agrees on, and this is a ceiling on what a session may add to a
         * prompt rather than an attempt to fill one exactly.
         */
        const val MEMORY_CHARS = 24_000

        /** The kinds that were said by somebody, and so can be said again. */
        val SAID = listOf(LlmSessionEventKind.USER, LlmSessionEventKind.AGENT)

        val log = LoggerFactory.getLogger(LlmSessionRecorder::class.java)
    }
}

/**
 * One line of a session's memory, with the name on it.
 *
 * The name is the whole reason this exists beside [ChatTurn]: a turn put to a
 * model is a role and some words, because that is all a provider takes, while
 * anybody reading a transcript needs to know which agent, tool or person the
 * words belong to.
 */
data class RememberedTurn(val role: String, val content: String, val actor: String)
