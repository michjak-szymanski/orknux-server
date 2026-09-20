package io.mszymanski.orknux.server.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.connection.ConnectionType
import io.mszymanski.orknux.connector.connection.SlackClients
import io.mszymanski.orknux.connector.connection.SlackThreads
import io.mszymanski.orknux.connector.connection.Thread
import io.mszymanski.orknux.connector.connection.WorkspaceConnection
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.connector.proxy.ProxyRuleSource
import io.mszymanski.orknux.server.security.plainCredentials
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Optional

/**
 * What a thread says about the files in it.
 *
 * A thread is how an agent finds out what is in a conversation, and a file
 * somebody uploaded three messages ago used to be invisible in one: the text
 * said "have a look at this" and the messages came back as though nothing had
 * come with them. An agent asked about "the file" had to guess a timestamp and
 * ask about attachments message by message, which is not something a model
 * does - so it answered that there was no file, which was what it had been
 * shown.
 *
 * Slack's own answer is stubbed on the loopback address rather than reached, so
 * what is measured is the mapping: what `conversations.replies` hands back
 * against what a plugin is given.
 */
class SlackThreadFilesTest {

    private lateinit var api: HttpServer

    /** What the stub answers `conversations.replies` with. Set per test. */
    private var replies: String = "{\"ok\":true,\"messages\":[]}"

    @BeforeEach
    fun start() {
        api = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        api.createContext("/") { exchange -> respond(exchange, replies) }
        api.start()
    }

    @AfterEach
    fun stop() = api.stop(0)

    @Test
    fun `a message carrying a file says so, with the id that fetches it`() {
        replies = """
            {"ok":true,"messages":[
              {"ts":"1700000000.000100","user":"U0000ALICE","text":"the invoice","reply_count":1,
               "files":[{"id":"F0000000001","name":"invoice.pdf","title":"invoice",
                         "mimetype":"application/pdf","filetype":"pdf","size":18452}]},
              {"ts":"1700000000.000200","user":"U0000BOB","text":"thanks"}
            ]}
        """.trimIndent()

        val read = threads().read(CONNECTION_ID, "C0000000001", "1700000000.000100") as Thread.Read

        val parent = read.messages.first()
        assertThat(parent.parent).isTrue()
        assertThat(parent.files).hasSize(1)
        assertThat(parent.files.single().id)
            .describedAs("what readAttachment takes, which is the whole point of carrying this")
            .isEqualTo("F0000000001")
        assertThat(parent.files.single().name).isEqualTo("invoice.pdf")
        assertThat(parent.files.single().mimetype).isEqualTo("application/pdf")
        assertThat(parent.files.single().size).isEqualTo(18452)

        // And a message with nothing attached says nothing, rather than null.
        assertThat(read.messages.last().files).isEmpty()
    }

    /**
     * A file with half its fields missing is still a file.
     *
     * Slack leaves out what does not apply - a snippet has no name of its own,
     * a pending upload has no size - and a thread that threw on one of those
     * would be a thread nobody could read because of a message nobody cares
     * about.
     */
    @Test
    fun `a file Slack described thinly is carried rather than refused`() {
        replies = """
            {"ok":true,"messages":[
              {"ts":"1700000000.000100","user":"U0000ALICE","text":"here",
               "files":[{"id":"F0000000002","title":"pasted text"}]}
            ]}
        """.trimIndent()

        val read = threads().read(CONNECTION_ID, "C0000000001", "1700000000.000100") as Thread.Read

        val file = read.messages.single().files.single()
        assertThat(file.id).isEqualTo("F0000000002")
        assertThat(file.name).isEqualTo("pasted text")
        assertThat(file.mimetype).isEmpty()
        assertThat(file.size).isZero()
    }

    /** [SlackThreads] as the application builds it, with Slack on the loopback address. */
    private fun threads(): SlackThreads {
        val clients = SlackClients(ProxyRouter(ProxyRuleSource { emptyList() }))
        clients.webApi.config.methodsEndpointUrlPrefix = "http://${api.address.hostString}:${api.address.port}/api/"
        return SlackThreads(connections(), plainCredentials(), clients)
    }

    private fun connections(): WorkspaceConnectionRepository {
        val connection = WorkspaceConnection(
            id = CONNECTION_ID,
            workspaceId = WORKSPACE_ID,
            name = "Support Slack",
            type = ConnectionType.SLACK,
            url = "https://slack.com/api",
            secret = "xoxb-not-a-real-token",
        )
        val repository = mock(WorkspaceConnectionRepository::class.java)
        `when`(repository.findById(CONNECTION_ID)).thenReturn(Optional.of(connection))
        return repository
    }

    private fun respond(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private companion object {
        const val CONNECTION_ID = 7L
        const val WORKSPACE_ID = 3L
    }
}
