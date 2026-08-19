package io.mszymanski.orknux.server.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.connection.ConnectionProbe
import io.mszymanski.orknux.connector.connection.ConnectionProperties
import io.mszymanski.orknux.connector.connection.HttpAnswer
import io.mszymanski.orknux.connector.connection.OutgoingHttp
import io.mszymanski.orknux.connector.model.ModelProvider
import io.mszymanski.orknux.connector.model.ModelProviderProbe
import io.mszymanski.orknux.connector.model.ProviderAuthMethod
import io.mszymanski.orknux.connector.model.ProviderType
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.connector.proxy.ProxyRule
import io.mszymanski.orknux.connector.proxy.ProxyRuleSource
import io.mszymanski.orknux.connector.security.SecretCipher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Whether a proxy rule actually moves a request.
 *
 * The point of this file is that it tests the clients rather than the table. A
 * rules table nothing consults would pass every repository test ever written
 * and still leave the one endpoint that needs a proxy unreachable, which is the
 * failure the feature exists to fix. So there is a real proxy here, on the
 * loopback address, and every assertion is about which of the two servers was
 * spoken to.
 *
 * The proxy stub is a forward proxy in the only sense that matters for plain
 * HTTP: a request routed through one arrives with the whole URL on the request
 * line rather than just the path, so a recorded absolute URL is proof the
 * request went through the proxy and not to the target.
 */
class ProxyRoutingTest {

    private lateinit var proxy: HttpServer
    private lateinit var target: HttpServer

    /** The absolute URLs the proxy was asked to fetch. Empty means it was never used. */
    private val proxied = CopyOnWriteArrayList<String>()

    /** What the proxy was told to authenticate with, if anything. */
    private val proxyCredentials = CopyOnWriteArrayList<String>()

    /** The paths the target was asked for directly. Empty means nothing reached it. */
    private val direct = CopyOnWriteArrayList<String>()

    /** Set to make the proxy demand credentials before it will do anything. */
    private var demandsCredentials = false

    @BeforeEach
    fun start() {
        proxied.clear()
        proxyCredentials.clear()
        direct.clear()
        demandsCredentials = false

        proxy = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        proxy.createContext("/") { exchange ->
            val offered = exchange.requestHeaders.getFirst("Proxy-Authorization")
            if (demandsCredentials && offered == null) {
                // The challenge a proxy sends when it wants to know who is
                // asking. The client only sends credentials in answer to one.
                exchange.responseHeaders.add("Proxy-Authenticate", """Basic realm="orknux"""")
                exchange.sendResponseHeaders(407, -1)
                exchange.close()
                return@createContext
            }
            if (offered != null) proxyCredentials += offered
            val url = exchange.requestURI.toString()
            proxied += url
            // A real proxy would fetch and relay; this one answers as whatever
            // was asked for would have, which for a token endpoint is a token.
            respond(exchange, if ("/oauth2/" in url) TOKEN else """{"through":"the proxy","data":[{"id":"one"}]}""")
        }
        proxy.start()

        target = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        target.createContext("/") { exchange ->
            direct += exchange.requestURI.path
            respond(exchange, """{"through":"nothing","data":[{"id":"one"}]}""")
        }
        target.start()
    }

    @AfterEach
    fun stop() {
        proxy.stop(0)
        target.stop(0)
    }

    @Test
    fun `a request whose URL matches a rule goes through that rule's proxy`() {
        val http = outgoing(rule(name = "the target", pattern = targetHost()))

        val answer = http.call("${targetUrl()}/report", "GET", emptyMap(), null)

        assertThat(answer).isInstanceOf(HttpAnswer.Answered::class.java)
        // The whole URL on the request line is what tells the proxy where to go,
        // and is the only thing that could have put it there.
        assertThat(proxied).containsExactly("${targetUrl()}/report")
        assertThat(direct).isEmpty()
    }

    @Test
    fun `a request that matches no rule goes straight to the target`() {
        val http = outgoing(rule(name = "somewhere else", pattern = """entra\.example\.com"""))

        http.call("${targetUrl()}/report", "GET", emptyMap(), null)

        assertThat(direct).containsExactly("/report")
        assertThat(proxied).isEmpty()
    }

    @Test
    fun `a rule that is turned off is ignored`() {
        val http = outgoing(rule(name = "the target", pattern = targetHost(), enabled = false))

        http.call("${targetUrl()}/report", "GET", emptyMap(), null)

        // The rule matches perfectly well; it is simply switched off, and the
        // request goes out the way it would if the rule were not there at all.
        assertThat(direct).containsExactly("/report")
        assertThat(proxied).isEmpty()
    }

    @Test
    fun `the first matching rule wins and the router says which rules it beat`() {
        // Both patterns match the same URL. The narrow one is second on purpose:
        // if specificity decided, this test would go the other way.
        val narrow = rule(name = "just the report", pattern = """/report""", position = 1)
        val broad = rule(name = "everything", pattern = """.*""", position = 0)
        val router = ProxyRouter(ProxyRuleSource { listOf(broad, narrow) })

        val matched = router.matching("${targetUrl()}/report")

        assertThat(matched.map { it.ruleName }).containsExactly("everything", "just the report")
        assertThat(router.resolve("${targetUrl()}/report")?.ruleName).isEqualTo("everything")
    }

    @Test
    fun `a proxy that asks who is calling is given the credentials its rule holds`() {
        demandsCredentials = true
        val http = outgoing(
            rule(name = "the target", pattern = targetHost(), username = "sentry", password = "open-sesame"),
        )

        val answer = http.call("${targetUrl()}/report", "GET", emptyMap(), null)

        assertThat(answer).isInstanceOf(HttpAnswer.Answered::class.java)
        assertThat(proxied).containsExactly("${targetUrl()}/report")
        // Basic, base64 of "sentry:open-sesame". Asserted as the header the
        // proxy actually saw rather than as anything this test built.
        assertThat(proxyCredentials).containsExactly("Basic c2VudHJ5Om9wZW4tc2VzYW1l")
    }

    @Test
    fun `a rule whose pattern will not compile is left out rather than breaking every call`() {
        val broken = rule(name = "broken", pattern = "(unclosed", position = 0)
        val working = rule(name = "the target", pattern = targetHost(), position = 1)
        val http = outgoing(broken, working)

        http.call("${targetUrl()}/report", "GET", emptyMap(), null)

        // The one bad rule costs itself and nothing else: the rule behind it
        // still fires, and the call still happens.
        assertThat(proxied).containsExactly("${targetUrl()}/report")
    }

    @Test
    fun `the token grant a provider needs goes through the rule matching the authority`() {
        // The endpoint the request the owner named is made to. Everything else
        // about this provider is reachable directly, which is the whole shape of
        // the problem: one address needs the proxy and the rest do not.
        val router = ProxyRouter(ProxyRuleSource { listOf(rule(name = "Entra", pattern = "/oauth2/")) })
        val properties = ConnectionProperties(entraAuthority = targetUrl())
        val probe = ModelProviderProbe(
            ConnectionProbe(properties, router),
            properties,
            ObjectMapper(),
            SecretCipher(TEST_KEY),
            router,
        )

        probe.check(
            ModelProvider(
                workspaceId = 1,
                name = "Azure OpenAI",
                type = ProviderType.AZURE_OPENAI,
                endpoint = targetUrl(),
                authMethod = ProviderAuthMethod.ENTRA_ID,
                secret = "the-secret",
                tenantId = "contoso.onmicrosoft.com",
                clientId = "00000000-0000-0000-0000-000000000001",
            ),
        )

        assertThat(proxied).singleElement().asString().contains("/oauth2/v2.0/token")
        // The model listing matches no rule, so it went out directly. Same
        // provider, same host, two different routes, decided by the URL.
        assertThat(direct).anyMatch { it.contains("/openai/models") }
    }

    private fun outgoing(vararg rules: ProxyRule): OutgoingHttp {
        val router = ProxyRouter(ProxyRuleSource { rules.toList() })
        val properties = ConnectionProperties()
        return OutgoingHttp(properties, ConnectionProbe(properties, router), router)
    }

    /** A rule pointing at the proxy stub, which is the only proxy in this file. */
    private fun rule(
        name: String,
        pattern: String,
        enabled: Boolean = true,
        position: Int = 0,
        username: String? = null,
        password: String? = null,
    ) = ProxyRule(
        id = position.toLong() + 1,
        name = name,
        pattern = pattern,
        proxyHost = proxy.address.hostString,
        proxyPort = proxy.address.port,
        username = username,
        password = password,
        enabled = enabled,
        position = position,
    )

    private fun targetUrl() = "http://${target.address.hostString}:${target.address.port}"

    /** The target's host and port, quoted so the dots in an address are literal. */
    private fun targetHost() = """\Q${target.address.hostString}:${target.address.port}\E"""

    private fun respond(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private companion object {
        /** What Entra answers a client credentials grant with. */
        const val TOKEN = """{"token_type":"Bearer","expires_in":3600,"access_token":"issued-token"}"""

        /** Any valid AES-256 key; nothing here is encrypted with it. */
        const val TEST_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }
}
