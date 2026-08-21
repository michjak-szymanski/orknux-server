package io.mszymanski.orknux.server.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.connection.ConnectionProbe
import io.mszymanski.orknux.connector.connection.ConnectionProperties
import io.mszymanski.orknux.connector.connection.JavaMailTransport
import io.mszymanski.orknux.connector.connection.MailSecurity
import io.mszymanski.orknux.connector.connection.SmtpServer
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.connector.proxy.ProxyRule
import io.mszymanski.orknux.connector.proxy.ProxyRuleSource
import io.mszymanski.orknux.connector.security.SecretCipher
import io.mszymanski.orknux.server.security.AuthMethod
import io.mszymanski.orknux.server.security.OIDC_REGISTRATION_ID
import io.mszymanski.orknux.server.security.OidcProperties
import io.mszymanski.orknux.server.security.OidcSecurityConfig
import io.mszymanski.orknux.server.security.OidcTransport
import io.mszymanski.orknux.server.security.SecurityProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * How far the proxy rules reach, asked of the three places they did not.
 *
 * Slack was brought under them and an audit then went through the rest of the
 * application a call site at a time. What it found was not one omission but a
 * pattern: everything built on [ProxyRouter] was routed, and everything a
 * library or a framework built for itself was not. Three of those mattered, and
 * each is asserted here against something on the loopback address rather than
 * against the code that configures it - a settings object with the right
 * property in it proves nothing about where the bytes went.
 *
 * The host used throughout is a name that does not resolve. That is the whole
 * design of these tests: if a call succeeds, it succeeded through the proxy,
 * because there is no other way for it to have succeeded at all. It is also
 * exactly the situation being fixed - a network where the proxy is the only
 * thing that can resolve an external name.
 */
class ProxyReachTest {

    /** A name with no address anywhere, which is the point. */
    private val unreachable = "provider.invalid"

    private lateinit var proxy: HttpServer

    /** The absolute URLs the proxy was asked to fetch. */
    private val proxied = CopyOnWriteArrayList<String>()

    /** What a discovery request is answered with. Set per test. */
    private var discovery: String = "{}"

    @BeforeEach
    fun start() {
        proxied.clear()
        proxy = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        proxy.createContext("/") { exchange ->
            // A request through a forward proxy arrives with the whole URL on
            // the request line rather than just the path, so recording it is
            // proof the request went through here and not to the target.
            proxied += exchange.requestURI.toString()
            respond(exchange, discovery)
        }
        proxy.start()
    }

    @AfterEach
    fun stop() = proxy.stop(0)

    private fun rules() = ProxyRuleSource {
        listOf(
            ProxyRule(
                id = 1,
                name = "everything at the invalid host",
                pattern = Regex.escape(unreachable),
                proxyHost = proxy.address.hostString,
                proxyPort = proxy.address.port,
                enabled = true,
            ),
        )
    }

    /* ------------------------------------------------------------ the lookup */

    /**
     * The lookup that beat every rule to it.
     *
     * Before any call was made, the host was resolved here - so on a network
     * where only the proxy can resolve a name, every call failed with "The host
     * could not be resolved" and the rules were never consulted. An installation
     * whose proxy configuration was entirely correct could not make one call,
     * and nothing on the page listing those rules said why.
     */
    @Test
    fun `a host only the proxy can resolve is not refused before the proxy is asked`() {
        val routed = probe(rules())
        assertThat(routed.vet("https://$unreachable/api")).isNull()
    }

    /** And the check is still made for a host this process dials itself. */
    @Test
    fun `a host nobody can resolve is still refused when no rule carries it`() {
        val direct = probe(ProxyRuleSource { emptyList() })
        assertThat(direct.vet("https://$unreachable/api")).isEqualTo("The host could not be resolved")
    }

    /**
     * What is still refused when a proxy carries it: an address written as an
     * address. The rule exists to keep a connection off cloud instance
     * metadata, and a proxy makes that somebody else's network rather than
     * harmless.
     */
    @Test
    fun `a link-local address is refused even when a rule carries it`() {
        val everything = ProxyRuleSource {
            listOf(
                ProxyRule(
                    id = 1,
                    name = "everything",
                    pattern = ".*",
                    proxyHost = proxy.address.hostString,
                    proxyPort = proxy.address.port,
                    enabled = true,
                ),
            )
        }
        assertThat(probe(everything).vet("http://169.254.169.254/latest/meta-data"))
            .isEqualTo("That host resolves to a link-local address")
    }

    /* -------------------------------------------------------------- the OIDC */

    /**
     * Discovery, which used to fail before the application had finished
     * starting.
     *
     * The worst of the three: `ClientRegistrations.fromIssuerLocation` builds
     * its own `RestTemplate` and takes no argument that would let one be given,
     * so the call went direct whatever the rules said - and it runs while the
     * context is being built, so the server did not come up. The page where the
     * proxy rules are written is behind the sign-in that was failing.
     */
    @Test
    fun `the identity provider is discovered through the proxy`() {
        // Plain HTTP so the stub can answer: a proxied https URL is a CONNECT
        // tunnel, and what is being proved here is which route the request took,
        // not that the JDK can tunnel - ProxyRoutingTest already holds that.
        val issuer = "http://$unreachable"
        discovery = """{"issuer":"$issuer","jwks_uri":"$issuer/jwks",
            "authorization_endpoint":"$issuer/auth","token_endpoint":"$issuer/token",
            "response_types_supported":["code"],"subject_types_supported":["public"],
            "id_token_signing_alg_values_supported":["RS256"]}"""

        val repository = OidcSecurityConfig().clientRegistrationRepository(
            SecurityProperties(
                authMethod = AuthMethod.OIDC,
                oidc = OidcProperties(issuer = issuer, clientId = "orknux"),
            ),
            OidcTransport(ProxyRouter(rules())),
        )

        assertThat(proxied).contains("$issuer/.well-known/openid-configuration")
        assertThat(repository.findByRegistrationId(OIDC_REGISTRATION_ID).providerDetails.tokenUri)
            .isEqualTo("$issuer/token")
    }

    /**
     * A document that calls itself something else is refused.
     *
     * `fromIssuerLocation` made this comparison and the map-taking method
     * cannot, so it is made by hand - and it is worth a test of its own because
     * losing it silently would be the kind of regression that only shows up as
     * people being sent somewhere else to sign in.
     */
    @Test
    fun `a discovery document naming another issuer is not accepted`() {
        val issuer = "http://$unreachable"
        discovery = """{"issuer":"http://somewhere.else.invalid","jwks_uri":"$issuer/jwks",
            "authorization_endpoint":"$issuer/auth","token_endpoint":"$issuer/token",
            "response_types_supported":["code"],"subject_types_supported":["public"],
            "id_token_signing_alg_values_supported":["RS256"]}"""

        val thrown = runCatching {
            OidcSecurityConfig().clientRegistrationRepository(
                SecurityProperties(
                    authMethod = AuthMethod.OIDC,
                    oidc = OidcProperties(issuer = issuer, clientId = "orknux"),
                ),
                OidcTransport(ProxyRouter(rules())),
            )
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown).hasMessageContaining("somewhere.else.invalid")
    }

    /* -------------------------------------------------------------- the mail */

    /**
     * A mail server reached through a proxy, which the networking page used to
     * admit in its own footer that it could not do.
     *
     * Jakarta Mail opens a `CONNECT` tunnel and then talks SMTP inside it, so
     * the proof is a `CONNECT` line arriving at something that is not the mail
     * server. This stub answers nothing after that - the session fails, and it
     * fails at the mail server rather than at the lookup, which is the whole
     * distinction being tested.
     */
    @Test
    fun `mail is sent through the proxy a rule names`() {
        val connects = CopyOnWriteArrayList<String>()
        val tunnel = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val listening = thread(isDaemon = true) {
            runCatching {
                tunnel.accept().use { socket ->
                    connects += socket.getInputStream().bufferedReader().readLine().orEmpty()
                }
            }
        }

        val router = ProxyRouter(
            ProxyRuleSource {
                listOf(
                    ProxyRule(
                        id = 1,
                        name = "mail",
                        pattern = "^smtp://",
                        proxyHost = tunnel.inetAddress.hostAddress,
                        proxyPort = tunnel.localPort,
                        enabled = true,
                    ),
                )
            },
        )

        JavaMailTransport(ConnectionProperties(mailTimeoutSeconds = 5), router).check(
            SmtpServer(
                host = unreachable,
                port = 587,
                username = null,
                password = null,
                from = "orknux@$unreachable",
                security = MailSecurity.NONE,
            ),
        )

        listening.join(10_000)
        tunnel.close()
        assertThat(connects).anyMatch { it.startsWith("CONNECT $unreachable:587") }
    }

    private fun probe(source: ProxyRuleSource) = ConnectionProbe(
        ConnectionProperties(),
        ProxyRouter(source),
        // Nothing here reads a stored credential; the key only has to exist.
        SecretCipher(""),
    )

    private fun respond(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
