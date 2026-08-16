package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkflowEdgeRepository
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.StepStatus
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * An action node running for real: a workflow whose action calls one of the
 * workspace's functions, and the JavaScript that runs in the sandbox deciding what
 * the next node is handed.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ActionNodeRunnerTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val conditions: WorkflowConditionRepository,
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
    private var workflowId: Long = 0

    @BeforeEach
    fun reset() {
        logs.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        actions.deleteAll()
        conditions.deleteAll()
        functions.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Incident Response" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
    }

    @Test
    fun `an action node calls the workspace's function and hands on what it returned`() {
        val functionId = function(
            "transformPayload",
            """
            export default async function transformPayload(input, format) {
              return { id: input.id, format: format, processed: true };
            }
            """.trimIndent(),
        )
        val actionId = functionAction(functionId, "Transform Data")
        graph(actionId)

        val runId = start()

        val step = steps.findAll().single { it.actionId == actionId }
        assertThat(step.status).isEqualTo(StepStatus.COMPLETED)
        assertThat(step.output).isEqualTo("""{"id":7,"format":"compact","processed":true}""")
        assertThat(executions.findAll().single { it.id == runId }.status).isEqualTo(ExecutionStatus.COMPLETED)
    }

    @Test
    fun `a function that throws stops the run, with the reason on the step`() {
        val functionId = function(
            "transformPayload",
            """export default function transformPayload(input) { throw new Error("bad payload"); }""",
        )
        graph(functionAction(functionId, "Transform Data"))

        start(expectFailure = true)

        val step = steps.findAll().single { it.actionId != null }
        assertThat(step.status).isEqualTo(StepStatus.FAILED)
        assertThat(step.error).contains("bad payload")
        assertThat(executions.findAll().single().status).isEqualTo(ExecutionStatus.FAILED)
    }

    @Test
    fun `a wait carries on as soon as its condition holds, without ever parking`() {
        val actionId = waitAction(
            """
            name: "Wait for Approval", subtype: INLINE_CONDITION,
            conditionExpression: "input.approved === true", timeoutSeconds: 5, retryIntervalSeconds: 1
            """,
        )
        graph(actionId)

        start(input = """{"approved":true}""")

        val step = steps.findAll().single { it.actionId == actionId }
        assertThat(step.status).isEqualTo(StepStatus.COMPLETED)
        // It held the first time it was asked, so nothing was ever waited for.
        assertThat(step.waitUntil).isNull()
    }

    @Test
    fun `a wait for a time parks the step, and the run carries on when it is up`() {
        val actionId = waitAction("""name: "Cool Down", subtype: TIME, durationSeconds: 1""")
        graph(actionId)

        start()

        val step = steps.findAll().single { it.actionId == actionId }
        assertThat(step.status).isEqualTo(StepStatus.COMPLETED)
        // It parked rather than sleeping through the step: the deadline it was
        // counting to is on the step, and the run said so once.
        assertThat(step.waitUntil).isNotNull()
        assertThat(logs.findAll().map { it.message }).anyMatch { it.contains("Cool Down is waiting") }
        assertThat(executions.findAll().single().status).isEqualTo(ExecutionStatus.COMPLETED)
    }

    @Test
    fun `a wait whose condition never holds fails when it runs out of time`() {
        val actionId = waitAction(
            """
            name: "Wait for Approval", subtype: INLINE_CONDITION,
            conditionExpression: "input.approved === true", timeoutSeconds: 1, retryIntervalSeconds: 1
            """,
        )
        graph(actionId)

        start(input = """{"approved":false}""", expectFailure = true)

        val step = steps.findAll().single { it.actionId == actionId }
        assertThat(step.status).isEqualTo(StepStatus.FAILED)
        assertThat(step.error).contains("ran out of time")
        assertThat(executions.findAll().single().status).isEqualTo(ExecutionStatus.FAILED)
    }

    @Test
    fun `a wait longer than the inline engine allows fails, and says what would carry it`() {
        val actionId = waitAction("""name: "Wait a Day", subtype: TIME, durationSeconds: 86400""")
        graph(actionId)

        start(expectFailure = true)

        val step = steps.findAll().single { it.actionId == actionId }
        assertThat(step.status).isEqualTo(StepStatus.FAILED)
        assertThat(step.error).contains("longer than the inline engine allows")
        assertThat(step.error).contains("orknux.temporal.enabled")
    }

    @Test
    fun `a condition node stops the run when its condition does not hold`() {
        val conditionId = graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                workspaceId: $workspaceId, name: "From alice", type: SLACK, property: MESSAGE_AUTHOR,
                check: IN_LIST, values: ["alice@example.com"]
              }) { id }
            }
            """,
        ).execute().path("createCondition.id").entity(Long::class.java).get()

        // The condition comes first; the action after it only runs if it holds.
        val functionId = function(
            "echo",
            """export default function echo(input) { return { seen: true }; }""",
        )
        val actionId = functionAction(functionId, "After the gate")
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "gate", kind: CONDITION, name: "From alice", conditionId: $conditionId, x: 0, y: 0 },
                  { key: "act", kind: ACTION, name: "Act", actionId: $actionId, x: 240, y: 0 }
                ],
                edges: [{ source: "gate", target: "act" }]
              }) { nodes { key conditionId } }
            }
            """,
        ).execute()

        start(input = """{"user":"bob@example.com"}""")

        val gate = steps.findAll().single { it.nodeKey == "gate" }
        val act = steps.findAll().single { it.nodeKey == "act" }
        assertThat(gate.status).isEqualTo(StepStatus.COMPLETED)
        assertThat(gate.output).contains("did not hold")
        // The run finished; the step after the gate was never started.
        assertThat(act.status).isEqualTo(StepStatus.PENDING)

        // And the run says so, rather than looking like one that did everything.
        val run = executions.findAll().single()
        assertThat(run.status).isEqualTo(ExecutionStatus.COMPLETED)
        assertThat(run.stoppedAtNodeKey).isEqualTo("gate")
        assertThat(run.stoppedReason).contains("From alice did not hold")
        assertThat(logs.findAll().map { it.message }).anyMatch { it.contains("stopped after gate") }
    }

    @Test
    fun `an action with no runtime yet says so rather than claiming it sent anything`() {
        val connectionId = graphQlTester.document(
            """
            mutation {
              createWorkspaceConnection(input: {
                workspaceId: $workspaceId, name: "Slack", type: SLACK_SOCKET_MODE, url: "https://slack.com/api"
              }) { id }
            }
            """,
        ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()
        val actionId = graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Send Slack Notification", type: EXECUTE, subtype: OUTGOING_CONNECTION,
                connectionId: $connectionId, connectionAction: SEND_MESSAGE, content: "Hello"
              }) { id }
            }
            """,
        ).execute().path("createAction.id").entity(Long::class.java).get()
        graph(actionId)

        start()

        val step = steps.findAll().single { it.actionId == actionId }
        assertThat(step.status).isEqualTo(StepStatus.SKIPPED)
        assertThat(step.output).contains("no runtime yet")
    }

    /**
     * The node decides what it passes, and the action is only ever the suggestion
     * it started from.
     *
     * The same node runs twice on the same input: once as the action suggested,
     * once with a plain value typed over one parameter — which is the thing an
     * expression-only definition cannot express. If the definition were read at
     * run time both runs would agree, and a workspace would need a near-duplicate
     * action per call site. The action itself is checked afterwards: editing a
     * node must not move anything else that uses it.
     */
    @Test
    fun `a node passes what it was given, not what the action suggests`() {
        val functionId = function(
            "transformPayload",
            """
            export default function transformPayload(input, format) {
              return { id: input.id, format: format };
            }
            """.trimIndent(),
        )
        val actionId = functionAction(functionId, "Transform Data")

        graph(actionId)
        val suggested = start()

        // The node is pointed at the same action; only what it passes changes.
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [{
                  key: "act", kind: ACTION, name: "Act", actionId: $actionId, x: 0, y: 0,
                  mappings: [
                    { name: "input", expression: "{{input.payload}}" },
                    { name: "format", expression: "verbose" }
                  ]
                }],
                edges: []
              }) { nodes { key mappings { name expression } } }
            }
            """,
        ).execute()
            .path("saveWorkflowGraph.nodes[0].mappings[?(@.name == 'format')].expression")
            .entityList(String::class.java).containsExactly("verbose")
        val overridden = start()

        assertThat(steps.findAll().single { it.executionId == suggested }.output)
            .isEqualTo("""{"id":7,"format":"compact"}""")
        // Same action, same input, and the plain value the node holds is what ran.
        assertThat(steps.findAll().single { it.executionId == overridden }.output)
            .isEqualTo("""{"id":7,"format":"verbose"}""")

        // And editing the node left the action alone.
        assertThat(actions.findAll().single { it.id == actionId }.mappings.map { it.argument to it.expression })
            .containsExactly("input" to "{{input.payload}}", "format" to "{{input.format}}")
    }

    private fun function(name: String, source: String): Long = graphQlTester.document(
        """
        mutation {
          createFunction(input: {
            workspaceId: $workspaceId, name: "$name", returnType: OBJECT, source: ${'"'}${'"'}${'"'}$source${'"'}${'"'}${'"'},
            params: [{ name: "input", type: OBJECT }, { name: "format", type: STRING }]
          }) { id }
        }
        """,
    ).execute().path("createFunction.id").entity(Long::class.java).get()

    /** A wait action; [settings] is the rest of the input, which differs by subtype. */
    private fun waitAction(settings: String): Long = graphQlTester.document(
        """
        mutation {
          createAction(input: { workspaceId: $workspaceId, type: WAIT, $settings }) { id }
        }
        """,
    ).execute().path("createAction.id").entity(Long::class.java).get()

    private fun functionAction(functionId: Long, name: String): Long = graphQlTester.document(
        """
        mutation {
          createAction(input: {
            workspaceId: $workspaceId, name: "$name", type: EXECUTE, subtype: FUNCTION, functionId: $functionId,
            mappings: [
              { argument: "input", expression: "{{input.payload}}" },
              { argument: "format", expression: "{{input.format}}" }
            ]
          }) { id }
        }
        """,
    ).execute().path("createAction.id").entity(Long::class.java).get()

    /** One action node, which is the whole workflow. */
    private fun graph(actionId: Long) {
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
    }

    /** Starts the run with something for the action to work on. */
    private fun start(
        input: String = """{"payload":{"id":7},"format":"compact"}""",
        expectFailure: Boolean = false,
    ): Long {
        val id = graphQlTester.document(
            """
            mutation(${'$'}input: String) {
              startExecution(workspaceId: $workspaceId, workflowId: $workflowId, input: ${'$'}input) { id status }
            }
            """,
        ).variable("input", input).execute().path("startExecution.id").entity(Long::class.java).get()

        val run = executions.findAll().single { it.id == id }
        if (!expectFailure) assertThat(run.status).isIn(ExecutionStatus.COMPLETED, ExecutionStatus.RUNNING)
        return id
    }
}
