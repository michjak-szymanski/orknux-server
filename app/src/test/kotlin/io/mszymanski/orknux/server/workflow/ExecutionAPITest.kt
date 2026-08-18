package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionService
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.StartExecutionInput
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.TestPropertySource

/**
 * Runs end to end: the app decides who may look and where the graph comes from,
 * the execution module runs it and keeps what happened.
 *
 * The inline engine, so a run has finished by the time the call answers and the
 * assertions can be about what it did; the Temporal path is ExecutionWorkflowTest.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
@TestPropertySource(properties = ["orknux.temporal.enabled=false"])
class ExecutionAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val runs: ExecutionService,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private var workflowId: Long = 0

    @BeforeEach
    fun reset() {
        logs.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        assignments.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        workflows.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        workflowId = graphQlTester.document(
            """
            mutation {
              createWorkflow(input: { workspaceId: $workspaceId, name: "Data Processing Pipeline" }) { workflowId }
            }
            """,
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "trigger", kind: TRIGGER, name: "Webhook Trigger", x: 0, y: 0 },
                  { key: "fetch", kind: AGENT, name: "Fetch Data", x: 200, y: 0 }
                ],
                edges: [{ source: "trigger", target: "fetch" }]
              }) { status }
            }
            """,
        ).execute()

        // Published, because a trigger runs the published copy: a graph that
        // was only ever saved is one somebody is still drawing.
        graphQlTester.document(
            """mutation { publishWorkflow(workspaceId: $workspaceId, workflowId: $workflowId) { status } }""",
        ).execute()
    }

    @Test
    fun `a run reads the graph from the workflow it names`() {
        val run = start()

        assertThat(run.workflowName).isEqualTo("Data Processing Pipeline")
        assertThat(run.steps.map { it.key }).containsExactly("trigger", "fetch")
    }

    @Test
    fun `the detail carries the run's steps and the workflow's edges`() {
        val id = start().id

        graphQlTester.document(
            """
            query {
              execution(id: $id) {
                workflowName status steps { key name status } edges { source target } logs { level message }
              }
            }
            """,
        ).execute()
            .path("execution.steps[*].key").entityList(String::class.java).containsExactly("trigger", "fetch")
            .path("execution.edges[0].source").entity(String::class.java).isEqualTo("trigger")
            .path("execution.edges[0].target").entity(String::class.java).isEqualTo("fetch")
            .path("execution.logs[*].message").entityList(String::class.java).hasSizeGreaterThan(0)
    }

    @Test
    fun `a workspace's runs are listed, and the filters reach the module`() {
        start()

        graphQlTester.document(
            """
            query {
              workspaceExecutions(workspaceId: $workspaceId, search: "pipeline", days: 1) {
                content { workflowName status }
                totalElements
              }
            }
            """,
        ).execute()
            .path("workspaceExecutions.totalElements").entity(Int::class.java).isEqualTo(1)

        graphQlTester.document(
            """query { workspaceExecutions(workspaceId: $workspaceId, search: "nothing-like-this") { totalElements } }""",
        ).execute().path("workspaceExecutions.totalElements").entity(Int::class.java).isEqualTo(0)
    }

    @Test
    fun `re-running starts the workflow again, as a manual run`() {
        val id = start().id

        val newId = graphQlTester.document("""mutation { rerunExecution(id: $id) { id trigger } }""")
            .execute()
            .path("rerunExecution.trigger").entity(String::class.java).isEqualTo("MANUAL")
            .path("rerunExecution.id").entity(Long::class.java).get()

        assertThat(newId).isNotEqualTo(id)
        assertThat(executions.findAll()).hasSize(2)
    }

    @Test
    fun `an unknown run is null rather than an error`() {
        graphQlTester.document("""query { execution(id: 999999) { id } }""")
            .execute()
            .path("execution").valueIsNull()
    }

    private fun start() = runs.startExecution(
        StartExecutionInput(workspaceId = workspaceId, workflowId = workflowId, trigger = ExecutionTrigger.WEBHOOK),
    )
}
