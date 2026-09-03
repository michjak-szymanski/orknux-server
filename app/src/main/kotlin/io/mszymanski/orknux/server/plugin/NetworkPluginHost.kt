package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.connector.proxy.ProxyRouter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * One HTTP request, made by the server because a plugin was granted it.
 *
 * **What this is, and what it is not.** It is not a network for the sandbox:
 * nothing in `PluginRunner`'s context changed, there is still no socket and no
 * `fetch`, and a package calling `require('net')` is as uninstallable as it was.
 * It is a function the server implements, called under a capability an
 * administrator accepted, whose argument and answer are both text.
 *
 * That distinction is the whole design. A plugin holding a socket could keep it
 * open, listen on it, or hand it to something else; a plugin calling this gets
 * a status, some headers and a string, and the connection is closed before it
 * sees any of them.
 *
 * ## Where it can get to
 *
 * Wherever [ProxyRouter] lets it, which is the same answer an MCP call and a
 * Slack call get. The client below is built from [ProxyRouter.builder], so an
 * installation's proxy rules are the boundary — and the trusted-certificate
 * list is honoured for the same reason, since both hang off that builder. A
 * second HTTP client here would be #176's bug in a new place: rules that cover
 * most of the product and quietly miss one door.
 *
 * **This is the widest capability there is and it is worth being plain about
 * what it costs.** A plugin granted it can reach anything the server can, which
 * on most installations includes hosts the person who accepted it cannot reach
 * themselves. What is here to make that survivable is a narrow shape — one
 * request, one answer, no redirect to a scheme it refuses, a bounded body — and
 * the proxy rules. What is *not* here is a URL allowlist, because a plugin
 * knows one outside service and the address it needs is its own business; an
 * installation that wants a list has proxy rules, which are where such a list
 * belongs and where the rest of the product already reads one.
 */
@Component
class NetworkPluginHost(
    private val mapper: ObjectMapper,
    proxies: ProxyRouter,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val client: HttpClient = proxies.builder()
        .connectTimeout(CONNECT_TIMEOUT)
        /*
         * Followed, but only between addresses this would have accepted in the
         * first place: `NORMAL` refuses a redirect from HTTPS to HTTP, which is
         * the one that turns a checked call into an unchecked one.
         */
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /**
     * `[url, method, headers, body]`, as the contract's helper sends it.
     *
     * Read defensively and refused in words. This is the boundary somebody
     * else's JavaScript writes to, so every shape of wrong has to come back as
     * something a plugin can say out loud.
     */
    fun request(argument: String): String {
        val given = runCatching { mapper.readTree(argument) }.getOrNull()
            ?: return refusal("the arguments were not JSON")
        if (!given.isArray || given.size() < 2) return refusal("that call takes a url and a method")

        val address = given.get(0)?.takeIf { it.isTextual }?.asString()?.trim()
            ?: return refusal("the first argument has to be a url")
        val uri = runCatching { URI.create(address) }.getOrNull()
            ?: return refusal("\"$address\" is not a url")
        /*
         * http and https and nothing else. `file:` would read the disk the
         * sandbox is kept off, and `jar:` and the rest are the same thought in
         * other spellings - a scheme this does not name is refused rather than
         * handed to whatever handler happens to be registered.
         */
        if (uri.scheme?.lowercase() !in ALLOWED) {
            return refusal("only http and https can be asked for, and that is ${uri.scheme ?: "no scheme"}")
        }

        val method = given.get(1)?.takeIf { it.isTextual }?.asString()?.uppercase() ?: "GET"
        if (method !in METHODS) return refusal("$method is not a method this makes")

        val body = given.get(3)?.takeIf { it.isTextual }?.asString()
        val request = HttpRequest.newBuilder(uri)
            .timeout(READ_TIMEOUT)
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())

        headers(given.get(2)).forEach { (name, value) -> request.header(name, value) }

        return try {
            val answered = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            val held = answered.body().orEmpty()
            if (held.length > MAX_BODY) {
                return refusal("the answer was larger than ${MAX_BODY / 1024} KB")
            }

            val answer = mapper.createObjectNode()
            answer.put("status", answered.statusCode())
            val headers = answer.putObject("headers")
            answered.headers().map().forEach { (name, values) -> headers.put(name, values.joinToString(", ")) }
            answer.put("body", held)
            mapper.writeValueAsString(answer)
        } catch (failure: IOException) {
            refusal(failure.message ?: "it could not be reached")
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            refusal("the request was interrupted")
        }
    }

    /**
     * The headers a plugin asked for, minus the ones it may not set.
     *
     * Dropped rather than refused: a plugin setting `Host` is much more likely
     * to be copying an example than to be up to something, and failing its whole
     * call over a header nobody reads teaches nothing. What it may not do is
     * make this server's own request look like it came from somewhere else.
     */
    private fun headers(given: JsonNode?): Map<String, String> {
        if (given == null || !given.isObject) return emptyMap()
        return given.propertyNames()
            .filter { it.lowercase() !in FORBIDDEN }
            .mapNotNull { name -> given.path(name).takeIf { it.isTextual }?.let { name to it.asString() } }
            .toMap()
    }

    private fun refusal(why: String): String =
        mapper.writeValueAsString(mapper.createObjectNode().put("error", why))
            .also { log.debug("A plugin's request was refused: {}", why) }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        val READ_TIMEOUT: Duration = Duration.ofSeconds(30)

        /** Large enough for an API's answer, small enough that it stays a string. */
        const val MAX_BODY = 2 * 1024 * 1024

        val ALLOWED = setOf("http", "https")

        val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")

        /**
         * Headers the caller does not get to set.
         *
         * `Host` and the forwarding ones because they are how a request is made
         * to look like somebody else's, and the rest because the client owns
         * them and a plugin setting one gets an answer it cannot read.
         */
        val FORBIDDEN = setOf(
            "host", "content-length", "connection", "upgrade", "transfer-encoding",
            "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "forwarded",
        )
    }
}
