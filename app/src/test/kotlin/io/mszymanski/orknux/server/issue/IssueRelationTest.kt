package io.mszymanski.orknux.server.issue

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
 * Issues linked to issues: made once, read from both ends, taken off from either.
 *
 * What is worth pinning down here is the thing that would fail silently. A link
 * is one row and the tracker draws it on two pages, so every test that matters
 * asks the far end - an issue that says it is blocked while the thing blocking
 * it has never heard of the arrangement is not a wrong answer anybody would
 * notice until they went looking for it.
 *
 * The rest is what the pair rule refuses: the same two issues linked twice
 * wearing different words, an issue linked to itself, and one linked across
 * workspaces where the number it draws would mean something else.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class IssueRelationTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val relations: IssueRelationRepository,
    @Autowired val events: IssueEventRepository,
    @Autowired val issues: IssueRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        relations.deleteAll()
        events.deleteAll()
        issues.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
    }

    private fun file(title: String): Long =
        graphQlTester.document(
            """mutation { createIssue(input: { workspaceId: $workspaceId, title: "$title" }) { id } }""",
        ).execute().path("createIssue.id").entity(Long::class.java).get()

    private fun relate(id: Long, other: Long, kind: String) =
        graphQlTester.document("""mutation { relateIssue(id: $id, otherId: $other, kind: $kind) { number } }""")
            .execute()

    private fun read(number: Int) =
        graphQlTester.document(
            """{ workspaceIssue(workspaceId: $workspaceId, number: $number) {
                 related { id kind number title status linkedBy }
               } }""",
        ).execute()

    @Test
    fun `a link made on one issue is on the other, saying the opposite`() {
        val connector = file("The connector")
        val waiting = file("The thing that waits")

        relate(waiting, connector, "BLOCKED_BY")
            .path("relateIssue.number").entity(Int::class.java).isEqualTo(2)

        read(2)
            .path("workspaceIssue.related[0].kind").entity(String::class.java).isEqualTo("BLOCKED_BY")
            .path("workspaceIssue.related[0].number").entity(Int::class.java).isEqualTo(1)
            // The far end travels with it, so "blocked by #1" can be read
            // without opening #1 to find out whether it is still open.
            .path("workspaceIssue.related[0].title").entity(String::class.java).isEqualTo("The connector")
            .path("workspaceIssue.related[0].status").entity(String::class.java).isEqualTo("OPEN")
            .path("workspaceIssue.related[0].linkedBy").entity(String::class.java).isEqualTo("alice")

        // Nobody wrote this half, and it is here.
        read(1)
            .path("workspaceIssue.related[0].kind").entity(String::class.java).isEqualTo("BLOCKS")
            .path("workspaceIssue.related[0].number").entity(Int::class.java).isEqualTo(2)

        /*
         * One row, and it faces the way the relation reads actively: the
         * blocker blocks. Somebody said "blocked by" and this is what was
         * stored, which is the whole reason neither end can be missing.
         */
        val stored = relations.findAll().single()
        assertThat(stored.kind).isEqualTo(IssueRelationKind.BLOCKS)
        assertThat(stored.issueId).isEqualTo(connector)
        assertThat(stored.otherIssueId).isEqualTo(waiting)
    }

    @Test
    fun `the same pair said twice, either way round, stays one link`() {
        val one = file("The connector")
        val other = file("The thing that waits")

        relate(other, one, "BLOCKED_BY")
        // The same state, asked for again: not an error, for the reason a second
        // press of a watch button is not.
        relate(other, one, "BLOCKED_BY").path("relateIssue.number").hasValue()
        // And the same fact said from the far end, which is the same fact.
        relate(one, other, "BLOCKS").path("relateIssue.number").hasValue()

        assertThat(relations.findAll()).hasSize(1)
    }

    @Test
    fun `a symmetric link is one row whichever end says it`() {
        val one = file("The connector")
        val other = file("The dashboard")

        relate(other, one, "RELATES_TO")
        relate(one, other, "RELATES_TO")

        val stored = relations.findAll().single()
        // Stored with the lower id first, because neither end is the subject of
        // "relates to" and without a rule the pair could be written twice.
        assertThat(stored.issueId).isEqualTo(minOf(one, other))
        assertThat(stored.otherIssueId).isEqualTo(maxOf(one, other))

        read(1).path("workspaceIssue.related[0].kind").entity(String::class.java).isEqualTo("RELATES_TO")
        read(2).path("workspaceIssue.related[0].kind").entity(String::class.java).isEqualTo("RELATES_TO")
    }

    @Test
    fun `a pair already linked another way is refused rather than replaced`() {
        val one = file("The connector")
        val other = file("The thing that waits")
        relate(other, one, "BLOCKED_BY")

        relate(other, one, "DUPLICATES")
            .errors().expect { it.message?.contains("already linked") == true }
            .verify()

        assertThat(relations.findAll().single().kind).isEqualTo(IssueRelationKind.BLOCKS)
    }

    @Test
    fun `an issue cannot be linked to itself, nor to one in another workspace`() {
        val here = file("The connector")
        val elsewhere = requireNotNull(workspaces.save(Workspace(name = "billing")).id)
        val there = graphQlTester.document(
            """mutation { createIssue(input: { workspaceId: $elsewhere, title: "Somewhere else" }) { id } }""",
        ).execute().path("createIssue.id").entity(Long::class.java).get()

        relate(here, here, "RELATES_TO")
            .errors().expect { it.message?.contains("itself") == true }
            .verify()
        relate(here, there, "RELATES_TO")
            .errors().expect { it.message?.contains("same workspace") == true }
            .verify()

        assertThat(relations.findAll()).isEmpty()
    }

    @Test
    fun `both issues record the link in their history, and both record it going`() {
        val connector = file("The connector")
        val waiting = file("The thing that waits")
        relate(waiting, connector, "BLOCKED_BY")

        assertThat(events.findByIssueIdOrderByAtAscIdAsc(waiting).map { it.kind to it.became })
            .contains(IssueEventKind.LINK to "BLOCKED_BY #1")
        assertThat(events.findByIssueIdOrderByAtAscIdAsc(connector).map { it.kind to it.became })
            .contains(IssueEventKind.LINK to "BLOCKS #2")

        val link = relations.findAll().single().id
        graphQlTester.document("mutation { unrelateIssue(id: $link) }")
            .execute().path("unrelateIssue").entity(Boolean::class.java).isEqualTo(true)

        assertThat(relations.findAll()).isEmpty()
        // Taken off on both sides, and written down on both sides: an issue
        // left saying something the other has forgotten is the failure this
        // whole arrangement exists to prevent.
        assertThat(events.findByIssueIdOrderByAtAscIdAsc(waiting).map { it.kind to it.was })
            .contains(IssueEventKind.LINK to "BLOCKED_BY #1")
        assertThat(events.findByIssueIdOrderByAtAscIdAsc(connector).map { it.kind to it.was })
            .contains(IssueEventKind.LINK to "BLOCKS #2")
        read(1).path("workspaceIssue.related").entityList(Any::class.java).hasSize(0)
        read(2).path("workspaceIssue.related").entityList(Any::class.java).hasSize(0)
    }

    @Test
    fun `deleting an issue takes its links with it`() {
        val one = file("The connector")
        val other = file("The thing that waits")
        relate(other, one, "BLOCKED_BY")

        graphQlTester.document("mutation { deleteIssue(id: $one) }").execute()
            .path("deleteIssue").entity(Boolean::class.java).isEqualTo(true)

        assertThat(relations.findAll()).isEmpty()
        read(2).path("workspaceIssue.related").entityList(Any::class.java).hasSize(0)
    }

    @Test
    fun `the box offering something to link to finds an issue by its number`() {
        val one = file("The connector")
        val two = file("Something else entirely")
        file("The thing that waits")

        // The hash is how people say it, and it has to work: `#2` is the second
        // issue, not the eleven issues with a 2 somewhere in their titles.
        graphQlTester.document("""{ issuesToLink(id: $one, search: "#2") { number title } }""")
            .execute()
            .path("issuesToLink[0].number").entity(Int::class.java).isEqualTo(2)

        // Words still work, and so does the bare number.
        graphQlTester.document("""{ issuesToLink(id: $one, search: "waits") { number } }""")
            .execute()
            .path("issuesToLink[0].number").entity(Int::class.java).isEqualTo(3)

        // Never itself, and never what is already linked.
        relate(one, two, "RELATES_TO")
        graphQlTester.document("""{ issuesToLink(id: $one, search: "") { number } }""")
            .execute()
            .path("issuesToLink").entityList(Any::class.java).hasSize(1)
            .path("issuesToLink[0].number").entity(Int::class.java).isEqualTo(3)
    }
}
