package io.mszymanski.orknux.server.integration

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * An MCP server behind a certificate nothing already trusts.
 *
 * Issue #322. An MCP server run inside somebody's own network is behind a
 * private authority or a self-signed certificate more often than not, and there
 * was no way to tell this installation to trust one: the only answer was the
 * JVM's own store, which an administrator running a container image cannot reach
 * without rebuilding it. What came back was `PKIX path building failed` reaching
 * the screen unedited — eight words naming no server, no certificate and no
 * remedy.
 *
 * Three things this pins, and the first is the one that matters:
 *
 *   the trust     a server whose certificate is pasted onto it is reached
 *   the refusal   one without it is refused, and the refusal says what to do
 *   the roots     the pasted authority is trusted *as well as* the usual ones,
 *                 not instead of them — a server that quietly stopped trusting
 *                 the public web the moment somebody added an internal CA would
 *                 be a far worse bug than the one being fixed
 *
 * The certificate is made here rather than checked in, because a checked-in one
 * expires and the test then fails for a reason that has nothing to do with the
 * code.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class McpServerCertificateTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpsServer
    private lateinit var pem: String

    @BeforeEach
    fun start() {
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)

        val made = selfSigned()
        pem = made.pem

        server = HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.httpsConfigurator = HttpsConfigurator(made.context)
        server.createContext("/mcp") { exchange ->
            /*
             * A whole working server, briefly. Answering only `initialize` would
             * leave the check failing on the tool listing, which is a true
             * failure about something this test is not about - and it would read
             * exactly like the trust having not worked.
             */
            val asked = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val body = if (asked.contains("tools/list")) {
                """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"ping","description":"answers"}]}}"""
            } else {
                """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18",""" +
                    """"serverInfo":{"name":"behind-tls","version":"1"},"capabilities":{}}}"""
            }
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.responseHeaders.add("Mcp-Session-Id", "s-1")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    /** Without the authority, refused - and the refusal says what to do about it. */
    @Test
    fun `a certificate nothing trusts is refused, and the reason names the remedy`() {
        val id = mcpServer("Private", address(), certificate = null)

        val answer = check(id)
        answer.path("checkMcpServer.reachable").entity(Boolean::class.java).isEqualTo(false)
        answer.path("checkMcpServer.detail").entity(String::class.java).satisfies({ said ->
            assertThat(said).contains("does not trust")
            // The remedy, not only the diagnosis. `PKIX path building failed`
            // is a true sentence that leaves the reader nowhere.
            assertThat(said).contains("internal authority")
            assertThat(said).doesNotContain("PKIX")
        })
    }

    /** With it, reached. */
    @Test
    fun `a server whose authority was pasted in is reached`() {
        val id = mcpServer("Private", address(), certificate = pem)

        val answer = check(id)
        answer.path("checkMcpServer.detail").entity(String::class.java).satisfies({ said ->
            assertThat(said).doesNotContain("does not trust")
        })
        answer.path("checkMcpServer.reachable").entity(Boolean::class.java).isEqualTo(true)
    }

    /**
     * The half a fix could quietly break.
     *
     * Trusting only what was pasted would pass the test above and stop this
     * installation reaching anything with an ordinary certificate — found weeks
     * later, by somebody else, on a different server.
     */
    @Test
    fun `the pasted authority is trusted as well as the usual ones, not instead`() {
        val id = mcpServer("Private", address(), certificate = pem)

        // A public host with an ordinary certificate, refused for any reason
        // except one about trust. Nothing here needs it to answer.
        val elsewhere = mcpServer("Public", "https://example.invalid/mcp", certificate = pem)

        check(id).path("checkMcpServer.reachable").entity(Boolean::class.java).isEqualTo(true)
        check(elsewhere).path("checkMcpServer.detail").entity(String::class.java).satisfies({ said ->
            assertThat(said).doesNotContain("does not trust")
        })
    }

    private fun address() = "https://${server.address.hostString}:${server.address.port}/mcp"

    private fun check(id: Long) =
        graphQlTester.document("mutation { checkMcpServer(id: $id) { reachable detail tools } }").execute()

    private fun mcpServer(name: String, address: String, certificate: String?): Long {
        val ca = certificate?.let { ", caCertificate: \"\"\"$it\"\"\"" } ?: ""
        return graphQlTester.document(
            """
            mutation {
              createMcpServer(input: { workspaceId: $workspaceId, name: "$name", address: "$address"$ca }) { id }
            }
            """,
        ).execute().path("createMcpServer.id").entity(Long::class.java).get()
    }

    private data class Made(val pem: String, val context: SSLContext)

    /**
     * A certificate authority nothing has heard of, and a server presenting it.
     *
     * Made by `keytool`, which ships with every JDK this builds on, rather than
     * by a library: the JDK's own certificate builder is behind `sun.security`
     * and is not exported, and pulling in BouncyCastle to make one throwaway
     * certificate would be a dependency the product does not otherwise have.
     *
     * Made for this run rather than checked in, because a checked-in certificate
     * expires and the test then fails for a reason that has nothing to do with
     * the code it is about.
     */
    private fun selfSigned(): Made {
        val home = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
        val store = Files.createTempFile("orknux-mcp-tls", ".p12")
        // keytool refuses to write over a file that is already there.
        Files.deleteIfExists(store)

        run(
            home, "-genkeypair",
            "-alias", "self",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-dname", "CN=localhost",
            "-validity", "1",
            // Both, because the check connects by whichever the loopback
            // address resolves to and the hostname check has to pass either way
            // - what is being measured here is the trust decision, not the name.
            "-ext", "SAN=dns:localhost,ip:127.0.0.1",
            "-keystore", store.toString(),
            "-storetype", "PKCS12",
            "-storepass", String(PASSWORD),
        )

        val pem = run(
            home, "-exportcert",
            "-rfc",
            "-alias", "self",
            "-keystore", store.toString(),
            "-storepass", String(PASSWORD),
        )

        val keys = KeyStore.getInstance("PKCS12").apply {
            Files.newInputStream(store).use { load(it, PASSWORD) }
        }
        Files.deleteIfExists(store)

        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keys, PASSWORD) }
        val context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }

        return Made(pem = pem, context = context)
    }

    /** One keytool call, with its output, refusing loudly rather than silently. */
    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val said = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        check(process.waitFor() == 0) { "keytool refused: $said" }
        return said
    }

    private companion object {
        val PASSWORD = "changeit".toCharArray()
    }
}
