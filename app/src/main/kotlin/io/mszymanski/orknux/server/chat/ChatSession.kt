package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.server.graphql.Refusal
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/**
 * One conversation, as this application knows it.
 *
 * The messages are not here. They live in Spring AI's chat memory store, keyed
 * by [conversationId] — which is the reason for using it: a workflow run will
 * key a conversation the same way, so every agent in that run reads and writes
 * one thread instead of each keeping its own. What this holds is everything
 * that store has no opinion about: who owns it, what it is called, what answers
 * it, and whether it is pinned.
 */
@Entity
@Table(name = "chat_session")
class ChatSession(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    /** A UUID: Spring AI's column is VARCHAR(36), which is exactly one. */
    @Column(name = "conversation_id", nullable = false, length = 36)
    val conversationId: String,

    @Column(nullable = false, length = 200)
    var title: String,

    /** The LDAP uid of whoever started it; the sidebar says USER CHATS. */
    @Column(name = "user_id", nullable = false, length = 120)
    val userId: String,

    /** Null once the model it named was removed, which leaves the history readable. */
    @Column(name = "model_id")
    var modelId: Long? = null,

    /**
     * The agent answering, when it is one rather than a bare model.
     *
     * The model stays set alongside: an agent supplies one, and keeping what was
     * actually used means the history still says what answered it after the
     * agent is edited or deleted.
     */
    @Column(name = "agent_id")
    var agentId: Long? = null,

    /**
     * The LLM session this chat writes into, or null for a chat that needs
     * none.
     *
     * Two ways it gets one. It was opened from a session's page, and is
     * continuing a conversation an agent was already having — that is a
     * pointer, set before the chat exists. Or it has an agent of its own, and
     * `ChatService.recording` opens it one on the first send: an agent calls
     * tools, and what a tool returned survives nowhere else once its round has
     * ended.
     *
     * The second of those bends a rule worth restating. A session is found by a
     * key its caller computed, and a chat computes no key — one invented for a
     * chat names a conversation nothing else can arrive at, which is the
     * opposite of what a session is for. It is bent only where a chat has
     * something a thread cannot hold, and bent as narrowly as it can be: the
     * key is this chat's conversation id, so nothing else can arrive at it even
     * by accident. A chat with a bare model calls no tools, has nothing to
     * keep, and still gets nothing at all.
     *
     * Never moved once set, which is why nothing outside that one method
     * assigns it. What was already said is copied into this chat's thread when
     * it opens, and a chat that changed session afterwards would be holding one
     * conversation's words while writing into another's.
     */
    @Column(name = "llm_session_id")
    var llmSessionId: Long? = null,

    @Column(nullable = false)
    var pinned: Boolean = false,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    /** Orders RECENT, and is null on a chat nobody has said anything in. */
    @Column(name = "last_message_at")
    var lastMessageAt: OffsetDateTime? = null,
)

interface ChatSessionRepository : JpaRepository<ChatSession, Long> {

    /**
     * The sidebar, in the order it draws: pinned first, then by when each was
     * last spoken in, then by when it was made — a new chat has no messages and
     * would otherwise sort last.
     */
    fun findByWorkspaceIdAndUserIdOrderByPinnedDescLastMessageAtDescCreatedAtDesc(
        workspaceId: Long,
        userId: String,
    ): List<ChatSession>

    fun findByConversationId(conversationId: String): ChatSession?

    fun findByModelId(modelId: Long): List<ChatSession>
}

class ChatSessionNotFoundException(val id: Long) : RuntimeException("No chat with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

/** An agent that cannot answer, and why — the picker says so rather than failing later. */
class ChatAgentUnusableException(val says: String) : RuntimeException(says), Refusal {

    override val arguments get() = mapOf("says" to says)
}

/**
 * A session that cannot be continued here, and why.
 *
 * Refused at the start rather than at the first send. A chat bound to a session
 * it may not write into is a chat that looks like a continuation and is not one,
 * and the first thing anybody would do with it is say something into nowhere.
 */
class ChatLlmSessionUnusableException(val says: String) : RuntimeException(says), Refusal {

    override val arguments get() = mapOf("says" to says)
}

class ChatTitleInvalidException : RuntimeException("A chat needs a title")

class ChatMessageEmptyException : RuntimeException("There is nothing to send")

class ChatDisabledException : RuntimeException("Chat is turned off for this installation")

class ChatModelNotChosenException :
    RuntimeException("This chat has no model to answer with; choose one first")

class ChatModelUnusableException(val reason: String) : RuntimeException(reason), Refusal {

    override val arguments get() = mapOf("reason" to reason)
}

