package io.mszymanski.orknux.server.workspace

import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.time.OffsetDateTime

/** The workspace audit view: what gets recorded, and the filters over it. */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WorkspaceActivityTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        assignments.deleteAll()
        workflows.deleteAll()
        agents.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @Test
    fun `records workflow activity`() {
        graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Data Processing Pipeline" }) { id } }""",
        ).execute().path("createWorkflow.id").entity(String::class.java).get().let { id ->
            graphQlTester.document("""mutation { setWorkflowEnabled(id: $id, enabled: false) { id } }""").execute()
            graphQlTester.document("""mutation { removeWorkflow(id: $id) }""").execute()
        }

        assertThat(audit.findAll().map { it.message }).containsExactlyInAnyOrder(
            "Workflow Data Processing Pipeline created",
            "Workflow Data Processing Pipeline disabled",
            "Workflow Data Processing Pipeline removed from this workspace",
        )
        assertThat(audit.findAll().map { it.category }).containsOnly(WorkspaceAuditCategory.WORKFLOW)
    }

    @Test
    fun `records agent activity, including mcp server changes`() {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Research Agent", type: LLM }) { id } }""",
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
    fun `records a directory group change on the workspace`() {
        graphQlTester.document(
            """mutation { updateWorkspace(id: $workspaceId, input: { name: "backend", ldapGroup: "cn=backend,ou=workspaces,dc=orknux,dc=io" }) { id } }""",
        ).execute()

        assertThat(audit.findAll().map { it.message }).containsExactly("Workspace LDAP group updated")
        assertThat(audit.findAll().single().category).isEqualTo(WorkspaceAuditCategory.WORKSPACE)
    }

    @Test
    fun `filters by category, user, search term and age`() {
        seed(WorkspaceAuditCategory.WORKFLOW, "Workflow Alpha created", "alice", days = 0)
        seed(WorkspaceAuditCategory.AGENT, "Agent Beta enabled", "bob", days = 0)
        seed(WorkspaceAuditCategory.AGENT, "Agent Gamma disabled", "alice", days = 40)

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
        seed(WorkspaceAuditCategory.WORKFLOW, "Workflow Alpha created", "alice", days = 0)
        seed(WorkspaceAuditCategory.AGENT, "Agent Beta enabled", "bob", days = 0)
        seed(WorkspaceAuditCategory.AGENT, "Agent Gamma disabled", "alice", days = 0)

        graphQlTester.document("""query { workspaceActivityUsers(workspaceId: $workspaceId) }""")
            .execute().path("workspaceActivityUsers").entityList(String::class.java).containsExactly("alice", "bob")
    }

    private fun activity(filter: String) = graphQlTester
        .document("""query { workspaceActivity(workspaceId: $workspaceId${if (filter.isEmpty()) "" else ", $filter"}) { content { message } } }""")
        .execute()
        .path("workspaceActivity.content[*].message")
        .entityList(String::class.java)

    private fun seed(category: WorkspaceAuditCategory, message: String, user: String, days: Long) {
        audit.save(
            WorkspaceAudit(
                workspaceId = workspaceId,
                category = category,
                message = message,
                date = OffsetDateTime.now().minusDays(days),
                userId = user,
            ),
        )
    }
}
