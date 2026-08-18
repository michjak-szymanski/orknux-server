package io.mszymanski.orknux.server.workspace

import io.mszymanski.orknux.server.security.Role
import io.mszymanski.orknux.server.security.RoleRepository
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
class WorkspaceAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val repository: WorkspaceRepository,
    @Autowired val auditRepository: WorkspaceAuditRepository,
    @Autowired val roles: RoleRepository,
) {

    @BeforeEach
    fun clearWorkspaces() {
        auditRepository.deleteAll()
        repository.deleteAll()
    }

    @Test
    fun `creates a workspace and reads it back`() {
        val id = graphQlTester.document(
            """
            mutation { createWorkspace(input: { name: "platform" }) { id name } }
            """,
        ).execute()
            .path("createWorkspace.name").entity(String::class.java).isEqualTo("platform")
            .path("createWorkspace.id").entity(String::class.java).get()

        assertThat(repository.findAll().single().id).isEqualTo(id.toLong())

        graphQlTester.document("""query { workspace(id: $id) { name } }""")
            .execute().path("workspace.name").entity(String::class.java).isEqualTo("platform")
    }

    @Test
    fun `creates a workspace with a description`() {
        graphQlTester.document(
            """
            mutation {
              createWorkspace(input: { name: "backend", description: "Core API, services, and data pipelines." }) {
                name
                description
              }
            }
            """,
        ).execute()
            .path("createWorkspace.description").entity(String::class.java)
            .isEqualTo("Core API, services, and data pipelines.")

        assertThat(repository.findAll().single().description)
            .isEqualTo("Core API, services, and data pipelines.")
    }

    @Test
    fun `leaves the description null when it is not supplied`() {
        graphQlTester.document("""mutation { createWorkspace(input: { name: "platform" }) { description } }""")
            .execute().path("createWorkspace.description").valueIsNull()
    }

    @Test
    fun `rejects a duplicate workspace name with a readable message`() {
        repository.save(Workspace(name = "platform"))

        graphQlTester.document("""mutation { createWorkspace(input: { name: "platform" }) { id } }""")
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors).singleElement()
                    .extracting { it.message }
                    .isEqualTo("""A workspace named "platform" already exists""")
            }

        assertThat(repository.findAll()).hasSize(1)
        assertThat(auditRepository.findAll()).isEmpty()
    }

    @Test
    fun `rejects a blank workspace name`() {
        graphQlTester.document("""mutation { createWorkspace(input: { name: "   " }) { id } }""")
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors).singleElement()
                    .extracting { it.message }
                    .isEqualTo("A workspace name is required")
            }

        assertThat(repository.findAll()).isEmpty()
    }

    @Test
    fun `trims the name and drops an empty description`() {
        graphQlTester.document("""mutation { createWorkspace(input: { name: "  platform  ", description: "  " }) { name description } }""")
            .execute()
            .path("createWorkspace.name").entity(String::class.java).isEqualTo("platform")
            .path("createWorkspace.description").valueIsNull()
    }

    @Test
    fun `lists all workspaces`() {
        repository.saveAll(listOf(Workspace(name = "platform"), Workspace(name = "research")))

        graphQlTester.document("""query { workspaces { content { name } totalElements totalPages } }""")
            .execute()
            .path("workspaces.content[*].name").entityList(String::class.java)
            .containsExactly("platform", "research")
            .path("workspaces.totalElements").entity(Int::class.java).isEqualTo(2)
            .path("workspaces.totalPages").entity(Int::class.java).isEqualTo(1)
    }

    @Test
    fun `pages through workspaces ordered by name`() {
        repository.saveAll(listOf(Workspace(name = "research"), Workspace(name = "platform"), Workspace(name = "design")))

        graphQlTester.document("""query { workspaces(page: 0, size: 2) { content { name } page size totalElements totalPages } }""")
            .execute()
            .path("workspaces.content[*].name").entityList(String::class.java)
            .containsExactly("design", "platform")
            .path("workspaces.page").entity(Int::class.java).isEqualTo(0)
            .path("workspaces.size").entity(Int::class.java).isEqualTo(2)
            .path("workspaces.totalElements").entity(Int::class.java).isEqualTo(3)
            .path("workspaces.totalPages").entity(Int::class.java).isEqualTo(2)

        graphQlTester.document("""query { workspaces(page: 1, size: 2) { content { name } } }""")
            .execute()
            .path("workspaces.content[*].name").entityList(String::class.java)
            .containsExactly("research")

        graphQlTester.document("""query { workspaces(page: 9, size: 2) { content { name } totalElements } }""")
            .execute()
            .path("workspaces.content").entityList(String::class.java).hasSize(0)
            .path("workspaces.totalElements").entity(Int::class.java).isEqualTo(3)
    }

    @Test
    fun `clamps out-of-range paging arguments`() {
        repository.saveAll((1..5).map { Workspace(name = "workspace-$it") })

        graphQlTester.document("""query { workspaces(page: -1, size: 0) { content { name } page size } }""")
            .execute()
            .path("workspaces.page").entity(Int::class.java).isEqualTo(0)
            .path("workspaces.size").entity(Int::class.java).isEqualTo(1)
            .path("workspaces.content[*].name").entityList(String::class.java).containsExactly("workspace-1")

        graphQlTester.document("""query { workspaces(size: 5000) { size } }""")
            .execute()
            .path("workspaces.size").entity(Int::class.java).isEqualTo(100)
    }

    @Test
    fun `updates a workspace name`() {
        val workspace = repository.save(Workspace(name = "platform"))

        graphQlTester.document("""mutation { updateWorkspace(id: ${workspace.id}, input: { name: "core" }) { name } }""")
            .execute().path("updateWorkspace.name").entity(String::class.java).isEqualTo("core")

        assertThat(repository.findAll().single().name).isEqualTo("core")
    }

    @Test
    fun `refuses to update a workspace onto an existing name`() {
        val workspace = repository.save(Workspace(name = "platform"))
        repository.save(Workspace(name = "research"))

        graphQlTester.document("""mutation { updateWorkspace(id: ${workspace.id}, input: { name: "research" }) { name } }""")
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors).singleElement()
                    .extracting { it.message }
                    .isEqualTo("""A workspace named "research" already exists""")
            }

        assertThat(repository.findByName("platform")).isNotNull()
        assertThat(auditRepository.findAll()).isEmpty()
    }

    @Test
    fun `refuses to update a workspace to a blank name`() {
        val workspace = repository.save(Workspace(name = "platform"))

        graphQlTester.document("""mutation { updateWorkspace(id: ${workspace.id}, input: { name: "  " }) { name } }""")
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors).singleElement()
                    .extracting { it.message }
                    .isEqualTo("A workspace name is required")
            }

        assertThat(repository.findAll().single().name).isEqualTo("platform")
    }

    @Test
    fun `records nothing when the name is unchanged`() {
        val workspace = repository.save(Workspace(name = "platform"))

        graphQlTester.document("""mutation { updateWorkspace(id: ${workspace.id}, input: { name: "platform" }) { name } }""")
            .execute().path("updateWorkspace.name").entity(String::class.java).isEqualTo("platform")

        assertThat(auditRepository.findAll()).isEmpty()
    }

    @Test
    fun `saves description and the roles that open it from the settings form`() {
        val workspace = repository.save(Workspace(name = "backend"))
        val backend = roles.save(Role(name = "backend"))

        graphQlTester.document(
            """
            mutation {
              updateWorkspace(id: ${workspace.id}, input: {
                name: "backend",
                description: "Core API, services, and data pipelines.",
                roleIds: [${backend.id}]
              }) { name description roles { name } }
            }
            """,
        ).execute()
            .path("updateWorkspace.description").entity(String::class.java)
            .isEqualTo("Core API, services, and data pipelines.")
            .path("updateWorkspace.roles[*].name").entityList(String::class.java).containsExactly("backend")

        val saved = repository.findAll().single()
        assertThat(saved.roles.map { it.name }).containsExactly("backend")
        // No rename, but the settings changes are still worth recording — and the
        // roles are named in the entry, because who can see a workspace is worth
        // being able to read out of the log a year later.
        assertThat(auditRepository.findAll().map { it.message })
            .containsExactlyInAnyOrder("Workspace roles set to backend", "Workspace description updated")
    }

    @Test
    fun `clears the roles when the last one is taken off`() {
        val backend = roles.save(Role(name = "backend"))
        val workspace = repository.save(Workspace(name = "backend", roles = mutableSetOf(backend)))

        graphQlTester.document(
            """mutation { updateWorkspace(id: ${workspace.id}, input: { name: "backend", roleIds: [] }) { roles { name } } }""",
        ).execute().path("updateWorkspace.roles").entityList(String::class.java).hasSize(0)

        assertThat(repository.findAll().single().roles).isEmpty()
    }

    @Test
    fun `deletes a workspace and reports whether it existed`() {
        val workspace = repository.save(Workspace(name = "platform"))

        graphQlTester.document("""mutation { deleteWorkspace(id: ${workspace.id}) }""")
            .execute().path("deleteWorkspace").entity(Boolean::class.java).isEqualTo(true)

        graphQlTester.document("""mutation { deleteWorkspace(id: ${workspace.id}) }""")
            .execute().path("deleteWorkspace").entity(Boolean::class.java).isEqualTo(false)

        assertThat(repository.findAll()).isEmpty()
    }

    @Test
    fun `returns null for an unknown workspace`() {
        graphQlTester.document("""query { workspace(id: 999999) { name } }""")
            .execute().path("workspace").valueIsNull()
    }

    @Test
    fun `records an audit entry for every mutation, attributed to the caller`() {
        val id = graphQlTester.document("""mutation { createWorkspace(input: { name: "platform" }) { id } }""")
            .execute().path("createWorkspace.id").entity(String::class.java).get()

        graphQlTester.document("""mutation { updateWorkspace(id: $id, input: { name: "core" }) { name } }""").execute()
        graphQlTester.document("""mutation { deleteWorkspace(id: $id) }""").execute()

        // Integration entries ride along with a workspace's life; the lifecycle is what
        // this asserts.
        val entries = auditRepository.findAll().filter { it.operationType != null }.sortedBy { it.id }

        assertThat(entries.map { it.operationType }).containsExactly(
            WorkspaceOperationType.ADD,
            WorkspaceOperationType.RENAME,
            WorkspaceOperationType.REMOVE,
        )
        assertThat(entries.map { it.userId }).containsOnly("alice")
        assertThat(entries.map { it.workspaceId }).containsOnly(id.toLong())
        assertThat(entries[0].oldWorkspaceName).isNull()
        assertThat(entries[0].newWorkspaceName).isEqualTo("platform")
        assertThat(entries[1].oldWorkspaceName).isEqualTo("platform")
        assertThat(entries[1].newWorkspaceName).isEqualTo("core")
        assertThat(entries[2].oldWorkspaceName).isEqualTo("core")
        assertThat(entries[2].newWorkspaceName).isNull()
    }

    @Test
    fun `does not record an audit entry when nothing was deleted`() {
        graphQlTester.document("""mutation { deleteWorkspace(id: 999999) }""")
            .execute().path("deleteWorkspace").entity(Boolean::class.java).isEqualTo(false)

        assertThat(auditRepository.findAll()).isEmpty()
    }
}
