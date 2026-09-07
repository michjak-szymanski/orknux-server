package io.mszymanski.orknux.server.shell

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.connection.McpClient
import io.mszymanski.orknux.connector.connection.McpHandshake
import io.mszymanski.orknux.connector.connection.McpServer
import io.mszymanski.orknux.connector.connection.McpServerRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * A server that says it jails each session is trusted to, and the session is
 * held rather than the directory.
 *
 * The other half of issue #337: orknux-shell advertises `sessionIsolation` under
 * its marker in `capabilities.experimental`, and the server reads that off the
 * handshake to decide whether to keep one session open and run on it - which is
 * what makes the jail the shell built usable. The stub speaks the protocol so
 * the marker is read from the handshake it actually sends, and records the
 * `Mcp-Session-Id` each request carried, which is what proves the session was
 * threaded rather than opened afresh.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class McpSessionIsolationTest(
    @Autowired val client: McpClient,
    @Autowired val servers: McpServerRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer

    /** The Mcp-Session-Id each call carried, by method, so the threading can be checked. */
    private val sessionsSeen = ConcurrentHashMap<String, String>()
    private val deleted = mutableListOf<String>()

    @BeforeEach
    fun reset() {
        servers.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `a server advertising the marker is read as isolating`() {
        val server = mcpServer(serve(isolating = true))

        val handshake = client.openSession(server)

        assertThat(handshake).isInstanceOf(McpHandshake.Open::class.java)
        assertThat((handshake as McpHandshake.Open).sessionIsolation).isTrue()
        assertThat(handshake.session).isEqualTo(SESSION)
    }

    @Test
    fun `a server without the marker is read as not isolating`() {
        val server = mcpServer(serve(isolating = false))

        val handshake = client.openSession(server) as McpHandshake.Open

        assertThat(handshake.sessionIsolation).isFalse()
    }

    @Test
    fun `a call on a session threads its id, and a close ends it`() {
        val server = mcpServer(serve(isolating = true))

        val answer = client.callOn(server, SESSION, "start_process", """{"command":"pwd"}""")
        client.closeSession(server, SESSION)

        assertThat(answer).contains("ran: pwd")
        assertThat(sessionsSeen["tools/call"]).isEqualTo(SESSION)
        assertThat(deleted).containsExactly(SESSION)
    }

    /** A stub that speaks the protocol, optionally advertising the isolation marker. */
    private fun serve(isolating: Boolean): String {
        val started = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        started.createContext("/rpc") { exchange ->
            if (exchange.requestMethod == "DELETE") {
                deleted += exchange.requestHeaders.getFirst("Mcp-Session-Id").orEmpty()
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
                return@createContext
            }

            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val method = Regex("\"method\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1).orEmpty()
            exchange.requestHeaders.getFirst("Mcp-Session-Id")?.let { sessionsSeen[method] = it }

            val marker =
                if (isolating) {
                    ""","experimental":{"io.mszymanski.orknux-shell":{"contract":"v3","version":"1","sessionIsolation":true}}"""
                } else {
                    ""
                }
            val answer = when (method) {
                "initialize" ->
                    """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18",
                       "capabilities":{"tools":{}$marker},"serverInfo":{"name":"orknux-shell","version":"1"}}}"""

                "tools/call" ->
                    """{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"ran: pwd"}]}}"""

                else -> ""
            }

            val bytes = answer.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.responseHeaders.add("Mcp-Session-Id", SESSION)
            if (bytes.isEmpty()) {
                exchange.sendResponseHeaders(202, -1)
            } else {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        started.start()
        server = started
        return "http://${started.address.hostString}:${started.address.port}/rpc"
    }

    private fun mcpServer(address: String): McpServer =
        servers.save(McpServer(workspaceId = workspaceId, name = "Shell", address = address))

    private companion object {
        const val SESSION = "stub-session-42"
    }
}
