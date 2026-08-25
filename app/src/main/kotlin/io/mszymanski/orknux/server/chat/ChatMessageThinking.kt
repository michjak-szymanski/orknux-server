package io.mszymanski.orknux.server.chat

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/**
 * What a reasoning model thought on its way to one answer.
 *
 * ## Why this is kept at all
 *
 * It was first built not to be. Thinking was shown on the answer being written
 * and was gone on reload, following what #227 settled for the cost of a turn -
 * and that was rejected. The distinction is that a cost is a number nobody goes
 * back to, and thinking is content: an answer whose reasoning disappears when
 * the page reloads is an answer nobody can check, which is most of the reason
 * for showing it.
 *
 * ## Why it is not in the session, where the tool calls are
 *
 * An agent's lookups survive a reload because they were already going into
 * `llm_session_event`, and a chat handed to an agent opens a session of its own
 * to hold them. Two things independently stop thinking riding that road.
 * Nothing writes reasoning there - [LlmSessionRecorder] keeps what was said and
 * what a tool gave back. And [ChatService.recording] opens a session only for a
 * chat with an agent, on purpose: a chat that calls no tools has nothing to
 * record, and computing a session key for one bends a rule that otherwise holds
 * everywhere. A bare model is the case this feature is most for, so the road is
 * closed exactly where it was wanted.
 *
 * ## And it is not the messages table the rules forbid
 *
 * Same shape and same argument as [ChatAnswerTake], which settled this ground.
 * The conversation is Spring AI's, keyed by a conversation id so a workflow run
 * can share the thread between its agents. What is here was deliberately kept
 * *out* of that thread: it is not part of the conversation, it is never put in
 * front of a model, and the chat screen is the only thing that reads it.
 *
 * Putting it in the thread is not a cheaper way of doing this. It is the bug:
 * everything that reads the thread - the copy control, the speech model, the
 * next turn's prompt - would read the thinking as something the model said.
 */
@Entity
@Table(name = "chat_message_thinking")
class ChatMessageThinking(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "chat_session_id", nullable = false)
    val chatSessionId: Long,

    /**
     * Which answer this is the thinking for: its place in the thread, counted
     * from the start.
     *
     * A position rather than a message id, for the reason [ChatAnswerTake]
     * gives - Spring AI's store gives a message none. The position holds for
     * the life of the chat: the thread is only appended to, and a regenerate
     * replaces the last turn in place rather than inserting.
     */
    @Column(name = "message_index", nullable = false)
    val messageIndex: Int,

    /**
     * Kept whole.
     *
     * What is drawn is folded rather than cut, so there is no length the screen
     * needs this shortened to - and reasoning truncated in the middle is worse
     * than none, because the part that explains the answer is usually its end.
     */
    @Column(nullable = false, columnDefinition = "text")
    var content: String,

    /**
     * How long the thinking went on for, measured over the reasoning frames
     * alone rather than over the turn.
     *
     * Nought where it did not arrive as a stream and there was no duration to
     * measure. The screen draws nothing at all for a nought rather than
     * "thought for 0 seconds", and never borrows the turn's own time to fill
     * the gap - the turn's time is already on the answer's own disclosure, and
     * two numbers on one screen that look like the same measurement and are not
     * is worse than one number missing.
     */
    @Column(nullable = false)
    var millis: Long = 0,

    @Column(name = "thought_at", nullable = false)
    var thoughtAt: OffsetDateTime = OffsetDateTime.now(),
)

interface ChatMessageThinkingRepository : JpaRepository<ChatMessageThinking, Long> {

    /** One chat's thinking, for putting back beside the answers it belongs to. */
    fun findByChatSessionId(chatSessionId: Long): List<ChatMessageThinking>

    /**
     * The row for one answer, or null.
     *
     * Read before writing so a regenerate replaces the thinking at that
     * position rather than adding a second row for it. One answer has one
     * thinking, and the unique constraint says so too - this is what keeps the
     * constraint from being the thing that reports it.
     */
    fun findByChatSessionIdAndMessageIndex(chatSessionId: Long, messageIndex: Int): ChatMessageThinking?
}
