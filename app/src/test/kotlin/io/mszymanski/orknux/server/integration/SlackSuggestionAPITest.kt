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
 * The `slackSuggestions` query, which is what a picker over an action's User and
 * Channel fields asks.
 *
 * It is the other half of [SlackTargetAPITest]'s question and it answers in the
 * same vocabulary, which is most of what is held here: the outcomes mean what
 * they mean there, so a form can draw both answers the same way. The rest is
 * the part that keeps this a suggestion. A picker that refuses what somebody
 * knows is right is worse than a plain box, so a name nothing offered still
 * saves, and it saves exactly as typed.
 *
 * Slack is a stand-in on the loopback address; nothing reaches the network.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SlackSuggestionAPITest(
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

        realEndpoint = slackClients.webApi.config.methodsEndpointUrlPrefix
        slackClients.webApi.config.methodsEndpointUrlPrefix =
            "http://${api.address.hostString}:${api.address.port}/api/"

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        // A connection of its own per test, which is also what keeps the
        // module's cache out of the way: it is keyed by connection, and no two
        // tests here share one.
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
    fun `the channels a connection can see come back as something to pick`() {
        answers["conversations.list"] =
            """{"ok":true,"channels":[""" +
                """{"id":"C0000000001","name":"notifications","name_normalized":"notifications"},""" +
                """{"id":"C0000000002","name":"general","name_normalized":"general"}""" +
                """]}"""

        graphQlTester.document(query(target = "CHANNEL", typed = "not")).execute()
            .path("slackSuggestions.outcome").entity(String::class.java).isEqualTo("FOUND")
            // What the field is filled with is what somebody could have typed.
            .path("slackSuggestions.matches[*].name").entityList(String::class.java)
            .containsExactly("#notifications")
            .path("slackSuggestions.matches[*].id").entityList(String::class.java)
            .containsExactly("C0000000001")
            .path("slackSuggestions.complete").entity(Boolean::class.java).isEqualTo(true)
            // Nothing worth saying under a picker that is working, which is the
            // usual answer.
            .path("slackSuggestions.message").entity(String::class.java).isEqualTo("")
    }

    @Test
    fun `a member comes back with the handle to type and the name to recognise`() {
        answers["users.list"] =
            """{"ok":true,"members":[{"id":"U0000000001","name":"alice","real_name":"Alice Adams"}]}"""

        graphQlTester.document(query(target = "USER", typed = "@al")).execute()
            .path("slackSuggestions.matches[*].name").entityList(String::class.java).containsExactly("@alice")
            .path("slackSuggestions.matches[*].realName").entityList(String::class.java)
            .containsExactly("Alice Adams")
    }

    @Test
    fun `an empty field asks for the first few of everything`() {
        answers["conversations.list"] =
            """{"ok":true,"channels":[{"id":"C0000000001","name":"general","name_normalized":"general"}]}"""

        // What a picker shows when it opens, before anything has been typed.
        graphQlTester.document(query(target = "CHANNEL", typed = null)).execute()
            .path("slackSuggestions.outcome").entity(String::class.java).isEqualTo("FOUND")
            .path("slackSuggestions.matches[*].name").entityList(String::class.java).containsExactly("#general")
    }

    @Test
    fun `a token that cannot introspect empties the picker with a reason rather than in silence`() {
        answers["conversations.list"] = """{"ok":false,"error":"missing_scope","needed":"channels:read"}"""

        graphQlTester.document(query(target = "CHANNEL", typed = "not")).execute()
            // Not NOT_FOUND: nothing about what was typed has been judged.
            .path("slackSuggestions.outcome").entity(String::class.java).isEqualTo("UNCHECKED")
            .path("slackSuggestions.matches[*].id").entityList(String::class.java).hasSize(0)
            .path("slackSuggestions.message").entity(String::class.java).satisfies {
                // The check's own sentence, word for word, and one line of it.
                assertThat(it).startsWith("Not checked").contains("channels:read and groups:read")
                assertThat(it.length).isLessThan(120)
            }
    }

    @Test
    fun `a name nothing suggests is still saved, exactly as typed`() {
        answers["conversations.list"] = """{"ok":true,"channels":[]}"""

        graphQlTester.document(query(target = "CHANNEL", typed = "#nowhere")).execute()
            .path("slackSuggestions.outcome").entity(String::class.java).isEqualTo("NOT_FOUND")

        // The point of the whole feature being a suggestion. A member who joined
        // a minute ago, an id pasted out of somebody else's message and a
        // private channel this bot was never invited to are all unsuggestable
        // and all correct, so nothing here may reach createAction.
        graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Tell the room", type: EXECUTE, subtype: OUTGOING_CONNECTION,
                connectionId: $connectionId, connectionAction: SEND_MESSAGE, content: "done",
                target: CHANNEL, targetName: "#nowhere"
              }) { targetName }
            }
            """,
        ).execute().path("createAction.targetName").entity(String::class.java).isEqualTo("#nowhere")

        assertThat(actions.findAll().single().targetName).isEqualTo("#nowhere")
    }

    @Test
    fun `a connection that is not there is a not-found rather than an outcome`() {
        // Access is the one thing this refuses on, as the check beside it does.
        graphQlTester.document(query(target = "CHANNEL", typed = "not", connection = connectionId + 9999))
            .execute()
            .errors().satisfy { errors ->
                assertThat(errors.map { it.errorType.toString() }).containsExactly("NOT_FOUND")
            }
    }

    @Test
    fun `a picker with no target set gets one list, and every row says which it is`() {
        answers["conversations.list"] =
            """{"ok":true,"channels":[{"id":"C0000000001","name":"alerts","name_normalized":"alerts"}]}"""
        answers["users.list"] =
            """{"ok":true,"members":[{"id":"U0000000001","name":"alastair"}]}"""

        graphQlTester.document(query(target = null, typed = "al")).execute()
            .path("slackSuggestions.outcome").entity(String::class.java).isEqualTo("FOUND")
            // Channels before members where they tie, which is what a merged
            // list needs a rule for at all.
            .path("slackSuggestions.matches[*].name").entityList(String::class.java)
            .containsExactly("#alerts", "@alastair")
            // In createAction's own vocabulary, so picking a row sets the
            // action's kind and its name together.
            .path("slackSuggestions.matches[*].target").entityList(String::class.java)
            .containsExactly("CHANNEL", "USER")
            .path("slackSuggestions.complete").entity(Boolean::class.java).isEqualTo(true)
    }

    @Test
    fun `a token that can read one list still fills the picker, and says what is missing`() {
        answers["conversations.list"] =
            """{"ok":true,"channels":[{"id":"C0000000001","name":"notifications","name_normalized":"notifications"}]}"""
        answers["users.list"] = """{"ok":false,"error":"missing_scope","needed":"users:read"}"""

        graphQlTester.document(query(target = null, typed = "not")).execute()
            .path("slackSuggestions.outcome").entity(String::class.java).isEqualTo("FOUND")
            .path("slackSuggestions.matches[*].name").entityList(String::class.java)
            .containsExactly("#notifications")
            // Half a search is not a search, whatever it found.
            .path("slackSuggestions.complete").entity(Boolean::class.java).isEqualTo(false)
            .path("slackSuggestions.message").entity(String::class.java).satisfies {
                assertThat(it).isEqualTo(
                    "Channels only - this connection's bot token is missing the users:read scope.",
                )
                assertThat(it.length).isLessThan(120)
            }
    }

    private fun query(target: String?, typed: String?, connection: Long = connectionId) =
        """
        query {
          slackSuggestions(
            connectionId: $connection, target: ${target ?: "null"},
            typed: ${typed?.let { "\"$it\"" } ?: "null"}
          ) {
            outcome message complete matches { id name realName target }
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
