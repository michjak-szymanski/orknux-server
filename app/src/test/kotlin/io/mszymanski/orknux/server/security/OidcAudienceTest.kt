package io.mszymanski.orknux.server.security

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidationException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Date

/**
 * Who a bearer token was minted for, and whether this server cares.
 *
 * It did not. The decoder was built from the issuer alone, so every check it
 * carried was about the token being genuine and current - never about it being
 * meant for this application. A realm or a tenant hosts several of them and
 * signs for all of them with these keys, so a token issued to the invoicing
 * client next door arrived here perfectly valid, and its `groups` claim then
 * decided what its holder could do. A group called `admins` over there is an
 * administrator here.
 *
 * The provider is a real one on the loopback address: it publishes a discovery
 * document and a JWK set, and the tokens are signed with the key it advertises.
 * That is what makes "refused" mean something here - every token in this file
 * would decode if the audience were not read, because every one of them is
 * properly signed by the issuer this server trusts.
 */
class OidcAudienceTest {

    private lateinit var provider: HttpServer
    private lateinit var issuer: String
    private lateinit var key: RSAKey

    @BeforeEach
    fun start() {
        key = RSAKeyGenerator(2048).keyID("signing").keyUse(KeyUse.SIGNATURE).generate()

        provider = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        issuer = "http://127.0.0.1:${provider.address.port}"

        provider.createContext("/.well-known/openid-configuration") { exchange ->
            respond(
                exchange,
                """{"issuer":"$issuer","jwks_uri":"$issuer/jwks",
                   "authorization_endpoint":"$issuer/auth","token_endpoint":"$issuer/token",
                   "response_types_supported":["code"],"subject_types_supported":["public"],
                   "id_token_signing_alg_values_supported":["RS256"]}""",
            )
        }
        provider.createContext("/jwks") { exchange ->
            respond(exchange, JWKSet(key.toPublicJWK()).toString())
        }
        provider.start()
    }

    @AfterEach
    fun stop() = provider.stop(0)

    private fun respond(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** The decoder as the application builds it, for an installation named [audiences] or its client id. */
    private fun decoder(audiences: List<String> = emptyList()): JwtDecoder =
        OidcSecurityConfig().jwtDecoder(
            SecurityProperties(
                authMethod = AuthMethod.OIDC,
                oidc = OidcProperties(issuer = issuer, clientId = CLIENT_ID, audiences = audiences),
            ),
        )

    /**
     * A genuine token from this provider, signed with the key it publishes.
     *
     * The `groups` claim is on every one of them deliberately: it is what would
     * grant administration if the token got through, so a refusal here is a
     * refusal of something that would otherwise have mattered.
     */
    private fun token(
        audience: List<String>,
        from: String = issuer,
        expiresAt: Instant = Instant.now().plusSeconds(300),
    ): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(from)
            .subject("alice")
            .audience(audience)
            .claim("groups", listOf("admins"))
            .issueTime(Date.from(expiresAt.minusSeconds(360)))
            .expirationTime(Date.from(expiresAt))
            .build()

        val signed = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), claims)
        signed.sign(RSASSASigner(key))
        return signed.serialize()
    }

    @Test
    fun `a token minted for this application is accepted`() {
        val jwt = decoder().decode(token(audience = listOf(CLIENT_ID)))

        assertThat(jwt.subject).isEqualTo("alice")
        assertThat(jwt.audience).contains(CLIENT_ID)
    }

    @Test
    fun `a token minted for another application in the same realm is refused`() {
        assertThatThrownBy { decoder().decode(token(audience = listOf("invoicing"))) }
            .isInstanceOf(JwtValidationException::class.java)
            .hasMessageContaining("aud")
    }

    @Test
    fun `a token naming no audience at all is refused`() {
        assertThatThrownBy { decoder().decode(token(audience = emptyList())) }
            .isInstanceOf(JwtValidationException::class.java)
            .hasMessageContaining("aud")
    }

    /** A token often names several applications; naming this one anywhere in the list is enough. */
    @Test
    fun `a token naming this application among others is accepted`() {
        val jwt = decoder().decode(token(audience = listOf("account", CLIENT_ID, "invoicing")))

        assertThat(jwt.audience).contains(CLIENT_ID)
    }

    /**
     * Keycloak writes `account` rather than the client id unless an audience mapper
     * is configured, so an installation has to be able to say what its tokens
     * actually carry - and saying so must not widen anything else.
     */
    @Test
    fun `a configured audience is accepted, and the client id no longer is`() {
        val configured = decoder(audiences = listOf("account"))

        assertThat(configured.decode(token(audience = listOf("account"))).subject).isEqualTo("alice")
        assertThatThrownBy { configured.decode(token(audience = listOf(CLIENT_ID))) }
            .isInstanceOf(JwtValidationException::class.java)
            .hasMessageContaining("aud")
    }

    /** Any one of several listed audiences will do, rather than all of them at once. */
    @Test
    fun `an installation naming several audiences accepts a token carrying either`() {
        val configured = decoder(audiences = listOf("account", "api://orknux"))

        assertThat(configured.decode(token(audience = listOf("api://orknux"))).subject).isEqualTo("alice")
        assertThat(configured.decode(token(audience = listOf("account"))).subject).isEqualTo("alice")
    }

    /**
     * The audience check replaced the validator chain the decoder came with, so the
     * two things that chain was already doing are worth pinning: a token from
     * somewhere else, and one that has run out, are still refused.
     */
    @Test
    fun `a token from a different issuer is still refused`() {
        assertThatThrownBy {
            decoder().decode(token(audience = listOf(CLIENT_ID), from = "https://elsewhere.example"))
        }
            .isInstanceOf(JwtValidationException::class.java)
            .hasMessageContaining("iss")
    }

    @Test
    fun `an expired token is still refused`() {
        assertThatThrownBy {
            decoder().decode(token(audience = listOf(CLIENT_ID), expiresAt = Instant.now().minusSeconds(300)))
        }
            .isInstanceOf(JwtValidationException::class.java)
            .hasMessageContaining("expired")
    }

    private companion object {
        const val CLIENT_ID = "orknux"
    }
}
