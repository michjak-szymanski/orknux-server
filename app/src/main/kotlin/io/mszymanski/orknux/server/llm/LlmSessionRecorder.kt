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
            .map(::turn)
            .map { said -> ChatTurn(said.role, said.content) }

    /**
     * The same tail as it stood before a moment, read the way a person reads
     * it: who said each line, and the calls made in between them.
     *
     * The other half of the split [remembered] is one side of. A model is given
     * what was said and nothing else, for the reason written there. A person
     * looking at the same stretch is asking how the answer was arrived at, and
     * that stretch with the lookup taken out of it reads as the agent having
     * simply known - which is the one thing somebody opened it to find out. So
     * the two readings are two methods rather than one with a flag, and only
     * this one puts the calls back.
     *
     * Nothing from here is ever put in front of a model. A [RememberedTurn]
     * that is [RememberedTurn.called] has no result threaded to it and never
     * had one; it is a line to read.
     *
     * Bounded by when the copy was taken, because a chat continuing a session
     * writes back into it: asked without the bound, a session would hand back
     * the chat's own turns as though they had been there to be copied.
     *
     * What was *said* is shaped by exactly the same rules as [remembered] -
     * same kinds, same counts, same order - so the turns that come back are the
     * tail that was copied rather than something merely like it, and the calls
     * are threaded through it without ever counting as part of it.
     */
    fun readBefore(session: Long, before: OffsetDateTime): List<RememberedTurn> =
        withCalls(
            session,
            tail(session) { events.latestBefore(session, SAID, before, PageRequest.of(0, MEMORY_TURNS)) },
        ).map(::turn)

    /**
     * The bounding and the ordering, in one place.
     *
     * Both readers want the same tail and differ only in where they stop, so
     * the rules that decide how much of a session is memory are written once.
     * Two copies of them would be two answers to "what was said", and the
     * second one to drift would be a chat labelling turns it did not carry.
     */
    private fun tail(session: Long, read: () -> List<LlmSessionEvent>): List<LlmSessionEvent> {
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
            .map { (event, _) -> event }
            .toList()
            .asReversed()
    }

    /**
     * The calls made between the ends of a tail, put back where they happened.
     *
     * Only between them. A call older than the oldest turn kept was not part of
     * the stretch this tail covers, and one newer than the newest happened
     * after it - so the two turns are the bounds, and a tail of fewer than two
     * turns has no inside for anything to have happened in.
     *
     * The bound is a moment and an id together, because a turn writes its
     * question, its calls and its answer inside the same millisecond: on the
     * clock alone the first call of an exchange and the question that caused it
     * are indistinguishable, and one of them would fall on the wrong side of
     * the other.
     *
     * Failing costs the calls and not the reading. A stretch that could not be
     * filled in is the conversation without its working, which is exactly what
     * was shown before there was any of this.
     */
    private fun withCalls(session: Long, said: List<LlmSessionEvent>): List<LlmSessionEvent> {
        if (said.size < 2) return said
        val first = said.first()
        val last = said.last()

        val calls = try {
            events.calledBetween(
                session,
                LlmSessionEventKind.TOOL,
                first.at,
                last.at,
                PageRequest.of(0, MEMORY_CALLS),
            )
        } catch (failure: Exception) {
            log.warn("The calls in session {} could not be read back", session, failure)
            return said
        }

        val inside = calls.filter { ORDER.compare(first, it) < 0 && ORDER.compare(it, last) < 0 }
        if (inside.isEmpty()) return said
        return (said + inside).sortedWith(ORDER)
    }

    /**
     * One recorded line, in the terms whoever reads it back needs.
     *
     * A call keeps a role of its own rather than being folded into the turn it
     * was made for, because the whole point of putting it back is that a reader
     * can tell the two apart.
     */
    private fun turn(event: LlmSessionEvent) = RememberedTurn(
        role = when (event.kind) {
            LlmSessionEventKind.USER -> "user"
            LlmSessionEventKind.TOOL -> RememberedTurn.CALL
            else -> "assistant"
        },
        content = event.content.orEmpty(),
        actor = event.actor,
    )

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

        /**
         * And how many calls may be threaded back through them.
         *
         * A ceiling rather than a budget. The stretch is already bounded at
         * both ends by turns somebody said, so this only bites on the agent
         * that made hundreds of lookups inside one exchange - and a page nobody
         * can scroll is not a better reading of that than a page that stops.
         */
        const val MEMORY_CALLS = 200

        /** The kinds that were said by somebody, and so can be said again. */
        val SAID = listOf(LlmSessionEventKind.USER, LlmSessionEventKind.AGENT)

        /**
         * The order a session reads in: when a line was written, and then which
         * of the lines written in that same instant came first.
         */
        val ORDER = compareBy<LlmSessionEvent>({ it.at }, { it.id ?: 0L })

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
data class RememberedTurn(val role: String, val content: String, val actor: String) {

    /**
     * True for a line that was a call rather than something anybody said.
     *
     * Asked rather than compared against a string wherever it matters, so there
     * is one spelling of what a call reads as and nothing can drift from it.
     */
    val called: Boolean get() = role == CALL

    companion object {
        /**
         * The role a call reads under.
         *
         * The word a provider uses for the same thing, so a reader that already
         * knows the roles a message can have does not have to learn another
         * one. It never reaches a provider: nothing built for a prompt is built
         * out of these.
         */
        const val CALL = "tool"
    }
}
