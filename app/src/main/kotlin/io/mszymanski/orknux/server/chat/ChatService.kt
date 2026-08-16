package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.ModelChatClient
import org.springframework.ai.chat.memory.ChatMemoryRepository
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.data.repository.findByIdOrNull
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
    private val agents: AgentRepository,
    private val briefing: AgentBriefing,
    private val conversation: AgentConversation,
) {

    fun sessions(workspaceId: Long, userId: String): List<ChatSession> =
        sessions.findByWorkspaceIdAndUserIdOrderByPinnedDescLastMessageAtDescCreatedAtDesc(workspaceId, userId)

    fun session(id: Long): ChatSession? = sessions.findByIdOrNull(id)

    /** Everything said in one chat, oldest first, as the store kept it. */
    fun messages(session: ChatSession): List<ChatMessage> =
        history.findByConversationId(session.conversationId).map(::ChatMessage)

    @Transactional
    fun start(workspaceId: Long, userId: String, title: String, modelId: Long?): ChatSession {
        val trimmed = title.trim().ifEmpty { throw ChatTitleInvalidException() }
        return sessions.save(
            ChatSession(
                workspaceId = workspaceId,
                conversationId = UUID.randomUUID().toString(),
                title = trimmed.take(TITLE_LENGTH),
                userId = userId,
                modelId = modelId,
                createdAt = OffsetDateTime.now(),
            ),
        )
    }

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

        val turns = briefed(session) + (thread + UserMessage(message)).map { ChatTurn(role(it), it.text.orEmpty()) }
        // An agent may need its tools before it can answer; a bare model cannot.
        val asked = session.agentId?.let { agents.findByIdOrNull(it) }
        return when (val answer = if (asked == null) models.complete(modelId, turns) else conversation.answer(modelId, asked, turns)) {
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
    fun beginSend(id: Long, text: String): ChatSendStart {
        val session = sessions.findByIdOrNull(id) ?: throw ChatSessionNotFoundException(id)
        val message = text.trim().ifEmpty { throw ChatMessageEmptyException() }
        val modelId = session.modelId ?: throw ChatModelNotChosenException()

        val thread = history.findByConversationId(session.conversationId)
        history.saveAll(session.conversationId, thread + UserMessage(message))
        session.lastMessageAt = OffsetDateTime.now()

        return ChatSendStart(
            modelId = modelId,
            agentId = session.agentId,
            conversationId = session.conversationId,
            turns = briefed(session) + (thread + UserMessage(message)).map { ChatTurn(role(it), it.text.orEmpty()) },
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
)

/** What one send produced: the answer, and how long the model took over it. */
data class ChatExchange(val session: ChatSession, val answer: String, val millis: Long)
