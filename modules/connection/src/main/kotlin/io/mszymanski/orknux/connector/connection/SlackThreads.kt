package io.mszymanski.orknux.connector.connection

import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/** One message in a thread, as much of it as anything outside Slack needs. */
data class ThreadMessage(
    val ts: String,
    val user: String?,
    val text: String,
    /** Whether this is the message the thread hangs under rather than a reply to it. */
    val parent: Boolean,
)

/** What a thread turned out to be, or why it could not be read. */
sealed interface Thread {

    /**
     * The thread, oldest first.
     *
     * [replies] is Slack's own count of the replies under the parent, which is
     * not the same as `messages.size - 1`: a page holds what was asked for and
     * the count is of the whole thread. It is the number a filter wants.
     */
    data class Read(val messages: List<ThreadMessage>, val replies: Int) : Thread

    /** Nothing was read, and it was not a failure: no such connection, no token. */
    data class NotPossible(val reason: String) : Thread

    /** Asked and refused. Slack's own word for it, passed through. */
    data class Refused(val reason: String) : Thread
}

/**
 * Reads a Slack thread back.
 *
 * Here for the reason [OutgoingMessages] is here: this is where the credentials
 * are. The connection module owns the row, holds the bot token and is the only
 * thing that decrypts it — everything else asks for a thread and is told what
 * came back.
 *
 * **This is the only way anything in this installation can read a thread, and it
 * has to be.** Neither a workspace function nor a plugin can do it for itself:
 * both run in a sandbox built with `IOAccess.NONE` and `HostAccess.NONE`, and
 * `PluginPermission` is a closed list that deliberately has no spelling for
 * "give me a socket". So the fetch happens on this side of that line and what
 * crosses it is data. See `SlackListener`, which puts the answer on the event a
 * trigger is matched against.
 *
 * Needs `channels:history` and its siblings on the bot token — the same scopes a
 * message trigger already needs, so a workspace hearing messages at all can read
 * the threads they are in.
 */
@Component
class SlackThreads(
    private val connections: WorkspaceConnectionRepository,
    private val credentials: ConnectionCredentials,
    slackClients: SlackClients,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Not `Slack.getInstance()`, for the reason [OutgoingMessages] gives. */
    private val slack = slackClients.webApi

    /**
     * The messages in one thread, oldest first.
     *
     * @param threadTs the parent's timestamp — Slack's `thread_ts`, which is
     *   what every reply in the thread carries and what the trigger context
     *   already holds.
     * @param limit how many to fetch. Capped, because a thread can run to
     *   thousands and nothing that reads one here is trying to hold all of it;
     *   [Thread.Read.replies] answers "how many" without paging.
     */
    fun read(connectionId: Long, channel: String, threadTs: String, limit: Int = DEFAULT_LIMIT): Thread {
        val connection = connections.findByIdOrNull(connectionId)
            ?: return Thread.NotPossible("the connection it would read through has been deleted")

        if (connection.type != ConnectionType.SLACK) {
            return Thread.NotPossible("${connection.type} connections have no threads to read")
        }

        val token = credentials.secretOf(connection).credential
            ?: return Thread.NotPossible("${connection.name} has no bot token stored")

        // The same guard posting has, and for the same reason: an app-level
        // token reads as `invalid_auth` with nothing to say which token is wrong.
        if (token.startsWith(APP_TOKEN_PREFIX)) {
            return Thread.Refused(
                "${connection.name} has an app-level token where its bot token belongs; " +
                    "reading a thread needs the xoxb- token",
            )
        }

        return try {
            val answer = slack.methods(token).conversationsReplies { request ->
                request.channel(channel).ts(threadTs).limit(limit.coerceIn(1, MAX_LIMIT))
            }

            if (!answer.isOk) {
                // Slack's own words - `channel_not_found`, `not_in_channel`,
                // `thread_not_found` - because those are what its documentation
                // is indexed by.
                return Thread.Refused(answer.error ?: "Slack refused the thread")
            }

            val messages = answer.messages.orEmpty()
            Thread.Read(
                messages = messages.map { held ->
                    ThreadMessage(
                        ts = held.ts.orEmpty(),
                        user = held.user ?: held.botId,
                        text = held.text.orEmpty(),
                        // The parent is the one whose own ts is the thread's.
                        parent = held.ts == threadTs,
                    )
                },
                /*
                 * Slack's count, and the parent is where it lives. Falling back
                 * to what was fetched is the honest second answer: a thread of
                 * three read as three is right, and it is only wrong past the
                 * limit - where the count would have been the whole point.
                 */
                replies = messages.firstOrNull { it.ts == threadTs }?.replyCount
                    ?: (messages.size - 1).coerceAtLeast(0),
            )
        } catch (failure: Exception) {
            log.warn("Could not read thread {} in {} on connection {}", threadTs, channel, connectionId, failure)
            Thread.Refused(failure.message ?: "Slack could not be reached")
        }
    }

    private companion object {
        /**
         * Its own copy, as [SlackBotUsers] keeps one and [OutgoingMessages]
         * keeps one. Three files each say what an app-level token looks like
         * rather than one saying it for all three, which is the shape the rest
         * of this module is already in.
         */
        const val APP_TOKEN_PREFIX = "xapp-"

        /**
         * Enough to see what a conversation is about without paging.
         *
         * A filter asking "is this the first reply" needs two; an agent handed a
         * thread to read wants the shape of it. Neither wants a thousand, and
         * the count comes back whatever this is.
         */
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
    }
}
