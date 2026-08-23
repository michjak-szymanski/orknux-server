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
 * @property message one line, ready to put on a screen. Never carries a
 *   credential.
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
 * One user or channel a connection can see, offered for somebody to pick.
 *
 * Picking one is a convenience and never the only way in: what a picker puts in
 * the field is [name], which is exactly what somebody could have typed, and the
 * field goes on accepting what nothing here offered.
 *
 * @property id Slack's own id - `C0123456789`, `U0123456789`.
 * @property name what the field is filled with when this is picked, and what a
 *   person recognises: `#general`, `@alice`.
 * @property realName the member's own name where Slack has one and it says
 *   something the handle does not - `Alice Adams`. Always null for a channel,
 *   whose one name is [name].
 */
data class SlackSuggestion(
    val id: String,
    val name: String,
    val realName: String? = null,
)

/**
 * What a connection has to offer against what somebody has typed so far.
 *
 * The three outcomes are [SlackTargetCheck]'s, and mean here what they mean
 * there. [SlackTargetOutcome.FOUND] is at least one match. [SlackTargetOutcome.NOT_FOUND]
 * is a list read to the end with nothing in it that matches - still advice,
 * because the list is minutes old and because a private channel this bot is not
 * in was never in it. [SlackTargetOutcome.UNCHECKED] is no suggestions and a
 * reason: a token with no read scope, a Slack that is rate-limiting, a list
 * still being read.
 *
 * @property message one line, and empty when there is nothing worth saying -
 *   which is the usual answer. Never carries a credential.
 * @property complete whether [matches] is everything that matches. False when
 *   Slack was not read to the end, and false when more matched than are
 *   returned; both mean the same thing to somebody typing, which is that there
 *   may be more and narrowing will find it.
 */
data class SlackSuggestions(
    val outcome: SlackTargetOutcome,
    val message: String,
    val matches: List<SlackSuggestion> = emptyList(),
    val complete: Boolean = false,
)

/**
 * Asks a Slack connection about the users and channels it can see: whether the
 * one somebody typed is among them, and which ones they might have meant.
 *
 * Here rather than in the server for the reason [OutgoingMessages] is: this is
 * where the token lives, and the token is decrypted in this module and nowhere
 * else. The server asks a question and is told an answer.
 *
 * **Two questions, one vocabulary.** [check] is one lookup about one name, and
 * [suggest] is a list to pick from; they answer with the same
 * [SlackTargetOutcome] and the same sentences, because they fail in the same
 * ways and a form draws them a few pixels apart. Where they differ is what they
 * cost: [check] reads one page and reports what it could not settle, while
 * [suggest] pages, caches per connection, and is bounded on both.
 *
 * **The scope is the whole design.** `users.list` and `users.info` need
 * `users:read`, and the channel lookups need `channels:read` and `groups:read`.
 * A bot token that posts perfectly well carries none of them, because nothing
 * about *sending* requires them. So a token that cannot introspect has to
 * produce a different answer from a name that is wrong, in words that say the
 * connection is fine and the typing has not been judged. That is
 * [SlackTargetOutcome.UNCHECKED], and suggesting degrades to it rather than to
 * a failure: an empty picker with no reason under it reads as a broken
 * connection, which is the one impression this must not leave.
 *
 * **Neither of them gates anything.** The field they describe is free text and
 * stays free text. `createAction` and `updateAction` do not call either.
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
     * Held by the component rather than by a call, which is the whole point of
     * it. See [SlackListingCache] for how long an entry lives and what bounds
     * it.
     */
    private val cache = SlackListingCache()

    /**
     * @param typed exactly what is in the field: `#general`, `general`,
     *   `@alice`, an address, or an id pasted out of Slack.
     */
    fun check(connectionId: Long, kind: SlackTargetKind, typed: String): SlackTargetCheck {
        val name = typed.trim()
        if (name.isEmpty()) return unchecked("")

        val opening = open(connectionId)
        if (opening is Opening.Shut) return unchecked(opening.why)
        val token = (opening as Opening.Ready).token

        return try {
            when (kind) {
                SlackTargetKind.CHANNEL -> channel(token, name)
                SlackTargetKind.USER -> user(token, name)
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
     * The users or channels this connection can see that match what has been
     * typed so far, most likely first.
     *
     * **Filtered here and not by Slack**, because neither endpoint takes a
     * search term: the list is read whole, once, and matched in memory. That is
     * what the cache is for, and it is why an empty [typed] is a perfectly good
     * question - it asks for the first few of everything, which is what a
     * picker shows when it opens.
     *
     * @param typed what is in the field so far, with or without its `#` or `@`.
     */
    fun suggest(connectionId: Long, kind: SlackTargetKind, typed: String): SlackSuggestions {
        val opening = open(connectionId)
        if (opening is Opening.Shut) return none(opening.why)
        val token = (opening as Opening.Ready).token

        // A miss is filled here, on this call. Being read is a state and not an
        // emptiness, which is why the wait ending has an answer of its own
        // rather than coming back as a list with nothing in it.
        val listing = cache.listing(SlackListingKey(connectionId, kind)) { read(connectionId, kind, token) }
            ?: return none("Not checked - this Slack is still being read. Try again in a moment.")

        return offer(kind, listing, typed.trim())
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
    private fun channel(token: String, typed: String): SlackTargetCheck {
        val name = typed.removePrefix("#")

        if (CHANNEL_ID.matches(name)) {
            val answer = slack.methods(token).conversationsInfo { it.channel(name) }
            if (answer.isOk) {
                val hit = answer.channel
                return found(hit.id, "#${hit.name}", "#${hit.name}")
            }
            return when (answer.error) {
                CHANNEL_NOT_FOUND -> notFound("No channel with the id $name.")
                else -> refusal(answer.error, CHANNEL_SCOPES)
            }
        }

        val answer = slack.methods(token).conversationsList {
            it.limit(PAGE)
                .excludeArchived(false)
                .types(listOf(ConversationType.PUBLIC_CHANNEL, ConversationType.PRIVATE_CHANNEL))
        }
        if (!answer.isOk) return refusal(answer.error, CHANNEL_SCOPES)

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
    private fun user(token: String, typed: String): SlackTargetCheck {
        val name = typed.removePrefix("@")

        if (USER_ID.matches(name)) {
            val answer = slack.methods(token).usersInfo { it.user(name) }
            return when {
                answer.isOk -> found(answer.user)
                answer.error == USER_NOT_FOUND ->
                    notFound("No member with the id $name.")
                else -> refusal(answer.error, USER_SCOPES)
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
                else -> refusal(answer.error, EMAIL_SCOPES)
            }
        }

        val answer = slack.methods(token).usersList { it.limit(PAGE) }
        if (!answer.isOk) return refusal(answer.error, USER_SCOPES)

        val hit = answer.members.orEmpty().firstOrNull { !it.isDeleted && it.answersTo(name) }
        if (hit != null) return found(hit)

        if (!answer.responseMetadata?.nextCursor.isNullOrBlank()) {
            return unchecked("Not checked - more members here than one lookup reads.")
        }

        return notFound("No member called @$name - though somebody who just joined looks the same.")
    }

    /**
     * What the connection can be asked with, or the one line saying why it
     * cannot be asked at all.
     *
     * One place, because [check] and [suggest] are refused for the same four
     * reasons and a second copy of these sentences is a second copy to keep
     * short.
     */
    private fun open(connectionId: Long): Opening {
        val connection = connections.findByIdOrNull(connectionId)
            ?: return Opening.Shut("That connection no longer exists.")

        if (connection.type != ConnectionType.SLACK) return Opening.Shut("Not a Slack connection.")

        val token = connection.secret?.takeIf { it.isNotBlank() }
            ?: return Opening.Shut("Not checked - no bot token stored.")

        // The token that opens the socket is not the token that looks anything
        // up, and the same sentence [OutgoingMessages] gives about posting.
        if (token.startsWith(APP_TOKEN_PREFIX)) {
            return Opening.Shut("Not checked - needs the xoxb- bot token, not the app-level one.")
        }

        return Opening.Ready(token)
    }

    /** Whether there is a token to ask with. */
    private sealed interface Opening {
        data class Ready(val token: String) : Opening

        /** @property why one line, ready to show. */
        data class Shut(val why: String) : Opening
    }

    /**
     * Reads one of the two lists whole, as far as the caps allow.
     *
     * Never throws, because this runs inside the cache's single read and a
     * caller waiting on it wants a listing that says it stopped short rather
     * than an exception in somebody else's stack.
     */
    private fun read(connectionId: Long, kind: SlackTargetKind, token: String): SlackListing = try {
        when (kind) {
            SlackTargetKind.CHANNEL -> readChannels(token)
            SlackTargetKind.USER -> readMembers(token)
        }
    } catch (failure: Exception) {
        log.warn("Could not read the {} list of connection {}", kind, connectionId, failure)
        SlackListing(emptyList(), complete = false, stoppedOn = NOT_REACHED)
    }

    /**
     * Archived channels are left out here and counted in by [check], which
     * looks inconsistent and is not: a channel that cannot be posted to has no
     * business being offered, and one somebody has already typed is still worth
     * saying exists.
     */
    private fun readChannels(token: String): SlackListing {
        val entries = mutableListOf<SlackSuggestion>()
        var cursor: String? = null

        repeat(MOST_PAGES) {
            val answer = slack.methods(token).conversationsList { request ->
                request.limit(READ_PAGE)
                    .excludeArchived(true)
                    .types(listOf(ConversationType.PUBLIC_CHANNEL, ConversationType.PRIVATE_CHANNEL))
                cursor?.takeIf { it.isNotBlank() }?.let(request::cursor)
                request
            }
            if (!answer.isOk) return listing(entries, complete = false, stoppedOn = answer.error ?: NOT_REACHED)

            answer.channels.orEmpty().forEach { channel ->
                val id = channel.id ?: return@forEach
                val name = channel.name ?: channel.nameNormalized ?: return@forEach
                entries += SlackSuggestion(id, "#$name")
            }

            cursor = answer.responseMetadata?.nextCursor
            if (cursor.isNullOrBlank()) return listing(entries, complete = true)
        }

        // Slack has more and the cap says stop. Reported as incomplete, which is
        // the difference between a short list and a wrong one.
        return listing(entries, complete = false)
    }

    private fun readMembers(token: String): SlackListing {
        val entries = mutableListOf<SlackSuggestion>()
        var cursor: String? = null

        repeat(MOST_PAGES) {
            val answer = slack.methods(token).usersList { request ->
                request.limit(READ_PAGE)
                cursor?.takeIf { it.isNotBlank() }?.let(request::cursor)
                request
            }
            if (!answer.isOk) return listing(entries, complete = false, stoppedOn = answer.error ?: NOT_REACHED)

            answer.members.orEmpty().forEach { member ->
                if (member.isDeleted) return@forEach
                val id = member.id ?: return@forEach
                val handle = member.name ?: return@forEach
                entries += SlackSuggestion(id, "@$handle", member.ownName(handle))
            }

            cursor = answer.responseMetadata?.nextCursor
            if (cursor.isNullOrBlank()) return listing(entries, complete = true)
        }

        return listing(entries, complete = false)
    }

    /**
     * One entry per id, whatever Slack sent.
     *
     * A cursor that hands back a page already seen is a thing that happens, and
     * the same channel drawn twice in a picker is the kind of defect people
     * report rather than explain away.
     */
    private fun listing(entries: List<SlackSuggestion>, complete: Boolean, stoppedOn: String? = null) =
        SlackListing(entries.distinctBy { it.id }, complete, stoppedOn)

    /**
     * Matches, in the order somebody would expect to see them, and the line to
     * put under them.
     *
     * The one rule worth stating: nothing concludes anything from a list that
     * stopped short. No matches in a complete list is [SlackTargetOutcome.NOT_FOUND];
     * no matches in a partial one is [SlackTargetOutcome.UNCHECKED] and the
     * reason it is partial, because the name may be in the part that was never
     * read.
     */
    private fun offer(kind: SlackTargetKind, listing: SlackListing, typed: String): SlackSuggestions {
        val wanted = typed.removePrefix("#").removePrefix("@")

        val matched = listing.entries
            .mapNotNull { entry -> rank(entry, wanted)?.let { it to entry } }
            .sortedWith(compareBy({ (rank, _) -> rank }, { (_, entry) -> entry.name.lowercase() }))
            .map { (_, entry) -> entry }

        val shown = matched.take(MOST_SHOWN)
        val complete = listing.complete && shown.size == matched.size

        if (shown.isNotEmpty()) {
            return SlackSuggestions(
                SlackTargetOutcome.FOUND,
                if (complete) "" else partial(kind, listing),
                shown,
                complete,
            )
        }

        if (!listing.complete) return none(reason(kind, listing.stoppedOn))

        return SlackSuggestions(SlackTargetOutcome.NOT_FOUND, nothingMatches(kind, typed), complete = true)
    }

    /**
     * How well one entry answers to what was typed, or null when it does not.
     *
     * Exact, then what starts with it, then what contains it - which is the
     * order somebody scanning a list reads them in. The id is matched too, so
     * that one pasted out of Slack finds its own row instead of emptying the
     * list.
     */
    private fun rank(entry: SlackSuggestion, wanted: String): Int? {
        // Nothing typed yet, so everything answers equally and the sort falls
        // through to the name.
        if (wanted.isBlank()) return EXACTLY
        val fields = listOfNotNull(entry.name.removePrefix("#").removePrefix("@"), entry.realName, entry.id)
        return fields.minOf { field ->
            when {
                field.equals(wanted, ignoreCase = true) -> EXACTLY
                field.startsWith(wanted, ignoreCase = true) -> FROM_THE_START
                field.contains(wanted, ignoreCase = true) -> SOMEWHERE
                else -> NOT_AT_ALL
            }
        }.takeIf { it != NOT_AT_ALL }
    }

    /** Why a list somebody is being shown is not the whole of it. */
    private fun partial(kind: SlackTargetKind, listing: SlackListing): String = when {
        listing.complete -> "Showing the first $MOST_SHOWN - keep typing to narrow it down."
        listing.stoppedOn == RATELIMITED -> "Slack is rate-limiting this connection, so this list is partial."
        listing.stoppedOn == null -> "More ${plural(kind)} here than one lookup reads - keep typing to narrow it."
        else -> "Slack stopped answering part way, so this list is partial."
    }

    /**
     * Why there is nothing to show, in the words [check] would have used.
     *
     * Deliberately the same sentences. A person meets these under one field or
     * the other and there is no version of this where two wordings for a
     * missing scope help them.
     */
    private fun reason(kind: SlackTargetKind, stoppedOn: String?): String = when (stoppedOn) {
        null -> "Not checked - more ${plural(kind)} here than one lookup reads."
        NOT_REACHED -> "Not checked - Slack could not be reached."
        else -> refusal(stoppedOn, scopesFor(kind)).message
    }

    private fun nothingMatches(kind: SlackTargetKind, typed: String): String = when {
        typed.isBlank() && kind == SlackTargetKind.CHANNEL -> "No channels this connection can see."
        typed.isBlank() -> "No members this connection can see."
        // The same caveat [check] gives, for the same reason: a list read to the
        // end is still not everything that exists.
        kind == SlackTargetKind.CHANNEL ->
            "No channel matches ${typed.take(TYPED_SHOWN)} - though a private one this bot is not in looks the same."
        else -> "No member matches ${typed.take(TYPED_SHOWN)} - though somebody who just joined looks the same."
    }

    private fun plural(kind: SlackTargetKind) = if (kind == SlackTargetKind.CHANNEL) "channels" else "members"

    private fun scopesFor(kind: SlackTargetKind) =
        if (kind == SlackTargetKind.CHANNEL) CHANNEL_SCOPES else USER_SCOPES

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

    /**
     * The second line of a suggestion, and null when there would not be one:
     * a name that only repeats the handle is a row twice as tall saying half
     * as much.
     */
    private fun User.ownName(handle: String): String? = sequenceOf(
        profile?.displayName,
        realName,
        profile?.realName,
    ).firstOrNull { !it.isNullOrBlank() && !it.equals(handle, ignoreCase = true) }

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
    private fun refusal(error: String?, scopes: String): SlackTargetCheck = when (error) {
        MISSING_SCOPE, NOT_ALLOWED_TOKEN_TYPE -> missingScope(scopes)
        RATELIMITED -> unchecked("Not checked - Slack is rate-limiting this connection. Try again shortly.")
        INVALID_AUTH, TOKEN_REVOKED, ACCOUNT_INACTIVE -> unchecked(
            "Not checked - Slack refused this connection's bot token ($error).",
        )
        else -> unchecked("Not checked - Slack answered ${error ?: "nothing usable"}.")
    }

    /**
     * The sentence this whole feature is built around, and the one the picker
     * reuses unchanged.
     *
     * Two words and a scope, in that order. "Not checked" is what keeps this
     * apart from "does not exist", which is the distinction the feature exists
     * for; the scope is what somebody does about it. Everything else that was
     * once here - that the connection is fine, that sending needs no such scope,
     * that the app has to be reinstalled - was true and was eight lines in a box
     * with room for one.
     */
    private fun missingScope(scopes: String): SlackTargetCheck =
        unchecked("Not checked - this connection's bot token is missing $scopes.")

    private fun found(id: String?, label: String, message: String) =
        SlackTargetCheck(SlackTargetOutcome.FOUND, message, id, label)

    private fun notFound(message: String) = SlackTargetCheck(SlackTargetOutcome.NOT_FOUND, message)

    private fun unchecked(message: String) = SlackTargetCheck(SlackTargetOutcome.UNCHECKED, message)

    private fun none(message: String) = SlackSuggestions(SlackTargetOutcome.UNCHECKED, message)

    private companion object {
        const val APP_TOKEN_PREFIX = "xapp-"

        /**
         * One page, and the largest either endpoint takes. Not a page size so
         * much as the size of the only page there is: what does not fit is
         * reported as unchecked rather than paged through. [suggest] pages
         * instead, and uses [READ_PAGE].
         */
        const val PAGE = 1000

        /**
         * What one page of a read for suggestions asks for.
         *
         * Smaller than [PAGE] on Slack's own advice: `conversations.list` times
         * out on large pages often enough that the documentation asks for no
         * more than a couple of hundred, and a timed-out page is a whole read
         * thrown away where a small one is a retry nobody notices.
         */
        const val READ_PAGE = 200

        /**
         * The cap that makes a read end. [READ_PAGE] times this is the most
         * entries one connection's list can hold, and with [SlackListingCache]'s
         * own cap it is the whole of what this feature can occupy: two thousand
         * rows of three short strings, thirty-two lists at a time.
         *
         * A Slack larger than that is not ruled on. It suggests from what was
         * read and says the list is partial, which is what somebody typing can
         * act on - the alternative is paging through fifty thousand members
         * while they wait.
         */
        const val MOST_PAGES = 10

        /**
         * How many matches come back. A picker shows a handful and a person
         * narrows it by typing; a list longer than this is scrolled rather than
         * read, and it is a lot of JSON to send on every keystroke.
         */
        const val MOST_SHOWN = 25

        /** Enough of what was typed to recognise it, in a line that has to fit. */
        const val TYPED_SHOWN = 30

        const val EXACTLY = 0
        const val FROM_THE_START = 1
        const val SOMEWHERE = 2
        const val NOT_AT_ALL = 3

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

        /** Not one of Slack's, and never shown: a read that never reached it. */
        const val NOT_REACHED = "not_reached"
    }
}
