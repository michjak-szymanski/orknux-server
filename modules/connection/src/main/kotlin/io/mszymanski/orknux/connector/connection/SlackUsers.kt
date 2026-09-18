package io.mszymanski.orknux.connector.connection

import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/** Who a Slack user id belongs to, or why that could not be said. */
sealed interface SlackUser {

    /**
     * The person. [displayName] is what the workspace sees beside their
     * messages and may be empty; [realName] is what their profile says they are
     * called; [name] is the old username every id still has.
     */
    data class Found(
        val id: String,
        val name: String,
        val realName: String?,
        val displayName: String?,
        /** True for an app's user, which mentions and blame both care about. */
        val bot: Boolean,
    ) : SlackUser

    /** Nothing happened, and it was not a failure: no such connection, no token. */
    data class NotPossible(val reason: String) : SlackUser

    /** Asked and refused. Slack's own word for it, passed through. */
    data class Refused(val reason: String) : SlackUser
}

/**
 * Says who a Slack user id is.
 *
 * A mention arrives in a message as `<@U0123ABCD>`, which names nobody: the id
 * is Slack's and the person behind it is a lookup away. That lookup is made
 * here - where the credentials live, on the server's side of the sandbox line -
 * so a script handed a message full of mentions can ask who was meant instead
 * of quoting the notation back.
 *
 * Needs `users:read` on the bot token.
 */
@Component
class SlackUsers(
    private val connections: WorkspaceConnectionRepository,
    private val credentials: ConnectionCredentials,
    slackClients: SlackClients,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Not `Slack.getInstance()`, for the reason [OutgoingMessages] gives. */
    private val slack = slackClients.webApi

    /**
     * @param userId the id, however the message spelled it: `U0123ABCD`,
     *   `@U0123ABCD` and `<@U0123ABCD>` are all the same person.
     * @param on the only workspace whose connections this may reach, or null for
     *   a caller that answers to no one workspace. A boundary, not a filter; see
     *   [SlackThreads.read].
     */
    fun info(connectionId: Long, userId: String, on: Long? = null): SlackUser {
        val id = unwrapped(userId)
            ?: return SlackUser.NotPossible("\"$userId\" is not a Slack user id; one looks like U0123ABCD")

        val connection = connections.findByIdOrNull(connectionId)
            ?: return SlackUser.NotPossible("the connection it would ask through has been deleted")

        // Said as though it were not there, which is what it is to this caller.
        if (on != null && connection.workspaceId != on) {
            return SlackUser.NotPossible("the connection it would ask through has been deleted")
        }

        if (connection.type != ConnectionType.SLACK) {
            return SlackUser.NotPossible("${connection.type} connections have no users to ask about")
        }

        val token = credentials.secretOf(connection).credential
            ?: return SlackUser.NotPossible("${connection.name} has no bot token stored")

        if (token.startsWith(APP_TOKEN_PREFIX)) {
            return SlackUser.Refused(
                "${connection.name} has an app-level token where its bot token belongs; " +
                    "looking a user up needs the xoxb- token",
            )
        }

        return try {
            val answer = slack.methods(token).usersInfo { it.user(id) }
            val user = answer.user
            when {
                answer.isOk && user != null -> SlackUser.Found(
                    id = user.id,
                    name = user.name.orEmpty(),
                    realName = user.realName?.ifBlank { null } ?: user.profile?.realName?.ifBlank { null },
                    displayName = user.profile?.displayName?.ifBlank { null },
                    bot = user.isBot,
                )

                // Slack's own words - `user_not_found`, `missing_scope` -
                // because that is what its docs are indexed by.
                else -> SlackUser.Refused(answer.error ?: "Slack refused the lookup")
            }
        } catch (failure: Exception) {
            log.warn("Could not look up a user on connection {}", connectionId, failure)
            SlackUser.Refused(failure.message ?: "Slack could not be reached")
        }
    }

    /** The bare id inside whatever the message wrapped it in, or null. */
    private fun unwrapped(said: String): String? {
        val bare = said.trim().removePrefix("<").removeSuffix(">").removePrefix("@")
        return bare.takeIf { USER_ID.matches(it) }
    }

    private companion object {
        const val APP_TOKEN_PREFIX = "xapp-"

        /** Slack's own shape for a person: members and workspace users both. */
        val USER_ID = Regex("[UW][A-Z0-9]{8,}")
    }
}
