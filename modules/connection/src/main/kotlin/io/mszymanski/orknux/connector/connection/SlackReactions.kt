package io.mszymanski.orknux.connector.connection

import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/** What became of a reaction something asked to add. */
sealed interface Reaction {

    /** Added, or already there - `already_reacted` is success as far as anything here cares. */
    data object Added : Reaction

    /** Nothing happened, and it was not a failure: no such connection, no token. */
    data class NotPossible(val reason: String) : Reaction

    /** Asked and refused. Slack's own word for it, passed through. */
    data class Refused(val reason: String) : Reaction
}

/**
 * Adds an emoji reaction to a message.
 *
 * Here for the reason [OutgoingMessages] and [SlackThreads] are here: this is
 * where the credentials live. The connection module owns the row, holds the bot
 * token and is the only thing that decrypts it - everything else asks for a
 * reaction and is told what came back.
 *
 * Neither a workspace function nor a plugin can do this for itself, for the same
 * reason they cannot read a thread: the sandbox has no socket and is not getting
 * one, so the call is made on this side of that line and what crosses is data.
 *
 * Needs `reactions:write` on the bot token.
 */
@Component
class SlackReactions(
    private val connections: WorkspaceConnectionRepository,
    private val credentials: ConnectionCredentials,
    slackClients: SlackClients,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Not `Slack.getInstance()`, for the reason [OutgoingMessages] gives. */
    private val slack = slackClients.webApi

    /**
     * @param timestamp the message's own `ts`, which is what a reaction hangs on
     *   - the value [Delivery.Sent.ts] carries and the one every thread message
     *   is keyed by.
     * @param emoji the short name, with or without colons: `thumbsup` and
     *   `:thumbsup:` both reach Slack as `thumbsup`.
     * @param on the only workspace whose connections this may reach, or null for
     *   a caller that answers to no one workspace. A boundary, not a filter; see
     *   [SlackThreads.read].
     */
    fun add(connectionId: Long, channel: String, timestamp: String, emoji: String, on: Long? = null): Reaction {
        val connection = connections.findByIdOrNull(connectionId)
            ?: return Reaction.NotPossible("the connection it would react through has been deleted")

        // Said as though it were not there, which is what it is to this caller.
        if (on != null && connection.workspaceId != on) {
            return Reaction.NotPossible("the connection it would react through has been deleted")
        }

        if (connection.type != ConnectionType.SLACK) {
            return Reaction.NotPossible("${connection.type} connections have nothing to react on")
        }

        val token = credentials.secretOf(connection).credential
            ?: return Reaction.NotPossible("${connection.name} has no bot token stored")

        if (token.startsWith(APP_TOKEN_PREFIX)) {
            return Reaction.Refused(
                "${connection.name} has an app-level token where its bot token belongs; " +
                    "adding a reaction needs the xoxb- token",
            )
        }

        // Slack wants the bare name: `:thumbsup:` is `invalid_name`.
        val name = emoji.trim().trim(':')
        if (name.isEmpty()) return Reaction.Refused("a reaction needs an emoji name")

        return try {
            val answer = slack.methods(token).reactionsAdd { request ->
                request.channel(channel).timestamp(timestamp).name(name)
            }

            when {
                answer.isOk -> Reaction.Added
                // Already there is the state the caller wanted, reached by
                // somebody else. Not worth failing a run over.
                answer.error == "already_reacted" -> Reaction.Added
                // Slack's own words - `channel_not_found`, `no_reaction`,
                // `invalid_name` - because that is what its docs are indexed by.
                else -> Reaction.Refused(answer.error ?: "Slack refused the reaction")
            }
        } catch (failure: Exception) {
            log.warn("Could not add reaction on connection {}", connectionId, failure)
            Reaction.Refused(failure.message ?: "Slack could not be reached")
        }
    }

    private companion object {
        const val APP_TOKEN_PREFIX = "xapp-"
    }
}
