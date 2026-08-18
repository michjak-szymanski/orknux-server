package io.mszymanski.orknux.server.security

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

/**
 * Removing a role a workspace still depends on.
 *
 * The refusal was written and mapped and nothing ever threw it, so deleting
 * such a role succeeded - and because the link is a join table with a cascade,
 * whoever held that role lost those workspaces silently, without anybody
 * deciding it. These pin the refusal, and that it says which workspaces are in
 * the way, since "take it off those first" is only useful advice if it names
 * them.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class RoleDeletionTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val roles: RoleRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var roleId: Long = 0

    @BeforeEach
    fun reset() {
        workspaces.deleteAll()
        roles.findAll().filter { !it.builtin }.forEach { roles.delete(it) }
        roleId = requireNotNull(roles.save(Role(name = "Support", description = "The support desk")).id)
    }

    private fun delete(id: Long) =
        graphQlTester.document("mutation { deleteRole(id: $id) }").execute()

    @Test
    fun `a role nothing depends on is removed`() {
        delete(roleId).path("deleteRole").entity(Boolean::class.java).isEqualTo(true)
        assertThat(roles.findById(roleId)).isEmpty()
    }

    @Test
    fun `a role a workspace depends on is refused, and the workspace is named`() {
        val role = roles.findById(roleId).get()
        workspaces.save(Workspace(name = "Acme Support", roles = mutableSetOf(role)))

        delete(roleId).errors().satisfy { problems ->
            assertThat(problems).hasSize(1)
            assertThat(problems.first().message).contains("Acme Support")
        }
        // And it is still there, which is the point: nobody lost access.
        assertThat(roles.findById(roleId)).isPresent()
    }

    /** A built-in role is refused for its own reason, which this must not shadow. */
    @Test
    fun `a built-in role is still refused as built in`() {
        val builtin = roles.findAll().first { it.builtin }

        delete(requireNotNull(builtin.id)).errors().satisfy { problems ->
            assertThat(problems.first().message).contains("built in")
        }
    }
}
