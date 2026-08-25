package io.mszymanski.orknux.server.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.connection.ConnectionRepository
import io.mszymanski.orknux.connector.connection.IncomingAction
import io.mszymanski.orknux.connector.connection.IncomingEvent
import io.mszymanski.orknux.connector.connection.SlackBotUsers
import io.mszymanski.orknux.connector.connection.SlackClients
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.server.trigger.WorkflowTriggerRepository
import io.mszymanski.orknux.server.workflow.WorkflowEdgeRepository
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionService
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * A trigger that waits for somebody to answer one of our own bots in a thread.
 *
 * **What it is really matching.** A bot token is a Slack user, and a thread
 * reply carries `parent_user_id` — the author of the message the thread hangs
 * under. So "a reply to one of ours" is that id against the users behind the
 * connections the trigger names, and those connections are not the one it
 * listens on: the socket is one Slack app, and the bots people want answered are
 * usually others.
 *
 * **Refused at save time, not at match time.** A watched connection whose token
 * will not authenticate has no user id to compare against, so a trigger built on
 * one is enabled, instanced and permanently deaf. The only way to notice that
 * later is to wait for a reply that never fires, which is why it is refused
 * while somebody is still looking at the form.
 *
 * Slack is a stand-in on the loopback address; nothing reaches the network.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SlackReplyTriggerTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val slackClients: SlackClients,
    @Autowired val botUsers: SlackBotUsers,
    @Autowired val publisher: ApplicationEventPublisher,
    @Autowired val runs: ExecutionService,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val triggers: WorkflowTriggerRepository,
    @Autowired val workspaceConnections: WorkspaceConnectionRepository,
    @Autowired val connections: ConnectionRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private lateinit var api: HttpServer
    private lateinit var realEndpoint: String

    /** `auth.test` answers by token, because a token is what a Slack user is. */
    private val perToken = ConcurrentHashMap<String, String>()

    private var workspaceId: Long = 0
    private var workflowId: Long = 0

    /** The connection the socket is open on. */
    private var listeningId: Long = 0

    /** The connection whose own messages a reply has to hang under. */
    private var watchedId: Long = 0

    @BeforeEach
    fun reset() {
        triggers.deleteAll()
        workspaceConnections.deleteAll()
        connections.deleteAll()
        logs.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        assignments.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        workflows.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        perToken.clear()
        perToken[LISTENING_TOKEN] = ok("orknux", LISTENING_BOT)
        perToken[WATCHED_TOKEN] = ok("helper", WATCHED_BOT)

        api = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        api.createContext("/") { exchange ->
            val bearer = exchange.requestHeaders.getFirst("Authorization").orEmpty().removePrefix("Bearer ")
            respond(exchange, perToken[bearer] ?: """{"ok":false,"error":"invalid_auth"}""")
        }
        api.start()

        // The one Slack client the application holds, pointed at the stand-in
        // and put back afterwards so nothing else in the suite inherits it.
        realEndpoint = slackClients.webApi.config.methodsEndpointUrlPrefix
        slackClients.webApi.config.methodsEndpointUrlPrefix =
            "http://${api.address.hostString}:${api.address.port}/api/"

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Thread Answers" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()

        listeningId = connection("Workspace Slack", LISTENING_TOKEN)
        watchedId = connection("Helper Bot", WATCHED_TOKEN)
        // Ids are reused across runs of the suite only in the sense that a row
        // is; the cache is keyed by connection, so it is cleared rather than
        // trusted to have expired.
        botUsers.forget(listeningId)
        botUsers.forget(watchedId)
    }

    @AfterEach
    fun stop() {
        slackClients.webApi.config.methodsEndpointUrlPrefix = realEndpoint
        api.stop(0)
    }

    @Test
    fun `a reply trigger names the connections whose messages it watches`() {
        graphQlTester.document(createReply("Answered In Thread", listOf(watchedId)))
            .execute()
            .path("createTrigger.action").entity(String::class.java).isEqualTo("REPLY")
            .path("createTrigger.watchedConnectionIds").entityList(String::class.java)
            .containsExactly(watchedId.toString())
    }

    /**
     * Otherwise it is every thread in every channel the bot can read, which is
     * not what anybody means and is a great many workflow runs.
     */
    @Test
    fun `a reply trigger watching nobody is refused`() {
        graphQlTester.document(createReply("Answered In Thread", emptyList()))
            .execute()
            .errors().satisfy { errors ->
                assertThat(errors.first().message).contains("at least one connection")
            }
        assertThat(triggers.findAll()).isEmpty()
    }

    /** A token with no user behind it can never be the parent of anything. */
    @Test
    fun `a watched connection whose token Slack refuses is refused here`() {
        val broken = connection("Broken Bot", "xoxb-a-token-slack-does-not-know")

        graphQlTester.document(createReply("Answered In Thread", listOf(broken)))
            .execute()
            .errors().satisfy { errors ->
                assertThat(errors.first().message)
                    .contains("Broken Bot")
                    .contains("invalid_auth")
            }
        assertThat(triggers.findAll()).isEmpty()
    }

    /** Every other event is about the connection it arrives on, and needs none of this. */
    @Test
    fun `a message trigger needs nobody to watch`() {
        graphQlTester.document(
            """
            mutation {
              createTrigger(input: {
                workspaceId: $workspaceId, name: "Anything Said",
                type: INCOMING_CONNECTION, connectionId: $listeningId, action: MESSAGE
              }) { id watchedConnectionIds }
            }
            """,
        ).execute()
            .path("createTrigger.watchedConnectionIds").entityList(String::class.java).hasSize(0)
    }

    @Test
    fun `a message and a reply are both offered, because both are delivered`() {
        graphQlTester.document("""query { supportedTriggerActions }""")
            .execute()
            .path("supportedTriggerActions").entityList(String::class.java)
            .contains("MENTION", "MESSAGE", "REPLY")
    }

    @Test
    fun `the picker is told which Slack user each connection posts as`() {
        val answered = graphQlTester.document(
            """query { slackBotUsers(workspaceId: $workspaceId) { connectionId name userId handle outcome message } }""",
        ).execute()

        assertThat(answered.path("slackBotUsers[*].userId").entityList(String::class.java).get())
            .containsExactlyInAnyOrder(LISTENING_BOT, WATCHED_BOT)
        assertThat(answered.path("slackBotUsers[*].handle").entityList(String::class.java).get())
            .containsExactlyInAnyOrder("@orknux", "@helper")
        // Two different tokens, so nothing is said about either of them.
        assertThat(answered.path("slackBotUsers[*].message").entityList(String::class.java).get())
            .containsOnly("")
    }

    /** The whole of the feature, end to end. */
    @Test
    fun `a reply to a watched bot's message starts the workflow`() {
        val trigger = graphQlTester.document(createReply("Answered In Thread", listOf(watchedId)))
            .execute().path("createTrigger.id").entity(Long::class.java).get()
        instance(trigger)

        publisher.publishEvent(reply(parentUserId = WATCHED_BOT))

        assertThat(started().map { it.workflowName }).containsExactly("Thread Answers")
    }

    /**
     * And the half that makes it a feature rather than "any reply at all": a
     * thread somebody else started is somebody else's conversation.
     */
    @Test
    fun `a reply to somebody else's message starts nothing`() {
        val trigger = graphQlTester.document(createReply("Answered In Thread", listOf(watchedId)))
            .execute().path("createTrigger.id").entity(Long::class.java).get()
        instance(trigger)

        publisher.publishEvent(reply(parentUserId = "U0000ALICE"))

        assertThat(started()).isEmpty()
        // One line, because this trigger had none. Issue #269: a reply trigger
        // watching the right bot, with the scope granted, that never fires,
        // looks exactly like one Slack is delivering nothing to - and the
        // difference is the whole of what somebody setting it up needs.
        graphQlTester.document(
            """query { triggerFirings(triggerId: $trigger) { totalElements content { outcome detail } } }""",
        )
            .execute()
            .path("triggerFirings.totalElements").entity(Int::class.java).isEqualTo(1)
            .path("triggerFirings.content[0].outcome").entity(String::class.java).isEqualTo("NOT_WATCHED")
            .path("triggerFirings.content[0].detail").entity(String::class.java)
            .satisfies { assertThat(it).contains("none of the watched bots wrote") }
    }

    /**
     * And only the first, which is what keeps the note from becoming the noise
     * it replaced. Every thread in every channel the bot can read arrives here;
     * a line for each would bury the firing somebody is looking for.
     */
    @Test
    fun `a second reply to somebody else's message adds nothing`() {
        val trigger = graphQlTester.document(createReply("Answered In Thread", listOf(watchedId)))
            .execute().path("createTrigger.id").entity(Long::class.java).get()
        instance(trigger)

        publisher.publishEvent(reply(parentUserId = "U0000ALICE"))
        publisher.publishEvent(reply(parentUserId = "U0000CAROL"))
        publisher.publishEvent(reply(parentUserId = "U0000DAVE"))

        graphQlTester.document("""query { triggerFirings(triggerId: $trigger) { totalElements } }""")
            .execute()
            .path("triggerFirings.totalElements").entity(Int::class.java).isEqualTo(1)
    }

    /**
     * A trigger that has fired stays quiet. The note answers "is anything
     * reaching this at all", and a trigger with a history has already answered
     * it.
     */
    @Test
    fun `a trigger that has already fired says nothing about a miss`() {
        val trigger = graphQlTester.document(createReply("Answered In Thread", listOf(watchedId)))
            .execute().path("createTrigger.id").entity(Long::class.java).get()
        instance(trigger)

        publisher.publishEvent(reply(parentUserId = WATCHED_BOT))
        publisher.publishEvent(reply(parentUserId = "U0000ALICE"))

        graphQlTester.document(
            """query { triggerFirings(triggerId: $trigger) { totalElements content { outcome } } }""",
        )
            .execute()
            .path("triggerFirings.totalElements").entity(Int::class.java).isEqualTo(1)
            .path("triggerFirings.content[0].outcome").entity(String::class.java).isEqualTo("STARTED")
    }

    /** A reply arriving with no parent at all is not a reply to anything of ours. */
    @Test
    fun `a reply carrying no parent starts nothing`() {
        val trigger = graphQlTester.document(createReply("Answered In Thread", listOf(watchedId)))
            .execute().path("createTrigger.id").entity(Long::class.java).get()
        instance(trigger)

        publisher.publishEvent(reply(parentUserId = null))

        assertThat(started()).isEmpty()
    }

    /** Watching two bots is one trigger, not two. */
    @Test
    fun `a trigger can watch more than one bot, and either one fires it`() {
        val second = connection("Second Bot", SECOND_TOKEN)
        botUsers.forget(second)
        perToken[SECOND_TOKEN] = ok("second", SECOND_BOT)

        val trigger = graphQlTester.document(createReply("Answered In Thread", listOf(watchedId, second)))
            .execute().path("createTrigger.id").entity(Long::class.java).get()
        instance(trigger)

        publisher.publishEvent(reply(parentUserId = WATCHED_BOT))
        publisher.publishEvent(reply(parentUserId = SECOND_BOT))

        assertThat(started()).hasSize(2)
    }

    private fun started() = runs.executions(workspaceId, null, null, null, null, null, null).content

    private fun reply(parentUserId: String?) = IncomingEvent(
        connectionId = listeningId,
        workspaceId = workspaceId,
        action = IncomingAction.REPLY,
        text = "on it",
        context = buildMap {
            put("channel", "C42")
            put("user", "U0000ALICE")
            put("ts", "1700000000.000200")
            put("threadTs", "1700000000.000100")
            parentUserId?.let { put("parentUserId", it) }
        },
    )

    private fun createReply(name: String, watched: List<Long>) = """
        mutation {
          createTrigger(input: {
            workspaceId: $workspaceId, name: "$name",
            type: INCOMING_CONNECTION, connectionId: $listeningId, action: REPLY,
            watchedConnectionIds: [${watched.joinToString(", ")}]
          }) { id action watchedConnectionIds }
        }
    """.trimIndent()

    private fun connection(name: String, secret: String): Long = graphQlTester.document(
        """
        mutation {
          createWorkspaceConnection(input: {
            workspaceId: $workspaceId, name: "$name", type: SLACK, secret: "$secret"
          }) { id }
        }
        """,
    ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()

    /** Gives the workflow a trigger node instancing [triggerId], and publishes it. */
    private fun instance(triggerId: Long) {
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "trigger", kind: TRIGGER, name: "Reply", triggerId: $triggerId, x: 0, y: 0 }
                ],
                edges: []
              }) { nodes { key } }
            }
            """,
        ).execute()
        graphQlTester.document(
            """mutation { publishWorkflow(workspaceId: $workspaceId, workflowId: $workflowId) { status } }""",
        ).execute()
    }

    private fun ok(handle: String, userId: String) =
        """{"ok":true,"team":"Acme","team_id":"T00000001","user":"$handle","user_id":"$userId","bot_id":"B01"}"""

    private fun respond(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private companion object {
        const val LISTENING_TOKEN = "xoxb-the-workspace-app"
        const val WATCHED_TOKEN = "xoxb-the-helper-bot"
        const val SECOND_TOKEN = "xoxb-the-second-bot"

        const val LISTENING_BOT = "U0000ORKNU"
        const val WATCHED_BOT = "U0000HELPR"
        const val SECOND_BOT = "U0000SECND"
    }
}
