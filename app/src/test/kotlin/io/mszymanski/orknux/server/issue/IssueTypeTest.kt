package io.mszymanski.orknux.server.issue

import io.mszymanski.orknux.server.mcp.IssueTools
import io.mszymanski.orknux.server.mcp.OrknuxScope
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
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * What kind of thing an issue is.
 *
 * The tracker had labels, which are free text and a set, and that is exactly
 * why it needed something else: a set cannot say "exactly one", and a label
 * exists only while an issue carries it, so there was nothing for a settings
 * page to administer. These pin the four things that follow from a type being
 * a row instead - it reaches the list, the history, the tools and a move; a
 * workspace can add and rename and delete one; deleting one that issues carry
 * is refused with the number; and an issue nobody has classified stays untyped
 * rather than being told it was always a bug.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class IssueTypeTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val issues: IssueRepository,
    @Autowired val types: IssueTypeRepository,
    @Autowired val events: IssueEventRepository,
    @Autowired val observers: IssueObserverRepository,
    @Autowired val news: IssueNewsRepository,
    @Autowired val issueTools: IssueTools,
    @Autowired val users: AppUserRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var supportId: Long = 0
    private var billingId: Long = 0
    private var bug: Long = 0
    private var feature: Long = 0

    @BeforeEach
    fun reset() {
        news.deleteAll()
        events.deleteAll()
        observers.deleteAll()
        issues.deleteAll()
        types.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        users.deleteAll()

        supportId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        billingId = requireNotNull(workspaces.save(Workspace(name = "billing")).id)
        users.save(AppUser(username = "alice", displayName = "Alice", type = UserType.INTERNAL))

        bug = requireNotNull(types.save(IssueType(workspaceId = supportId, name = "bug")).id)
        feature = requireNotNull(types.save(IssueType(workspaceId = supportId, name = "feature")).id)
    }

    private fun file(title: String, typeId: Long? = null, workspace: Long = supportId): Long {
        val typed = typeId?.let { ", typeId: $it" }.orEmpty()
        return graphQlTester.document(
            """mutation { createIssue(input: { workspaceId: $workspace, title: "$title"$typed }) { id } }""",
        ).execute().path("createIssue.id").entity(Long::class.java).get()
    }

    private fun titles(filter: String = "", order: String = ""): List<String> =
        graphQlTester.document(
            "{ workspaceIssues(workspaceId: $supportId, status: null, size: 50$filter$order) " +
                "{ content { title type { name } } } }",
        ).execute().path("workspaceIssues.content[*].title").entityList(String::class.java).get()

    private fun scope() = OrknuxScope(workspaceId = supportId, mayWrite = true)

    // ---- the catalogue -------------------------------------------------

    @Test
    fun `a new workspace begins with bug and feature`() {
        val made = graphQlTester
            .document("""mutation { createWorkspace(input: { name: "onboarding" }) { id } }""")
            .execute().path("createWorkspace.id").entity(Long::class.java).get()

        assertThat(types.findByWorkspaceIdOrderByNameAsc(made).map { it.name })
            .containsExactly("bug", "feature")
    }

    @Test
    fun `a workspace adds a type, renames it, and the issues carrying it follow`() {
        val typed = file("The reply is late", typeId = bug)

        graphQlTester.document("""mutation { createIssueType(workspaceId: $supportId, name: "chore") { name issues } }""")
            .execute()
            .path("createIssueType.name").entity(String::class.java).isEqualTo("chore")
            .path("createIssueType.issues").entity(Int::class.java).isEqualTo(0)

        graphQlTester.document("""mutation { renameIssueType(id: $bug, name: "defect") { name issues } }""")
            .execute()
            .path("renameIssueType.name").entity(String::class.java).isEqualTo("defect")
            // The count is what the settings page shows, and a rename does not
            // move an issue off it.
            .path("renameIssueType.issues").entity(Int::class.java).isEqualTo(1)

        // Nothing was rewritten: the issue points at the row, so it reads the
        // new word without having been saved.
        graphQlTester.document("{ workspaceIssue(workspaceId: $supportId, number: 1) { type { name } } }")
            .execute().path("workspaceIssue.type.name").entity(String::class.java).isEqualTo("defect")
        assertThat(issues.findById(typed).get().type?.name).isEqualTo("defect")

        assertThat(audit.findAll().map { it.message })
            .contains("Issue type chore added", "Issue type bug renamed to defect")
    }

    @Test
    fun `two types differing only in case are one type`() {
        graphQlTester.document("""mutation { createIssueType(workspaceId: $supportId, name: "Bug") { name } }""")
            .execute().errors()
            .expect { it.message?.contains("already files issues as Bug") == true }
            .verify()
    }

    @Test
    fun `a type nothing carries is deleted, and one that issues carry is refused with the count`() {
        file("The reply is late", typeId = bug)
        file("The reply is still late", typeId = bug)

        graphQlTester.document("mutation { deleteIssueType(id: $feature) }")
            .execute().path("deleteIssueType").entity(Boolean::class.java).isEqualTo(true)

        graphQlTester.document("mutation { deleteIssueType(id: $bug) }")
            .execute().errors()
            .expect { it.message == "bug is on 2 issues, so it cannot be deleted. Retype those issues, then delete it." }
            .verify()

        assertThat(types.findByWorkspaceIdOrderByNameAsc(supportId).map { it.name }).containsExactly("bug")
        assertThat(audit.findAll().map { it.message }).contains("Issue type feature deleted")
    }

    @Test
    fun `the catalogue says how many issues carry each type`() {
        file("The reply is late", typeId = bug)
        file("A digest would help", typeId = feature)
        file("Nobody has decided about this one")

        graphQlTester.document("{ workspaceIssueTypes(workspaceId: $supportId) { name issues } }")
            .execute()
            .path("workspaceIssueTypes[*].name").entityList(String::class.java).containsExactly("bug", "feature")
            .path("workspaceIssueTypes[*].issues").entityList(Int::class.java).containsExactly(1, 1)
    }

    @Test
    fun `a workspace holding typed issues is deleted, on either engine`() {
        file("The reply is late", typeId = bug)
        file("A digest would help", typeId = feature)

        /*
         * The #169 shape, and the reason the foreign key says SET NULL rather
         * than the RESTRICT the product behaves like. Deleting a workspace
         * cascades into the issues and into the types, and SQLite enforces a
         * foreign key the moment a row goes rather than at the end of the
         * statement - so a RESTRICT here would delete a workspace on Postgres
         * every time and refuse on the engine orknux-one ships with, depending
         * on which of the two cascades ran first.
         */
        graphQlTester.document("mutation { deleteWorkspace(id: $supportId) }")
            .execute().path("deleteWorkspace").entity(Boolean::class.java).isEqualTo(true)

        assertThat(workspaces.findById(supportId)).isEmpty
        assertThat(types.findByWorkspaceIdOrderByNameAsc(supportId)).isEmpty()
        assertThat(issues.findAll().filter { it.workspaceId == supportId }).isEmpty()
    }

    // ---- the issue -----------------------------------------------------

    @Test
    fun `an issue filed without a type is untyped, and untyped is what it says`() {
        file("Nobody has decided about this one")

        graphQlTester.document("{ workspaceIssue(workspaceId: $supportId, number: 1) { type { name } } }")
            .execute().path("workspaceIssue.type").valueIsNull()
    }

    @Test
    fun `a type of another workspace is refused rather than read as untyped`() {
        val theirs = requireNotNull(types.save(IssueType(workspaceId = billingId, name = "incident")).id)

        graphQlTester.document(
            """mutation { createIssue(input: { workspaceId: $supportId, title: "Wrong", typeId: $theirs }) { id } }""",
        ).execute().errors().expect { it.message?.contains("No issue type with id $theirs") == true }.verify()
    }

    @Test
    fun `an empty type clears it, and an absent one leaves it alone`() {
        val id = file("The reply is late", typeId = bug)

        graphQlTester.document("""mutation { updateIssue(id: $id, input: { title: "The reply is late still" }) { type { name } } }""")
            .execute().path("updateIssue.type.name").entity(String::class.java).isEqualTo("bug")

        graphQlTester.document("""mutation { updateIssue(id: $id, input: { typeId: "" }) { type { name } } }""")
            .execute().path("updateIssue.type").valueIsNull()
    }

    // ---- the list ------------------------------------------------------

    @Test
    fun `the list filters by type, by untyped, and by neither`() {
        file("The reply is late", typeId = bug)
        file("A digest would help", typeId = feature)
        file("Nobody has decided about this one")

        assertThat(titles(", typeId: \"$bug\"")).containsExactly("The reply is late")
        // The empty string is the untyped ones, which is the question a tracker
        // gets asked and the one a nullable id could not put.
        assertThat(titles(", typeId: \"\"")).containsExactly("Nobody has decided about this one")
        assertThat(titles()).hasSize(3)
    }

    @Test
    fun `sorting by type keeps the untyped issues in the list, at the end`() {
        file("A digest would help", typeId = feature)
        file("Nobody has decided about this one")
        file("The reply is late", typeId = bug)

        // The one worth the test: a sort across a nullable association is a
        // left join or it is a filter, and a filter here would silently drop
        // every issue nobody has classified - which is the majority of an old
        // tracker on the day this ships.
        assertThat(titles(order = ", order: TYPE, ascending: true"))
            .containsExactly("The reply is late", "A digest would help", "Nobody has decided about this one")
        assertThat(titles(order = ", order: TYPE, ascending: false"))
            .containsExactly("A digest would help", "The reply is late", "Nobody has decided about this one")
    }

    @Test
    fun `a type that names nothing here is refused rather than answered with an empty tracker`() {
        file("The reply is late", typeId = bug)

        graphQlTester.document("{ workspaceIssues(workspaceId: $supportId, typeId: \"999999\") { totalElements } }")
            .execute().errors().expect { it.message?.contains("No issue type with id 999999") == true }.verify()
    }

    // ---- the history ---------------------------------------------------

    @Test
    fun `typing, retyping and untyping are each written down, by name`() {
        val id = file("The reply is late")

        graphQlTester.document("""mutation { updateIssue(id: $id, input: { typeId: "$bug" }) { id } }""").execute()
        graphQlTester.document("""mutation { updateIssue(id: $id, input: { typeId: "$feature" }) { id } }""").execute()
        graphQlTester.document("""mutation { updateIssue(id: $id, input: { typeId: "" }) { id } }""").execute()
        // And a save that changes nothing writes nothing, because the page
        // posts the whole form every time.
        graphQlTester.document("""mutation { updateIssue(id: $id, input: { typeId: "" }) { id } }""").execute()

        val typed = events.findAll().filter { it.kind == IssueEventKind.TYPE }.sortedBy { it.id }
        assertThat(typed.map { it.was to it.became })
            .containsExactly(null to "bug", "bug" to "feature", "feature" to null)
        assertThat(typed.map { it.actor }).containsOnly("alice")
    }

    @Test
    fun `the history keeps the word the type had at the time`() {
        val id = file("The reply is late")
        graphQlTester.document("""mutation { updateIssue(id: $id, input: { typeId: "$bug" }) { id } }""").execute()
        graphQlTester.document("""mutation { renameIssueType(id: $bug, name: "defect") { id } }""").execute()

        // The issue reads `defect` now; March still says `bug`, which is what
        // happened. A history that pointed at the row would rewrite the past.
        assertThat(events.findAll().first { it.kind == IssueEventKind.TYPE }.became).isEqualTo("bug")
    }

    // ---- moving between workspaces --------------------------------------

    @Test
    fun `a move is refused where the destination does not file that type`() {
        val id = file("The reply is late", typeId = bug)

        graphQlTester.document("mutation { moveIssue(id: $id, workspaceId: $billingId) { id } }")
            .execute().errors()
            .expect {
                it.message == "This issue is a bug, and billing does not file those. " +
                    "Add bug to its issue types or change this issue's type, then move it."
            }
            .verify()
    }

    @Test
    fun `a move re-points the type at the destination's own row of the same name`() {
        val theirs = requireNotNull(types.save(IssueType(workspaceId = billingId, name = "Bug")).id)
        val id = file("The invoice is late", typeId = bug)

        graphQlTester.document("mutation { moveIssue(id: $id, workspaceId: $billingId) { type { name } } }")
            .execute().path("moveIssue.type.name").entity(String::class.java).isEqualTo("Bug")

        // The row, not the word: the issue is now on billing's catalogue, so
        // deleting support's `bug` is no longer refused on its account.
        assertThat(issues.findById(id).get().type?.id).isEqualTo(theirs)
    }

    @Test
    fun `an untyped issue moves with nothing in the way`() {
        val id = file("The invoice is late")

        graphQlTester.document("mutation { moveIssue(id: $id, workspaceId: $billingId) { type { name } } }")
            .execute().path("moveIssue.type").valueIsNull()
    }

    // ---- the tools an agent works through --------------------------------

    @Test
    fun `the tools list the types, file with one, and refuse a word nothing is called`() {
        assertThat(issueTools.types(scope()))
            .contains("\"type\":\"bug\"")
            .contains("\"type\":\"feature\"")

        issueTools.open(scope(), """{"title":"The reply is late","type":"Bug"}""")
        assertThat(issues.findByWorkspaceIdAndNumber(supportId, 1)?.type?.name).isEqualTo("bug")

        // Refused with the words that would have worked, so the next call is
        // right rather than being another guess.
        val refused = issueTools.open(scope(), """{"title":"Nope","type":"defect"}""")
        assertThat(refused).contains("There is no issue type called defect here")
        assertThat(refused).contains("bug, feature")

        // Nothing said is untyped, which is the state and not a guess.
        issueTools.open(scope(), """{"title":"Nobody has decided"}""")
        assertThat(issues.findByWorkspaceIdAndNumber(supportId, 2)?.type).isNull()
    }

    @Test
    fun `the tools retype an issue, untype it, and write the history both times`() {
        val id = file("The reply is late")

        issueTools.update(scope(), """{"issue":1,"type":"feature"}""")
        assertThat(issues.findById(id).get().type?.name).isEqualTo("feature")

        issueTools.update(scope(), """{"issue":1,"type":"untyped"}""")
        assertThat(issues.findById(id).get().type).isNull()

        assertThat(events.findAll().filter { it.kind == IssueEventKind.TYPE }.map { it.was to it.became })
            .containsExactly(null to "feature", "feature" to null)
    }

    @Test
    fun `the tools filter the list by type and by untyped`() {
        file("The reply is late", typeId = bug)
        file("Nobody has decided about this one")

        assertThat(issueTools.list(scope(), """{"type":"bug"}"""))
            .contains("The reply is late")
            .doesNotContain("Nobody has decided")
        assertThat(issueTools.list(scope(), """{"type":"untyped"}"""))
            .contains("Nobody has decided")
            .doesNotContain("The reply is late")
    }
}
