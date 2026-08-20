package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.connector.model.ModelKind
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import org.springframework.ai.chat.memory.ChatMemoryRepository
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.data.repository.findByIdOrNull
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Chats, and the history in them.
 *
 * The history is Spring AI's — [ChatMemoryRepository], keyed by the session's
 * conversation id. Nothing here keeps its own copy of a message, which is what
 * makes this reusable: a workflow run will key a conversation the same way, and
 * every agent in that run will read and append to one thread instead of each
 * carrying its own.
 *
 * Spring AI's store holds a role, some text and an order, and nothing else. What
 * a message was answered by, and how long it took, are this application's
 * business, so they are on the session rather than pretended into the store.
 */
@Service
class ChatService(
    private val sessions: ChatSessionRepository,
    private val history: ChatMemoryRepository,
    private val models: ModelChatClient,
    private val catalogue: ModelService,
    private val agents: AgentRepository,
    private val briefing: AgentBriefing,
    private val conversation: AgentConversation,
    private val entityManager: EntityManager,
    private val llmSessions: LlmSessionRepository,
    private val recorder: LlmSessionRecorder,
) {

    fun sessions(workspaceId: Long, userId: String): List<ChatSession> =
        sessions.findByWorkspaceIdAndUserIdOrderByPinnedDescLastMessageAtDescCreatedAtDesc(workspaceId, userId)

    /**
     * The caller's chats in this workspace that said the given thing.
     *
     * Titles are searched on the screen, where every chat is already held; this
     * is the other question — what was actually said — and it is asked of the
     * store rather than by loading every conversation to look through it.
     *
     * Read straight from Spring AI's table because its repository knows one way
     * of finding a message, by conversation. Only the ids come back: the screen
     * has the chats and only needs to know which of them to keep.
     */
    fun mentioning(workspaceId: Long, userId: String, text: String): List<Long> {
        val looking = text.trim()
        if (looking.isEmpty()) return emptyList()

        val held = sessions(workspaceId, userId).associateBy { it.conversationId }
        if (held.isEmpty()) return emptyList()

        @Suppress("UNCHECKED_CAST")
        val found = entityManager
            .createNativeQuery(
                """
                SELECT DISTINCT conversation_id FROM spring_ai_chat_memory
                WHERE conversation_id IN (:conversations) AND content ILIKE :looking ESCAPE '!'
                """.trimIndent(),
            )
            .setParameter("conversations", held.keys)
            // Escaped, so a chat about 100% or a file_name is searched for and
            // not turned into a wildcard by the search itself.
            .setParameter("looking", "%" + looking.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%")
            .resultList as List<String>

        return found.mapNotNull { held[it]?.id }
    }

    fun session(id: Long): ChatSession? = sessions.findByIdOrNull(id)

    /** Everything said in one chat, oldest first, as the store kept it. */
    fun messages(session: ChatSession): List<ChatMessage> =
        history.findByConversationId(session.conversationId).map(::ChatMessage)

    /**
     * Opens a chat, optionally continuing an LLM session.
     *
     * @param llmSessionId the session this chat carries on, or null for the
     *   ordinary chat that carries on nothing. What was already said there is
     *   copied into this chat's thread as it opens, which is what makes it a
     *   continuation rather than a blank box — and is why nothing reads the
     *   session again on any later send: the thread already holds it, and
     *   putting it in front of the model twice would have it hear everything
     *   said so far in duplicate.
     */
    @Transactional
    fun start(
        workspaceId: Long,
        userId: String,
        title: String,
        modelId: Long?,
        llmSessionId: Long? = null,
    ): ChatSession {
        val trimmed = title.trim().ifEmpty { throw ChatTitleInvalidException() }
        // Checked before anything is written: a chat bound to a session it may
        // not write into looks like a continuation and is not one.
        llmSessionId?.let { continuable(workspaceId, it) }

        val opened = sessions.save(
            ChatSession(
                workspaceId = workspaceId,
                conversationId = UUID.randomUUID().toString(),
                title = trimmed.take(TITLE_LENGTH),
                userId = userId,
                modelId = modelId ?: defaultModel(workspaceId, userId),
                llmSessionId = llmSessionId,
                createdAt = OffsetDateTime.now(),
            ),
        )
        llmSessionId?.let { seed(opened, it) }
        return opened
    }

    /**
     * Refuses a session this workspace has no business continuing.
     *
     * A session belongs to one workspace, the same way an agent does, and the
     * chat is opened from that workspace's page — so the only way the two
     * disagree is somebody naming an id that is not theirs. Refused by name
     * rather than by silence, because the caller who meant the right session and
     * mistyped deserves to know which half was wrong.
     */
    private fun continuable(workspaceId: Long, llmSessionId: Long) {
        val session = llmSessions.findByIdOrNull(llmSessionId)
            ?: throw ChatLlmSessionUnusableException("That session no longer exists")
        if (session.workspaceId != workspaceId) {
            throw ChatLlmSessionUnusableException("That session belongs to another workspace")
        }
    }

    /**
     * Puts what was already said into the new chat's thread.
     *
     * [LlmSessionRecorder.remembered] rather than the whole transcript: it is
     * already the tail a model can be shown — what was *said*, bounded, oldest
     * first — and the chat wants exactly that. Tool calls and system notes stay
     * on the session's own page, where somebody reading how the agent worked
     * will look for them; they are not turns in a conversation and pasting them
     * into one would read as the agent talking to itself.
     */
    private fun seed(chat: ChatSession, llmSessionId: Long) {
        val said = recorder.remembered(llmSessionId)
        if (said.isEmpty()) return
        history.saveAll(
            chat.conversationId,
            said.map { if (it.role == "user") UserMessage(it.content) else AssistantMessage(it.content) },
        )
    }

    /**
     * What a chat answers with when the caller named nothing.
     *
     * A chat with no model is a dead end: every send fails, and the reason is
     * only visible once someone has already typed a message. So a new chat picks
     * up the model this person last chatted with here — a chat opens where the
     * last one left off — and failing that any model the workspace can chat
     * with. Choosing stays a preference rather than a prerequisite.
     *
     * Null only when the workspace has no usable chat model at all, which is a
     * real condition worth reporting rather than papering over.
     */
    private fun defaultModel(workspaceId: Long, userId: String): Long? =
        sessions(workspaceId, userId)
            .sortedByDescending { it.lastMessageAt ?: it.createdAt }
            .firstNotNullOfOrNull { it.modelId }
            ?: catalogue.models(workspaceId)
                .firstOrNull { it.enabled && it.kind == ModelKind.CHAT }
                ?.id

    @Transactional
    fun rename(id: Long, title: String): ChatSession {
        val session = sessions.findByIdOrNull(id) ?: throw ChatSessionNotFoundException(id)
        session.title = title.trim().ifEmpty { throw ChatTitleInvalidException() }.take(TITLE_LENGTH)
        return session
    }

    @Transactional
    fun setPinned(id: Long, pinned: Boolean): ChatSession {
        val session = sessions.findByIdOrNull(id) ?: throw ChatSessionNotFoundException(id)
        session.pinned = pinned
        return session
    }

    @Transactional
    fun chooseModel(id: Long, modelId: Long?): ChatSession {
        val session = sessions.findByIdOrNull(id) ?: throw ChatSessionNotFoundException(id)
        session.modelId = modelId
        // Choosing a bare model ends the agent's part in it: what answers next
        // should be what the picker says answers.
        session.agentId = null
        return session
    }

    /**
     * Hands the chat to one of the workspace's agents, or back to a bare model.
     *
     * The agent's model becomes the chat's, because an agent that cannot be run
     * is not one to hand a conversation to — and a chat answering on some other
     * model would not be answering as what the screen says it is.
     */
    @Transactional
    fun chooseAgent(id: Long, agentId: Long?): ChatSession {
        val session = sessions.findByIdOrNull(id) ?: throw ChatSessionNotFoundException(id)
        if (agentId == null) {
            session.agentId = null
            return session
        }

        val agent = agents.findByIdOrNull(agentId) ?: throw ChatAgentUnusableException("That agent no longer exists")
        if (agent.workspaceId != session.workspaceId) {
            throw ChatAgentUnusableException("That agent belongs to another workspace")
        }
        if (!agent.enabled) throw ChatAgentUnusableException("${agent.name} is not active")
        val model = agent.modelId
            ?: throw ChatAgentUnusableException("${agent.name} has no model chosen, so it cannot answer")

        session.agentId = agentId
        session.modelId = model
        return session
    }

    /**
     * Deletes the chat and the history with it.
     *
     * The store is keyed by conversation, not by us, so it has to be told: a
     * deleted chat that left its messages behind would be a conversation nobody
     * can reach and nobody can clear.
     */
    @Transactional
    fun delete(id: Long): Boolean {
        val session = sessions.findByIdOrNull(id) ?: return false
        history.deleteByConversationId(session.conversationId)
        sessions.delete(session)
        return true
    }

    /**
     * Says something, and answers it.
     *
     * The user's turn is written to the history before the model is asked, so a
     * provider that fails leaves the conversation holding what was actually
     * said rather than losing it. The whole thread goes to the model, which is
     * the point of keeping one.
     */
    @Transactional
    fun send(id: Long, text: String): ChatExchange {
        val session = sessions.findByIdOrNull(id) ?: throw ChatSessionNotFoundException(id)
        val message = text.trim().ifEmpty { throw ChatMessageEmptyException() }
        val modelId = session.modelId ?: throw ChatModelNotChosenException()

        val thread = history.findByConversationId(session.conversationId)
        history.saveAll(session.conversationId, thread + UserMessage(message))
        session.lastMessageAt = OffsetDateTime.now()
        recordSaid(session, message)

        val turns = briefed(session) + (thread + UserMessage(message)).map { ChatTurn(role(it), it.text.orEmpty()) }
        // An agent may need its tools before it can answer; a bare model cannot.
        val asked = session.agentId?.let { agents.findByIdOrNull(it) }
        return when (
            val answer = if (asked == null) {
                models.complete(modelId, turns)
            } else {
                conversation.answer(modelId, asked, turns, session.llmSessionId)
            }
        ) {
            is ChatCompletion.Failed -> throw ChatModelUnusableException(answer.reason)
            // The loop runs tools to a conclusion, so nothing reaching here is
            // still asking for one.
            is ChatCompletion.CalledTools ->
                throw ChatModelUnusableException("The model asked for a tool that could not be run")
            is ChatCompletion.Answered -> {
                history.saveAll(
                    session.conversationId,
                    history.findByConversationId(session.conversationId) + AssistantMessage(answer.content),
                )
                session.lastMessageAt = OffsetDateTime.now()
                recordAnswer(session, answer.content)
                ChatExchange(session, answer.content, answer.millis)
            }
        }
    }

    /**
     * The first half of a send: everything settled before the model is asked.
     *
     * Split from [finishSend] because a streaming answer takes as long as the
     * model takes, and holding a database transaction open for a minute holds a
     * connection out of the pool for a minute. The user's turn is still written
     * before the call, for the same reason [send] writes it first.
     */
    @Transactional
    /**
     * @param images pictures sent with this message, as `data:` URLs.
     *
     * Handed to the model as part of the turn rather than described in it: a
     * model that can see is sent the picture, and one that cannot ignores the
     * part. They are not written to the history — that store keeps text, and a
     * base64 image in a conversation log is a conversation log nobody can read.
     */
    fun beginSend(id: Long, text: String, images: List<String> = emptyList()): ChatSendStart {
        val session = sessions.findByIdOrNull(id) ?: throw ChatSessionNotFoundException(id)
        val message = text.trim().ifEmpty { throw ChatMessageEmptyException() }
        val modelId = session.modelId ?: throw ChatModelNotChosenException()

        val thread = history.findByConversationId(session.conversationId)
        history.saveAll(session.conversationId, thread + UserMessage(message))
        session.lastMessageAt = OffsetDateTime.now()
        recordSaid(session, message)

        return ChatSendStart(
            modelId = modelId,
            agentId = session.agentId,
            llmSessionId = session.llmSessionId,
            conversationId = session.conversationId,
            turns = briefed(session) +
                (thread + UserMessage(message)).mapIndexed { at, held ->
                    // Only the last turn is the one being sent now, so only it
                    // carries what was attached to it.
                    val last = at == thread.size
                    ChatTurn(role(held), held.text.orEmpty(), if (last) images else emptyList())
                },
        )
    }

    /**
     * The second half: what the model finally said goes into the history.
     *
     * Read back rather than appended to what [beginSend] had, because the model
     * was thinking for a while and the thread is the source of truth for what
     * is in it.
     */
    @Transactional
    fun finishSend(id: Long, answer: String) {
        val session = sessions.findByIdOrNull(id) ?: throw ChatSessionNotFoundException(id)
        history.saveAll(
            session.conversationId,
            history.findByConversationId(session.conversationId) + AssistantMessage(answer),
        )
        session.lastMessageAt = OffsetDateTime.now()
        recordAnswer(session, answer)
    }

    /**
     * What the person said, into the session this chat is continuing.
     *
     * Under their own name rather than the chat's title, because a transcript
     * that cannot say who spoke is not a transcript — and the point of this is
     * that a later run reading the session finds what a person told it, not
     * another line from an agent.
     *
     * Written before the model is asked, for the same reason the user's turn
     * goes into the history first: a provider that fails should leave the
     * session holding what was actually said.
     */
    private fun recordSaid(session: ChatSession, said: String) {
        session.llmSessionId?.let { recorder.userSaid(it, session.userId, said) }
    }

    /**
     * And what answered, once the answer is whole.
     *
     * Only for a bare model. An agent's answer is already written by
     * [AgentConversation] as its round ends — tool calls and all, which is a
     * record this could not produce — and writing it again here would put every
     * agent answer in the session twice. A bare model has no such round, so
     * nothing else would ever write the line.
     *
     * Called from the end of a send rather than from the stream, so an answer
     * that arrives in eighty pieces is one line in the transcript rather than
     * eighty.
     */
    private fun recordAnswer(session: ChatSession, answer: String) {
        val into = session.llmSessionId ?: return
        if (session.agentId != null) return
        val named = session.modelId?.let { catalogue.model(it) }?.name ?: FALLBACK_ACTOR
        recorder.agentSaid(into, named, answer)
    }

    /** Spring AI's types, in the words a provider's API uses. */
    private fun role(message: Message): String = when (message.messageType) {
        MessageType.USER -> "user"
        MessageType.ASSISTANT -> "assistant"
        MessageType.SYSTEM -> "system"
        MessageType.TOOL -> "tool"
    }

    private companion object {
        /** Matches the column. */
        const val TITLE_LENGTH = 200

        /** What a bare model's answer is signed with once the model it named is gone. */
        const val FALLBACK_ACTOR = "model"
    }

    /**
     * The system turn a chat with an agent opens with.
     *
     * A list rather than a nullable turn so both send paths can prepend it
     * without either having to ask whether there is one — for a chat with a bare
     * model it is simply empty.
     */
    private fun briefed(session: ChatSession): List<ChatTurn> {
        val agent = session.agentId?.let { agents.findByIdOrNull(it) } ?: return emptyList()
        val said = briefing.of(agent) ?: return emptyList()
        return listOf(ChatTurn("system", said))
    }
}

/** One message out of the history. */
data class ChatMessage(val role: String, val content: String) {
    constructor(message: Message) : this(message.messageType.name.lowercase(), message.text.orEmpty())
}

/**
 * What a send needs before the model is called.
 *
 * [agentId] is set when an agent is answering, because the streaming endpoint
 * has to know whether to run the tool loop and cannot ask the session again
 * without another transaction.
 */
data class ChatSendStart(
    val modelId: Long,
    val conversationId: String,
    val turns: List<ChatTurn>,
    val agentId: Long? = null,
    /**
     * The LLM session this round is recorded into, or null for a chat
     * continuing none. Carried here for the same reason [agentId] is: the
     * streaming endpoint runs outside the transaction that read the chat and
     * cannot ask it again.
     */
    val llmSessionId: Long? = null,
)

/** What one send produced: the answer, and how long the model took over it. */
data class ChatExchange(val session: ChatSession, val answer: String, val millis: Long)
