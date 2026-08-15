package io.mszymanski.gyloli.server.workflow

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

@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WorkflowGraphAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: TeamWorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val triggers: WorkflowTriggerRepository,
    @Autowired val audit: TeamAuditRepository,
    @Autowired val teams: TeamRepository,
) {

    private var teamId: Long = 0
    private var otherTeamId: Long = 0
    private var workflowId: Long = 0

    @BeforeEach
    fun seed() {
        triggers.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        audit.deleteAll()
        teams.deleteAll()

        teamId = requireNotNull(teams.save(Team(name = "backend")).id)
        otherTeamId = requireNotNull(teams.save(Team(name = "frontend")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { teamId: $teamId, name: "Data Processing Pipeline" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(String::class.java).get().toLong()
        audit.deleteAll()
    }

    @Test
    fun `a trigger node instances a definition from the team's catalogue`() {
        val triggerId = trigger(teamId, "Slack Mention Handler")

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(teamId: $teamId, workflowId: $workflowId, input: {
                nodes: [{ key: "start", kind: TRIGGER, name: "Mention", triggerId: $triggerId, x: 0, y: 0 }],
                edges: []
              }) { nodes { key triggerId } }
            }
            """,
        ).execute().path("saveWorkflowGraph.nodes[0].triggerId").entity(Long::class.java).isEqualTo(triggerId)

        assertThat(nodes.findByTriggerId(triggerId).map { it.workflowId }).containsExactly(workflowId)
    }

    @Test
    fun `refuses a definition from another team's catalogue`() {
        val theirs = trigger(otherTeamId, "Theirs")

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(teamId: $teamId, workflowId: $workflowId, input: {
                nodes: [{ key: "start", kind: TRIGGER, name: "Mention", triggerId: $theirs, x: 0, y: 0 }],
                edges: []
              }) { nodes { key } }
            }
            """,
        ).execute().errors().expect { it.message?.contains("not in this team's catalogue") == true }.verify()
    }

    @Test
    fun `a new workflow starts as an empty draft`() {
        graphQlTester.document(
            """query { workflowGraph(teamId: $teamId, workflowId: $workflowId) { name status nodes { key } edges { source } } }""",
        ).execute()
            .path("workflowGraph.status").entity(String::class.java).isEqualTo("DRAFT")
            .path("workflowGraph.nodes").entityList(String::class.java).hasSize(0)
            .path("workflowGraph.edges").entityList(String::class.java).hasSize(0)
    }

    /** One catalogue entry, of the scheduled kind, which needs no connection. */
    private fun trigger(team: Long, name: String): Long = graphQlTester.document(
        """
        mutation {
          createTrigger(input: { teamId: $team, name: "$name", type: SCHEDULED, cron: "0 2 * * *" }) { id }
        }
        """,
    ).execute().path("createTrigger.id").entity(Long::class.java).get()

    @Test
    fun `saves nodes and edges, and reads them back`() {
        save()

        val response = graphQlTester.document(
            """
            query {
              workflowGraph(teamId: $teamId, workflowId: $workflowId) {
                nodes { key kind name description agentClass modelProvider x y }
                edges { source target }
              }
            }
            """,
        ).execute()

        val keys = response.path("workflowGraph.nodes[*].key").entityList(String::class.java).get()
        val sources = response.path("workflowGraph.edges[*].source").entityList(String::class.java).get()
        assertThat(keys).containsExactlyInAnyOrder("trigger", "research", "report")
        assertThat(sources).containsExactlyInAnyOrder("trigger", "research")

        val research = nodes.findByWorkflowId(workflowId).single { it.nodeKey == "research" }
        assertThat(research.kind).isEqualTo(NodeKind.AGENT)
        assertThat(research.agentClass).isEqualTo("ReAct Reasoning Agent")
        assertThat(research.modelProvider).isEqualTo("GPT-4o (Gyloli Shared)")
        assertThat(research.positionX).isEqualTo(320.0)
    }

    @Test
    fun `saving replaces the previous graph`() {
        save()

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(teamId: $teamId, workflowId: $workflowId, input: {
                nodes: [{ key: "only", kind: TRIGGER, name: "Only node", x: 0, y: 0 }],
                edges: []
              }) { nodes { key } edges { source } }
            }
            """,
        ).execute()
            .path("saveWorkflowGraph.nodes[*].key").entityList(String::class.java).containsExactly("only")
            .path("saveWorkflowGraph.edges").entityList(String::class.java).hasSize(0)

        assertThat(nodes.findByWorkflowId(workflowId)).hasSize(1)
        assertThat(edges.findByWorkflowId(workflowId)).isEmpty()
    }

    @Test
    fun `rejects an edge that refers to a missing node`() {
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(teamId: $teamId, workflowId: $workflowId, input: {
                nodes: [{ key: "a", kind: TRIGGER, name: "A", x: 0, y: 0 }],
                edges: [{ source: "a", target: "ghost" }]
              }) { nodes { key } }
            }
            """,
        ).execute().errors().expect { true }.verify()

        assertThat(nodes.findByWorkflowId(workflowId)).isEmpty()
    }

    @Test
    fun `publishing needs at least one node, then flips the status`() {
        graphQlTester.document("""mutation { publishWorkflow(teamId: $teamId, workflowId: $workflowId) { status } }""")
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors).singleElement()
                    .extracting { it.message }
                    .isEqualTo("Add at least one node before publishing")
            }

        save()

        graphQlTester.document("""mutation { publishWorkflow(teamId: $teamId, workflowId: $workflowId) { status } }""")
            .execute().path("publishWorkflow.status").entity(String::class.java).isEqualTo("PUBLISHED")

        assertThat(audit.findAll().map { it.message })
            .contains("Workflow Data Processing Pipeline published")
    }

    @Test
    fun `editing a published workflow puts it back into draft`() {
        save()
        graphQlTester.document("""mutation { publishWorkflow(teamId: $teamId, workflowId: $workflowId) { status } }""")
            .execute()

        save()

        graphQlTester.document("""query { workflowGraph(teamId: $teamId, workflowId: $workflowId) { status } }""")
            .execute().path("workflowGraph.status").entity(String::class.java).isEqualTo("DRAFT")
    }

    @Test
    fun `a team without the workflow assigned cannot read its graph`() {
        graphQlTester.document(
            """query { workflowGraph(teamId: $otherTeamId, workflowId: $workflowId) { status } }""",
        ).execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors).singleElement()
                    .extracting { it.message }
                    .isEqualTo("No workflow assignment with id $workflowId")
            }
    }

    private fun save() {
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(teamId: $teamId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "trigger", kind: TRIGGER, name: "Webhook Trigger", description: "Listens for payload", x: 40, y: 120 },
                  {
                    key: "research", kind: AGENT, name: "Research Agent", description: "Generates reasoning",
                    agentClass: "ReAct Reasoning Agent", modelProvider: "GPT-4o (Gyloli Shared)", x: 320, y: 120
                  },
                  { key: "report", kind: PUBLISH_TASK, name: "Generate Report", description: "Performs script", x: 600, y: 120 }
                ],
                edges: [
                  { source: "trigger", target: "research" },
                  { source: "research", target: "report" }
                ]
              }) { status }
            }
            """,
        ).execute().path("saveWorkflowGraph.status").entity(String::class.java).isEqualTo("DRAFT")
    }
}
