package io.mszymanski.orknux.server.trigger

import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.workflow.WorkflowEdgeRepository
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.web.client.RestClient

/**
 * How much the open internet may hand this server.
 *
 * A webhook is called by something that cannot sign in, and what it posts is
 * kept several times over on the way to becoming a run's input - a String, a
 * tree, a copy of the tree, its serialisation, and a row. Until this the caller
 * chose the size of all five.
 *
 * Made over the real port rather than through MockMvc, because the limit is a
 * filter and MockMvc runs only the filters it is handed: a test that went round
 * it would be testing the endpoint under conditions it never meets.
 *
 * The limit is turned down to 32KB here so that "too large" is a string a test
 * can build without a second thought. Nothing about the rule changes with the
 * number.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["orknux.webhook.max-body-size=32KB"],
)
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WebhookBodyLimitTest(
    @LocalServerPort val port: Int,
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val triggers: WorkflowTriggerRepository,
    @Autowired val firings: TriggerFiringRepository,
    @Autowired val objects: WorkflowObjectRepository,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    /** Status handling is off, so a refusal can be asserted on rather than thrown. */
    private val client = RestClient.builder()
        .baseUrl("http://localhost:$port")
        .defaultStatusHandler({ true }, { _, _ -> })
        .build()

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        firings.deleteAll()
        triggers.deleteAll()
        logs.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        assignments.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        workflows.deleteAll()
        objects.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        arm()
    }

    /**
     * The half that matters: an anonymous caller cannot decide how much of this
     * server's memory to spend.
     */
    @Test
    fun `a body larger than the limit is refused`() {
        val answer = call("zendesk/ticket-created", oversized())

        // By number rather than by name: the constant for 413 was renamed and
        // the old spelling no longer equals the new one.
        assertThat(answer.statusCode.value()).isEqualTo(413)
    }

    /**
     * Refused before anything is routed, which is the whole reason the limit is
     * a filter: nothing reads the body, nothing looks a trigger up, and nothing
     * is written down.
     */
    @Test
    fun `a body larger than the limit never reaches a trigger`() {
        call("zendesk/ticket-created", oversized())

        assertThat(firings.findAll()).isEmpty()
        assertThat(executions.findAll()).isEmpty()
    }

    /**
     * The endpoint's own rule survives the new one: a caller must not be able to
     * tell an armed path from an empty one. Running ahead of routing is what
     * keeps that true - the answer cannot depend on a path nothing has looked at
     * yet.
     */
    @Test
    fun `the refusal does not say whether the path exists`() {
        val armed = call("zendesk/ticket-created", oversized())
        val unknown = call("zendesk/does-not-exist", oversized())

        assertThat(armed.statusCode).isEqualTo(unknown.statusCode)
        assertThat(armed.body).isEqualTo(unknown.body)
    }

    /**
     * The half a fix could quietly break: refusing everything would pass every
     * assertion above.
     *
     * The body is read once by the filter and handed on, so this also pins down
     * that what the endpoint gets is the body that arrived rather than an empty
     * stream somebody already drained.
     */
    @Test
    fun `a body within the limit still starts the workflow`() {
        val answer = call("zendesk/ticket-created", """{"id":"T-1"}""")

        assertThat(answer.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(answer.body).contains("\"started\":1")
        assertThat(executions.findAll()).singleElement().satisfies({
            assertThat(it.input).contains("\"id\":\"T-1\"")
        })
    }

    /** Comfortably past the 32KB this context allows, and still valid JSON. */
    private fun oversized(): String = """{"id":"${"T".repeat(64 * 1024)}"}"""

    /** One anonymous call, the way anything out there would make it. */
    private fun call(path: String, body: String): ResponseEntity<String> =
        client.post().uri("/api/webhooks/$path")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String::class.java)

    /** A workflow with a webhook trigger on it, published, ready to be called. */
    private fun arm() {
        val workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Triage" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()

        val objectId = graphQlTester.document(
            """
            mutation {
              createObject(input: {
                workspaceId: $workspaceId, name: "Ticket",
                properties: [{ name: "id", kind: STRING }]
              }) { id }
            }
            """,
        ).execute().path("createObject.id").entity(Long::class.java).get()

        val triggerId = graphQlTester.document(
            """
            mutation {
              createTrigger(input: {
                workspaceId: $workspaceId, name: "Ticket Created", type: WEBHOOK,
                webhookPath: "zendesk/ticket-created", objectId: $objectId
              }) { id }
            }
            """,
        ).execute().path("createTrigger.id").entity(Long::class.java).get()

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "trigger", kind: TRIGGER, name: "Ticket Created", triggerId: $triggerId, x: 0, y: 0 }
                ],
                edges: []
              }) { nodes { key triggerId } }
            }
            """,
        ).execute().path("saveWorkflowGraph.nodes[0].triggerId").entity(Long::class.java).isEqualTo(triggerId)

        // A trigger runs the published copy; a graph that was only saved is one
        // somebody is still drawing.
        graphQlTester.document(
            """mutation { publishWorkflow(workspaceId: $workspaceId, workflowId: $workflowId) { status } }""",
        ).execute()
    }
}
