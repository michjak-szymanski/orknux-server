package io.mszymanski.orknux.connector.connection

/**
 * Something that happened at the other end of a connection.
 *
 * This module owns the sockets and the credentials that open them, but it knows
 * nothing about triggers or workflows — those live in the application, which
 * depends on this module and not the other way round. So an arriving event is
 * published, and whoever cares subscribes.
 */
data class IncomingEvent(
    /** The workspace connection the event arrived on. */
    val connectionId: Long,
    val workspaceId: Long,
    val action: IncomingAction,
    /** What was said, where it can be read as text. */
    val text: String? = null,
    /**
     * Everything else worth handing to a workflow: for Slack, the channel, the
     * user, and the message timestamps that a reply has to quote.
     *
     * A thread reply carries `parentUserId` as well — Slack's own id for whoever
     * wrote the message the thread hangs under. It is what "a reply to something
     * *we* wrote" is decided by, because a bot token is a Slack user; see
     * `SlackBotUsers`.
     */
    val context: Map<String, String> = emptyMap(),
)

/**
 * The kinds of event a connection can raise.
 *
 * These names are the contract with the application's trigger actions, which are
 * matched by name — see `IncomingTriggerListener` and the test that pins them
 * together.
 */
enum class IncomingAction {
    MENTION,
    REPLY,
    MESSAGE,
    ISSUE_CREATED,
    ISSUE_UPDATED,
}

/**
 * Which of those a listener actually raises today.
 *
 * The enum is the vocabulary; this is the part of it that is wired. Slack raises
 * three — a mention, a message in a channel it can read, and a thread reply —
 * and nothing raises anything from an issue tracker, so a trigger offered on one
 * of those could never fire. Nothing is more confusing than a configuration
 * screen that accepts a setting the system cannot honour, so what is deliverable
 * is stated here and the trigger catalogue offers exactly this.
 *
 * Adding a publisher means adding its action here, and the compiler will not
 * remind you: the test beside `IncomingTriggerListener` is what does.
 */
object DeliverableActions {
    val published: Set<IncomingAction> =
        setOf(IncomingAction.MENTION, IncomingAction.MESSAGE, IncomingAction.REPLY)
}
