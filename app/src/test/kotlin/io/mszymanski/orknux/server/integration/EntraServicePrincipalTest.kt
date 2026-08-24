package io.mszymanski.orknux.server.integration

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.connection.CheckOutcome
import io.mszymanski.orknux.connector.connection.ConnectionProbe
import io.mszymanski.orknux.connector.connection.ConnectionProperties
import io.mszymanski.orknux.connector.model.ModelProvider
import io.mszymanski.orknux.connector.model.ModelProviderProbe
import io.mszymanski.orknux.connector.model.ProviderAuthMethod
import io.mszymanski.orknux.connector.model.ProviderType
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.connector.proxy.ProxyRuleSource
import io.mszymanski.orknux.connector.security.SecretCipher
import io.mszymanski.orknux.connector.security.SecretReferences
import io.mszymanski.orknux.connector.security.SecretVariables
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * Authenticating an Azure OpenAI provider as a service principal.
 *
 * The credential is not a key on the resource but a tenant, an app registration
 * and its secret, exchanged with Entra ID for a token. Two things are worth
 * holding still: that the exchange is a client credentials grant carrying what
 * was configured, and that the token it returns is kept rather than re-fetched
 * for every call — a provider used in a chat asks for one on every message.
 *
 * Both servers are on the loopback address, so this touches no network and does
 * not need Azure.
 */
class EntraServicePrincipalTest {

    private lateinit var entra: HttpServer
    private lateinit var azure: HttpServer

    /** How many grants Entra was asked for, and what the last one said. */
    private val grants = AtomicInteger()
    private var lastForm: String = ""

    /**
     * What Entra answers the next grant with.
     *
     * Set by the test that wants a refusal. The 401 it uses carries no
     * `WWW-Authenticate` header, which is what Entra's token endpoint actually
     * sends and is the whole point of the test that sets it.
     */
    private var refusal: Pair<Int, String>? = null

    @BeforeEach
    fun start() {
        refusal = null
        entra = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        entra.createContext("/") { exchange ->
            grants.incrementAndGet()
            lastForm = exchange.requestBody.reader().use { it.readText() }
            val (status, text) = refusal
                ?: (200 to """{"token_type":"Bearer","expires_in":3600,"access_token":"issued-token"}""")
            val body = text.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        entra.start()

        // Stands in for the Azure OpenAI resource, and reports what it was sent.
        azure = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        azure.createContext("/") { exchange ->
            val authorization = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            val body = """{"data":[{"id":"$authorization"}]}""".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        azure.start()
    }

    @AfterEach
    fun stop() {
        entra.stop(0)
        azure.stop(0)
    }

    @Test
    fun `a service principal is exchanged for a token, and the token is what Azure is called with`() {
        val probe = probe()
        val provider = provider()

        val result = probe.check(provider)

        assertThat(result.outcome).isEqualTo(CheckOutcome.CONNECTED)
        // The resource saw the issued token, so the grant is what authenticated
        // the call rather than anything stored against the provider.
        assertThat(result.message).isEqualTo("Connected; 1 model listed")

        assertThat(lastForm).contains("grant_type=client_credentials")
        assertThat(lastForm).contains("client_id=00000000-0000-0000-0000-000000000001")
        assertThat(lastForm).contains("client_secret=the-secret")
        // The scope is URL-encoded, which is why it is matched on a fragment.
        assertThat(lastForm).contains("cognitiveservices.azure.com")
    }

    @Test
    fun `the token is kept, so a second call does not ask Entra again`() {
        val probe = probe()
        val provider = provider()

        probe.check(provider)
        probe.check(provider)
        probe.check(provider)

        // Three calls to the resource, one grant: a chat sending three messages
        // should not send three requests to Microsoft first.
        assertThat(grants.get()).isEqualTo(1)
    }

    @Test
    fun `rotating the secret asks for a new token rather than reusing the old one`() {
        val probe = probe()
        val provider = provider()

        probe.check(provider)
        provider.secret = "rotated"
        probe.check(provider)

        assertThat(grants.get()).isEqualTo(2)
    }

    @Test
    fun `a client secret Entra refuses is reported as a refusal, in Entra's own words`() {
        // The 401 the stub sends has no WWW-Authenticate header, because Entra's
        // does not. Every client in this application used to carry a
        // java.net.Authenticator so proxy credentials could be supplied, and the
        // JDK throws IOException("WWW-Authenticate header missing for response
        // code 401") for exactly this answer whenever one is set - so this
        // screen reported a sentence about a header nobody sent, in place of the
        // one fact worth having.
        refusal = 401 to (
            "{\"error\":\"invalid_client\",\"error_description\":\"AADSTS7000215: " +
                "Invalid client secret provided.\\r\\nTrace ID: 4c4a\\r\\nCorrelation ID: 9b0f\"}"
        )

        val result = probe().check(provider())

        assertThat(result.outcome).isEqualTo(CheckOutcome.FAILED)
        // The status says it was refused; Entra's own code says which credential
        // and what to do about it. The trace and correlation ids are not on the
        // screen - they are on the line below the one worth reading.
        assertThat(result.message).isEqualTo(
            "Entra ID refused the credentials (401): AADSTS7000215: Invalid client secret provided.",
        )
    }

    @Test
    fun `a credential this installation cannot decrypt is not sent to Entra at all`() {
        // What every provider on an installation running with the wrong
        // ORKNUX_SECRET_KEY holds: the envelope, handed back by decrypt because
        // it would not open.
        val stored = SecretCipher(OTHER_KEY).encrypt("the-secret")
        val provider = provider().apply { secret = SecretCipher(TEST_KEY).decrypt(stored) }

        val result = probe().check(provider)

        assertThat(result.outcome).isEqualTo(CheckOutcome.FAILED)
        assertThat(result.message).isEqualTo(
            "This provider's credential cannot be read with the current secret key. " +
                "Enter it again, or restore the key it was saved with.",
        )
        // Nothing went out. An envelope posted as a client secret comes back a
        // 401 and reads as a wrong secret, which is the wrong thing to fix.
        assertThat(grants.get()).isZero()
    }

    private fun probe(): ModelProviderProbe {
        val properties = ConnectionProperties(entraAuthority = url(entra))
        // A real cipher with a real key: these providers hold plaintext secrets
        // set in the test, so nothing here is ever in an envelope — the cipher
        // is only asked whether one is.
        val router = ProxyRouter(ProxyRuleSource { emptyList() })
        return ModelProviderProbe(
            ConnectionProbe(properties, router, SecretCipher(TEST_KEY)),
            properties,
            ObjectMapper(),
            // No provider here reads a workspace secret; each holds its own.
            SecretReferences(SecretVariables { _, _ -> null }, SecretCipher(TEST_KEY)),
            router,
        )
    }

    /**
     * An Azure OpenAI provider that authenticates as a service principal. Its
     * endpoint is the stub resource, so `check` lists models from it.
     */
    private fun provider() = ModelProvider(
        workspaceId = 1,
        name = "Azure OpenAI",
        type = ProviderType.AZURE_OPENAI,
        endpoint = url(azure),
        authMethod = ProviderAuthMethod.ENTRA_ID,
        secret = "the-secret",
        tenantId = "contoso.onmicrosoft.com",
        clientId = "00000000-0000-0000-0000-000000000001",
    )

    private fun url(server: HttpServer) = "http://${server.address.hostString}:${server.address.port}"

    private companion object {
        /** Any valid AES-256 key; the providers here hold plaintext secrets. */
        const val TEST_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

        /** A second one, so a value written with it is unreadable with the first. */
        const val OTHER_KEY = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
    }
}
