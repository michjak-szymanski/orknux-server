package io.mszymanski.orknux.server.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.connection.ConnectionRepository
import io.mszymanski.orknux.connector.connection.SlackClients
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.server.action.WorkflowActionRepository
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
import java.util.concurrent.ConcurrentHashMap

/**
 * The `slackTarget` query, which is what a form asks beside the User and
 * Channel fields of a Slack action.
 *
 * Two things are worth holding here. The first is that the query answers rather
 * than fails, whatever it finds - a name that is wrong and a token that cannot
 * look one up both come back as an outcome and a sentence. The second is that
 * the field it describes is still free text: an action saves with a target
 * nothing on the connection matches, because a member who joined a minute ago
 * and a private channel this bot was never invited to are indistinguishable
 * from a typo from out here, and refusing on that would cost more than it saves.
 *
 * Slack is a stand-in on the loopback address; nothing reaches the network.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SlackTargetAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val slackClients: SlackClients,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val workspaceConnections: WorkspaceConnectionRepository,
    @Autowired val connections: ConnectionRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private lateinit var api: HttpServer
    private lateinit var realEndpoint: String

    private val answers = ConcurrentHashMap<String, String>()

    private var workspaceId: Long = 0
    private var connectionId: Long = 0

    @BeforeEach
    fun reset() {
        actions.deleteAll()
        workspaceConnections.deleteAll()
        connections.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        answers.clear()
        answers["auth.test"] = """{"ok":true,"team":"Acme","team_id":"T00000001"}"""
        api = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        api.createContext("/") { exchange ->
            respond(exchange, answers[exchange.requestURI.path.substringAfterLast('/')] ?: """{"ok":true}""")
        }
        api.start()

        // The one Slack client the application holds, pointed at the stand-in
        // and put back afterwards so nothing else in the suite inherits it.
        realEndpoint = slackClients.webApi.config.methodsEndpointUrlPrefix
        slackClients.webApi.config.methodsEndpointUrlPrefix =
            "http://${api.address.hostString}:${api.address.port}/api/"

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        connectionId = graphQlTester.document(
            """
            mutation {
              createWorkspaceConnection(input: {
                workspaceId: $workspaceId, name: "Support Slack", type: SLACK, secret: "xoxb-not-a-real-token"
              }) { id }
            }
            """,
        ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()
    }

    @AfterEach
    fun stop() {
        slackClients.webApi.config.methodsEndpointUrlPrefix = realEndpoint
        api.stop(0)
    }

    @Test
    fun `a channel the connection can see comes back with Slack's own name for it`() {
        answers["conversations.list"] =
            """{"ok":true,"channels":[{"id":"C0000000001","name":"notifications","name_normalized":"notifications"}]}"""

        graphQlTester.document(query(target = "CHANNEL", name = "#notifications")).execute()
            .path("slackTarget.outcome").entity(String::class.java).isEqualTo("FOUND")
            .path("slackTarget.id").entity(String::class.java).isEqualTo("C0000000001")
            .path("slackTarget.label").entity(String::class.java).isEqualTo("#notifications")
    }

    @Test
    fun `a token that cannot introspect is not reported as a channel that is missing`() {
        answers["conversations.list"] = """{"ok":false,"error":"missing_scope","needed":"channels:read"}"""

        graphQlTester.document(query(target = "CHANNEL", name = "#notifications")).execute()
            .path("slackTarget.outcome").entity(String::class.java).isEqualTo("UNCHECKED")
            .path("slackTarget.message").entity(String::class.java).satisfies {
                // One line, and it names the scope. A form draws this under a
                // field in a side panel; a paragraph there gets scrolled past.
                assertThat(it).startsWith("Not checked").contains("channels:read and groups:read")
                assertThat(it.length).isLessThan(120)
            }
    }

    @Test
    fun `a name nothing matches is advice, and the action saves with it anyway`() {
        answers["conversations.list"] = """{"ok":true,"channels":[]}"""

        graphQlTester.document(query(target = "CHANNEL", name = "#nowhere")).execute()
            .path("slackTarget.outcome").entity(String::class.java).isEqualTo("NOT_FOUND")

        // The point of the whole feature being a query and not a guard. Nothing
        // in createAction asks this, so a channel the lookup cannot see is
        // saved exactly as typed.
        graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Tell the room", type: EXECUTE, subtype: OUTGOING_CONNECTION,
                connectionId: $connectionId, connectionAction: SEND_MESSAGE, content: "done",
                targetName: "#nowhere"
              }) { targetName }
            }
            """,
        ).execute().path("createAction.targetName").entity(String::class.java).isEqualTo("#nowhere")

        assertThat(actions.findAll().single().targetName).isEqualTo("#nowhere")
    }

    @Test
    fun `a user is asked about through the same field`() {
        answers["users.list"] =
            """{"ok":true,"members":[{"id":"U0000000001","name":"alice","real_name":"Alice Adams"}]}"""

        graphQlTester.document(query(target = "USER", name = "@alice")).execute()
            .path("slackTarget.outcome").entity(String::class.java).isEqualTo("FOUND")
            .path("slackTarget.label").entity(String::class.java).isEqualTo("Alice Adams")
    }

    @Test
    fun `a connection that is not there is a not-found rather than an outcome`() {
        // Access is the one thing this refuses on: the question is about
        // somebody else's connection, or about none.
        graphQlTester.document(query(target = "CHANNEL", name = "#notifications", connection = connectionId + 9999))
            .execute()
            .errors().satisfy { errors ->
                assertThat(errors.map { it.errorType.toString() }).containsExactly("NOT_FOUND")
            }
    }

    @Test
    fun `a name asked about without a target is looked for in both places`() {
        answers["conversations.list"] =
            """{"ok":true,"channels":[{"id":"C0000000001","name":"notifications","name_normalized":"notifications"}]}"""
        answers["users.list"] =
            """{"ok":true,"members":[{"id":"U0000000001","name":"alice","real_name":"Alice Adams"}]}"""

        // The shape a caller actually has. An action whose target kind is not
        // set yet could not name one here, so it asked nothing at all and its
        // panel drew nothing - which is what this merge was reported for.
        graphQlTester.document(query(target = null, name = "alice")).execute()
            .path("slackTarget.outcome").entity(String::class.java).isEqualTo("FOUND")
            .path("slackTarget.id").entity(String::class.java).isEqualTo("U0000000001")
            // And it is told which kind, in the vocabulary createAction takes,
            // so the field it could not fill in is now fillable.
            .path("slackTarget.target").entity(String::class.java).isEqualTo("USER")

        graphQlTester.document(query(target = null, name = "notifications")).execute()
            .path("slackTarget.outcome").entity(String::class.java).isEqualTo("FOUND")
            .path("slackTarget.target").entity(String::class.java).isEqualTo("CHANNEL")
    }

    @Test
    fun `a token that can read only one half does not report the other as missing`() {
        answers["conversations.list"] = """{"ok":true,"channels":[]}"""
        answers["users.list"] = """{"ok":false,"error":"missing_scope","needed":"users:read"}"""

        graphQlTester.document(query(target = null, name = "alice")).execute()
            // Not NOT_FOUND. A name absent from the half that could be read is
            // not a name that does not exist.
            .path("slackTarget.outcome").entity(String::class.java).isEqualTo("UNCHECKED")
            .path("slackTarget.target").valueIsNull()
            .path("slackTarget.message").entity(String::class.java).satisfies {
                // The scope that is actually missing, and only that one.
                assertThat(it).startsWith("Not checked").contains("users:read")
                assertThat(it).doesNotContain("channels:read").doesNotContain("groups:read")
                assertThat(it.length).isLessThan(120)
            }
    }

    @Test
    fun `a token that can read neither half names both scopes in one line`() {
        answers["conversations.list"] = """{"ok":false,"error":"missing_scope","needed":"channels:read"}"""
        answers["users.list"] = """{"ok":false,"error":"missing_scope","needed":"users:read"}"""

        graphQlTester.document(query(target = null, name = "support")).execute()
            .path("slackTarget.outcome").entity(String::class.java).isEqualTo("UNCHECKED")
            .path("slackTarget.message").entity(String::class.java).satisfies {
                assertThat(it).contains("the channels:read, groups:read and users:read scopes")
                assertThat(it.length).isLessThan(120)
            }
    }

    private fun query(target: String?, name: String, connection: Long = connectionId) =
        """
        query {
          slackTarget(connectionId: $connection, target: ${target ?: "null"}, name: "$name") {
            outcome message id label target
          }
        }
        """

    private fun respond(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }
}
