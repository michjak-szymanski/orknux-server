package io.mszymanski.orknux.server.security

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
 * Visibility comes from directory group membership: the configured admin role
 * sees everything, everyone else needs to be in the group named on the workspace.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
class WorkspaceVisibilityTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
) {

    private var backendId: Long = 0
    private var frontendId: Long = 0

    @BeforeEach
    fun seed() {
        assignments.deleteAll()
        workflows.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        backendId = requireNotNull(
            workspaces.save(Workspace(name = "backend", ldapGroup = "cn=backend,ou=workspaces,dc=orknux,dc=io")).id,
        )
        frontendId = requireNotNull(
            workspaces.save(Workspace(name = "frontend", ldapGroup = "cn=frontend,ou=workspaces,dc=orknux,dc=io")).id,
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
    fun `a member sees only the workspaces whose group they belong to`() {
        graphQlTester.document("""query { workspaces { content { name } totalElements totalPages } }""")
            .execute()
            .path("workspaces.content[*].name").entityList(String::class.java).containsExactly("backend")
            .path("workspaces.totalElements").entity(Int::class.java).isEqualTo(1)
            .path("workspaces.totalPages").entity(Int::class.java).isEqualTo(1)
    }

    @Test
    @WithMockUser(username = "nobody", roles = ["USERS"])
    fun `someone in no workspace group sees nothing`() {
        graphQlTester.document("""query { workspaces { content { name } totalElements } }""")
            .execute()
            .path("workspaces.content").entityList(String::class.java).hasSize(0)
            .path("workspaces.totalElements").entity(Int::class.java).isEqualTo(0)
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `a workspace with no directory group is administrators-only`() {
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
            """You do not have access to workspace "frontend"""",
        )
        forbidden(
            """mutation { createWorkflow(input: { workspaceId: $frontendId, name: "Sneaky" }) { id } }""",
            """You do not have access to workspace "frontend"""",
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
            """You do not have access to workspace "frontend"""",
        )
        forbidden(
            """mutation { removeWorkflow(id: ${assignment.id}) }""",
            """You do not have access to workspace "frontend"""",
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
            """You do not have access to workspace "frontend"""",
        )
    }

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
