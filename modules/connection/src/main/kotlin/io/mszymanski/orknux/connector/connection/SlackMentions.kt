package io.mszymanski.orknux.connector.connection

import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/** What a name became, ready to put in a message. */
sealed interface Mention {

    /**
     * The notation Slack renders as a mention: `<@U…>` for a person,
     * `<!subteam^S…>` for a user group. [label] is who that is, for the
     * sentence being written around it.
     */
    data class Resolved(val mention: String, val id: String, val label: String) : Mention

    /** Nothing answers to the name. Not a failure - the caller asked. */
    data class NobodyCalled(val name: String) : Mention

    /** Nothing happened, and it was not a failure: no such connection, no token. */
    data class NotPossible(val reason: String) : Mention

    /** Asked and refused. Slack's own word for it, passed through. */
    data class Refused(val reason: String) : Mention
}

/**
 * Turns a name into the notation Slack renders as a mention.
 *
 * A posted `@dana` is four characters of text; what pings Dana is `<@U0123ABCD>`,
 * and the id half of that is nothing a script knows. The lookup is made here -
 * where the credentials live, on the server's side of the sandbox line - so a
 * script says who it means and is handed the notation to paste.
 *
 * People are found the way the send target box finds them, through
 * [SlackDirectory]'s cached listings; user groups have no cache and are asked
 * for directly, because a workspace holds a handful of them and the call is a
 * single page.
 *
 * Needs `users:read` for people and `usergroups:read` for groups; a token
 * missing one still answers for the other.
 */
@Component
class SlackMentions(
    private val connections: WorkspaceConnectionRepository,
    private val credentials: ConnectionCredentials,
    private val directory: SlackDirectory,
    slackClients: SlackClients,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Not `Slack.getInstance()`, for the reason [OutgoingMessages] gives. */
    private val slack = slackClients.webApi

    /**
     * @param name who to mention: a display name, a username, an email, an id,
     *   or a user group's handle - with or without the `@`.
     * @param on the only workspace whose connections this may reach, or null for
     *   a caller that answers to no one workspace. A boundary, not a filter; see
     *   [SlackThreads.read].
     */
    fun of(connectionId: Long, name: String, on: Long? = null): Mention {
        val asked = name.trim().removePrefix("@")
        if (asked.isEmpty()) return Mention.NotPossible("a mention needs a name")

        val connection = connections.findByIdOrNull(connectionId)
            ?: return Mention.NotPossible("the connection it would ask through has been deleted")

        // Said as though it were not there, which is what it is to this caller.
        if (on != null && connection.workspaceId != on) {
            return Mention.NotPossible("the connection it would ask through has been deleted")
        }

        if (connection.type != ConnectionType.SLACK) {
            return Mention.NotPossible("${connection.type} connections have nobody to mention")
        }

        // An id already is the answer, whichever kind it is.
        if (USER_ID.matches(asked)) return Mention.Resolved("<@$asked>", asked, asked)
        if (GROUP_ID.matches(asked)) return Mention.Resolved("<!subteam^$asked>", asked, asked)

        /*
         * People first, through the same cached listings the send target box
         * reads - a person is what a mention almost always means, and the
         * cache is what keeps a loop of them off the rate limit.
         */
        directory.resolve(connectionId, "@$asked")?.let { id ->
            if (USER_ID.matches(id)) return Mention.Resolved("<@$id>", id, asked)
        }

        val token = credentials.secretOf(connection).credential
            ?: return Mention.NotPossible("${connection.name} has no bot token stored")

        if (token.startsWith(APP_TOKEN_PREFIX)) {
            return Mention.Refused(
                "${connection.name} has an app-level token where its bot token belongs; " +
                    "resolving a mention needs the xoxb- token",
            )
        }

        return try {
            val answer = slack.methods(token).usergroupsList { it }
            when {
                answer.isOk -> {
                    val group = answer.usergroups.orEmpty().firstOrNull {
                        it.handle.equals(asked, ignoreCase = true) || it.name.equals(asked, ignoreCase = true)
                    }
                    if (group != null) {
                        Mention.Resolved("<!subteam^${group.id}>", group.id, group.handle ?: group.name ?: asked)
                    } else {
                        Mention.NobodyCalled(asked)
                    }
                }

                // A token without usergroups:read has still answered for
                // people above; what it cannot see is not "nobody".
                answer.error == "missing_scope" -> Mention.NobodyCalled(asked)
                else -> Mention.Refused(answer.error ?: "Slack refused the lookup")
            }
        } catch (failure: Exception) {
            log.warn("Could not resolve a mention on connection {}", connectionId, failure)
            Mention.Refused(failure.message ?: "Slack could not be reached")
        }
    }

    private companion object {
        const val APP_TOKEN_PREFIX = "xapp-"

        /** Slack's own shapes: a person, and a user group. */
        val USER_ID = Regex("[UW][A-Z0-9]{8,}")
        val GROUP_ID = Regex("S[A-Z0-9]{8,}")
    }
}
