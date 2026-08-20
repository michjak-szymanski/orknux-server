package io.mszymanski.orknux.connector.connection

import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/** What became of a message a workflow asked to send. */
sealed interface Delivery {

    /** Sent. [ts] is the message's own timestamp, which is what a reply threads onto. */
    data class Sent(val channel: String, val ts: String?) : Delivery

    /**
     * Nothing was sent, and it was not a failure: a connection with no
     * credentials, or a service this does not speak yet. The step reports it and
     * the run carries on, because a half-drawn workflow is not a broken one.
     */
    data class NotPossible(val reason: String) : Delivery

    /** Tried and refused. The step fails with this, because somebody meant it to send. */
    data class Refused(val reason: String) : Delivery
}

/**
 * Sends what a workflow decided to say.
 *
 * Here rather than in the server because this is where the credentials are: the
 * connection module owns the row, holds the token, and is the only thing that
 * decrypts it. The server asks for a message to go out and is told what
 * happened; it never sees the key.
 *
 * Only Slack for now, and only messages. The other services have connections and
 * no runtime, which is said plainly rather than pretended around.
 */
@Component
class OutgoingMessages(
    private val connections: WorkspaceConnectionRepository,
    slackClients: SlackClients,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Not `Slack.getInstance()`: that one carries the SDK's own HTTP client and
     * would post to Slack without ever asking whether a proxy rule covers the
     * address. See [SlackClients].
     */
    private val slack = slackClients.webApi

    /**
     * @param target a channel id, a channel name, or a user id — Slack accepts
     *   all three where it wants a conversation.
     * @param threadTs when set, the message joins that thread instead of the
     *   channel. This is what makes a workflow answer where it was asked.
     */
    fun send(connectionId: Long, target: String, text: String, threadTs: String? = null): Delivery {
        val connection = connections.findByIdOrNull(connectionId)
            ?: return Delivery.NotPossible("the connection it sends through has been deleted")

        if (connection.type != ConnectionType.SLACK && connection.type != ConnectionType.SLACK_SOCKET_MODE) {
            return Delivery.NotPossible("${connection.type} connections cannot send messages yet")
        }

        val token = connection.secret?.takeIf { it.isNotBlank() }
            ?: return Delivery.NotPossible("${connection.name} has no bot token stored")

        // The token that opens the socket is not the token that posts. Saying so
        // here saves reading an `invalid_auth` from Slack and guessing why.
        if (token.startsWith(APP_TOKEN_PREFIX)) {
            return Delivery.Refused(
                "${connection.name} has an app-level token where its bot token belongs; " +
                    "posting needs the xoxb- token",
            )
        }

        return try {
            val answer = slack.methods(token).chatPostMessage { request ->
                request.channel(target).text(text)
                if (!threadTs.isNullOrBlank()) request.threadTs(threadTs)
                request
            }

            if (answer.isOk) {
                Delivery.Sent(answer.channel ?: target, answer.ts)
            } else {
                // Slack's own words: `channel_not_found`, `not_in_channel`,
                // `invalid_auth`. Passed through rather than reworded, because
                // they are what the Slack documentation is indexed by.
                Delivery.Refused(answer.error ?: "Slack refused the message")
            }
        } catch (failure: Exception) {
            log.warn("Could not post to Slack on connection {}", connectionId, failure)
            Delivery.Refused(failure.message ?: "Slack could not be reached")
        }
    }

    private companion object {
        const val APP_TOKEN_PREFIX = "xapp-"
    }
}
