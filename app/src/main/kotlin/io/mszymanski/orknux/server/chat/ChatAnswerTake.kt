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
 * An answer the chat gave before it was asked to answer again.
 *
 * Regenerating takes the model's last turn back off the thread before asking:
 * a conversation holding two answers to one question was never had, and the
 * second would be answering the first. This is where the one that was taken off
 * goes, so that pressing the button is not a way of losing the answer you were
 * about to keep.
 *
 * It is not part of the conversation, and this is not the messages table the
 * rules forbid. The conversation is Spring AI's, keyed by a conversation id so
 * a workflow run can share the thread between its agents; what is here was
 * deliberately taken out of that thread, is never put in front of a model, and
 * is read by the chat screen and nothing else.
 */
@Entity
@Table(name = "chat_answer_take")
class ChatAnswerTake(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "chat_session_id", nullable = false)
    val chatSessionId: Long,

    /**
     * Which answer this is an earlier take of: its place in the thread, counted
     * from the start.
     *
     * A position rather than a message id, because Spring AI's store gives a
     * message none - it keeps a conversation, a role, some text and an order.
     * The position holds for the life of the chat: the thread is only appended
     * to, and a regenerate replaces the last turn in place rather than
     * inserting, so nothing after an answer can move it.
     */
    @Column(name = "message_index", nullable = false)
    val messageIndex: Int,

    @Column(nullable = false, columnDefinition = "text")
    val content: String,

    @Column(name = "taken_at", nullable = false)
    val takenAt: OffsetDateTime = OffsetDateTime.now(),
)

interface ChatAnswerTakeRepository : JpaRepository<ChatAnswerTake, Long> {

    /** One chat's earlier takes, oldest first — the order they were said in. */
    fun findByChatSessionIdOrderByIdAsc(chatSessionId: Long): List<ChatAnswerTake>
}

/**
 * There is nothing at the end of this chat to ask again, and why.
 *
 * Only the answer the conversation ends on can be regenerated: anything earlier
 * has been answered on top of, and giving a different answer to a question that
 * was followed by three more turns would rewrite what those turns were replying
 * to.
 */
class ChatNothingToRegenerateException(val says: String) : RuntimeException(says), Refusal {

    override val arguments get() = mapOf("says" to says)
}

