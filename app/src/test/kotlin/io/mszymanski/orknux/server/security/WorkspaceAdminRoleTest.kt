package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.server.issue.Issue
import io.mszymanski.orknux.server.issue.IssueNewsRepository
import io.mszymanski.orknux.server.issue.IssueObserverRepository
import io.mszymanski.orknux.server.issue.IssueRepository
import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.UserType
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * The workspace administrator role, tested from both sides of the line.
 *
 * This is a permissions boundary, so it is not enough to show that the new
 * things work. Three questions have to be answered together, and a test that
 * answers only the first is a test that would pass on a role granting
 * everything: a workspace administrator can do the new things **in their own
 * workspace**, cannot do them **in another one**, and cannot do anything
 * **installation-wide** anywhere.
 *
 * The seeding is what makes the middle question askable. Dana holds `support`
 * and `billing`, so she can see both workspaces - and only `support` administers
 * one. Every refusal below is therefore about administering rather than about
 * seeing, which is the distinction the role exists to draw and the one a test
 * seeded with a role that opens nothing could not tell apart.
 *
 * Roles are granted by name here, the way [WorkspaceVisibilityTest] grants them:
 * a caller holding `ROLE_SUPPORT` holds the role called `support`.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
class WorkspaceAdminRoleTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val roles: RoleRepository,
    @Autowired val issues: IssueRepository,
    @Autowired val observers: IssueObserverRepository,
    @Autowired val news: IssueNewsRepository,
    @Autowired val users: AppUserRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var supportId: Long = 0
    private var billingId: Long = 0
    private var supportRoleId: Long = 0
    private var billingRoleId: Long = 0
    private var eveId: Long = 0

    @BeforeEach
    fun seed() {
        news.deleteAll()
        observers.deleteAll()
        issues.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        users.deleteAll()
        // Everything but the built-in role, which is this installation's and not
        // any test's to remove.
        roles.deleteAll(roles.findAll().filterNot { it.builtin })

        val support = roles.save(Role(name = "support"))
        val billing = roles.save(Role(name = "billing"))
        supportRoleId = requireNotNull(support.id)
        billingRoleId = requireNotNull(billing.id)

        /*
         * One role opens and administers support; the same person's other role
         * only opens billing. That asymmetry is the whole fixture: without it
         * every refusal below could be explained by not being able to see the
         * workspace, and the test would prove nothing about administering it.
         */
        supportId = requireNotNull(
            workspaces.save(
                Workspace(
                    name = "support",
                    roles = mutableSetOf(support),
                    adminRoles = mutableSetOf(support),
                ),
            ).id,
        )
        billingId = requireNotNull(
            workspaces.save(Workspace(name = "billing", roles = mutableSetOf(billing))).id,
        )

        eveId = requireNotNull(
            users.save(AppUser(username = "eve", displayName = "Eve", type = UserType.INTERNAL)).id,
        )
        users.save(AppUser(username = "dana", displayName = "Dana", type = UserType.INTERNAL))
    }

    private fun issue(workspace: Long, number: Int = 1) = requireNotNull(
        issues.save(
            Issue(workspaceId = workspace, number = number, title = "The reply is late", reporter = "alice"),
        ).id,
    )

    private fun rename(workspace: Long, to: String) = graphQlTester.document(
        """mutation { updateWorkspace(id: $workspace, input: { name: "$to" }) { id name } }""",
    ).execute()

    // ---- What the role grants, in the workspace it was granted for ----

    @Test
    @WithMockUser(username = "dana", roles = ["SUPPORT", "BILLING"])
    fun `a workspace administrator renames the workspace they administer`() {
        rename(supportId, "support desk")
            .path("updateWorkspace.name").entity(String::class.java).isEqualTo("support desk")

        assertThat(requireNotNull(workspaces.findByIdOrNull(supportId)).name).isEqualTo("support desk")
    }

    @Test
    @WithMockUser(username = "dana", roles = ["SUPPORT", "BILLING"])
    fun `a workspace administrator puts somebody else on one of their issues`() {
        val id = issue(supportId)

        graphQlTester.document(
            """mutation { observeIssue(id: $id, observerKind: USER, observerId: "$eveId") {
                 observers { name addedBy }
               } }""",
        ).execute()
            .path("observeIssue.observers[0].name").entity(String::class.java).isEqualTo("Eve")
            .path("observeIssue.observers[0].addedBy").entity(String::class.java).isEqualTo("dana")
    }

    @Test
    @WithMockUser(username = "dana", roles = ["SUPPORT", "BILLING"])
    fun `the interface is told which workspaces they administer, and it is not all of them`() {
        // One boolean per workspace rather than one per person, which is exactly
        // the thing option B could not have said.
        graphQlTester.document("""query { workspaces { content { name administered } } }""")
            .execute()
            .path("workspaces.content[*].name").entityList(String::class.java)
            .containsExactly("billing", "support")
            .path("workspaces.content[*].administered").entityList(Boolean::class.java)
            .containsExactly(false, true)
    }

    @Test
    @WithMockUser(username = "alice", roles = ["ADMINS"])
    fun `an installation administrator administers every workspace without being named on one`() {
        assertThat(requireNotNull(workspaces.findByIdOrNull(billingId)).adminRoles).isEmpty()

        graphQlTester.document("""query { workspaces { content { name administered } } }""")
            .execute()
            .path("workspaces.content[*].administered").entityList(Boolean::class.java)
            .containsExactly(true, true)

        rename(billingId, "billing desk")
            .path("updateWorkspace.name").entity(String::class.java).isEqualTo("billing desk")
    }

    // ---- The same person, the workspace they only work in ----

    @Test
    @WithMockUser(username = "dana", roles = ["SUPPORT", "BILLING"])
    fun `they cannot rename the workspace they only work in`() {
        rename(billingId, "billing desk")
            .errors().expect { it.message?.startsWith("This action needs a role that administers billing") == true }
            .verify()

        assertThat(requireNotNull(workspaces.findByIdOrNull(billingId)).name).isEqualTo("billing")
    }

    @Test
    @WithMockUser(username = "dana", roles = ["SUPPORT", "BILLING"])
    fun `they cannot put somebody on an issue in the workspace they only work in`() {
        val id = issue(billingId)

        graphQlTester.document(
            """mutation { observeIssue(id: $id, observerKind: USER, observerId: "$eveId") { id } }""",
        ).execute()
            .errors().expect { it.message?.startsWith("This action needs a role that administers billing") == true }
            .verify()

        assertThat(observers.findAll()).isEmpty()
    }

    @Test
    @WithMockUser(username = "dana", roles = ["SUPPORT", "BILLING"])
    fun `a move needs both ends, so administering only the source is not enough`() {
        val moving = issue(supportId)

        graphQlTester.document(
            """mutation { moveIssue(id: $moving, workspaceId: $billingId) { id } }""",
        ).execute()
            .errors().expect { it.message?.startsWith("This action needs a role that administers billing") == true }
            .verify()

        assertThat(requireNotNull(issues.findByIdOrNull(moving)).workspaceId).isEqualTo(supportId)
    }

    @Test
    @WithMockUser(username = "dana", roles = ["SUPPORT", "BILLING"])
    fun `and administering only the destination is not enough either`() {
        val moving = issue(billingId)

        graphQlTester.document(
            """mutation { moveIssue(id: $moving, workspaceId: $supportId) { id } }""",
        ).execute()
            .errors().expect { it.message?.startsWith("This action needs a role that administers billing") == true }
            .verify()

        assertThat(requireNotNull(issues.findByIdOrNull(moving)).workspaceId).isEqualTo(billingId)
    }

    @Test
    @WithMockUser(username = "dana", roles = ["SUPPORT", "BILLING"])
    fun `administering both ends moves the issue`() {
        // Billing administered too, which is the one thing the two tests above
        // were missing - so what they proved was the missing half and not some
        // other refusal standing in the way.
        val billing = requireNotNull(workspaces.findByIdOrNull(billingId))
        billing.adminRoles = mutableSetOf(requireNotNull(roles.findByIdOrNull(billingRoleId)))
        workspaces.save(billing)

        val moving = issue(supportId)
        graphQlTester.document(
            """mutation { moveIssue(id: $moving, workspaceId: $billingId) { id workspaceId } }""",
        ).execute()
            .path("moveIssue.workspaceId").entity(Long::class.java).isEqualTo(billingId)
    }

    // ---- A workspace they administer somewhere else entirely ----

    @Test
    @WithMockUser(username = "carol", roles = ["SUPPORT"])
    fun `a workspace they cannot see reads as not there rather than as refused`() {
        // Carol administers support and has never heard of billing. Being told
        // "you may not administer that" would confirm the id is a real one, so
        // the answer is the one every other query gives for an id that is not
        // theirs.
        rename(billingId, "billing desk")
            .errors().expect { it.message == "That does not exist, or you do not have access to it" }
            .verify()
    }

    // ---- What the role does not grant, anywhere ----

    @Test
    @WithMockUser(username = "dana", roles = ["SUPPORT", "BILLING"])
    fun `they cannot edit the role list of the workspace they administer`() {
        graphQlTester.document(
            """mutation { updateWorkspace(
                 id: $supportId,
                 input: { name: "support", roleIds: [$supportRoleId, $billingRoleId] }
               ) { id } }""",
        ).execute()
            .errors().expect { it.message == "This action requires the administrator role" }
            .verify()

        assertThat(requireNotNull(workspaces.findByIdOrNull(supportId)).roles.map { it.id })
            .containsExactly(supportRoleId)
    }

    @Test
    @WithMockUser(username = "dana", roles = ["SUPPORT", "BILLING"])
    fun `nor hand the role that administers it to somebody else`() {
        graphQlTester.document(
            """mutation { updateWorkspace(
                 id: $supportId,
                 input: { name: "support", adminRoleIds: [] }
               ) { id } }""",
        ).execute()
            .errors().expect { it.message == "This action requires the administrator role" }
            .verify()

        assertThat(requireNotNull(workspaces.findByIdOrNull(supportId)).adminRoles.map { it.id })
            .containsExactly(supportRoleId)
    }

    @Test
    @WithMockUser(username = "dana", roles = ["SUPPORT", "BILLING"])
    fun `sending back the list they were shown is not editing it`() {
        // The settings form loads the workspace and posts what it loaded, so a
        // workspace administrator who changed only the name is sending the
        // current lists and means nothing by it.
        graphQlTester.document(
            """mutation { updateWorkspace(
                 id: $supportId,
                 input: { name: "support desk", roleIds: [$supportRoleId], adminRoleIds: [$supportRoleId] }
               ) { name } }""",
        ).execute()
            .path("updateWorkspace.name").entity(String::class.java).isEqualTo("support desk")
    }

    @Test
    @WithMockUser(username = "dana", roles = ["SUPPORT", "BILLING"])
    fun `they cannot create or delete a workspace, which is not a workspace's own business`() {
        graphQlTester.document("""mutation { createWorkspace(input: { name: "theirs" }) { id } }""")
            .execute()
            .errors().expect { it.message == "This action requires the administrator role" }
            .verify()

        graphQlTester.document("""mutation { deleteWorkspace(id: $supportId) }""")
            .execute()
            .errors().expect { it.message == "This action requires the administrator role" }
            .verify()

        assertThat(workspaces.findAll().map { it.name }).containsExactlyInAnyOrder("support", "billing")
    }

    @Test
    @WithMockUser(username = "dana", roles = ["SUPPORT", "BILLING"])
    fun `nor reach anything installation-wide from the workspace they administer`() {
        // One per family rather than one per mutation: what is being checked is
        // that administering a workspace is not a way into the Admin section,
        // and every one of these is guarded by the same `requireAdmin`.
        val refused = listOf(
            """mutation { createRole(input: { name: "theirs" }) { id } }""",
            """mutation { createUser(input: { username: "mallory", displayName: "Mallory" }) { id } }""",
            """mutation { createConnection(input: { name: "theirs", type: SLACK, url: "https://slack.invalid" }) { id } }""",
            """mutation { createProxyRule(
                 input: { name: "theirs", pattern: "*.invalid", proxyHost: "proxy.invalid", proxyPort: 3128 }
               ) { id } }""",
        )

        refused.forEach { document ->
            graphQlTester.document(document).execute()
                .errors().expect { it.message == "This action requires the administrator role" }
                .verify()
        }
    }

    // ---- The invariant between the two lists ----

    @Test
    @WithMockUser(username = "alice", roles = ["ADMINS"])
    fun `a role cannot administer a workspace it is not assigned to`() {
        graphQlTester.document(
            """mutation { updateWorkspace(
                 id: $supportId,
                 input: { name: "support", roleIds: [$supportRoleId], adminRoleIds: [$billingRoleId] }
               ) { id } }""",
        ).execute()
            .errors().expect { it.message?.startsWith("billing cannot administer this workspace") == true }
            .verify()

        assertThat(requireNotNull(workspaces.findByIdOrNull(supportId)).adminRoles.map { it.id })
            .containsExactly(supportRoleId)
    }

    @Test
    @WithMockUser(username = "alice", roles = ["ADMINS"])
    fun `adding a role and marking it administering in one save is allowed`() {
        graphQlTester.document(
            """mutation { updateWorkspace(
                 id: $supportId,
                 input: {
                   name: "support",
                   roleIds: [$supportRoleId, $billingRoleId],
                   adminRoleIds: [$supportRoleId, $billingRoleId]
                 }
               ) { id } }""",
        ).execute().path("updateWorkspace.id").hasValue()

        assertThat(requireNotNull(workspaces.findByIdOrNull(supportId)).adminRoles.map { it.name })
            .containsExactlyInAnyOrder("support", "billing")

        // And it is written down, because how a workspace came to be somebody's
        // is the line to read out of the log a year later.
        assertThat(audit.findAll().map { it.message })
            .anySatisfy { assertThat(it).isEqualTo("Workspace administered by billing, support") }
    }
}
