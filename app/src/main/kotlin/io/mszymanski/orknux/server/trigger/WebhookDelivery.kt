package io.mszymanski.orknux.server.trigger

import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * What arrived at a webhook besides its body, as the run is handed it.
 *
 * A body is not the whole of a webhook call, and for several senders it is not
 * the part that says what happened: GitHub puts the event's name in
 * `X-GitHub-Event` and the delivery's identity in `X-GitHub-Delivery`, GitLab and
 * Stripe do the same under names of their own, and none of it is in the JSON. A
 * workflow handed only the body cannot tell a pull request from a push, so it is
 * handed this as well — under `webhook`, beside whatever the body brought.
 *
 * **This is a description of the request, not the proof that came with it.** The
 * function that guards a webhook is handed the headers whole, because checking a
 * signature is what it is for; what is written here goes into the run's input,
 * which is stored, listed and read by people, so the headers HTTP has names for
 * carrying a credential are left out of it. That is a list of the ones with
 * names, not a promise: a sender that invents a header of its own to carry a
 * token is not something this can recognise, and a signature stays because it
 * proves one body rather than granting anything.
 */
class WebhookDelivery(
    /** The path it was called on, without the endpoint's own prefix. */
    private val path: String,
    headers: Map<String, String>,
) {

    /**
     * Lower-cased, because a header's case is the sender's whim and a workflow
     * should not have to guess which spelling it got — the same reading the
     * guarding function is given.
     */
    private val headers: Map<String, String> = headers
        .mapKeys { (name, _) -> name.lowercase() }
        .filterKeys { it !in CREDENTIALS }

    /** What is put into the run's input under `webhook`. */
    fun asJson(mapper: ObjectMapper): ObjectNode {
        val node = mapper.createObjectNode()
        node.put("path", path)
        val named = node.putObject("headers")
        headers.forEach { (name, value) -> named.put(name, value) }
        return node
    }

    private companion object {

        /**
         * The headers whose whole job is to carry a credential.
         *
         * Written out rather than matched by pattern. A rule guessing from a name
         * would drop `x-github-hook-id` for holding "id" or keep `x-auth` for not
         * holding "token", and a filter nobody can predict is one somebody debugs
         * by reading this file anyway — so it may as well be the list.
         */
        val CREDENTIALS = setOf(
            "authorization",
            "proxy-authorization",
            "cookie",
            "set-cookie",
            "x-api-key",
            "x-auth-token",
        )
    }
}
