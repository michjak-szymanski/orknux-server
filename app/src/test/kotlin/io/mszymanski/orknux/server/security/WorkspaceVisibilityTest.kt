package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.server.action.ActionSubtype
import io.mszymanski.orknux.server.action.ActionType
import io.mszymanski.orknux.server.action.WorkflowAction
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.issue.Issue
import io.mszymanski.orknux.server.issue.IssueRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAudit
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceOperationType
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflow
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workflow.Workflow
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

/**
 * Visibility comes from roles: the configured admin authority sees everything,
 * everyone else needs to hold a role the workspace is assigned.
 *
 * The roles here are granted by name — a caller holding `ROLE_BACKEND` holds the
 * role called `backend` — which is the path that keeps installations working when
 * they upgrade into roles without writing any mapping.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
class WorkspaceVisibilityTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val roles: RoleRepository,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val issues: IssueRepository,
) {

    private var backendId: Long = 0
    private var frontendId: Long = 0

    @BeforeEach
    fun seed() {
        issues.deleteAll()
        agents.deleteAll()
        actions.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        // Everything but the built-in role, which is this installation's and not
        // any test's to remove.
        roles.deleteAll(roles.findAll().filterNot { it.builtin })

        val backend = roles.save(Role(name = "backend"))
        val frontend = roles.save(Role(name = "frontend"))
        backendId = requireNotNull(
            workspaces.save(Workspace(name = "backend", roles = mutableSetOf(backend))).id,
        )
        frontendId = requireNotNull(
            workspaces.save(Workspace(name = "frontend", roles = mutableSetOf(frontend))).id,
        )
    }

    @Test
    @WithMockUser(username = "alice", roles = ["ADMINS"])
    fun `an admin sees every workspace`() {
        graphQlTester.document("""query { workspaces { content { name } totalElements } }""")
            .execute()
            .path("workspaces.content[*].name").entityList(String::class.java).containsExactly("backend", "frontend")
            .path("workspaces.totalElements").entity(Int::class.java).isEqualTo(2)
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `a member sees only the workspaces whose role they hold`() {
        graphQlTester.document("""query { workspaces { content { name } totalElements totalPages } }""")
            .execute()
            .path("workspaces.content[*].name").entityList(String::class.java).containsExactly("backend")
            .path("workspaces.totalElements").entity(Int::class.java).isEqualTo(1)
            .path("workspaces.totalPages").entity(Int::class.java).isEqualTo(1)
    }

    @Test
    @WithMockUser(username = "nobody", roles = ["USERS"])
    fun `someone holding no workspace role sees nothing`() {
        graphQlTester.document("""query { workspaces { content { name } totalElements } }""")
            .execute()
            .path("workspaces.content").entityList(String::class.java).hasSize(0)
            .path("workspaces.totalElements").entity(Int::class.java).isEqualTo(0)
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `a workspace with no roles is administrators-only`() {
        val orphan = workspaces.save(Workspace(name = "secret"))

        graphQlTester.document("""query { workspace(id: ${orphan.id}) { name } }""")
            .execute().path("workspace").valueIsNull()

        graphQlTester.document("""query { workspaces { content { name } } }""")
            .execute()
            .path("workspaces.content[*].name").entityList(String::class.java).containsExactly("backend")
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `a hidden workspace reads as missing`() {
        graphQlTester.document("""query { workspace(id: $backendId) { name } }""")
            .execute().path("workspace.name").entity(String::class.java).isEqualTo("backend")

        graphQlTester.document("""query { workspace(id: $frontendId) { name } }""")
            .execute().path("workspace").valueIsNull()
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `a member cannot create, rename or delete workspaces`() {
        forbidden(
            """mutation { createWorkspace(input: { name: "platform" }) { id } }""",
            "This action requires the administrator role",
        )
        forbidden(
            """mutation { updateWorkspace(id: $backendId, input: { name: "core" }) { id } }""",
            "This action requires the administrator role",
        )
        forbidden(
            """mutation { deleteWorkspace(id: $backendId) }""",
            "This action requires the administrator role",
        )

        assertThat(workspaces.findAll().map { it.name }).containsExactlyInAnyOrder("backend", "frontend")
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `a member cannot reach another workspace's workflows`() {
        graphQlTester.document("""query { workspaceWorkflows(workspaceId: $backendId) { totalElements } }""")
            .execute().path("workspaceWorkflows.totalElements").entity(Int::class.java).isEqualTo(0)

        forbidden(
            """query { workspaceWorkflows(workspaceId: $frontendId) { totalElements } }""",
            "That does not exist, or you do not have access to it",
        )
        forbidden(
            """mutation { createWorkflow(input: { workspaceId: $frontendId, name: "Sneaky" }) { id } }""",
            "That does not exist, or you do not have access to it",
        )

        assertThat(workflows.findAll()).isEmpty()
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `a member cannot toggle or remove another workspace's workflow`() {
        val workflow = workflows.save(Workflow(name = "Frontend Deploy"))
        val assignment = assignments.save(WorkspaceWorkflow(workspaceId = frontendId, workflow = workflow))

        forbidden(
            """mutation { setWorkflowEnabled(id: ${assignment.id}, enabled: false) { enabled } }""",
            "That does not exist, or you do not have access to it",
        )
        forbidden(
            """mutation { removeWorkflow(id: ${assignment.id}) }""",
            "That does not exist, or you do not have access to it",
        )

        assertThat(assignments.findAll()).hasSize(1)
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `the audit log only shows entries for visible workspaces`() {
        audit.save(entry(backendId, "backend"))
        audit.save(entry(frontendId, "frontend"))

        graphQlTester.document("""query { workspaceAudit { content { newWorkspaceName } totalElements } }""")
            .execute()
            .path("workspaceAudit.content[*].newWorkspaceName").entityList(String::class.java).containsExactly("backend")
            .path("workspaceAudit.totalElements").entity(Int::class.java).isEqualTo(1)

        forbidden(
            """query { workspaceAudit(workspaceId: $frontendId) { totalElements } }""",
            "That does not exist, or you do not have access to it",
        )
    }

    /**
     * A refusal used to read "You do not have access to workspace "frontend"",
     * which answers a question nobody may ask: the name of a workspace the
     * caller is not in, handed over by any id that happens to belong to it.
     * GraphQL reports errors with a 200, so trying every id in turn is a script.
     */
    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `a refusal never names the workspace it is protecting`() {
        graphQlTester.document("""query { workspaceWorkflows(workspaceId: $frontendId) { totalElements } }""")
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors.single().message).doesNotContain("frontend")
                // Answered as absent rather than as forbidden, which is what the
                // REST side has always said to the same exception.
                assertThat(errors.single().errorType.toString()).isEqualTo("NOT_FOUND")
            }
    }

    /**
     * The other half of the same leak.
     *
     * A refusal that no longer names the workspace still answers a real id
     * differently from an arbitrary one, so walking the numbers still maps out
     * what exists here and roughly how much of it there is. Answering null makes
     * an entity in a workspace the caller cannot see indistinguishable from one
     * that was never created.
     */
    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `an entity in a hidden workspace reads as one that is not there`() {
        val mine = requireNotNull(actions.save(action(backendId, "Mine")).id)
        val theirs = requireNotNull(actions.save(action(frontendId, "Theirs")).id)

        graphQlTester.document("""query { action(id: $mine) { name } }""")
            .execute().path("action.name").entity(String::class.java).isEqualTo("Mine")

        graphQlTester.document("""query { action(id: $theirs) { name } }""")
            .execute().path("action").valueIsNull()
    }

    /**
     * The two answers have to be the same answer, not merely both empty: an
     * error for one and null for the other is still a yes and a no.
     */
    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `an id that is not yours answers exactly as an id that is not real`() {
        val theirs = requireNotNull(agents.save(agent(frontendId)).id)

        val notYours = graphQlTester.document("""query { agent(id: $theirs) { name } }""").execute()
        val notReal = graphQlTester.document("""query { agent(id: 999999) { name } }""").execute()

        notYours.path("agent").valueIsNull()
        notReal.path("agent").valueIsNull()
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `an agent in a visible workspace is still returned`() {
        val mine = requireNotNull(agents.save(agent(backendId)).id)

        graphQlTester.document("""query { agent(id: $mine) { name } }""")
            .execute().path("agent.name").entity(String::class.java).isEqualTo("Helper")
    }

    /**
     * The issue query takes a workspace id rather than the issue's own, so the
     * whole query answers null rather than refusing - otherwise the workspace id
     * itself is the number to walk.
     */
    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `an issue in a hidden workspace reads as one that is not there`() {
        issues.save(issue(backendId, "Mine"))
        issues.save(issue(frontendId, "Theirs"))

        graphQlTester.document("""query { workspaceIssue(workspaceId: $backendId, number: 1) { title } }""")
            .execute().path("workspaceIssue.title").entity(String::class.java).isEqualTo("Mine")

        graphQlTester.document("""query { workspaceIssue(workspaceId: $frontendId, number: 1) { title } }""")
            .execute().path("workspaceIssue").valueIsNull()
    }

    private fun action(workspaceId: Long, name: String) = WorkflowAction(
        workspaceId = workspaceId,
        name = name,
        type = ActionType.EXECUTE,
        subtype = ActionSubtype.HTTP_REQUEST,
        url = "https://example.test/",
    )

    private fun agent(workspaceId: Long) = Agent(
        workspaceId = workspaceId,
        name = "Helper",
        type = AgentType.LLM,
    )

    private fun issue(workspaceId: Long, title: String) = Issue(
        workspaceId = workspaceId,
        number = 1,
        title = title,
        reporter = "alice",
    )

    private fun entry(workspaceId: Long, name: String) = WorkspaceAudit(
        workspaceId = workspaceId,
        newWorkspaceName = name,
        message = "seeded",
        operationType = WorkspaceOperationType.ADD,
        date = OffsetDateTime.now(),
        userId = "alice",
    )

    private fun forbidden(document: String, message: String) {
        graphQlTester.document(document)
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors).singleElement().extracting { it.message }.isEqualTo(message)
            }
    }
}
