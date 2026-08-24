package io.mszymanski.orknux.server.model

import com.sun.net.httpserver.HttpServer
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where a service principal's token request is allowed to go.
 *
 * The authority is an installation's own setting rather than something a
 * workspace member types, which is why this call was the last outbound one with
 * nobody asking where it went. What travels on it is the application's client
 * secret, in the body of a POST, so an unvetted address is a credential posted
 * wherever that address points - a link-local one being exactly where a cloud
 * instance keeps its own.
 *
 * The stub is a real server on the loopback address and it records what it was
 * asked for. The refused authority is `0.0.0.0` on that same port: a host the
 * guard will not call, and a socket that would otherwise land on the stub. So
 * an empty record means the request was stopped rather than merely lost.
 */
class EntraAuthorityGuardTest {

    private lateinit var authority: HttpServer

    /** What the stub was asked for. Empty means nothing was sent. */
    private val asked = CopyOnWriteArrayList<String>()

    /** No proxy rules: this test is about the address guard, not about routing. */
    private val proxies = ProxyRouter(ProxyRuleSource { emptyList() })

    @BeforeEach
    fun start() {
        asked.clear()

        authority = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        authority.createContext("/$TENANT/oauth2/v2.0/token") { exchange ->
            asked += exchange.requestURI.path
            exchange.requestBody.use { it.readBytes() }
            val body = """{"access_token":"issued-token","expires_in":3600}"""
                .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        authority.start()
    }

    @AfterEach
    fun stop() = authority.stop(0)

    @Test
    fun `an authority the guard refuses never receives the client secret`() {
        val resolved = probeAt("http://0.0.0.0:${authority.address.port}").credentials(provider())

        assertThat(resolved).isInstanceOf(ModelProviderProbe.Credential.Failed::class.java)
        // A sentence rather than a log line, because it travels out as the
        // failure the provider check and the chat put in front of whoever
        // configured this.
        assertThat((resolved as ModelProviderProbe.Credential.Failed).reason)
            .startsWith("The Entra ID authority cannot be called:")
            .contains("link-local")
        assertThat(asked).isEmpty()
    }

    /**
     * The half that matters most: a guard refusing everything would pass the
     * test above and leave every Entra ID provider unable to authenticate.
     */
    @Test
    fun `an ordinary authority still issues a token`() {
        val at = "http://${authority.address.hostString}:${authority.address.port}"

        val resolved = probeAt(at).credentials(provider())

        assertThat(resolved).isInstanceOf(ModelProviderProbe.Credential.Header::class.java)
        assertThat((resolved as ModelProviderProbe.Credential.Header).header.value)
            .isEqualTo("Bearer issued-token")
        assertThat(asked).containsExactly("/$TENANT/oauth2/v2.0/token")
    }

    /**
     * A probe pointed at one authority. Built here rather than injected because
     * the two tests want different ones, and the token cache is the instance's -
     * a fresh probe each time is a fresh question each time.
     */
    private fun probeAt(entraAuthority: String): ModelProviderProbe {
        val properties = ConnectionProperties(entraAuthority = entraAuthority)
        return ModelProviderProbe(
            ConnectionProbe(properties, proxies, SecretCipher("")),
            properties,
            ObjectMapper(),
            // Never asked to decrypt anything: the secret here is plain text,
            // which is what the cipher recognises it as. And no provider here
            // reads a workspace secret; each holds its own.
            SecretReferences(SecretVariables { _, _ -> null }, SecretCipher("")),
            proxies,
        )
    }

    private fun provider() = ModelProvider(
        workspaceId = 1,
        name = "Azure OpenAI",
        type = ProviderType.AZURE_OPENAI,
        endpoint = "https://example.test/",
        authMethod = ProviderAuthMethod.ENTRA_ID,
        secret = "client-secret",
        tenantId = TENANT,
        clientId = "application-id",
    )

    private companion object {
        const val TENANT = "tenant-1"
    }
}
