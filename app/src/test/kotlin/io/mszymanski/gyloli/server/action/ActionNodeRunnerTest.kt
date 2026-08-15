package io.mszymanski.gyloli.server.action

import io.mszymanski.gyloli.server.condition.WorkflowConditionRepository
import io.mszymanski.gyloli.server.team.Team
import io.mszymanski.gyloli.server.team.TeamAuditRepository
import io.mszymanski.gyloli.server.team.TeamRepository
import io.mszymanski.gyloli.server.workflow.TeamWorkflowRepository
import io.mszymanski.gyloli.server.workflow.WorkflowEdgeRepository
import io.mszymanski.gyloli.server.workflow.WorkflowNodeRepository
import io.mszymanski.gyloli.server.workflow.WorkflowRepository
import io.mszymanski.gyloli.workflow.execution.ExecutionLogRepository
import io.mszymanski.gyloli.workflow.execution.ExecutionStatus
import io.mszymanski.gyloli.workflow.execution.ExecutionStepRepository
import io.mszymanski.gyloli.workflow.execution.StepStatus
import io.mszymanski.gyloli.workflow.execution.WorkflowExecutionRepository
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
 * team's functions, and the JavaScript that runs in the sandbox deciding what
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
    @Autowired val assignments: TeamWorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val teams: TeamRepository,
    @Autowired val audit: TeamAuditRepository,
) {

    private var teamId: Long = 0
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
        teams.deleteAll()

        teamId = requireNotNull(teams.save(Team(name = "backend")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { teamId: $teamId, name: "Incident Response" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
    }

    @Test
    fun `an action node calls the team's function and hands on what it returned`() {
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
    fun `a wait carries on as soon as its condition holds`() {
        val actionId = graphQlTester.document(
            """
            mutation {
              createAction(input: {
                teamId: $teamId, name: "Wait for Approval", type: WAIT, subtype: INLINE_CONDITION,
                conditionExpression: "input.approved === true", timeoutSeconds: 5, retryIntervalSeconds: 1
              }) { id }
            }
            """,
        ).execute().path("createAction.id").entity(Long::class.java).get()
        graph(actionId)

        start(input = """{"approved":true}""")

        assertThat(steps.findAll().single { it.actionId == actionId }.status).isEqualTo(StepStatus.COMPLETED)
    }

    @Test
    fun `a condition node stops the run when its condition does not hold`() {
        val conditionId = graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                teamId: $teamId, name: "From alice", type: SLACK, property: MESSAGE_AUTHOR,
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
              saveWorkflowGraph(teamId: $teamId, workflowId: $workflowId, input: {
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
              createTeamConnection(input: {
                teamId: $teamId, name: "Slack", type: SLACK_SOCKET_MODE, url: "https://slack.com/api"
              }) { id }
            }
            """,
        ).execute().path("createTeamConnection.id").entity(Long::class.java).get()
        val actionId = graphQlTester.document(
            """
            mutation {
              createAction(input: {
                teamId: $teamId, name: "Send Slack Notification", type: EXECUTE, subtype: OUTGOING_CONNECTION,
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

    private fun function(name: String, source: String): Long = graphQlTester.document(
        """
        mutation {
          createFunction(input: {
            teamId: $teamId, name: "$name", returnType: OBJECT, source: ${'"'}${'"'}${'"'}$source${'"'}${'"'}${'"'},
            params: [{ name: "input", type: OBJECT }, { name: "format", type: STRING }]
          }) { id }
        }
        """,
    ).execute().path("createFunction.id").entity(Long::class.java).get()

    private fun functionAction(functionId: Long, name: String): Long = graphQlTester.document(
        """
        mutation {
          createAction(input: {
            teamId: $teamId, name: "$name", type: EXECUTE, subtype: FUNCTION, functionId: $functionId,
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
              saveWorkflowGraph(teamId: $teamId, workflowId: $workflowId, input: {
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
              startExecution(teamId: $teamId, workflowId: $workflowId, input: ${'$'}input) { id status }
            }
            """,
        ).variable("input", input).execute().path("startExecution.id").entity(Long::class.java).get()

        val run = executions.findAll().single { it.id == id }
        if (!expectFailure) assertThat(run.status).isIn(ExecutionStatus.COMPLETED, ExecutionStatus.RUNNING)
        return id
    }
}
