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
    private val directory: SlackDirectory,
    /** Where the bot token comes from: the connection's own copy, or a workspace secret. */
    private val credentials: ConnectionCredentials,
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
     * @param target where it goes, exactly as somebody typed it: `#general`,
     *   `general`, `@alice`, an address, or an id pasted out of Slack. Resolved
     *   to Slack's own id before it is posted — see below.
     * @param threadTs when set, the message joins that thread instead of the
     *   channel. This is what makes a workflow answer where it was asked.
     */
    fun send(connectionId: Long, target: String, text: String, threadTs: String? = null): Delivery {
        val connection = connections.findByIdOrNull(connectionId)
            ?: return Delivery.NotPossible("the connection it sends through has been deleted")

        if (connection.type != ConnectionType.SLACK) {
            return Delivery.NotPossible("${connection.type} connections cannot send messages yet")
        }

        val token = credentials.secretOf(connection).credential
            ?: return Delivery.NotPossible("${connection.name} has no bot token stored")

        // The token that opens the socket is not the token that posts. Saying so
        // here saves reading an `invalid_auth` from Slack and guessing why.
        if (token.startsWith(APP_TOKEN_PREFIX)) {
            return Delivery.Refused(
                "${connection.name} has an app-level token where its bot token belongs; " +
                    "posting needs the xoxb- token",
            )
        }

        /*
         * What was typed, turned into what Slack answers to.
         *
         * `chat.postMessage` resolves a channel *name* itself and does not
         * resolve a person's handle: "@alice" in that argument is a
         * `channel_not_found`, and a person is reached by their user id. So the
         * one field a workflow fills in cannot be handed over as it stands, and
         * the fix belongs here rather than in a rule people have to remember
         * about which sigil to type.
         *
         * Falling back to what was typed is deliberate. A name the connection's
         * listing does not hold is not a name Slack does not have - a private
         * channel this bot is in is the obvious case - and Slack's own answer
         * about it is better than one invented here.
         */
        val channel = directory.resolve(connectionId, target) ?: target

        return try {
            val answer = slack.methods(token).chatPostMessage { request ->
                request.channel(channel).text(text)
                if (!threadTs.isNullOrBlank()) request.threadTs(threadTs)
                request
            }

            if (answer.isOk) {
                Delivery.Sent(answer.channel ?: channel, answer.ts)
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
