package io.mszymanski.orknux.connector.connection

import com.slack.api.model.ConversationType
import com.slack.api.model.User
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/** Which of an action's two target fields is being asked about. */
enum class SlackTargetKind {
    CHANNEL,
    USER,
}

/** What asking a Slack connection about a typed target came back with. */
enum class SlackTargetOutcome {

    /** Slack named it. */
    FOUND,

    /**
     * Slack answered, and nothing it can see goes by that. Advice and never a
     * verdict: see [SlackTargetCheck].
     */
    NOT_FOUND,

    /**
     * The question was never put. A scope the token does not carry, no token at
     * all, a rate limit, a workspace too large for one lookup. Kept apart from
     * [NOT_FOUND] because the two want opposite things done about them, and
     * because a field that reads as broken when the token merely cannot
     * introspect is worse than a field with no checking in it.
     */
    UNCHECKED,
}

/**
 * Whether a typed Slack user or channel is one a connection can see.
 *
 * **Advice, not a gate.** Nothing refuses a save on the strength of this. A
 * member who joined a minute ago, a private channel the bot was never invited
 * to, and an id pasted out of somebody else's message all read as [NOT_FOUND]
 * from here while being perfectly correct, so the wording of every answer says
 * what was observed rather than what should be done about it.
 *
 * @property message one sentence or two, ready to put on a screen. Never empty,
 *   and never carries a credential.
 * @property id Slack's own id, when it was found.
 * @property label what Slack calls it - `#general`, `Alice Adams` - when it was
 *   found.
 */
data class SlackTargetCheck(
    val outcome: SlackTargetOutcome,
    val message: String,
    val id: String? = null,
    val label: String? = null,
)

/**
 * Asks a Slack connection whether a user or a channel somebody typed is one it
 * can see.
 *
 * Here rather than in the server for the reason [OutgoingMessages] is: this is
 * where the token lives, and the token is decrypted in this module and nowhere
 * else. The server asks a question and is told an answer.
 *
 * **One lookup, and no cache.** A call per question, with no page after the
 * first. That is deliberate and it is why the answers are worded the way they
 * are: what one lookup cannot settle is reported as [SlackTargetOutcome.UNCHECKED]
 * rather than guessed at. A picker over the same two endpoints wants a cache per
 * connection, pagination and something to say about staleness; none of that is
 * needed to catch a typo, and all of it would have to be right before the typo
 * could be caught at all.
 *
 * **The scope is the whole design.** `users.list` and `users.info` need
 * `users:read`, and the channel lookups need `channels:read` and `groups:read`.
 * A bot token that posts perfectly well carries none of them, because nothing
 * about *sending* requires them. So a token that cannot introspect has to
 * produce a different answer from a name that is wrong, in words that say the
 * connection is fine and the typing has not been judged. [missingScope] is that
 * sentence, and it is the part a picker will reuse unchanged.
 *
 * **No token reaches a message or a log.** The credential is passed to the SDK
 * and is not interpolated into anything that comes back out; a failure is logged
 * by connection id.
 */
@Component
class SlackDirectory(
    private val connections: WorkspaceConnectionRepository,
    slackClients: SlackClients,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** For the same reason [OutgoingMessages] does not use `Slack.getInstance()`. */
    private val slack = slackClients.webApi

    /**
     * @param typed exactly what is in the field: `#general`, `general`,
     *   `@alice`, an address, or an id pasted out of Slack.
     */
    fun check(connectionId: Long, kind: SlackTargetKind, typed: String): SlackTargetCheck {
        val name = typed.trim()
        if (name.isEmpty()) return unchecked("")

        val connection = connections.findByIdOrNull(connectionId)
            ?: return unchecked("That connection no longer exists.")

        if (connection.type != ConnectionType.SLACK) {
            return unchecked("Not a Slack connection.")
        }

        val token = connection.secret?.takeIf { it.isNotBlank() }
            ?: return unchecked("Not checked - no bot token stored.")

        // The token that opens the socket is not the token that looks anything
        // up, and the same sentence [OutgoingMessages] gives about posting.
        if (token.startsWith(APP_TOKEN_PREFIX)) {
            return unchecked("Not checked - needs the xoxb- bot token, not the app-level one.")
        }

        return try {
            when (kind) {
                SlackTargetKind.CHANNEL -> channel(connection.name, token, name)
                SlackTargetKind.USER -> user(connection.name, token, name)
            }
        } catch (failure: Exception) {
            // Logged rather than passed on. The SDK's own exception text is a
            // response body and has never held a token, but a message that goes
            // to a screen is not the place to start relying on that.
            log.warn("Could not ask Slack about a {} on connection {}", kind, connectionId, failure)
            unchecked("Not checked - Slack could not be reached.")
        }
    }

    /**
     * A channel by id when it looks like one, and by name otherwise.
     *
     * Both types are asked for in the one call, because the honest question is
     * what this connection can see and a list of only the public half presented
     * as the whole would rule out private channels it never looked at. A token
     * that can read one and not the other therefore gets "not checked", which is
     * true.
     */
    private fun channel(connectionName: String, token: String, typed: String): SlackTargetCheck {
        val name = typed.removePrefix("#")

        if (CHANNEL_ID.matches(name)) {
            val answer = slack.methods(token).conversationsInfo { it.channel(name) }
            if (answer.isOk) {
                val hit = answer.channel
                return found(hit.id, "#${hit.name}", "#${hit.name}")
            }
            return when (answer.error) {
                CHANNEL_NOT_FOUND -> notFound("No channel with the id $name.")
                else -> refusal(connectionName, typed, answer.error, CHANNEL_SCOPES, "channel names")
            }
        }

        val answer = slack.methods(token).conversationsList {
            it.limit(PAGE)
                .excludeArchived(false)
                .types(listOf(ConversationType.PUBLIC_CHANNEL, ConversationType.PRIVATE_CHANNEL))
        }
        if (!answer.isOk) return refusal(connectionName, typed, answer.error, CHANNEL_SCOPES, "channel names")

        val hit = answer.channels.orEmpty().firstOrNull {
            name.equals(it.name, ignoreCase = true) || name.equals(it.nameNormalized, ignoreCase = true)
        }
        if (hit != null) return found(hit.id, "#${hit.name}", "#${hit.name}")

        // Nothing beyond the first page is read, so nothing beyond it is ruled
        // out either. Saying "no such channel" off a list that was cut short is
        // exactly the wrong answer to give about a name somebody knows is right.
        if (!answer.responseMetadata?.nextCursor.isNullOrBlank()) {
            return unchecked("Not checked - more channels here than one lookup reads.")
        }

        return notFound("No channel called #$name - though a private one this bot is not in looks the same.")
    }

    /** A member by id, by address, or by whichever of the several names Slack keeps. */
    private fun user(connectionName: String, token: String, typed: String): SlackTargetCheck {
        val name = typed.removePrefix("@")

        if (USER_ID.matches(name)) {
            val answer = slack.methods(token).usersInfo { it.user(name) }
            return when {
                answer.isOk -> found(answer.user)
                answer.error == USER_NOT_FOUND ->
                    notFound("No member with the id $name.")
                else -> refusal(connectionName, typed, answer.error, USER_SCOPES, "user names")
            }
        }

        // An address has its own method and its own scope. Worth the branch:
        // without it an address falls through to the name match below, matches
        // nothing, and is reported as a member who does not exist.
        if (EMAIL.matches(name)) {
            val answer = slack.methods(token).usersLookupByEmail { it.email(name) }
            return when {
                answer.isOk -> found(answer.user)
                answer.error == USERS_NOT_FOUND || answer.error == USER_NOT_FOUND ->
                    notFound("No member with the address $name.")
                else -> refusal(connectionName, typed, answer.error, EMAIL_SCOPES, "addresses")
            }
        }

        val answer = slack.methods(token).usersList { it.limit(PAGE) }
        if (!answer.isOk) return refusal(connectionName, typed, answer.error, USER_SCOPES, "user names")

        val hit = answer.members.orEmpty().firstOrNull { !it.isDeleted && it.answersTo(name) }
        if (hit != null) return found(hit)

        if (!answer.responseMetadata?.nextCursor.isNullOrBlank()) {
            return unchecked("Not checked - more members here than one lookup reads.")
        }

        return notFound("No member called @$name - though somebody who just joined looks the same.")
    }

    /**
     * Every name Slack lets a person be addressed by, because a field somebody
     * types into is filled in from whichever one they were looking at.
     */
    private fun User.answersTo(name: String): Boolean = sequenceOf(
        this.name,
        realName,
        profile?.displayName,
        profile?.displayNameNormalized,
        profile?.realName,
        profile?.realNameNormalized,
    ).any { it != null && it.equals(name, ignoreCase = true) }

    private fun found(user: User): SlackTargetCheck {
        val label = user.profile?.displayName?.ifBlank { null }
            ?: user.realName?.ifBlank { null }
            ?: user.name
        return found(user.id, label, label)
    }

    /**
     * Slack answered something other than an answer.
     *
     * The one that matters is `missing_scope`; the rest are here so that none of
     * them is quietly turned into "it does not exist", which is the only way
     * this feature can do harm.
     */
    private fun refusal(
        connectionName: String,
        typed: String,
        error: String?,
        scopes: String,
        what: String,
    ): SlackTargetCheck = when (error) {
        MISSING_SCOPE, NOT_ALLOWED_TOKEN_TYPE -> missingScope(connectionName, typed, scopes, what)
        RATELIMITED -> unchecked("Not checked - Slack is rate-limiting this connection. Try again shortly.")
        INVALID_AUTH, TOKEN_REVOKED, ACCOUNT_INACTIVE -> unchecked(
            "Not checked - Slack refused this connection's bot token ($error).",
        )
        else -> unchecked("Not checked - Slack answered ${error ?: "nothing usable"}.")
    }

    /**
     * The sentence this whole feature is built around, and the one a picker over
     * the same endpoints will need unchanged.
     *
     * It has three jobs and they are all the same job. It has to say the
     * connection is not broken, because a person reading a warning under a field
     * they filled in correctly will go and look at the connection otherwise. It
     * has to say the typing has not been judged, because "could not check" and
     * "does not exist" look identical on a screen unless the words separate
     * them. And it has to say what to do, because the scope is addable and the
     * only reason it is missing is that sending never needed it.
     */
    private fun missingScope(
        connectionName: String,
        typed: String,
        scopes: String,
        what: String,
    ): SlackTargetCheck = unchecked("Not checked - this connection's bot token has no $scopes scope.")

    private fun found(id: String?, label: String, message: String) =
        SlackTargetCheck(SlackTargetOutcome.FOUND, message, id, label)

    private fun notFound(message: String) = SlackTargetCheck(SlackTargetOutcome.NOT_FOUND, message)

    private fun unchecked(message: String) = SlackTargetCheck(SlackTargetOutcome.UNCHECKED, message)

    private companion object {
        const val APP_TOKEN_PREFIX = "xapp-"

        /**
         * One page, and the largest either endpoint takes. Not a page size so
         * much as the size of the only page there is: what does not fit is
         * reported as unchecked rather than paged through.
         */
        const val PAGE = 1000

        /**
         * A Slack id is nine characters or more and is written in upper case, so
         * a channel name - which Slack keeps lower case - cannot be mistaken for
         * one. Kept strict for that reason: a name read as an id would be looked
         * up by `conversations.info`, come back `channel_not_found`, and be
         * reported as a channel that does not exist.
         */
        val CHANNEL_ID = Regex("""[CGD][A-Z0-9]{8,}""")
        val USER_ID = Regex("""[UW][A-Z0-9]{8,}""")

        /** Enough to tell an address from a handle, and no more than that. */
        val EMAIL = Regex("""[^@\s]+@[^@\s]+\.[^@\s]+""")

        const val CHANNEL_SCOPES = "the channels:read and groups:read scopes"
        const val USER_SCOPES = "the users:read scope"
        const val EMAIL_SCOPES = "the users:read and users:read.email scopes"

        const val MISSING_SCOPE = "missing_scope"
        const val NOT_ALLOWED_TOKEN_TYPE = "not_allowed_token_type"
        const val RATELIMITED = "ratelimited"
        const val INVALID_AUTH = "invalid_auth"
        const val TOKEN_REVOKED = "token_revoked"
        const val ACCOUNT_INACTIVE = "account_inactive"
        const val CHANNEL_NOT_FOUND = "channel_not_found"
        const val USER_NOT_FOUND = "user_not_found"
        const val USERS_NOT_FOUND = "users_not_found"
    }
}
