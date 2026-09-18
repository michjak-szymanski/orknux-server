package io.mszymanski.orknux.connector.connection

import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/** What a link to a Slack message came to. */
sealed interface LinkedMessage {

    /** The message the link points at, as data. */
    data class Found(
        val channel: String,
        val ts: String,
        val user: String?,
        val text: String,
        /** The thread it sits in, where it sits in one. */
        val threadTs: String?,
    ) : LinkedMessage

    /** Nothing happened, and it was not a failure: no such connection, no token, not a link. */
    data class NotPossible(val reason: String) : LinkedMessage

    /** Asked and refused. Slack's own word for it, passed through. */
    data class Refused(val reason: String) : LinkedMessage
}

/**
 * Reads the one message a Slack permalink points at.
 *
 * A message pasted into another message travels as its permalink -
 * `https://team.slack.com/archives/C123/p1726650000123456` - and until now
 * nothing could follow it: a script saw the address of a message it could not
 * read. The link names the channel and the timestamp, which is everything a
 * lookup needs, so the follow is made here - where the credentials live, on
 * the server's side of the sandbox line, like every other Slack call.
 *
 * A link into a thread carries `thread_ts` in its query, and the reply it
 * points at is only readable through `conversations.replies`; a top-level
 * message comes back from `conversations.history` asked for exactly one.
 *
 * Needs the history scope for the channel's kind on the bot token -
 * `channels:history`, `groups:history`, `im:history` or `mpim:history`.
 */
@Component
class SlackMessages(
    private val connections: WorkspaceConnectionRepository,
    private val credentials: ConnectionCredentials,
    slackClients: SlackClients,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Not `Slack.getInstance()`, for the reason [OutgoingMessages] gives. */
    private val slack = slackClients.webApi

    /**
     * @param link the message's permalink, as Slack writes one.
     * @param on the only workspace whose connections this may reach, or null for
     *   a caller that answers to no one workspace. A boundary, not a filter; see
     *   [SlackThreads.read].
     */
    fun read(connectionId: Long, link: String, on: Long? = null): LinkedMessage {
        val pointed = parse(link)
            ?: return LinkedMessage.NotPossible(
                "that is not a Slack message link; one looks like https://…slack.com/archives/C…/p…",
            )

        val connection = connections.findByIdOrNull(connectionId)
            ?: return LinkedMessage.NotPossible("the connection it would read through has been deleted")

        // Said as though it were not there, which is what it is to this caller.
        if (on != null && connection.workspaceId != on) {
            return LinkedMessage.NotPossible("the connection it would read through has been deleted")
        }

        if (connection.type != ConnectionType.SLACK) {
            return LinkedMessage.NotPossible("${connection.type} connections have no messages to read")
        }

        val token = credentials.secretOf(connection).credential
            ?: return LinkedMessage.NotPossible("${connection.name} has no bot token stored")

        if (token.startsWith(APP_TOKEN_PREFIX)) {
            return LinkedMessage.Refused(
                "${connection.name} has an app-level token where its bot token belongs; " +
                    "reading a message needs the xoxb- token",
            )
        }

        return try {
            /*
             * A reply only exists inside its thread: history returns the parent
             * for a thread's ts, and the link says which case this is by
             * carrying thread_ts. Either way exactly one message is asked for.
             */
            val found = if (pointed.threadTs != null) {
                val answer = slack.methods(token).conversationsReplies { request ->
                    request.channel(pointed.channel).ts(pointed.threadTs).latest(pointed.ts).inclusive(true).limit(1)
                }
                if (!answer.isOk) return LinkedMessage.Refused(answer.error ?: "Slack refused the read")
                answer.messages?.lastOrNull { it.ts == pointed.ts }
            } else {
                val answer = slack.methods(token).conversationsHistory { request ->
                    request.channel(pointed.channel).latest(pointed.ts).oldest(pointed.ts).inclusive(true).limit(1)
                }
                if (!answer.isOk) return LinkedMessage.Refused(answer.error ?: "Slack refused the read")
                answer.messages?.firstOrNull()
            } ?: return LinkedMessage.Refused("the message the link points at is not there any more")

            LinkedMessage.Found(
                channel = pointed.channel,
                ts = found.ts,
                user = found.user,
                text = found.text.orEmpty(),
                threadTs = found.threadTs,
            )
        } catch (failure: Exception) {
            log.warn("Could not read a linked message on connection {}", connectionId, failure)
            LinkedMessage.Refused(failure.message ?: "Slack could not be reached")
        }
    }

    /** The channel and timestamp a permalink spells, or null where it spells neither. */
    private fun parse(link: String): Pointed? {
        val match = PERMALINK.find(link.trim()) ?: return null
        val (channel, digits) = match.destructured
        // p1726650000123456 is the ts 1726650000.123456: the dot goes back
        // before the last six digits, which is how Slack itself unfolds it.
        if (digits.length <= MICROS) return null
        val ts = digits.dropLast(MICROS) + "." + digits.takeLast(MICROS)
        val threadTs = THREAD_TS.find(link)?.groupValues?.get(1)
        return Pointed(channel, ts, threadTs)
    }

    private data class Pointed(val channel: String, val ts: String, val threadTs: String?)

    private companion object {
        const val APP_TOKEN_PREFIX = "xapp-"
        const val MICROS = 6

        /** The archive path every permalink carries, whatever the team's own host is. */
        val PERMALINK = Regex("""/archives/([CGD][A-Z0-9]+)/p(\d+)""")
        val THREAD_TS = Regex("""[?&]thread_ts=(\d+\.\d+)""")
    }
}
