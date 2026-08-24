package io.mszymanski.orknux.connector.connection

import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** Whether a connection's bot token was able to say who it posts as. */
enum class SlackBotUserOutcome {

    /** Slack named the user, and [SlackBotUser.userId] is it. */
    FOUND,

    /**
     * The question was never answered: no token, the app-level token in the bot
     * token's place, a credential Slack refused, or Slack out of reach. Kept
     * apart from a null id so that "we do not know" cannot be read as "nobody".
     */
    UNCHECKED,
}

/**
 * Who a Slack connection posts as.
 *
 * **A bot token is a Slack user.** That is the whole of why this exists: a
 * thread reply carries `parent_user_id`, the author of the message it hangs
 * under, and the only way to ask "is this a reply to something *we* wrote" is to
 * know the user id each of our bot tokens writes under. `auth.test` answers
 * that, needs no scope at all, and is asked once per token rather than once per
 * reply.
 *
 * **Two connections holding the same bot token are the same Slack user**, and
 * nothing about an arriving event can tell them apart. [message] says so where
 * it is true rather than leaving a picker to imply a distinction Slack does not
 * make.
 *
 * @property name the connection's name, so a list of these can be drawn without
 *   a second lookup.
 * @property message one line, ready to put on a screen, and empty when there is
 *   nothing worth saying — which is the usual answer. Never carries a credential.
 * @property userId Slack's own id for the bot user, `U0123456789`, when it was
 *   found. This is what `parent_user_id` is compared against.
 * @property handle what Slack calls that user, `@orknux`, when it was found.
 * @property receives whether the token carries a scope under which a channel's
 *   messages arrive at all, and **null when Slack did not say** — a response
 *   that carried no scope header has not reported an absence, and reporting one
 *   would send somebody to fix a token that is fine.
 */
data class SlackBotUser(
    val connectionId: Long,
    val name: String,
    val outcome: SlackBotUserOutcome,
    val message: String,
    val userId: String? = null,
    val handle: String? = null,
    val teamId: String? = null,
    val receives: Boolean? = null,
)

/**
 * Which Slack user each of a workspace's connections posts as, asked once and
 * remembered.
 *
 * Here rather than in the server for the reason [SlackDirectory] is: this is
 * where the token is decrypted, and the token is decrypted in this module and
 * nowhere else.
 *
 * **Never per event.** A busy channel delivers a message a second and the reply
 * filter runs on every one of them; an `auth.test` per reply would spend the
 * connection's rate limit on a question whose answer is a property of the token.
 * So an answer is kept for [GOOD_FOR], keyed by connection and thrown away the
 * moment the credential behind it changes — the same fingerprint rule
 * [SlackListener] closes a session on, for the same reason.
 *
 * **Bounded by what an installation has.** One entry per Slack connection and
 * never more, so this grows with the connections somebody made rather than with
 * traffic. An entry for a connection that has gone is dropped on the next miss.
 *
 * **No token reaches a message or a log.** The credential is handed to the SDK;
 * a failure is logged by connection id.
 */
@Component
class SlackBotUsers(
    private val connections: WorkspaceConnectionRepository,
    /** Where the bot token comes from: the connection's own copy, or a workspace secret. */
    private val credentials: ConnectionCredentials,
    slackClients: SlackClients,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** For the same reason [SlackDirectory] does not use `Slack.getInstance()`. */
    private val slack = slackClients.webApi

    private val known = ConcurrentHashMap<Long, Remembered>()

    /**
     * One connection's bot user, from the cache where it is fresh and from
     * Slack where it is not.
     */
    fun identify(connectionId: Long): SlackBotUser {
        val connection = connections.findByIdOrNull(connectionId)
            ?: return unchecked(connectionId, "—", "That connection no longer exists.")

        if (connection.type != ConnectionType.SLACK) {
            return unchecked(connectionId, connection.name, "Not a Slack connection.")
        }

        val token = credentials.secretOf(connection).credential
            ?: return unchecked(connectionId, connection.name, "No bot token stored, so it posts as nobody.")

        // The token that opens the socket is not the token that posts, and the
        // two are easy to paste into each other's box.
        if (token.startsWith(APP_TOKEN_PREFIX)) {
            return unchecked(
                connectionId,
                connection.name,
                "Needs the xoxb- bot token, not the app-level one.",
            )
        }

        val fingerprint = token.hashCode()
        remembered(connectionId, fingerprint)?.let { return it.copy(name = connection.name) }

        val asked = ask(connectionId, connection.name, token)
        known[connectionId] = Remembered(fingerprint, asked)
        return asked
    }

    /**
     * Several at once, each told where it shares its Slack user with another in
     * the same list.
     *
     * The note is made here rather than by whatever draws the list, because it
     * is a fact about Slack and not about a screen: one token is one user, and
     * two connections carrying the same token are one user twice over.
     */
    fun identify(connectionIds: Collection<Long>): List<SlackBotUser> {
        val each = connectionIds.distinct().map(::identify)
        val byUser = each.filter { it.userId != null }.groupBy { it.userId }

        return each.map { one ->
            val alongside = byUser[one.userId].orEmpty().filter { it.connectionId != one.connectionId }
            // Only where there is nothing more pressing to say: a token that
            // cannot authenticate has a problem, and this is a caveat.
            if (alongside.isEmpty() || one.message.isNotEmpty()) one else one.copy(message = sharedWith(alongside))
        }
    }

    /**
     * The Slack user ids these connections post under — what `parent_user_id` is
     * compared against.
     *
     * A connection whose token could not be asked contributes nothing rather
     * than a placeholder, so a reply is never matched by an id nobody has.
     */
    fun userIdsOf(connectionIds: Collection<Long>): Set<String> =
        connectionIds.distinct().mapNotNull { identify(it).userId }.toSet()

    /** Forgets one answer, for a caller that has just changed the credential. */
    fun forget(connectionId: Long) {
        known.remove(connectionId)
    }

    /**
     * `auth.test`, which every valid token answers and no scope is needed for.
     *
     * Which is what makes it the right question here: a bot token set up only to
     * post carries no read scope at all, and a check that needed one would
     * report every such connection as broken.
     */
    private fun ask(connectionId: Long, name: String, token: String): SlackBotUser = try {
        val answer = slack.methods(token).authTest { it }
        if (answer.isOk) {
            val missing = missingHistory(answer.httpResponseHeaders)
            SlackBotUser(
                connectionId = connectionId,
                name = name,
                outcome = SlackBotUserOutcome.FOUND,
                message = missing?.let(::historyLine).orEmpty(),
                userId = answer.userId,
                handle = answer.user?.let { "@$it" },
                teamId = answer.teamId,
                receives = missing?.isEmpty(),
            )
        } else {
            // Slack's own word for it - `invalid_auth`, `token_revoked` - which
            // is the useful half of the sentence.
            unchecked(connectionId, name, "Slack refused the bot token: ${answer.error ?: "no reason given"}.")
        }
    } catch (failure: Exception) {
        // Logged rather than passed on: the SDK's message is a response body,
        // and a line that goes to a screen is not the place to start trusting it.
        log.warn("Could not ask Slack who connection {} posts as", connectionId, failure)
        unchecked(connectionId, name, "Slack could not be reached, so this was not checked.")
    }

    /**
     * The history scopes this token is short of, empty when it has one, and null
     * when the response said nothing about scopes at all.
     *
     * Read off `x-oauth-scopes`, which every Web API response carries and a
     * stand-in need not. Null is the honest answer to a header that was not
     * there — an absence Slack did not report is not an absence.
     */
    private fun missingHistory(headers: Map<String, List<String>>?): List<String>? {
        val granted = headers?.get(OAUTH_SCOPES)?.firstOrNull()?.split(",")?.map { it.trim() } ?: return null
        if (granted.isEmpty()) return null
        return if (HISTORY_SCOPES.any { it in granted }) emptyList() else HISTORY_SCOPES
    }

    /**
     * One line, and the public channel named first: it is what nearly everybody
     * means, and the other three are what the same scope is called elsewhere.
     */
    private fun historyLine(missing: List<String>) = if (missing.isEmpty()) {
        ""
    } else {
        "Messages will not arrive - the bot token carries no ${missing.first()}."
    }

    private fun sharedWith(alongside: List<SlackBotUser>): String {
        val names = alongside.joinToString(" and ") { it.name }
        return "The same Slack user as $names, so a reply cannot tell them apart."
    }

    private fun remembered(connectionId: Long, fingerprint: Int): SlackBotUser? {
        val held = known[connectionId] ?: return null
        // A rotated token is a different user until proved otherwise, so the
        // answer goes with the credential it was asked under.
        if (held.fingerprint != fingerprint) {
            known.remove(connectionId, held)
            return null
        }
        val goodFor = if (held.answer.outcome == SlackBotUserOutcome.FOUND) GOOD_FOR else A_REFUSAL_IS_GOOD_FOR
        if (System.currentTimeMillis() - held.readAt > goodFor.toMillis()) {
            known.remove(connectionId, held)
            return null
        }
        return held.answer
    }

    private fun unchecked(connectionId: Long, name: String, why: String) = SlackBotUser(
        connectionId = connectionId,
        name = name,
        outcome = SlackBotUserOutcome.UNCHECKED,
        message = why,
    )

    private class Remembered(
        val fingerprint: Int,
        val answer: SlackBotUser,
        val readAt: Long = System.currentTimeMillis(),
    )

    private companion object {
        /**
         * Long enough that a busy channel costs one call, short enough that a
         * token reinstalled with more scopes is noticed without a restart.
         */
        val GOOD_FOR: Duration = Duration.ofMinutes(10)

        /** "Try again shortly" is advice, and advice that waits ten minutes is not. */
        val A_REFUSAL_IS_GOOD_FOR: Duration = Duration.ofSeconds(30)

        const val OAUTH_SCOPES = "x-oauth-scopes"

        /** What the app-level token starts with, so it can be told from the bot one. */
        const val APP_TOKEN_PREFIX = "xapp-"

        /**
         * The four scopes a `message` event arrives under, one per kind of
         * conversation. Any one of them means something can arrive.
         */
        val HISTORY_SCOPES = listOf("channels:history", "groups:history", "im:history", "mpim:history")
    }
}
