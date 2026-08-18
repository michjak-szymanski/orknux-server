package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.connector.connection.CheckOutcome
import io.mszymanski.orknux.connector.connection.CheckResult
import io.mszymanski.orknux.connector.connection.MailDelivery
import io.mszymanski.orknux.connector.connection.MailMessage
import io.mszymanski.orknux.connector.connection.MailSecurity
import io.mszymanski.orknux.connector.connection.MailTransport
import io.mszymanski.orknux.connector.connection.SmtpServer
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.server.workflow.WorkflowEdgeRepository
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStep
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.NodeKind
import io.mszymanski.orknux.workflow.execution.StepStatus
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.WithMockUser

/**
 * Sending mail: a workspace's SMTP connection, an action that sends through it,
 * and what a step reports when the server will not take the message.
 *
 * Everything is real except the last step. [MailTransport] is the one seam that
 * cannot be exercised without a mail server listening somewhere, so it is
 * replaced with something that writes down what it was handed - which means the
 * suite sends nobody a mail and still runs the connection, the action, the
 * node's parameters and the runner as they are deployed. A test that opened a
 * socket to prove this would be testing jakarta.mail.
 *
 * The host is `localhost` throughout, because the host is vetted for real before
 * anything is sent: a made-up name would be refused as unresolvable before the
 * fake was ever reached, which would prove the wrong thing.
 *
 * Two of these call the runner directly rather than starting a workflow. What
 * separates a mail worth retrying from one that never will be is the `permanent`
 * flag on the failure, and the engine the suite runs on has no retries to make a
 * visible difference with - so the flag is read where it is set.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SendMailActionTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val runner: ActionNodeRunner,
    @Autowired val transport: RecordingMailTransport,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val connections: WorkspaceConnectionRepository,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val jdbc: JdbcTemplate,
) {

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
        connections.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        transport.forget()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Incident Response" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
        connectionId = mailConnection()
    }

    @Test
    fun `a node sends the mail its action describes, through the workspace's mail server`() {
        val actionId = mailAction()
        graph(actionId)

        start()

        val (server, message) = transport.sent.single()
        // The server the connection holds, credentials included, resolved by the
        // module that stores them rather than by anything in the workflow.
        assertThat(server).isEqualTo(
            SmtpServer(
                host = "localhost",
                port = 2525,
                username = "orknux",
                password = "s3cret",
                from = "orknux@example.com",
                security = MailSecurity.STARTTLS,
            ),
        )
        assertThat(message).isEqualTo(
            MailMessage(
                to = listOf("oncall@example.com"),
                subject = "Incident opened",
                body = "Something is on fire.",
                cc = listOf("audit@example.com"),
                replyTo = "noreply@example.com",
            ),
        )

        val step = steps.findAll().single { it.actionId == actionId }
        assertThat(step.status).isEqualTo(StepStatus.COMPLETED)
        // What the next node is handed: where to find it in the mail log, and who
        // it actually went to.
        assertThat(step.output).contains(""""messageId":"<sent@localhost>"""")
        assertThat(step.output).contains(""""recipients":["oncall@example.com"]""")
    }

    @Test
    fun `a node says who to write to, so one action serves every workflow that needs one`() {
        val actionId = mailAction()

        runner.run(step(actionId, """{"to":"someone-else@example.com","subject":"Yours","body":"Not the action's."}"""), null, null)

        val (_, message) = transport.sent.single()
        assertThat(message.to).containsExactly("someone-else@example.com")
        assertThat(message.subject).isEqualTo("Yours")
        assertThat(message.body).isEqualTo("Not the action's.")
    }

    @Test
    fun `a list of recipients is the addresses it names, however it was written`() {
        val actionId = mailAction()

        runner.run(
            step(actionId, """{"to":"first@example.com; second@example.com , ","subject":"Both","body":"Hello."}"""),
            null,
            null,
        )

        // Semicolons because a list pasted out of a mail client has them, and the
        // trailing comma because a list that was edited usually does.
        assertThat(transport.sent.single().second.to)
            .containsExactly("first@example.com", "second@example.com")
    }

    @Test
    fun `a node with nobody to write to reports it rather than sending`() {
        val actionId = mailAction(to = null)

        val result = runner.run(step(actionId, """{"subject":"Incident opened","body":"Something is on fire."}"""), null, null)

        // A half-drawn node is not a broken run, and nothing was handed to a
        // server that would only have refused it.
        assertThat(result.status).isEqualTo(StepStatus.SKIPPED)
        assertThat(result.output).contains("has nobody to send to")
        assertThat(transport.sent).isEmpty()
    }

    @Test
    fun `a mail the server refuses stops the step, and is not tried again`() {
        transport.answer = { _, _ -> MailDelivery.Refused("550 the sender is not authorised", permanent = true) }
        val actionId = mailAction()

        assertThatThrownBy { runner.run(step(actionId), null, null) }
            .isInstanceOf(ActionFailedException::class.java)
            // The addresses it tried, because a rejected recipient is nearly
            // always the parameter that was wired to the wrong field.
            .hasMessageContaining("oncall@example.com")
            .hasMessageContaining("550 the sender is not authorised")
            .extracting { (it as ActionFailedException).permanent }
            .isEqualTo(true)
    }

    @Test
    fun `a mail server that could not be reached is worth coming back to`() {
        transport.answer = { _, _ -> MailDelivery.Refused("connection timed out", permanent = false) }
        val actionId = mailAction()

        assertThatThrownBy { runner.run(step(actionId), null, null) }
            .isInstanceOf(ActionFailedException::class.java)
            .extracting { (it as ActionFailedException).permanent }
            .isEqualTo(false)
    }

    @Test
    fun `an action whose connection has been emptied reports it instead of failing the run`() {
        val actionId = mailAction()
        // What a workspace does when it disconnects the mail server but leaves
        // the workflows that used it alone.
        graphQlTester.document("""mutation { disconnectWorkspaceConnection(id: $connectionId) }""").execute()

        val result = runner.run(step(actionId), null, null)

        assertThat(result.status).isEqualTo(StepStatus.SKIPPED)
        assertThat(result.output).contains("sent no mail")
        assertThat(transport.sent).isEmpty()
    }

    @Test
    fun `checking a mail connection asks the server, not the web`() {
        transport.checked = CheckResult(CheckOutcome.CONNECTED, "Connected to localhost:2525")

        graphQlTester.document(
            """mutation { testWorkspaceConnection(id: $connectionId) { status lastCheckMessage } }""",
        ).execute()
            .path("testWorkspaceConnection.status").entity(String::class.java).isEqualTo("CONNECTED")
            .path("testWorkspaceConnection.lastCheckMessage").entity(String::class.java)
            .isEqualTo("Connected to localhost:2525")

        // A HEAD request to a mail server answers nothing worth reporting, so
        // the check has to go through the transport that speaks SMTP.
        assertThat(transport.checks).isNotEmpty()

        transport.checked = CheckResult(CheckOutcome.FAILED, "The server rejected the credentials")
        graphQlTester.document(
            """mutation { testWorkspaceConnection(id: $connectionId) { status } }""",
        ).execute().path("testWorkspaceConnection.status").entity(String::class.java).isEqualTo("FAILED")
    }

    @Test
    fun `the mail password is encrypted in the database, like every other credential`() {
        val stored = jdbc.queryForObject(
            "select secret from workspace_connection where id = ?",
            String::class.java,
            connectionId,
        )

        assertThat(stored).isNotNull().doesNotContain("s3cret")
        // Readable through the entity, which is where the converter is: nothing
        // above it knows the column is encrypted.
        assertThat(connections.findAll().single().secret).isEqualTo("s3cret")
    }

    @Test
    fun `a mail action refuses a connection that is not a mail server`() {
        val slack = graphQlTester.document(
            """
            mutation {
              createWorkspaceConnection(input: {
                workspaceId: $workspaceId, name: "Slack", type: SLACK, url: "https://slack.com/api",
                authType: BEARER_TOKEN, secret: "xoxb-token"
              }) { id }
            }
            """,
        ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()

        // Saving cleanly and then skipping every time it ran would be a slower
        // way to learn the same thing.
        graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Mail", type: EXECUTE, subtype: SEND_EMAIL, connectionId: $slack
              }) { id }
            }
            """,
        ).execute().errors().expect { it.message?.contains("SMTP connection") == true }.verify()
    }

    /** The workspace's mail server, as somebody would set one up on the screen. */
    private fun mailConnection(): Long = graphQlTester.document(
        """
        mutation {
          createWorkspaceConnection(input: {
            workspaceId: $workspaceId, name: "Company Mail", type: SMTP, url: "localhost",
            smtpPort: 2525, smtpUsername: "orknux", smtpFrom: "orknux@example.com",
            smtpSecurity: STARTTLS, secret: "s3cret"
          }) { id }
        }
        """,
    ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()

    /** An action that says the whole mail, which is what a node then seeds from. */
    private fun mailAction(to: String? = "oncall@example.com"): Long = graphQlTester.document(
        """
        mutation {
          createAction(input: {
            workspaceId: $workspaceId, name: "Tell On-Call", type: EXECUTE, subtype: SEND_EMAIL,
            connectionId: $connectionId, ${if (to == null) "" else """emailTo: "$to","""}
            emailSubject: "Incident opened", content: "Something is on fire.",
            emailCc: "audit@example.com", emailReplyTo: "noreply@example.com"
          }) { id }
        }
        """,
    ).execute().path("createAction.id").entity(Long::class.java).get()

    /**
     * A step as the planner writes one down, for the tests that ask the runner
     * directly. [mappings] is what the node passes; empty leaves every parameter
     * to fall back on the action's own setting.
     */
    private fun step(actionId: Long, mappings: String = "{}") = ExecutionStep(
        executionId = 0,
        nodeKey = "mail",
        kind = NodeKind.ACTION,
        name = "Tell On-Call",
        actionId = actionId,
        mappings = mappings,
        x = 0.0,
        y = 0.0,
        order = 0,
    )

    /** One mail node, which is the whole workflow; its parameters are the action's. */
    private fun graph(actionId: Long) {
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [{ key: "mail", kind: ACTION, name: "Tell On-Call", actionId: $actionId, x: 0, y: 0 }],
                edges: []
              }) { nodes { key actionId } }
            }
            """,
        ).execute().path("saveWorkflowGraph.nodes[0].actionId").entity(Long::class.java).isEqualTo(actionId)
    }

    private fun start(): Long = graphQlTester.document(
        """
        mutation {
          startExecution(workspaceId: $workspaceId, workflowId: $workflowId, input: "{}") { id status }
        }
        """,
    ).execute().path("startExecution.id").entity(Long::class.java).get()

    /**
     * Stands in for the mail server, and remembers what it was asked to send.
     *
     * Registered as primary rather than as the only transport, so the real one is
     * still built by the context it replaces - a bean that fails to construct is
     * something this suite should notice.
     */
    @TestConfiguration
    class FakeTransport {

        @Bean
        @Primary
        fun recordingMailTransport(): RecordingMailTransport = RecordingMailTransport()
    }
}

/** What was handed to the mail server, and what it was told to answer. */
class RecordingMailTransport : MailTransport {

    val sent = mutableListOf<Pair<SmtpServer, MailMessage>>()
    val checks = mutableListOf<SmtpServer>()

    /** Sent, unless a test says otherwise. */
    var answer: (SmtpServer, MailMessage) -> MailDelivery =
        { _, message -> MailDelivery.Sent(message.to, "<sent@localhost>") }

    var checked: CheckResult = CheckResult(CheckOutcome.CONNECTED, "Connected")

    override fun deliver(server: SmtpServer, message: MailMessage): MailDelivery {
        sent += server to message
        return answer(server, message)
    }

    override fun check(server: SmtpServer): CheckResult {
        checks += server
        return checked
    }

    fun forget() {
        sent.clear()
        checks.clear()
        answer = { _, message -> MailDelivery.Sent(message.to, "<sent@localhost>") }
        checked = CheckResult(CheckOutcome.CONNECTED, "Connected")
    }
}
