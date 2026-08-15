package io.mszymanski.gyloli.server.workflow

import io.mszymanski.gyloli.server.action.WorkflowActionRepository
import io.mszymanski.gyloli.server.action.WorkflowFunctionRepository
import io.mszymanski.gyloli.server.condition.WorkflowConditionRepository
import io.mszymanski.gyloli.server.team.Team
import io.mszymanski.gyloli.server.team.TeamAuditRepository
import io.mszymanski.gyloli.server.team.TeamRepository
import io.mszymanski.gyloli.server.trigger.WorkflowTriggerRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Whether a graph holds together.
 *
 * The rules are about ports, not kinds: what a node needs has to be produced by
 * something before it. What is asserted here is that the ports come from the
 * catalogue — so editing an action changes what its node needs — and that a
 * graph still being drawn can be saved while an impossible one cannot.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class GraphValidatorTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val triggers: WorkflowTriggerRepository,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val conditions: WorkflowConditionRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val assignments: TeamWorkflowRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val teams: TeamRepository,
    @Autowired val audit: TeamAuditRepository,
) {

    private var teamId: Long = 0
    private var workflowId: Long = 0

    @BeforeEach
    fun reset() {
        nodes.deleteAll()
        edges.deleteAll()
        actions.deleteAll()
        conditions.deleteAll()
        functions.deleteAll()
        triggers.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        audit.deleteAll()
        teams.deleteAll()

        teamId = requireNotNull(teams.save(Team(name = "backend")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { teamId: $teamId, name: "Wiring" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
    }

    @Test
    fun `a node is told what it needs, from the catalogue entry it points at`() {
        val functionId = function("summarize")
        val actionId = functionAction(functionId, "Summarize", mapOf("order" to "{{input.order}}"))
        val triggerId = scheduled("nightly", """{ "order": { "id": 7 } }""")

        val problems = save(
            nodes = """
                { key: "start", kind: TRIGGER, name: "Nightly", triggerId: $triggerId, x: 0, y: 0 },
                { key: "act", kind: ACTION, name: "Summarize", actionId: $actionId, x: 200, y: 0 }
            """,
            edges = """{ source: "start", target: "act" }""",
        )

        // The trigger's payload carries `order`, which is what the action asks for.
        assertThat(problems).isEmpty()

        graphQlTester.document(
            """
            query {
              workflowGraph(teamId: $teamId, workflowId: $workflowId) {
                nodes { key inputs { display } outputs { display } }
              }
            }
            """,
        ).execute()
            .path("workflowGraph.nodes[1].inputs[*].display").entityList(String::class.java)
            .containsExactly("order: string")
            .path("workflowGraph.nodes[1].outputs[*].display").entityList(String::class.java)
            .containsExactly("result: object")
    }

    @Test
    fun `a node asking for something nothing produces is warned about`() {
        val functionId = function("summarize")
        val actionId = functionAction(functionId, "Summarize", mapOf("order" to "{{input.missing}}"))
        val triggerId = scheduled("nightly", """{ "order": { "id": 7 } }""")

        val problems = save(
            nodes = """
                { key: "start", kind: TRIGGER, name: "Nightly", triggerId: $triggerId, x: 0, y: 0 },
                { key: "act", kind: ACTION, name: "Summarize", actionId: $actionId, x: 200, y: 0 }
            """,
            edges = """{ source: "start", target: "act" }""",
        )

        assertThat(problems).anySatisfy {
            assertThat(it).contains("WARNING").contains("needs missing: string")
        }
    }

    @Test
    fun `a wait hands on what it was given, so what is after it still sees the trigger`() {
        val triggerId = scheduled("nightly", """{ "order": { "id": 7 } }""")
        val waitId = wait("Pause")
        val functionId = function("summarize")
        val actionId = functionAction(functionId, "Summarize", mapOf("order" to "{{input.order}}"))

        val problems = save(
            nodes = """
                { key: "start", kind: TRIGGER, name: "Nightly", triggerId: $triggerId, x: 0, y: 0 },
                { key: "hold", kind: ACTION, name: "Pause", actionId: $waitId, x: 200, y: 0 },
                { key: "act", kind: ACTION, name: "Summarize", actionId: $actionId, x: 400, y: 0 }
            """,
            edges = """
                { source: "start", target: "hold" },
                { source: "hold", target: "act" }
            """,
        )

        assertThat(problems).isEmpty()
    }

    @Test
    fun `a node with nothing chosen, or nothing before it, is warned about`() {
        val problems = save(
            nodes = """
                { key: "start", kind: TRIGGER, name: "Nightly", x: 0, y: 0 },
                { key: "act", kind: ACTION, name: "Orphan", x: 400, y: 0 }
            """,
            edges = "",
        )

        assertThat(problems).anySatisfy { assertThat(it).contains("no trigger chosen") }
        assertThat(problems).anySatisfy { assertThat(it).contains("Orphan has nothing before it") }
    }

    @Test
    fun `nothing can feed a trigger, and nothing can follow a publish task`() {
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(teamId: $teamId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "act", kind: ACTION, name: "Act", x: 0, y: 0 },
                  { key: "start", kind: TRIGGER, name: "Nightly", x: 200, y: 0 }
                ],
                edges: [{ source: "act", target: "start" }]
              }) { nodes { key } }
            }
            """,
        ).execute().errors().expect { it.message?.contains("Nothing can feed") == true }.verify()

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(teamId: $teamId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "out", kind: PUBLISH_TASK, name: "Publish", x: 0, y: 0 },
                  { key: "act", kind: ACTION, name: "Act", x: 200, y: 0 }
                ],
                edges: [{ source: "out", target: "act" }]
              }) { nodes { key } }
            }
            """,
        ).execute().errors().expect { it.message?.contains("Nothing can follow") == true }.verify()
    }

    @Test
    fun `a condition node needs what its condition asks about`() {
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
        // A schedule carries no message, so the author is not there to ask about.
        val triggerId = scheduled("nightly", null)

        val problems = save(
            nodes = """
                { key: "start", kind: TRIGGER, name: "Nightly", triggerId: $triggerId, x: 0, y: 0 },
                { key: "gate", kind: CONDITION, name: "From alice", conditionId: $conditionId, x: 200, y: 0 }
            """,
            edges = """{ source: "start", target: "gate" }""",
        )

        assertThat(problems).anySatisfy {
            assertThat(it).contains("From alice needs user: string")
        }
    }

    /** Saves the graph and answers with its problems, as "SEVERITY message". */
    private fun save(nodes: String, edges: String): List<String> = graphQlTester.document(
        """
        mutation {
          saveWorkflowGraph(teamId: $teamId, workflowId: $workflowId, input: {
            nodes: [$nodes],
            edges: [$edges]
          }) { problems { severity nodeKey message } }
        }
        """,
    ).execute()
        .path("saveWorkflowGraph.problems[*]").entityList(Map::class.java).get()
        .map { "${it["severity"]} ${it["message"]}" }

    private fun scheduled(name: String, payload: String?): Long = graphQlTester.document(
        """
        mutation(${'$'}payload: String) {
          createTrigger(input: {
            teamId: $teamId, name: "$name", type: SCHEDULED, cron: "0 2 * * *", payload: ${'$'}payload
          }) { id }
        }
        """,
    ).variable("payload", payload).execute().path("createTrigger.id").entity(Long::class.java).get()

    private fun function(name: String): Long = graphQlTester.document(
        """
        mutation {
          createFunction(input: {
            teamId: $teamId, name: "$name", returnType: OBJECT, params: [{ name: "order", type: OBJECT }]
          }) { id }
        }
        """,
    ).execute().path("createFunction.id").entity(Long::class.java).get()

    private fun functionAction(functionId: Long, name: String, mappings: Map<String, String>): Long {
        val mapped = mappings.entries.joinToString(", ") {
            """{ argument: "${it.key}", expression: "${it.value}" }"""
        }
        return graphQlTester.document(
            """
            mutation {
              createAction(input: {
                teamId: $teamId, name: "$name", type: EXECUTE, subtype: FUNCTION,
                functionId: $functionId, mappings: [$mapped]
              }) { id }
            }
            """,
        ).execute().path("createAction.id").entity(Long::class.java).get()
    }

    private fun wait(name: String): Long = graphQlTester.document(
        """
        mutation {
          createAction(input: {
            teamId: $teamId, name: "$name", type: WAIT, subtype: TIME, durationSeconds: 1
          }) { id }
        }
        """,
    ).execute().path("createAction.id").entity(Long::class.java).get()
}
