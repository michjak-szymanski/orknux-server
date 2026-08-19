package io.mszymanski.orknux.server.integration

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.connection.AuthType
import io.mszymanski.orknux.connector.connection.CheckOutcome
import io.mszymanski.orknux.connector.connection.ConnectionProbe
import io.mszymanski.orknux.connector.connection.ConnectionProperties
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.connector.proxy.ProxyRuleSource
import io.mszymanski.orknux.connector.connection.ConnectionTarget
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
    private val probe = ConnectionProbe(ConnectionProperties(), ProxyRouter(ProxyRuleSource { emptyList() }))

    /** Whatever status the path is named after, and nothing in the body. */
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

    private fun check(status: Int): Pair<CheckOutcome, String> {
        val url = "http://${server.address.hostString}:${server.address.port}/$status"
        val result = probe.check(ConnectionTarget(url, AuthType.NONE, null, emptyList()))
        return result.outcome to result.message
    }
}
