package io.mszymanski.gyloli.server.team

import io.mszymanski.gyloli.server.agent.AgentRepository
import io.mszymanski.gyloli.server.workflow.TeamWorkflowRepository
import io.mszymanski.gyloli.server.workflow.WorkflowRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.time.OffsetDateTime

/** The team audit view: what gets recorded, and the filters over it. */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class TeamActivityTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val audit: TeamAuditRepository,
    @Autowired val teams: TeamRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: TeamWorkflowRepository,
) {

    private var teamId: Long = 0

    @BeforeEach
    fun reset() {
        assignments.deleteAll()
        workflows.deleteAll()
        agents.deleteAll()
        audit.deleteAll()
        teams.deleteAll()
        teamId = requireNotNull(teams.save(Team(name = "backend")).id)
    }

    @Test
    fun `records workflow activity`() {
        graphQlTester.document(
            """mutation { createWorkflow(input: { teamId: $teamId, name: "Data Processing Pipeline" }) { id } }""",
        ).execute().path("createWorkflow.id").entity(String::class.java).get().let { id ->
            graphQlTester.document("""mutation { setWorkflowEnabled(id: $id, enabled: false) { id } }""").execute()
            graphQlTester.document("""mutation { removeWorkflow(id: $id) }""").execute()
        }

        assertThat(audit.findAll().map { it.message }).containsExactlyInAnyOrder(
            "Workflow Data Processing Pipeline created",
            "Workflow Data Processing Pipeline disabled",
            "Workflow Data Processing Pipeline removed from this team",
        )
        assertThat(audit.findAll().map { it.category }).containsOnly(TeamAuditCategory.WORKFLOW)
    }

    @Test
    fun `records agent activity, including mcp server changes`() {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { teamId: $teamId, name: "Research Agent", type: REACT }) { id } }""",
        ).execute().path("createAgent.id").entity(String::class.java).get()

        graphQlTester.document("""mutation { setAgentEnabled(id: $id, enabled: true) { id } }""").execute()
        graphQlTester.document(
            """
            mutation {
              updateAgent(id: $id, input: {
                name: "Research Agent",
                systemPrompt: "You are a research agent.",
                mcpServers: ["brave-search"]
              }) { id }
            }
            """,
        ).execute()
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "Research Agent", systemPrompt: "You are a research agent.", mcpServers: [] }) { id } }""",
        ).execute()

        assertThat(audit.findAll().map { it.message }).contains(
            "Agent Research Agent created",
            "Agent Research Agent enabled",
            "Agent Research Agent system prompt updated",
            "MCP Server brave-search added to Research Agent",
            "MCP Server brave-search removed from Research Agent",
        )
    }

    @Test
    fun `records a directory group change on the team`() {
        graphQlTester.document(
            """mutation { updateTeam(id: $teamId, input: { name: "backend", ldapGroup: "cn=backend,ou=teams,dc=gyloli,dc=io" }) { id } }""",
        ).execute()

        assertThat(audit.findAll().map { it.message }).containsExactly("Team LDAP group updated")
        assertThat(audit.findAll().single().category).isEqualTo(TeamAuditCategory.TEAM)
    }

    @Test
    fun `filters by category, user, search term and age`() {
        seed(TeamAuditCategory.WORKFLOW, "Workflow Alpha created", "alice", days = 0)
        seed(TeamAuditCategory.AGENT, "Agent Beta enabled", "bob", days = 0)
        seed(TeamAuditCategory.AGENT, "Agent Gamma disabled", "alice", days = 40)

        activity("category: AGENT").containsExactly("Agent Beta enabled", "Agent Gamma disabled")
        activity("""userId: "bob"""").containsExactly("Agent Beta enabled")
        activity("""search: "alpha"""").containsExactly("Workflow Alpha created")
        // The search also covers who did it.
        activity("""search: "bob"""").containsExactly("Agent Beta enabled")
        activity("days: 30").containsExactly("Agent Beta enabled", "Workflow Alpha created")
        activity("").hasSize(3)
    }

    @Test
    fun `lists the users seen in the log`() {
        seed(TeamAuditCategory.WORKFLOW, "Workflow Alpha created", "alice", days = 0)
        seed(TeamAuditCategory.AGENT, "Agent Beta enabled", "bob", days = 0)
        seed(TeamAuditCategory.AGENT, "Agent Gamma disabled", "alice", days = 0)

        graphQlTester.document("""query { teamActivityUsers(teamId: $teamId) }""")
            .execute().path("teamActivityUsers").entityList(String::class.java).containsExactly("alice", "bob")
    }

    private fun activity(filter: String) = graphQlTester
        .document("""query { teamActivity(teamId: $teamId${if (filter.isEmpty()) "" else ", $filter"}) { content { message } } }""")
        .execute()
        .path("teamActivity.content[*].message")
        .entityList(String::class.java)

    private fun seed(category: TeamAuditCategory, message: String, user: String, days: Long) {
        audit.save(
            TeamAudit(
                teamId = teamId,
                category = category,
                message = message,
                date = OffsetDateTime.now().minusDays(days),
                userId = user,
            ),
        )
    }
}
