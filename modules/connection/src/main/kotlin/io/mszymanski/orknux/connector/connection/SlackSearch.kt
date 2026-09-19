package io.mszymanski.orknux.connector.connection

import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/** What a search of Slack's messages came to. */
sealed interface SlackSearched {

    /** The matches, as data, and Slack's own count of the whole result. */
    data class Found(
        val matches: List<Match>,
        /** How many the whole search holds, not how many came back. */
        val total: Int,
    ) : SlackSearched {

        data class Match(
            val channel: String?,
            val channelName: String?,
            val ts: String?,
            val user: String?,
            val text: String,
            /** The way back to the message, for a reader who wants the thread around it. */
            val permalink: String?,
        )
    }

    /** Nothing happened, and it was not a failure: no such connection, no token. */
    data class NotPossible(val reason: String) : SlackSearched

    /** Asked and refused. Slack's own word for it, passed through. */
    data class Refused(val reason: String) : SlackSearched
}

/**
 * Searches Slack's messages by query, the way the search box does.
 *
 * One honesty note, and it is Slack's rather than ours: `search.messages`
 * answers only for a **user** token (`xoxp-`, with `search:read`). So a
 * connection's user token is what a search runs on when one is stored, and
 * only a connection without one falls back to the bot token - whose
 * `not_allowed_token_type` is then passed through as the true answer instead
 * of being dressed up here, because it says exactly what to do: store a user
 * token on the connection.
 */
@Component
class SlackSearch(
    private val connections: WorkspaceConnectionRepository,
    private val credentials: ConnectionCredentials,
    slackClients: SlackClients,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Not `Slack.getInstance()`, for the reason [OutgoingMessages] gives. */
    private val slack = slackClients.webApi

    /**
     * @param query what to search for, in Slack's own search syntax -
     *   `in:#channel`, `from:@name` and the rest work as they do in the box.
     * @param limit how many matches to bring back, capped to a page.
     * @param on the only workspace whose connections this may reach, or null for
     *   a caller that answers to no one workspace. A boundary, not a filter; see
     *   [SlackThreads.read].
     */
    fun search(connectionId: Long, query: String, limit: Int, on: Long? = null): SlackSearched {
        if (query.isBlank()) return SlackSearched.NotPossible("nothing was asked; the query is empty")

        val connection = connections.findByIdOrNull(connectionId)
            ?: return SlackSearched.NotPossible("the connection it would search through has been deleted")

        // Said as though it were not there, which is what it is to this caller.
        if (on != null && connection.workspaceId != on) {
            return SlackSearched.NotPossible("the connection it would search through has been deleted")
        }

        if (connection.type != ConnectionType.SLACK) {
            return SlackSearched.NotPossible("${connection.type} connections have no messages to search")
        }

        // The user token first, because it is the one search answers for; the
        // bot token only when there is none, so the refusal that comes back
        // names the actual gap rather than a token that was never tried.
        val token = credentials.userTokenOf(connection).credential
            ?: credentials.secretOf(connection).credential
            ?: return SlackSearched.NotPossible("${connection.name} has no token stored")

        return try {
            val answer = slack.methods(token).searchMessages { request ->
                request.query(query).count(limit.coerceIn(1, MOST_MATCHES))
            }
            if (!answer.isOk) return SlackSearched.Refused(answer.error ?: "Slack refused the search")

            SlackSearched.Found(
                matches = answer.messages?.matches.orEmpty().map { match ->
                    SlackSearched.Found.Match(
                        channel = match.channel?.id,
                        channelName = match.channel?.name,
                        ts = match.ts,
                        user = match.user,
                        text = match.text.orEmpty(),
                        permalink = match.permalink,
                    )
                },
                total = answer.messages?.total ?: 0,
            )
        } catch (failure: Exception) {
            log.warn("Could not search Slack on connection {}", connectionId, failure)
            SlackSearched.Refused(failure.message ?: "Slack could not be reached")
        }
    }

    private companion object {
        /** One page of Slack's own search, which is as much as an answer should carry. */
        const val MOST_MATCHES = 50
    }
}
