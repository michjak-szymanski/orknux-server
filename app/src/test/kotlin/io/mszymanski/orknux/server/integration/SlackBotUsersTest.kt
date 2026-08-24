package io.mszymanski.orknux.server.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.connection.ConnectionType
import io.mszymanski.orknux.connector.connection.SlackBotUserOutcome
import io.mszymanski.orknux.connector.connection.SlackBotUsers
import io.mszymanski.orknux.connector.connection.SlackClients
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Who a Slack connection posts as, which is the fact a reply trigger is built
 * on: a bot token is a Slack user, and a thread reply names the user who wrote
 * its parent.
 *
 * Three things are worth holding here. That `auth.test` is asked once per token
 * rather than once per reply, because a busy channel would otherwise spend the
 * connection's rate limit on a question whose answer never changes. That a
 * token which cannot be asked comes back as a sentence rather than as an
 * exception or an empty picker. And that two connections holding the same token
 * are told they are one Slack user, because nothing on an arriving event could
 * ever tell them apart and a picker that implied otherwise would be lying.
 *
 * Slack is a stand-in on the loopback address; nothing reaches the network.
 */
class SlackBotUsersTest {

    private lateinit var api: HttpServer

    /** Keyed by method name and answered verbatim. */
    private val answers = ConcurrentHashMap<String, String>()

    /** `auth.test` answers for a particular token, where a test holds two. */
    private val perToken = ConcurrentHashMap<String, String>()

    /** Extra response headers, which is where Slack states the granted scopes. */
    private val headers = ConcurrentHashMap<String, String>()

    /** Which methods were called, in order, so a second ask can be told from a cache hit. */
    private val asked = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun start() {
        answers.clear()
        perToken.clear()
        headers.clear()
        asked.clear()
        answers["auth.test"] = ok(OURS)

        api = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        api.createContext("/") { exchange ->
            val method = exchange.requestURI.path.substringAfterLast('/')
            asked += method
            headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            // A token is a Slack user, so the stand-in answers by token rather
            // than by method alone - which is the whole of what the shared-token
            // case is about.
            val bearer = exchange.requestHeaders.getFirst("Authorization").orEmpty().removePrefix("Bearer ")
            respond(exchange, perToken[bearer] ?: answers[method] ?: """{"ok":true}""")
        }
        api.start()
    }

    @AfterEach
    fun stop() = api.stop(0)

    @Test
    fun `a working bot token says which Slack user it posts as`() {
        val bot = botUsers().identify(SUPPORT)

        assertThat(bot.outcome).isEqualTo(SlackBotUserOutcome.FOUND)
        assertThat(bot.userId).isEqualTo(OURS)
        assertThat(bot.handle).isEqualTo("@orknux")
        assertThat(bot.name).isEqualTo("Support Slack")
        // Nothing is wrong, so nothing is said. A line under every row would be
        // a line nobody reads when one of them matters.
        assertThat(bot.message).isEmpty()
    }

    /**
     * The reason there is a cache at all: this is consulted on every reply in
     * every channel the bot reads.
     */
    @Test
    fun `the same connection is asked once and remembered`() {
        val botUsers = botUsers()

        repeat(5) { assertThat(botUsers.identify(SUPPORT).userId).isEqualTo(OURS) }

        assertThat(asked.count { it == "auth.test" }).isEqualTo(1)
    }

    /** And a credential that changed is a user that may have changed with it. */
    @Test
    fun `forgetting a connection asks again`() {
        val botUsers = botUsers()
        botUsers.identify(SUPPORT)

        botUsers.forget(SUPPORT)
        botUsers.identify(SUPPORT)

        assertThat(asked.count { it == "auth.test" }).isEqualTo(2)
    }

    /**
     * A refusal is a sentence, not a failure.
     *
     * This is what a form draws beside the picker, and what saving a reply
     * trigger on such a connection is refused with — before it becomes a trigger
     * that is enabled, instanced and permanently deaf.
     */
    @Test
    fun `a token Slack refuses says so in one line, and names no user`() {
        answers["auth.test"] = """{"ok":false,"error":"invalid_auth"}"""

        val bot = botUsers().identify(SUPPORT)

        assertThat(bot.outcome).isEqualTo(SlackBotUserOutcome.UNCHECKED)
        assertThat(bot.userId).isNull()
        assertThat(bot.message).contains("invalid_auth")
        assertThat(bot.message.length).isLessThan(120)
    }

    /** The two tokens are easy to paste into each other's box, and say different things. */
    @Test
    fun `the app-level token in the bot token's place is named as that`() {
        val bot = botUsers(secret = "xapp-1-not-a-real-token").identify(SUPPORT)

        assertThat(bot.outcome).isEqualTo(SlackBotUserOutcome.UNCHECKED)
        assertThat(bot.message).contains("xoxb-")
        // Never asked, because there is nothing to ask with.
        assertThat(asked).doesNotContain("auth.test")
    }

    /**
     * Receiving is a different question from posting, and a token set up to post
     * carries none of the scopes it needs.
     */
    @Test
    fun `a token with no history scope says messages will not arrive`() {
        headers["x-oauth-scopes"] = "chat:write,users:read"

        val bot = botUsers().identify(SUPPORT)

        // Still a perfectly good identity: it can be watched for replies, it
        // just cannot be the connection that hears them.
        assertThat(bot.outcome).isEqualTo(SlackBotUserOutcome.FOUND)
        assertThat(bot.receives).isFalse()
        assertThat(bot.message).contains("channels:history")
        assertThat(bot.message.length).isLessThan(120)
    }

    @Test
    fun `a token carrying a history scope is left alone`() {
        headers["x-oauth-scopes"] = "chat:write,channels:history"

        val bot = botUsers().identify(SUPPORT)

        assertThat(bot.receives).isTrue()
        assertThat(bot.message).isEmpty()
    }

    /**
     * An absence Slack did not report is not an absence.
     *
     * A response with no scope header has said nothing about scopes, and telling
     * somebody their token is missing one would send them to fix a token that is
     * fine.
     */
    @Test
    fun `a response that says nothing about scopes reports nothing about them`() {
        val bot = botUsers().identify(SUPPORT)

        assertThat(bot.receives).isNull()
        assertThat(bot.message).isEmpty()
    }

    /**
     * The distinction Slack does not make, said out loud.
     *
     * Two connections, one bot token, one Slack user. `parent_user_id` on a
     * reply is that user, so a trigger watching one of them is watching both and
     * a picker offering a choice between them is offering one that does not
     * exist.
     */
    @Test
    fun `two connections sharing a bot token are told they are one Slack user`() {
        val bots = botUsers(twoConnections = true).identify(listOf(SUPPORT, OPS))

        assertThat(bots.map { it.userId }).containsExactly(OURS, OURS)
        assertThat(bots.first().message).contains("The same Slack user as Ops Slack")
        assertThat(bots.last().message).contains("The same Slack user as Support Slack")
        assertThat(bots.map { it.message.length }).allMatch { it < 120 }
    }

    @Test
    fun `connections with different tokens are not said to share anything`() {
        perToken[THEIR_SECRET] = ok(THEIRS)

        val bots = botUsers(twoConnections = true, otherSecret = THEIR_SECRET).identify(listOf(SUPPORT, OPS))

        assertThat(bots.map { it.userId }).containsExactly(OURS, THEIRS)
        assertThat(bots.map { it.message }).containsExactly("", "")
    }

    /** The ids a reply is matched against, and nothing from a token that could not be asked. */
    @Test
    fun `only the connections that answered contribute an id`() {
        val ids = botUsers(twoConnections = true, otherSecret = null).userIdsOf(listOf(SUPPORT, OPS))

        assertThat(ids).containsExactly(OURS)
    }

    /** [SlackBotUsers] as the application builds it, pointed at the stand-in. */
    private fun botUsers(
        secret: String? = SECRET,
        twoConnections: Boolean = false,
        otherSecret: String? = SECRET,
    ): SlackBotUsers {
        val clients = SlackClients(ProxyRouter(ProxyRuleSource { emptyList() }))
        clients.webApi.config.methodsEndpointUrlPrefix = "http://${api.address.hostString}:${api.address.port}/api/"
        return SlackBotUsers(connections(secret, twoConnections, otherSecret), plainCredentials(), clients)
    }

    private fun connections(
        secret: String?,
        twoConnections: Boolean,
        otherSecret: String?,
    ): WorkspaceConnectionRepository {
        val repository = mock(WorkspaceConnectionRepository::class.java)
        `when`(repository.findById(SUPPORT)).thenReturn(Optional.of(connection(SUPPORT, "Support Slack", secret)))
        if (twoConnections) {
            `when`(repository.findById(OPS)).thenReturn(Optional.of(connection(OPS, "Ops Slack", otherSecret)))
        }
        return repository
    }

    private fun connection(id: Long, name: String, secret: String?) = WorkspaceConnection(
        id = id,
        workspaceId = 1,
        name = name,
        type = ConnectionType.SLACK,
        url = "https://slack.com/api",
        secret = secret,
    )

    private fun ok(userId: String) =
        """{"ok":true,"team":"Acme","team_id":"T00000001","user":"orknux","user_id":"$userId","bot_id":"B0000000001"}"""

    private fun respond(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private companion object {
        const val SUPPORT = 7L
        const val OPS = 8L
        const val SECRET = "xoxb-not-a-real-token"
        const val THEIR_SECRET = "xoxb-a-different-token"

        /** The Slack user the bot token posts as. */
        const val OURS = "U0000ORKNU"

        /** And the user a second, different token posts as. */
        const val THEIRS = "U0000OTHER"
    }
}
