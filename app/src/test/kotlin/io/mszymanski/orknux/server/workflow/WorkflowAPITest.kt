package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
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
class WorkflowAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private var otherWorkspaceId: Long = 0

    @BeforeEach
    fun reset() {
        assignments.deleteAll()
        workflows.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        otherWorkspaceId = requireNotNull(workspaces.save(Workspace(name = "frontend")).id)
    }

    @Test
    fun `creates a workflow and assigns it to the workspace, enabled`() {
        graphQlTester.document(
            """
            mutation {
              createWorkflow(input: {
                workspaceId: $workspaceId,
                name: "Backend CI/CD Pipeline",
                description: "Automated build, test, and deploy pipeline"
              }) { name description enabled }
            }
            """,
        ).execute()
            .path("createWorkflow.name").entity(String::class.java).isEqualTo("Backend CI/CD Pipeline")
            .path("createWorkflow.description").entity(String::class.java)
            .isEqualTo("Automated build, test, and deploy pipeline")
            .path("createWorkflow.enabled").entity(Boolean::class.java).isEqualTo(true)

        assertThat(workflows.findAll()).hasSize(1)
        assertThat(assignments.findAll().single().workspaceId).isEqualTo(workspaceId)
    }

    @Test
    fun `lists only the workflows of the requested workspace, ordered by name`() {
        create("Security Audit")
        create("Backend CI/CD Pipeline")
        create("Frontend Deploy", workspace = otherWorkspaceId)

        graphQlTester.document("""query { workspaceWorkflows(workspaceId: $workspaceId) { content { name } totalElements } }""")
            .execute()
            .path("workspaceWorkflows.content[*].name").entityList(String::class.java)
            .containsExactly("Backend CI/CD Pipeline", "Security Audit")
            .path("workspaceWorkflows.totalElements").entity(Int::class.java).isEqualTo(2)
    }

    @Test
    fun `pages through workflows`() {
        create("A workflow")
        create("B workflow")
        create("C workflow")

        graphQlTester.document("""query { workspaceWorkflows(workspaceId: $workspaceId, page: 1, size: 2) { content { name } totalPages } }""")
            .execute()
            .path("workspaceWorkflows.content[*].name").entityList(String::class.java).containsExactly("C workflow")
            .path("workspaceWorkflows.totalPages").entity(Int::class.java).isEqualTo(2)
    }

    @Test
    fun `rejects a duplicate workflow name`() {
        create("Backend CI/CD Pipeline")

        graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Backend CI/CD Pipeline" }) { id } }""",
        ).execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors).singleElement()
                    .extracting { it.message }
                    .isEqualTo("""A workflow named "Backend CI/CD Pipeline" already exists""")
            }

        assertThat(workflows.findAll()).hasSize(1)
    }

    @Test
    fun `rejects a blank workflow name`() {
        graphQlTester.document("""mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "  " }) { id } }""")
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors).singleElement()
                    .extracting { it.message }
                    .isEqualTo("A workflow name is required")
            }
    }

    @Test
    fun `rejects a workflow for an unknown workspace`() {
        graphQlTester.document("""mutation { createWorkflow(input: { workspaceId: 999999, name: "Orphan" }) { id } }""")
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors).singleElement()
                    .extracting { it.message }
                    .isEqualTo("No workspace with id 999999")
            }

        assertThat(workflows.findAll()).isEmpty()
    }

    @Test
    fun `disables and re-enables a workflow`() {
        val id = create("Backend CI/CD Pipeline")

        graphQlTester.document("""mutation { setWorkflowEnabled(id: $id, enabled: false) { enabled } }""")
            .execute().path("setWorkflowEnabled.enabled").entity(Boolean::class.java).isEqualTo(false)

        assertThat(assignments.findAll().single().enabled).isFalse()

        graphQlTester.document("""mutation { setWorkflowEnabled(id: $id, enabled: true) { enabled } }""")
            .execute().path("setWorkflowEnabled.enabled").entity(Boolean::class.java).isEqualTo(true)
    }

    @Test
    fun `removing a workflow keeps the definition`() {
        val id = create("Backend CI/CD Pipeline")

        graphQlTester.document("""mutation { removeWorkflow(id: $id) }""")
            .execute().path("removeWorkflow").entity(Boolean::class.java).isEqualTo(true)

        assertThat(assignments.findAll()).isEmpty()
        assertThat(workflows.findAll()).hasSize(1)

        graphQlTester.document("""mutation { removeWorkflow(id: $id) }""")
            .execute().path("removeWorkflow").entity(Boolean::class.java).isEqualTo(false)
    }

    @Test
    fun `deleting a workspace takes its assignments with it`() {
        create("Backend CI/CD Pipeline")

        workspaces.deleteById(workspaceId)

        assertThat(assignments.findAll()).isEmpty()
        assertThat(workflows.findAll()).hasSize(1)
    }

    private fun create(name: String, workspace: Long = workspaceId): String =
        graphQlTester.document("""mutation { createWorkflow(input: { workspaceId: $workspace, name: "$name" }) { id } }""")
            .execute().path("createWorkflow.id").entity(String::class.java).get()
}
