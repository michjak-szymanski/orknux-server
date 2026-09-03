package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.connector.connection.SlackThreads
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
        /*
         * No workspace scoping, and the reason is not that it was forgotten: a
         * request names an address rather than one of the workspace's own
         * things, so there is nothing here for a workspace to be the boundary
         * of. What bounds this is the grant and the proxy rules, which is said
         * at length on the capability and on NetworkPluginHost.
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

    private fun refusal(why: String): String =
        mapper.writeValueAsString(mapper.createObjectNode().put("error", why))

    private companion object {
        /** What the connector uses when nothing says otherwise; repeated so the door has its own answer. */
        const val DEFAULT_LIMIT = 50
    }
}
