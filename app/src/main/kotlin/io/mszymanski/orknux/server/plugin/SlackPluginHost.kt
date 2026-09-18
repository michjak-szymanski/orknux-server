package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.connector.connection.Delivery
import io.mszymanski.orknux.connector.connection.LinkedMessage
import io.mszymanski.orknux.connector.connection.Mention
import io.mszymanski.orknux.connector.connection.OutgoingMessages
import io.mszymanski.orknux.connector.connection.Reaction
import io.mszymanski.orknux.connector.connection.SlackMentions
import io.mszymanski.orknux.connector.connection.SlackMessages
import io.mszymanski.orknux.connector.connection.SlackReactions
import io.mszymanski.orknux.connector.connection.SlackThreads
import io.mszymanski.orknux.connector.connection.SlackUser
import io.mszymanski.orknux.connector.connection.SlackUsers
import io.mszymanski.orknux.connector.connection.Thread
import io.mszymanski.orknux.workflow.script.PluginCapability
import io.mszymanski.orknux.workflow.script.PluginHost
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * What the server does on a plugin's behalf.
 *
 * The far side of the one door a plugin has out of its sandbox. It is here
 * rather than in the execution module because this is where the connections and
 * their credentials are, and the execution module goes on knowing nothing about
 * Slack — it knows only that a capability was granted and that something answers
 * it.
 *
 * **Everything crosses as JSON, both ways.** A plugin handed a live object could
 * walk from it to a class loader; a plugin handed a string can read the string.
 * That is what keeps this a door rather than a hole, and it is why nothing here
 * returns anything richer than text.
 *
 * A refusal is data rather than an exception. A plugin asking about a connection
 * that has been deleted needs to be able to say so — "that connection is gone"
 * is a sentence a workflow can act on, and an exception here would come out as a
 * plugin that failed for reasons nobody can read. Issue #316.
 */
@Component
class SlackPluginHost(
    private val threads: SlackThreads,
    private val messages: OutgoingMessages,
    private val reactions: SlackReactions,
    private val linked: SlackMessages,
    private val users: SlackUsers,
    private val mentions: SlackMentions,
    private val mapper: ObjectMapper,
    /**
     * The other thing the server does on a caller's behalf; see
     * [NetworkPluginHost].
     *
     * One host object answers every capability, so this is where the second one
     * is reached from. A host per capability would mean the runner holding a
     * list and deciding which to ask, which is a decision with nothing in it.
     */
    private val network: NetworkPluginHost,
) : PluginHost {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun ask(capability: PluginCapability, argument: String, on: Long?): String = when (capability) {
        PluginCapability.SLACK_READ_THREAD -> readThread(argument, on)
        PluginCapability.SLACK_POST_MESSAGE -> postMessage(argument, on)
        PluginCapability.SLACK_ADD_REACTION -> addReaction(argument, on)
        PluginCapability.SLACK_READ_MESSAGE -> readMessage(argument, on)
        PluginCapability.SLACK_READ_USER -> readUser(argument, on)
        PluginCapability.SLACK_MENTION -> mention(argument, on)
        /*
         * No workspace scoping, and the reason is not that it was forgotten: a
         * request names an address rather than one of the workspace's own
         * things, so there is nothing here for a workspace to be the boundary
         * of. What bounds this is the proxy rules - and, for a plugin, the
         * grant as well; a workspace's own function has it without asking,
         * which is said at length on the capability and on NetworkPluginHost.
         */
        PluginCapability.NETWORK_REQUEST -> network.request(argument)
    }

    /**
     * `[connectionId, channel, threadTs, limit]`, as the contract's helper sends
     * it.
     *
     * Read defensively and refused in words. This is the boundary a plugin
     * writes to, so what arrives is whatever somebody's JavaScript passed, and
     * every shape of wrong has to come back as something they can act on.
     */
    private fun readThread(argument: String, on: Long?): String {
        val given = runCatching { mapper.readTree(argument) }.getOrNull()
            ?: return refusal("the arguments were not JSON")
        if (!given.isArray || given.size() < 3) {
            return refusal("that call takes a connection, a channel and a thread")
        }

        /*
         * A number or a string of one, because both are what actually arrive.
         *
         * A trigger publishes its connection as `"7"` - everything on a payload
         * is text, since that is what a payload is - so a function handed
         * `trigger.connection` and passing it straight on was refused for giving
         * the very thing the product told it to give. Accepting only the shape
         * the plugin template happens to declare would have made the documented
         * path the one that does not work.
         */
        val connectionId = given.get(0)?.let { held ->
            when {
                held.isNumber -> held.asLong()
                held.isTextual -> held.asString().trim().toLongOrNull()
                else -> null
            }
        } ?: return refusal("the first argument has to be a Slack connection")
        val channel = given.get(1)?.takeIf { it.isTextual }?.asString()
            ?: return refusal("the second argument has to be a channel")
        val threadTs = given.get(2)?.takeIf { it.isTextual }?.asString()
            ?: return refusal("the third argument has to be a thread")
        val limit = given.get(3)?.takeIf { it.isNumber }?.asInt()

        return when (val read = threads.read(connectionId, channel, threadTs, limit ?: DEFAULT_LIMIT, on)) {
            is Thread.Read -> {
                val answer = mapper.createObjectNode()
                val messages = answer.putArray("messages")
                read.messages.forEach { held ->
                    messages.addObject()
                        .put("ts", held.ts)
                        .put("user", held.user)
                        .put("text", held.text)
                        .put("parent", held.parent)
                }
                answer.put("replies", read.replies)
                mapper.writeValueAsString(answer)
            }

            // Both refusals, said in the words they came with. A plugin deciding
            // what to do about "not_in_channel" needs to be told that and not a
            // sentence somebody rewrote.
            is Thread.NotPossible -> refusal(read.reason)
            is Thread.Refused -> refusal(read.reason)
        }.also { log.debug("A plugin read thread {} in {} on connection {}", threadTs, channel, connectionId) }
    }

    /**
     * `[connectionId, channel, text, threadTs?]`, and answers `{ ts }` where it
     * sent or `{ error }` where it did not.
     *
     * `ts` is the message's own timestamp, which is what a reply threads onto and
     * what a reaction hangs on - so a function that posts and then reacts has the
     * one value it needs from the first call.
     */
    private fun postMessage(argument: String, on: Long?): String {
        val given = runCatching { mapper.readTree(argument) }.getOrNull()
            ?: return refusal("the arguments were not JSON")
        if (!given.isArray || given.size() < 3) {
            return refusal("that call takes a connection, a channel and some text")
        }
        val connectionId = connectionOf(given.get(0))
            ?: return refusal("the first argument has to be a Slack connection")
        val channel = given.get(1)?.takeIf { it.isTextual }?.asString()
            ?: return refusal("the second argument has to be a channel")
        val text = given.get(2)?.takeIf { it.isTextual }?.asString()
            ?: return refusal("the third argument has to be the message text")
        val threadTs = given.get(3)?.takeIf { it.isTextual }?.asString()

        return when (val sent = messages.send(connectionId, channel, text, threadTs, on)) {
            is Delivery.Sent -> mapper.writeValueAsString(
                mapper.createObjectNode().put("channel", sent.channel).put("ts", sent.ts),
            )
            is Delivery.NotPossible -> refusal(sent.reason)
            is Delivery.Refused -> refusal(sent.reason)
        }.also { log.debug("A script posted to {} on connection {}", channel, connectionId) }
    }

    /**
     * `[connectionId, channel, ts, emoji]`, and answers `{ ok: true }` where it
     * reacted or `{ error }` where it did not.
     */
    private fun addReaction(argument: String, on: Long?): String {
        val given = runCatching { mapper.readTree(argument) }.getOrNull()
            ?: return refusal("the arguments were not JSON")
        if (!given.isArray || given.size() < 4) {
            return refusal("that call takes a connection, a channel, a message and an emoji")
        }
        val connectionId = connectionOf(given.get(0))
            ?: return refusal("the first argument has to be a Slack connection")
        val channel = given.get(1)?.takeIf { it.isTextual }?.asString()
            ?: return refusal("the second argument has to be a channel")
        val ts = given.get(2)?.takeIf { it.isTextual }?.asString()
            ?: return refusal("the third argument has to be a message timestamp")
        val emoji = given.get(3)?.takeIf { it.isTextual }?.asString()
            ?: return refusal("the fourth argument has to be an emoji name")

        return when (val reacted = reactions.add(connectionId, channel, ts, emoji, on)) {
            is Reaction.Added -> mapper.writeValueAsString(mapper.createObjectNode().put("ok", true))
            is Reaction.NotPossible -> refusal(reacted.reason)
            is Reaction.Refused -> refusal(reacted.reason)
        }.also { log.debug("A script reacted on {} on connection {}", channel, connectionId) }
    }

    /**
     * `[connectionId, link]`, and answers the message the permalink points at,
     * or `{ error }` where it could not be read.
     */
    private fun readMessage(argument: String, on: Long?): String {
        val given = runCatching { mapper.readTree(argument) }.getOrNull()
            ?: return refusal("the arguments were not JSON")
        if (!given.isArray || given.size() < 2) {
            return refusal("that call takes a connection and a message link")
        }
        val connectionId = connectionOf(given.get(0))
            ?: return refusal("the first argument has to be a Slack connection")
        val link = given.get(1)?.takeIf { it.isTextual }?.asString()
            ?: return refusal("the second argument has to be a message link")

        return when (val read = linked.read(connectionId, link, on)) {
            is LinkedMessage.Found -> mapper.writeValueAsString(
                mapper.createObjectNode()
                    .put("channel", read.channel)
                    .put("ts", read.ts)
                    .put("user", read.user)
                    .put("text", read.text)
                    .put("threadTs", read.threadTs),
            )
            is LinkedMessage.NotPossible -> refusal(read.reason)
            is LinkedMessage.Refused -> refusal(read.reason)
        }.also { log.debug("A script followed a message link on connection {}", connectionId) }
    }

    /**
     * `[connectionId, userId]`, and answers who that is, or `{ error }`. The id
     * may arrive wrapped as the mention notation it came from.
     */
    private fun readUser(argument: String, on: Long?): String {
        val given = runCatching { mapper.readTree(argument) }.getOrNull()
            ?: return refusal("the arguments were not JSON")
        if (!given.isArray || given.size() < 2) {
            return refusal("that call takes a connection and a user id")
        }
        val connectionId = connectionOf(given.get(0))
            ?: return refusal("the first argument has to be a Slack connection")
        val userId = given.get(1)?.takeIf { it.isTextual }?.asString()
            ?: return refusal("the second argument has to be a user id")

        return when (val found = users.info(connectionId, userId, on)) {
            is SlackUser.Found -> mapper.writeValueAsString(
                mapper.createObjectNode()
                    .put("id", found.id)
                    .put("name", found.name)
                    .put("realName", found.realName)
                    .put("displayName", found.displayName)
                    .put("bot", found.bot),
            )
            is SlackUser.NotPossible -> refusal(found.reason)
            is SlackUser.Refused -> refusal(found.reason)
        }.also { log.debug("A script looked up a user on connection {}", connectionId) }
    }

    /**
     * `[connectionId, name]`, and answers `{ mention, id, label }` - the text to
     * put in a message - or `{ error }`. A name nothing answers to is an error in
     * words rather than a guess, because a mention that pings the wrong person is
     * worse than one that asks again.
     */
    private fun mention(argument: String, on: Long?): String {
        val given = runCatching { mapper.readTree(argument) }.getOrNull()
            ?: return refusal("the arguments were not JSON")
        if (!given.isArray || given.size() < 2) {
            return refusal("that call takes a connection and a name")
        }
        val connectionId = connectionOf(given.get(0))
            ?: return refusal("the first argument has to be a Slack connection")
        val name = given.get(1)?.takeIf { it.isTextual }?.asString()
            ?: return refusal("the second argument has to be a name")

        return when (val resolved = mentions.of(connectionId, name, on)) {
            is Mention.Resolved -> mapper.writeValueAsString(
                mapper.createObjectNode()
                    .put("mention", resolved.mention)
                    .put("id", resolved.id)
                    .put("label", resolved.label),
            )
            is Mention.NobodyCalled -> refusal("nobody in that Slack answers to \"${resolved.name}\"")
            is Mention.NotPossible -> refusal(resolved.reason)
            is Mention.Refused -> refusal(resolved.reason)
        }.also { log.debug("A script resolved a mention on connection {}", connectionId) }
    }

    /**
     * A number or a string of one, because both are what actually arrive: a
     * trigger publishes its connection as `"7"`, since everything on a payload is
     * text, and a plugin declares it as a number. See [readThread].
     */
    private fun connectionOf(node: tools.jackson.databind.JsonNode?): Long? = node?.let { held ->
        when {
            held.isNumber -> held.asLong()
            held.isTextual -> held.asString().trim().toLongOrNull()
            else -> null
        }
    }

    private fun refusal(why: String): String =
        mapper.writeValueAsString(mapper.createObjectNode().put("error", why))

    private companion object {
        /** What the connector uses when nothing says otherwise; repeated so the door has its own answer. */
        const val DEFAULT_LIMIT = 50
    }
}
