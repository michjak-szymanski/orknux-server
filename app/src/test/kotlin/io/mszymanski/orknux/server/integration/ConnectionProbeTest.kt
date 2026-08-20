package io.mszymanski.orknux.server.integration

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.connection.AuthType
import io.mszymanski.orknux.connector.connection.CheckOutcome
import io.mszymanski.orknux.connector.connection.ConnectionProbe
import io.mszymanski.orknux.connector.connection.ConnectionProperties
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.connector.proxy.ProxyRuleSource
import io.mszymanski.orknux.connector.connection.ConnectionTarget
import io.mszymanski.orknux.connector.security.SecretCipher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * What a probe is willing to call a connection.
 *
 * A socket that opened is not a service that works: a URL serving nothing
 * answers 404 as readily as a working one answers 200, and reporting either as
 * "Connection successful" is how a screen ends up saying *Connection successful
 * — Answered with 415*. So the status the endpoint chose is read rather than
 * merely counted.
 *
 * The server here is a real one on the loopback address, which keeps the test
 * off the network while still exercising the client: a stubbed HttpClient would
 * only prove the `when` branches match themselves.
 */
class ConnectionProbeTest {

    private lateinit var server: HttpServer
    private val probe = ConnectionProbe(
        ConnectionProperties(),
        ProxyRouter(ProxyRuleSource { emptyList() }),
        SecretCipher(TEST_KEY),
    )

    /**
     * Whatever status the path is named after, and nothing in the body.
     *
     * Note what it does *not* send with a 401: a `WWW-Authenticate` header. That
     * is not an oversight in the stub, it is the shape Azure's token endpoint
     * answers in, and the shape that used to be reported as *WWW-Authenticate
     * header missing for response code 401* instead of as a refusal.
     */
    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val status = exchange.requestURI.path.trimStart('/').toIntOrNull() ?: 200
            // A redirect without a Location is not one anybody would send.
            if (status in 300..399) exchange.responseHeaders.add("Location", "https://example.test/moved")
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `an answer the service meant is a connection`() {
        assertThat(check(200)).isEqualTo(CheckOutcome.CONNECTED to "Answered 200")
        assertThat(check(204).first).isEqualTo(CheckOutcome.CONNECTED)
    }

    @Test
    fun `refusing a HEAD is still an answer from the right service`() {
        // An MCP server or a webhook takes a POST and says so. That is the
        // service talking, which is what the check is asking about.
        val (outcome, message) = check(415)
        assertThat(outcome).isEqualTo(CheckOutcome.CONNECTED)
        assertThat(message).isEqualTo("Reachable; it does not answer a HEAD (415)")
        assertThat(check(405).first).isEqualTo(CheckOutcome.CONNECTED)
    }

    @Test
    fun `a redirect is a service answering, and says where it points`() {
        // Slack answers 301 to a HEAD on its API. The probe does not follow one
        // — that could carry the credentials to another host — but something
        // is plainly there, and where it points is usually the right URL.
        val (outcome, message) = check(301)
        assertThat(outcome).isEqualTo(CheckOutcome.CONNECTED)
        assertThat(message).startsWith("Reachable; it redirects to ")
        assertThat(check(308).first).isEqualTo(CheckOutcome.CONNECTED)
    }

    @Test
    fun `nothing served at the URL is not a connection`() {
        assertThat(check(404)).isEqualTo(CheckOutcome.FAILED to "Nothing is served at that URL (404)")
        assertThat(check(410).first).isEqualTo(CheckOutcome.FAILED)
    }

    @Test
    fun `a service that is failing is not a connection either`() {
        assertThat(check(503)).isEqualTo(CheckOutcome.FAILED to "The service is failing (503)")
    }

    @Test
    fun `credentials the service refuses are the failure they look like`() {
        assertThat(check(401).first).isEqualTo(CheckOutcome.FAILED)
        assertThat(check(403).second).isEqualTo("The service rejected the credentials (403)")
    }

    @Test
    fun `a 401 with no WWW-Authenticate header is still reported as a refusal`() {
        // The regression this file exists to hold. Every client here used to
        // carry a java.net.Authenticator so that proxy credentials could be
        // supplied, and the JDK throws IOException("WWW-Authenticate header
        // missing for response code 401") for this exact answer whenever one is
        // set - before the authenticator is asked anything. The status is the
        // only thing worth saying and it was the one thing that did not survive.
        assertThat(check(401).second).isEqualTo("The service rejected the credentials (401)")
    }

    @Test
    fun `a credential this installation cannot decrypt says so rather than being sent`() {
        // Written with one key and read with another, which is what an
        // installation that lost its ORKNUX_SECRET_KEY has in every secret
        // column: decrypt hands the envelope back rather than throwing.
        val stored = SecretCipher(OTHER_KEY).encrypt("the-real-token")
        val asRead = SecretCipher(TEST_KEY).decrypt(stored)

        val url = "http://${server.address.hostString}:${server.address.port}/200"
        val result = probe.check(ConnectionTarget(url, AuthType.BEARER_TOKEN, asRead, emptyList()))

        // Not "the service rejected the credentials": nothing was sent, and
        // retyping the token would not fix what is wrong.
        assertThat(result.outcome).isEqualTo(CheckOutcome.FAILED)
        assertThat(result.message).isEqualTo(
            "The stored credential cannot be read with the current secret key. " +
                "Enter it again, or restore the key it was saved with.",
        )
    }

    private fun check(status: Int): Pair<CheckOutcome, String> {
        val url = "http://${server.address.hostString}:${server.address.port}/$status"
        val result = probe.check(ConnectionTarget(url, AuthType.NONE, null, emptyList()))
        return result.outcome to result.message
    }

    private companion object {
        /** Two valid AES-256 keys, so that a value written with one is unreadable with the other. */
        const val TEST_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        const val OTHER_KEY = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
    }
}
