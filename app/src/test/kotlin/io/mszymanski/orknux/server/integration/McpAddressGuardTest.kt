package io.mszymanski.orknux.server.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.connection.AuthType
import io.mszymanski.orknux.connector.connection.ConnectionProbe
import io.mszymanski.orknux.connector.connection.ConnectionProperties
import io.mszymanski.orknux.connector.connection.McpClient
import io.mszymanski.orknux.connector.connection.McpListing
import io.mszymanski.orknux.connector.connection.McpProperties
import io.mszymanski.orknux.connector.connection.McpServer
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
 * Where an MCP server's address is allowed to take a request.
 *
 * An address is typed in by whoever adds the server, and every call carries the
 * stored bearer credential, so two things are worth holding still: that an
 * address the guard refuses is never dialled at all, and that a server which
 * answers with a redirect does not get to say where the credential goes next.
 *
 * Both servers here are real ones on the loopback address, and the refused
 * address is `0.0.0.0` on the same port - which is a host the guard refuses and
 * a socket that would otherwise reach the stub. That is what makes "refused"
 * mean something: if the check were dropped the request would land, and the
 * stub would say so.
 */
class McpAddressGuardTest {

    private lateinit var server: HttpServer
    private lateinit var elsewhere: HttpServer

    /** What the stub was asked, in order. Empty means it was never called. */
    private val methods = CopyOnWriteArrayList<String>()
    private val followed = CopyOnWriteArrayList<String>()

    private val client = McpClient(ObjectMapper(), McpProperties(), ConnectionProbe(ConnectionProperties()))

    @BeforeEach
    fun start() {
        methods.clear()
        followed.clear()

        // Stands in for the MCP server: answers the handshake and one listing.
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/rpc") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val method = Regex("\"method\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1).orEmpty()
            methods += method

            val answer = when (method) {
                "initialize" ->
                    """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{},
                       "serverInfo":{"name":"stub","version":"1"}}}"""

                "tools/list" ->
                    """{"jsonrpc":"2.0","id":2,"result":{"tools":[
                       {"name":"web_search","description":"Search the web",
                        "inputSchema":{"type":"object","properties":{
                          "query":{"type":"string","description":"What to search for"}},"required":["query"]}}]}}"""

                "tools/call" ->
                    """{"jsonrpc":"2.0","id":3,"result":{"content":[
                       {"type":"text","text":"Nothing found, which is the point"}]}}"""

                else -> ""
            }
            respond(exchange, answer)
        }

        // Where a redirect would send the credential. It answers the handshake
        // perfectly well, so anything landing here is a request that moved host.
        elsewhere = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        elsewhere.createContext("/") { exchange ->
            followed += exchange.requestURI.path
            respond(
                exchange,
                """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{}}}""",
            )
        }

        server.start()
        elsewhere.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
        elsewhere.stop(0)
    }

    @Test
    fun `an address the guard refuses is never asked anything`() {
        val listing = client.tools(mcpServer("Brave Search", refusedAddress()))

        assertThat(listing).isInstanceOf(McpListing.Failed::class.java)
        // Named, and in a sentence: this is what the person who typed the
        // address in has to recognise as being about their server.
        assertThat((listing as McpListing.Failed).reason)
            .startsWith("Brave Search cannot be called:")
            .contains("link-local")
        // The socket was never opened, so the credential never left.
        assertThat(methods).isEmpty()
    }

    @Test
    fun `a refused address comes back as the tool's own answer`() {
        val answer = client.call(mcpServer("Brave Search", refusedAddress()), "web_search", """{"query":"anything"}""")

        // A tool result rather than an exception, so the model relays it and
        // the conversation carries the reason to whoever is reading.
        assertThat(answer).contains("Brave Search cannot be called")
        assertThat(methods).isEmpty()
    }

    @Test
    fun `a redirect does not get to move the credential to another host`() {
        val moving = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        moving.createContext("/rpc") { exchange ->
            exchange.responseHeaders.add("Location", "http://${host(elsewhere)}/rpc")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        moving.start()

        try {
            val listing = client.tools(mcpServer("Brave Search", "http://${host(moving)}/rpc"))

            assertThat(listing).isInstanceOf(McpListing.Failed::class.java)
            // Nothing arrived at the other host: a 302 is an answer, not an
            // instruction to send the bearer token somewhere else.
            assertThat(followed).isEmpty()
        } finally {
            moving.stop(0)
        }
    }

    @Test
    fun `an ordinary address is still listed and still called`() {
        val configured = mcpServer("Brave Search", "http://${host(server)}/rpc")

        val listing = client.tools(configured)

        assertThat(listing).isInstanceOf(McpListing.Tools::class.java)
        assertThat((listing as McpListing.Tools).tools.single().name).isEqualTo("web_search")
        assertThat(client.call(configured, "web_search", """{"query":"orknux"}"""))
            .contains("Nothing found, which is the point")
        assertThat(methods).contains("initialize", "tools/list", "tools/call")
    }

    /**
     * The stub's own port on a host the guard will not call.
     *
     * `0.0.0.0` is the unspecified address, which a client resolves to this
     * machine - so this is the same server by another name, and the only thing
     * standing between the request and it is the check being tested.
     */
    private fun refusedAddress(): String = "http://0.0.0.0:${server.address.port}/rpc"

    private fun mcpServer(name: String, address: String) = McpServer(
        workspaceId = 1,
        name = name,
        address = address,
        authType = AuthType.BEARER_TOKEN,
        secret = "the-token",
    )

    private fun host(server: HttpServer): String = "${server.address.hostString}:${server.address.port}"

    private fun respond(exchange: HttpExchange, answer: String) {
        val bytes = answer.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.responseHeaders.add("Mcp-Session-Id", "stub-session")
        if (bytes.isEmpty()) {
            exchange.sendResponseHeaders(202, -1)
        } else {
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        exchange.close()
    }
}
