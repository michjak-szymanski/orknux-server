package io.mszymanski.gyloli.connector.connection

/**
 * Something that happened at the other end of a connection.
 *
 * This module owns the sockets and the credentials that open them, but it knows
 * nothing about triggers or workflows — those live in the application, which
 * depends on this module and not the other way round. So an arriving event is
 * published, and whoever cares subscribes.
 */
data class IncomingEvent(
    /** The team connection the event arrived on. */
    val connectionId: Long,
    val teamId: Long,
    val action: IncomingAction,
    /** What was said, where it can be read as text. */
    val text: String? = null,
    /**
     * Everything else worth handing to a workflow: for Slack, the channel, the
     * user, and the message timestamps that a reply has to quote.
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
