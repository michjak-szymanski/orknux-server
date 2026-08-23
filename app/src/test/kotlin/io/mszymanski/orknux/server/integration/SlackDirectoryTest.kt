package io.mszymanski.orknux.server.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.connection.ConnectionType
import io.mszymanski.orknux.connector.connection.SlackClients
import io.mszymanski.orknux.connector.connection.SlackDirectory
import io.mszymanski.orknux.connector.connection.SlackTargetKind
import io.mszymanski.orknux.connector.connection.SlackTargetOutcome
import io.mszymanski.orknux.connector.connection.WorkspaceConnection
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.connector.proxy.ProxyRuleSource
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What a Slack connection is willing to say about a user or a channel somebody
 * typed.
 *
 * The whole point of this check is the answer it gives when it cannot give one.
 * `users.list` needs `users:read` and the channel lookups need `channels:read`
 * and `groups:read`, and a bot token set up to post carries none of them,
 * because posting needs none of them. A field that reported "no such channel"
 * because the token cannot introspect would send somebody to correct a name
 * that was right, so most of what is asserted here is that the two answers stay
 * apart and that the sentence in between says which one it is.
 *
 * Slack is a stand-in on the loopback address, reached the way
 * `SlackProxyRoutingTest` reaches one: the SDK's endpoint prefix is pointed at
 * it, so the real client, the real request and the real parsing all run and
 * nothing goes near the network.
 */
class SlackDirectoryTest {

    private lateinit var api: HttpServer

    /** Keyed by method name - `conversations.list` - and answered verbatim. */
    private val answers = ConcurrentHashMap<String, String>()

    /** Which methods were called, in order. */
    private val asked = CopyOnWriteArrayList<String>()

    /** Every `Authorization` header the stand-in saw, to prove what leaks and what does not. */
    private val authorizations = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun start() {
        answers.clear()
        asked.clear()
        authorizations.clear()
        // The SDK resolves the team behind a token before it calls anything, so
        // this answers whether a test asked for it or not.
        answers["auth.test"] = """{"ok":true,"team":"Acme","team_id":"T00000001"}"""

        api = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        api.createContext("/") { exchange ->
            val method = exchange.requestURI.path.substringAfterLast('/')
            asked += method
            exchange.requestHeaders.getFirst("Authorization")?.let { authorizations += it }
            respond(exchange, answers[method] ?: """{"ok":true}""")
        }
        api.start()
    }

    @AfterEach
    fun stop() = api.stop(0)

    @Test
    fun `a channel the connection can see is found, with what Slack calls it`() {
        answers["conversations.list"] = channels("C0000000001" to "notifications")

        val check = directory().check(CONNECTION_ID, SlackTargetKind.CHANNEL, "#notifications")

        assertThat(check.outcome).isEqualTo(SlackTargetOutcome.FOUND)
        assertThat(check.id).isEqualTo("C0000000001")
        assertThat(check.label).isEqualTo("#notifications")
    }

    @Test
    fun `a channel nobody has is not found, and says why that is still advice`() {
        answers["conversations.list"] = channels("C0000000001" to "notifications")

        val check = directory().check(CONNECTION_ID, SlackTargetKind.CHANNEL, "#notifcations")

        assertThat(check.outcome).isEqualTo(SlackTargetOutcome.NOT_FOUND)
        // A private channel the bot was never invited to is not in the list
        // either, so the sentence has to leave room for one.
        assertThat(check.message)
            .contains("No channel Support Slack can see is called #notifcations")
            .contains("advice rather than a verdict")
    }

    @Test
    fun `a token that cannot read channels says so, and does not say the channel is missing`() {
        answers["conversations.list"] = """{"ok":false,"error":"missing_scope","needed":"channels:read"}"""

        val check = directory().check(CONNECTION_ID, SlackTargetKind.CHANNEL, "#notifications")

        // The distinction this whole feature exists for.
        assertThat(check.outcome).isEqualTo(SlackTargetOutcome.UNCHECKED)
        assertThat(check.outcome).isNotEqualTo(SlackTargetOutcome.NOT_FOUND)
        assertThat(check.message)
            .contains("Slack was not asked whether #notifications exists")
            .contains("does not carry the channels:read and groups:read scopes")
            // Nothing is broken, and nothing typed here has been judged: the two
            // things a person reading this under a correct name needs told.
            .contains("Nothing is wrong with the connection and nothing is wrong with what is typed here")
            .contains("sending a message needs no such scope")
            .contains("has not been checked rather than found to be wrong")
            // And what to do about it, because the scope is addable.
            .contains("Add the channels:read and groups:read scopes to the Slack app and reinstall it")
    }

    @Test
    fun `the same sentence names the scope a user lookup needs`() {
        answers["users.list"] = """{"ok":false,"error":"missing_scope","needed":"users:read"}"""

        val check = directory().check(CONNECTION_ID, SlackTargetKind.USER, "@alice")

        assertThat(check.outcome).isEqualTo(SlackTargetOutcome.UNCHECKED)
        assertThat(check.message)
            .contains("does not carry the users:read scope")
            .contains("Add the users:read scope to the Slack app and reinstall it")
    }

    @Test
    fun `a workspace larger than one lookup reads is not ruled on`() {
        // One page, and Slack saying there is more. Answering "no such channel"
        // off a list that was cut short is the one thing this must never do.
        answers["conversations.list"] =
            """{"ok":true,"channels":[{"id":"C0000000001","name":"notifications"}],""" +
                """"response_metadata":{"next_cursor":"dXNlcjpVMDYxTkZUVDI="}}"""

        val check = directory().check(CONNECTION_ID, SlackTargetKind.CHANNEL, "#somewhere-else")

        assertThat(check.outcome).isEqualTo(SlackTargetOutcome.UNCHECKED)
        assertThat(check.message).contains("more channels than one lookup reads")
    }

    @Test
    fun `a member is found by any of the names Slack lets them be addressed by`() {
        answers["users.list"] =
            """{"ok":true,"members":[{"id":"U0000000001","name":"alice","real_name":"Alice Adams",""" +
                """"profile":{"display_name":"al","real_name":"Alice Adams"}}]}"""

        val directory = directory()
        assertThat(directory.check(CONNECTION_ID, SlackTargetKind.USER, "@al").id).isEqualTo("U0000000001")
        assertThat(directory.check(CONNECTION_ID, SlackTargetKind.USER, "alice").id).isEqualTo("U0000000001")
        assertThat(directory.check(CONNECTION_ID, SlackTargetKind.USER, "Alice Adams").outcome)
            .isEqualTo(SlackTargetOutcome.FOUND)
    }

    @Test
    fun `an id pasted out of Slack is looked up as an id`() {
        answers["users.info"] = """{"ok":true,"user":{"id":"U0000000001","name":"alice","real_name":"Alice Adams"}}"""

        val check = directory().check(CONNECTION_ID, SlackTargetKind.USER, "U0000000001")

        assertThat(check.outcome).isEqualTo(SlackTargetOutcome.FOUND)
        assertThat(check.label).isEqualTo("Alice Adams")
        // The list is the expensive call and an id does not need it.
        assertThat(asked).contains("users.info").doesNotContain("users.list")
    }

    @Test
    fun `an address is looked up as an address rather than matched against handles`() {
        answers["users.lookupByEmail"] = """{"ok":true,"user":{"id":"U0000000001","name":"alice"}}"""

        val check = directory().check(CONNECTION_ID, SlackTargetKind.USER, "alice@example.test")

        // Without this branch an address matches no handle and is reported as a
        // member who does not exist, which is the wrong answer to a right one.
        assertThat(check.outcome).isEqualTo(SlackTargetOutcome.FOUND)
        assertThat(asked).contains("users.lookupByEmail").doesNotContain("users.list")
    }

    @Test
    fun `a channel name is never mistaken for an id`() {
        answers["conversations.list"] = channels("C0000000001" to "general")

        assertThat(directory().check(CONNECTION_ID, SlackTargetKind.CHANNEL, "general").outcome)
            .isEqualTo(SlackTargetOutcome.FOUND)
        assertThat(asked).contains("conversations.list").doesNotContain("conversations.info")
    }

    @Test
    fun `a connection with nothing to ask with says so instead of calling Slack`() {
        val none = directory(secret = null).check(CONNECTION_ID, SlackTargetKind.CHANNEL, "#notifications")
        assertThat(none.outcome).isEqualTo(SlackTargetOutcome.UNCHECKED)
        assertThat(none.message).contains("has no bot token stored")

        // The token that opens the socket cannot look anything up either, and
        // saying so beats reading an invalid_auth back and guessing why.
        val appToken = directory(secret = "xapp-1-test").check(CONNECTION_ID, SlackTargetKind.USER, "@alice")
        assertThat(appToken.outcome).isEqualTo(SlackTargetOutcome.UNCHECKED)
        assertThat(appToken.message).contains("app-level token where its bot token belongs")

        // And a connection that is not Slack at all has no such question to answer.
        val http = directory(type = ConnectionType.HTTP).check(CONNECTION_ID, SlackTargetKind.CHANNEL, "#anything")
        assertThat(http.outcome).isEqualTo(SlackTargetOutcome.UNCHECKED)
        assertThat(http.message).contains("only a Slack connection can be asked")

        assertThat(asked).isEmpty()
    }

    @Test
    fun `an empty field is not a question`() {
        val check = directory().check(CONNECTION_ID, SlackTargetKind.CHANNEL, "   ")

        assertThat(check.outcome).isEqualTo(SlackTargetOutcome.UNCHECKED)
        assertThat(asked).isEmpty()
    }

    @Test
    fun `no answer ever carries the token`() {
        answers["conversations.list"] = """{"ok":false,"error":"missing_scope","needed":"channels:read"}"""
        answers["users.list"] = """{"ok":false,"error":"invalid_auth"}"""

        val directory = directory(secret = SECRET)
        val messages = listOf(
            directory.check(CONNECTION_ID, SlackTargetKind.CHANNEL, "#notifications").message,
            directory.check(CONNECTION_ID, SlackTargetKind.USER, "@alice").message,
            directory.check(CONNECTION_ID, SlackTargetKind.CHANNEL, "").message,
        )

        assertThat(messages).noneMatch { it.contains(SECRET) || it.contains("xoxb") }
        // And the token did go out, so this is not passing because nothing ran.
        assertThat(authorizations).isNotEmpty().allMatch { it == "Bearer $SECRET" }
    }

    /** [SlackDirectory] as the application builds it, pointed at the stand-in. */
    private fun directory(
        secret: String? = SECRET,
        type: ConnectionType = ConnectionType.SLACK,
    ): SlackDirectory {
        val clients = SlackClients(ProxyRouter(ProxyRuleSource { emptyList() }))
        clients.webApi.config.methodsEndpointUrlPrefix = "http://${api.address.hostString}:${api.address.port}/api/"
        return SlackDirectory(connections(secret, type), clients)
    }

    private fun connections(secret: String?, type: ConnectionType): WorkspaceConnectionRepository {
        val connection = WorkspaceConnection(
            id = CONNECTION_ID,
            workspaceId = 1,
            name = "Support Slack",
            type = type,
            url = "https://slack.com",
            secret = secret,
        )
        val repository = mock(WorkspaceConnectionRepository::class.java)
        `when`(repository.findById(CONNECTION_ID)).thenReturn(Optional.of(connection))
        return repository
    }

    private fun channels(vararg named: Pair<String, String>): String =
        named.joinToString(",", """{"ok":true,"channels":[""", "]}") { (id, name) ->
            """{"id":"$id","name":"$name","name_normalized":"$name"}"""
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
        const val SECRET = "xoxb-not-a-real-token"
    }
}
