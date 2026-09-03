package io.mszymanski.orknux.server.plugin

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.workflow.script.PluginCapability
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * The widest capability there is, and the things that hold it in.
 *
 * A plugin an administrator has agreed to can ask the server to make a request.
 * That is a real grant with a real cost — it reaches anything the server can —
 * so what is pinned here is less "it works" than the shape of it:
 *
 *   it works      a granted plugin gets a status, headers and a body back
 *   as data       and gets them as JSON, so there is no socket, no stream and
 *                 nothing to keep open on either side
 *   schemes       `file:` is refused. It would read the disk the sandbox is
 *                 kept off, which is the one thing this must never become
 *   methods       an invented one is refused rather than sent
 *   headers       a plugin cannot set `Host` or the forwarding ones, so it
 *                 cannot make this server's request look like somebody else's
 *   refusals      a host that is not there comes back as data, because a plugin
 *                 has to be able to say something about it
 *
 * Not covered here, and deliberately: where a request may *get to*. That is the
 * proxy rules, they are `ProxyRouter`'s, and a copy of them here would be a
 * second opinion about the thing that governs every other outbound call.
 */
@SpringBootTest
class NetworkCapabilityTest(@Autowired val host: SlackPluginHost, @Autowired val mapper: ObjectMapper) {

    private fun ask(vararg given: Any?): Map<*, *> {
        val argument = mapper.writeValueAsString(given.toList())
        return mapper.readValue(host.ask(PluginCapability.NETWORK_REQUEST, argument, null), Map::class.java)
    }

    @Test
    fun `a granted plugin gets the answer, as data`() {
        val answered = ask("http://${where()}/hello", "GET", emptyMap<String, String>(), null)

        assertThat(answered["status"]).isEqualTo(200)
        assertThat(answered["body"]).isEqualTo("""{"said":"hello"}""")
        @Suppress("UNCHECKED_CAST")
        val headers = answered["headers"] as Map<String, String>
        assertThat(headers.keys.map { it.lowercase() }).contains("content-type")
    }

    /**
     * The one refusal that matters most.
     *
     * `file:` would read the filesystem this sandbox is built to be kept off,
     * and the whole design rests on a capability being a function rather than a
     * way back to the machine.
     */
    @Test
    fun `a scheme that is not http is refused`() {
        assertThat(ask("file:///etc/passwd", "GET")["error"] as String)
            .contains("only http and https")

        assertThat(ask("jar:file:///tmp/x.jar!/y", "GET")["error"]).isNotNull()
    }

    @Test
    fun `a method it does not make is refused rather than sent`() {
        assertThat(ask("http://${where()}/hello", "TRACE")["error"] as String).contains("TRACE")
    }

    /**
     * A plugin cannot make this server's request look like somebody else's.
     *
     * Dropped rather than refused: setting `Host` is far more often somebody
     * copying an example than somebody up to something, and failing the whole
     * call over a header nobody reads teaches nothing.
     */
    @Test
    fun `the headers that would disguise the request are not sent`() {
        ask(
            "http://${where()}/echo",
            "GET",
            mapOf("Host" to "elsewhere.example", "X-Forwarded-For" to "10.0.0.1", "X-Asked" to "yes"),
            null,
        )

        assertThat(seen).containsKey("x-asked")
        assertThat(seen).describedAs("a plugin cannot forge where a request came from").doesNotContainKey("x-forwarded-for")
        assertThat(seen["host"]).isEqualTo(where())
    }

    @Test
    fun `a host that is not there comes back as a sentence`() {
        val answered = ask("http://127.0.0.1:1/nothing", "GET")

        assertThat(answered["error"]).isNotNull()
        assertThat(answered["status"]).isNull()
    }

    @Test
    fun `what is not a url at all is refused before anything is sent`() {
        assertThat(ask("not a url", "GET")["error"]).isNotNull()
        assertThat(ask(null, "GET")["error"] as String).contains("has to be a url")
    }

    companion object {

        /** What the last request carried, so the headers can be looked at. */
        val seen = mutableMapOf<String, String>()

        private val server: HttpServer =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                createContext("/") { exchange ->
                    seen.clear()
                    exchange.requestHeaders.forEach { (name, values) ->
                        seen[name.lowercase()] = values.joinToString(", ")
                    }
                    val body = """{"said":"hello"}""".toByteArray(StandardCharsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                    exchange.close()
                }
                start()
            }

        private fun where() = "${server.address.hostString}:${server.address.port}"

        @JvmStatic
        @AfterAll
        fun stop() = server.stop(0)
    }
}
