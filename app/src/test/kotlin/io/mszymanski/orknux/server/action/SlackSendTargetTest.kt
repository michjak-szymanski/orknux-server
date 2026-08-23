package io.mszymanski.orknux.server.action

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.connection.ConnectionRepository
import io.mszymanski.orknux.connector.connection.SlackClients
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.server.workflow.WorkflowEdgeRepository
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.StepStatus
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
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
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where a Slack send actually goes, now that an action stores no message kind.
 *
 * The kind was CHANNEL or USER beside the name, and it never travelled:
 * `OutgoingMessages` takes the destination as a string and Slack's one
 * `channel` argument is what receives it. Removing it therefore rests on the
 * one field being enough on its own, which is what is checked here - a bare
 * name, a `#name`, an `@name`, an address and an id, all put in the same box,
 * all arriving at the conversation they name.
 *
 * **What makes that true is resolution, not Slack's good manners.**
 * `chat.postMessage` resolves a channel *name* and does not resolve a person's
 * handle - `@alice` there is a `channel_not_found` - so a person is reached by
 * their user id and the field has to be turned into one before it is posted to.
 * The alternative would have been a rule about typing an id for a person, which
 * is a rule somebody forgets and then wonders where their message went. The
 * assertions below are on the `channel` Slack was handed, because that is the
 * only place the difference shows.
 *
 * Slack is a stand-in on the loopback address; nothing reaches the network.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SlackSendTargetTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val slackClients: SlackClients,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val workspaceConnections: WorkspaceConnectionRepository,
    @Autowired val connections: ConnectionRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private lateinit var api: HttpServer
    private lateinit var realEndpoint: String

    private val answers = ConcurrentHashMap<String, String>()

    /** Every `channel` Slack was posted to, in the order the runs sent them. */
    private val posted = CopyOnWriteArrayList<String>()

    private var workspaceId: Long = 0
    private var workflowId: Long = 0
    private var connectionId: Long = 0

    @BeforeEach
    fun reset() {
        logs.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        actions.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        workspaceConnections.deleteAll()
        connections.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        posted.clear()
        answers.clear()
        answers["auth.test"] = """{"ok":true,"team":"Acme","team_id":"T00000001"}"""
        answers["chat.postMessage"] = """{"ok":true,"channel":"C0000000001","ts":"1700000000.000100"}"""
        answers["conversations.list"] =
            """{"ok":true,"channels":[{"id":"C0000000001","name":"notifications","name_normalized":"notifications"}]}"""
        answers["users.list"] =
            """{"ok":true,"members":[{"id":"U0000000001","name":"alice","real_name":"Alice Adams","profile":{"email":"alice@example.com"}}]}"""
        answers["users.lookupByEmail"] =
            """{"ok":true,"user":{"id":"U0000000001","name":"alice","real_name":"Alice Adams"}}"""

        api = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        api.createContext("/") { exchange ->
            val method = exchange.requestURI.path.substringAfterLast('/')
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            if (method == "chat.postMessage") posted += channelIn(body)
            respond(exchange, answers[method] ?: """{"ok":true}""")
        }
        api.start()

        realEndpoint = slackClients.webApi.config.methodsEndpointUrlPrefix
        slackClients.webApi.config.methodsEndpointUrlPrefix =
            "http://${api.address.hostString}:${api.address.port}/api/"

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        // A connection of its own per test, which keeps the module's listing
        // cache out of the way: it is keyed by connection, and no two tests
        // here share one.
        connectionId = graphQlTester.document(
            """
            mutation {
              createWorkspaceConnection(input: {
                workspaceId: $workspaceId, name: "Support Slack", type: SLACK, secret: "xoxb-not-a-real-token"
              }) { id }
            }
            """,
        ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Notify" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
    }

    @AfterEach
    fun stop() {
        slackClients.webApi.config.methodsEndpointUrlPrefix = realEndpoint
        api.stop(0)
    }

    /**
     * The whole point of the removal, in one test.
     *
     * Six things somebody might type into the one field, and the conversation
     * each of them reaches. Nothing anywhere says which kind any of them is:
     * the channel and the person are told apart by what Slack answers to, at
     * the moment the message goes, and never by a column filled in weeks
     * earlier by somebody who might have picked the other one.
     */
    @Test
    fun `every shape of destination reaches the conversation it names, with no kind stored`() {
        // A bare name is the case the removed column claimed to disambiguate.
        // It costs both lookups and settles itself.
        assertThat(sentTo("notifications")).isEqualTo("C0000000001")
        assertThat(sentTo("#notifications")).isEqualTo("C0000000001")
        assertThat(sentTo("C0000000001")).isEqualTo("C0000000001")

        // And the half that was never going to work by name. Slack does not
        // resolve a handle in `channel`, so this is the assertion that says the
        // field is enough on its own.
        assertThat(sentTo("@alice")).isEqualTo("U0000000001")
        assertThat(sentTo("alice")).isEqualTo("U0000000001")
        assertThat(sentTo("alice@example.com")).isEqualTo("U0000000001")
    }

    /**
     * A name the connection's own lists do not hold is still sent.
     *
     * A private channel this bot is in does not come back from
     * `conversations.list`, and refusing to post to one because a lookup could
     * not see it would break sends that work today. Slack gets what was typed,
     * and its own answer about it is the one that is reported.
     */
    @Test
    fun `a destination nothing can be found for is handed to Slack as it was typed`() {
        assertThat(sentTo("#hush-hush")).isEqualTo("#hush-hush")
    }

    /** And the setting is gone from the surface, not merely unread. */
    @Test
    fun `an action cannot be given a message kind at all`() {
        graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Tell the room", type: EXECUTE, subtype: OUTGOING_CONNECTION,
                connectionId: $connectionId, connectionAction: SEND_MESSAGE, content: "done",
                target: CHANNEL, targetName: "#notifications"
              }) { id }
            }
            """,
        ).execute().errors().satisfy { errors ->
            assertThat(errors.mapNotNull { it.message }).anyMatch { it.contains("target") }
        }

        assertThat(actions.findAll()).isEmpty()
    }

    /** Builds a send with this destination, runs it, and answers with what Slack was posted to. */
    private fun sentTo(destination: String): String {
        val actionId = graphQlTester.document(
            """
            mutation(${'$'}name: String!, ${'$'}destination: String) {
              createAction(input: {
                workspaceId: $workspaceId, name: ${'$'}name, type: EXECUTE, subtype: OUTGOING_CONNECTION,
                connectionId: $connectionId, connectionAction: SEND_MESSAGE,
                content: "Your request is approved", targetName: ${'$'}destination
              }) { id targetName }
            }
            """,
        )
            .variable("name", "Send to $destination")
            .variable("destination", destination)
            .execute()
            // Free text, and stored exactly as it was typed. Resolution happens
            // when it is sent and writes nothing back.
            .path("createAction.targetName").entity(String::class.java).isEqualTo(destination)
            .path("createAction.id").entity(Long::class.java).get()

        // No mappings: the node is seeded from the action, which is the ordinary
        // case and the one where the definition's own destination is what runs.
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [{ key: "act", kind: ACTION, name: "Act", actionId: $actionId, x: 0, y: 0 }],
                edges: []
              }) { nodes { key actionId } }
            }
            """,
        ).execute().path("saveWorkflowGraph.nodes[0].actionId").entity(Long::class.java).isEqualTo(actionId)

        val runId = graphQlTester.document(
            """mutation { startExecution(workspaceId: $workspaceId, workflowId: $workflowId) { id } }""",
        ).execute().path("startExecution.id").entity(Long::class.java).get()

        val step = steps.findAll().single { it.executionId == runId }
        assertThat(step.status).isEqualTo(StepStatus.COMPLETED)
        return posted.last()
    }

    /**
     * The `channel` the Slack client sent, whichever way it sent it.
     *
     * The SDK posts these methods as a form, and reading the JSON shape too
     * means a client that changes its mind fails this on the assertion rather
     * than on a missing key.
     */
    private fun channelIn(body: String): String {
        val form = body.split('&')
            .map { it.split('=', limit = 2) }
            .filter { it.size == 2 }
            .associate { (name, value) ->
                URLDecoder.decode(name, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
            }
        form["channel"]?.let { return it }
        return CHANNEL_IN_JSON.find(body)?.groupValues?.get(1) ?: "nothing named a channel in: $body"
    }

    private fun respond(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private companion object {
        val CHANNEL_IN_JSON = """"channel"\s*:\s*"([^"]*)"""".toRegex()
    }
}
