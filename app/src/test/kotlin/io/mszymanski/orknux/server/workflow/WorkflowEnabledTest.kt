package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.trigger.FiringOutcome
import io.mszymanski.orknux.server.trigger.TriggerFiringRepository
import io.mszymanski.orknux.server.trigger.TriggerScheduler
import io.mszymanski.orknux.server.trigger.WorkflowTriggerRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.time.OffsetDateTime

/**
 * What the switch on a workflow actually stops.
 *
 * It used to stop nothing: the flag was written, audited and shown, and no path
 * that starts a run ever read it, so a workflow somebody had switched off still
 * answered its trigger. What it means now is drawn along one line - a workflow
 * that is off is not started by anything that starts by itself, and is still
 * started by a person pressing Run - so both halves are held here, together
 * with the record that says which of them happened.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WorkflowEnabledTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val scheduler: TriggerScheduler,
    @Autowired val tools: OrknuxTools,
    @Autowired val triggers: WorkflowTriggerRepository,
    @Autowired val firings: TriggerFiringRepository,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    /** The definition's id, which is what a run and a graph are asked for. */
    private var workflowId: Long = 0

    /** The assignment's id, which is what the switch is on. */
    private var assignmentId: Long = 0

    @BeforeEach
    fun reset() {
        firings.deleteAll()
        triggers.deleteAll()
        logs.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        val created = graphQlTester.document(
            """
            mutation {
              createWorkflow(input: { workspaceId: $workspaceId, name: "Nightly Sync" }) { id workflowId }
            }
            """,
        ).execute()
        assignmentId = created.path("createWorkflow.id").entity(Long::class.java).get()
        workflowId = created.path("createWorkflow.workflowId").entity(Long::class.java).get()
    }

    @Test
    fun `a workflow that is switched off is not started when its trigger fires`() {
        instance(scheduled("Nightly Data Sync"))
        switchOff()

        scheduler.tick(OffsetDateTime.now())

        assertThat(executions.findAll()).isEmpty()
    }

    @Test
    fun `the firing says the workflow is switched off rather than that nothing instances it`() {
        instance(scheduled("Nightly Data Sync"))
        switchOff()

        scheduler.tick(OffsetDateTime.now())

        // The complaint this whole switch caused was a workflow silently not
        // running, so the trigger's own log has to name the reason.
        assertThat(firings.findAll()).singleElement().satisfies({
            assertThat(it.outcome).isEqualTo(FiringOutcome.WORKFLOW_DISABLED)
            assertThat(it.detail).isEqualTo("Nightly Sync is switched off in this workspace")
            assertThat(it.runsStarted).isZero()
        })
    }

    @Test
    fun `a workflow that is switched on still runs when its trigger fires`() {
        instance(scheduled("Nightly Data Sync"))

        scheduler.tick(OffsetDateTime.now())

        assertThat(executions.findAll()).singleElement().satisfies({
            assertThat(it.workflowName).isEqualTo("Nightly Sync")
            assertThat(it.trigger).isEqualTo(ExecutionTrigger.SCHEDULE)
        })
        assertThat(firings.findAll().single().outcome).isEqualTo(FiringOutcome.STARTED)
    }

    /**
     * The decision the switch is drawn around: off means it does not start by
     * itself, not that it cannot be tried. Somebody switches a misbehaving
     * workflow off and opens the editor to fix it, and refusing Run there would
     * leave them switching it back on - putting it live, half-fixed - purely in
     * order to test it.
     */
    @Test
    fun `Run still starts a workflow that is switched off`() {
        instance(scheduled("Nightly Data Sync"))
        switchOff()

        graphQlTester.document(
            """
            mutation {
              startExecution(workspaceId: $workspaceId, workflowId: $workflowId) { id status }
            }
            """,
        ).execute().errors().verify()

        assertThat(executions.findAll()).singleElement().satisfies({
            assertThat(it.trigger).isEqualTo(ExecutionTrigger.MANUAL)
        })
    }

    @Test
    fun `the editor is told the workflow is switched off, so Run is not a silent exception`() {
        instance(scheduled("Nightly Data Sync"))

        graphQlTester.document(graphQuery()).execute().path("workflowGraph.enabled").entity(Boolean::class.java)
            .isEqualTo(true)

        switchOff()

        graphQlTester.document(graphQuery()).execute().path("workflowGraph.enabled").entity(Boolean::class.java)
            .isEqualTo(false)
    }

    @Test
    fun `a tool call will not start a workflow that is switched off`() {
        instance(scheduled("Nightly Data Sync"))
        switchOff()

        val answer = tools.run(
            OrknuxScope(workspaceId = workspaceId, mayWrite = true),
            "orknux_run_workflow",
            """{ "workflow": "Nightly Sync" }""",
        )

        assertThat(answer).contains("switched off")
        assertThat(executions.findAll()).isEmpty()
    }

    @Test
    fun `the list stops promising a next run once the workflow is switched off`() {
        instance(scheduled("Nightly Data Sync"))

        graphQlTester.document(listQuery()).execute()
            .path("workspaceWorkflows.content[0].nextRun").entity(String::class.java).get()

        switchOff()

        graphQlTester.document(listQuery()).execute()
            .path("workspaceWorkflows.content[0].nextRun").valueIsNull()
    }

    private fun graphQuery() = "{ workflowGraph(workspaceId: $workspaceId, workflowId: $workflowId) { enabled } }"

    private fun listQuery() = "{ workspaceWorkflows(workspaceId: $workspaceId) { content { enabled nextRun } } }"

    private fun switchOff() {
        graphQlTester.document(
            """mutation { setWorkflowEnabled(id: $assignmentId, enabled: false) { enabled } }""",
        ).execute().path("setWorkflowEnabled.enabled").entity(Boolean::class.java).isEqualTo(false)
    }

    private fun scheduled(name: String): Long = graphQlTester.document(
        """
        mutation {
          createTrigger(input: {
            workspaceId: $workspaceId, name: "$name", type: SCHEDULED, cron: "* * * * *", timezone: "UTC"
          }) { id }
        }
        """,
    ).execute().path("createTrigger.id").entity(Long::class.java).get()

    /** The workflow instances the definition, published, which is what a trigger runs. */
    private fun instance(triggerId: Long) {
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [{ key: "start", kind: TRIGGER, name: "Nightly", triggerId: $triggerId, x: 0, y: 0 }],
                edges: []
              }) { nodes { key } }
            }
            """,
        ).execute()

        graphQlTester.document(
            """mutation { publishWorkflow(workspaceId: $workspaceId, workflowId: $workflowId) { status } }""",
        ).execute()
    }
}
