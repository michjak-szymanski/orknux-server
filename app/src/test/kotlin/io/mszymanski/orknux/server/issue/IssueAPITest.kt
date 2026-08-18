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
 * A workspace's issue tracker: filed, filtered, assigned, commented on.
 *
 * The number is the part worth pinning down. It is per workspace, because "#3"
 * is what people say and what they mean is the third issue here - so two
 * workspaces both have a #1, and the ids underneath differ.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class IssueAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val issues: IssueRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var elsewhereId: Long = 0

    @BeforeEach
    fun reset() {
        issues.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        elsewhereId = requireNotNull(workspaces.save(Workspace(name = "billing")).id)
    }

    private fun file(title: String, labels: String = "[]", workspace: Long = workspaceId): Long =
        graphQlTester.document(
            """mutation { createIssue(input: {
                 workspaceId: $workspace, title: "$title", description: "Something to look at", labels: $labels
               }) { id number status reporter } }""",
        ).execute()
            .path("createIssue.status").entity(String::class.java).isEqualTo("OPEN")
            .path("createIssue.reporter").entity(String::class.java).isEqualTo("alice")
            .path("createIssue.id").entity(Long::class.java).get()

    @Test
    fun `issues are numbered from one within each workspace`() {
        file("The reply is late")
        file("The trigger fires twice")
        file("Billing is wrong", workspace = elsewhereId)

        graphQlTester.document("""{ workspaceIssues(workspaceId: $workspaceId) { totalElements content { number title } } }""")
            .execute()
            .path("workspaceIssues.totalElements").entity(Int::class.java).isEqualTo(2)
            // Newest first: a tracker is read from the top.
            .path("workspaceIssues.content[0].number").entity(Int::class.java).isEqualTo(2)
            .path("workspaceIssues.content[1].number").entity(Int::class.java).isEqualTo(1)

        graphQlTester.document("""{ workspaceIssues(workspaceId: $elsewhereId) { content { number title } } }""")
            .execute()
            // Its own #1, not #3.
            .path("workspaceIssues.content[0].number").entity(Int::class.java).isEqualTo(1)
    }

    @Test
    fun `the search reads the title, the description and the labels`() {
        file("The reply is late", labels = """["slack", "urgent"]""")
        file("Something else entirely")

        graphQlTester.document("""{ workspaceIssues(workspaceId: $workspaceId, search: "slack") { content { title } } }""")
            .execute()
            .path("workspaceIssues.content").entityList(Any::class.java).hasSize(1)
            .path("workspaceIssues.content[0].title").entity(String::class.java).isEqualTo("The reply is late")

        graphQlTester.document("""{ workspaceIssues(workspaceId: $workspaceId, search: "reply") { content { title } } }""")
            .execute()
            .path("workspaceIssues.content").entityList(Any::class.java).hasSize(1)

        // The description too, which both of these share.
        graphQlTester.document("""{ workspaceIssues(workspaceId: $workspaceId, search: "look at") { content { title } } }""")
            .execute()
            .path("workspaceIssues.content").entityList(Any::class.java).hasSize(2)
    }

    @Test
    fun `closing one takes it out of the open list and says so in the audit`() {
        val id = file("The reply is late")

        graphQlTester.document("""mutation { updateIssue(id: $id, input: { status: CLOSED }) { status } }""")
            .execute()
            .path("updateIssue.status").entity(String::class.java).isEqualTo("CLOSED")

        graphQlTester.document("""{ workspaceIssues(workspaceId: $workspaceId, status: OPEN) { totalElements } }""")
            .execute()
            .path("workspaceIssues.totalElements").entity(Int::class.java).isEqualTo(0)

        assertThat(audit.findAll().map { it.message }).anySatisfy { assertThat(it).contains("closed") }
    }

    @Test
    fun `a comment lands on the issue, in order, and an empty one is refused`() {
        val id = file("The reply is late")

        graphQlTester.document("""mutation { commentOnIssue(id: $id, content: "Looking at it now") { comments { author content } } }""")
            .execute()
            .path("commentOnIssue.comments[0].author").entity(String::class.java).isEqualTo("alice")

        graphQlTester.document("""mutation { commentOnIssue(id: $id, content: "Fixed in the trigger") { comments { content } } }""")
            .execute()
            .path("commentOnIssue.comments").entityList(Any::class.java).hasSize(2)
            .path("commentOnIssue.comments[1].content").entity(String::class.java).isEqualTo("Fixed in the trigger")

        graphQlTester.document("""mutation { commentOnIssue(id: $id, content: "   ") { id } }""")
            .execute()
            .errors().expect { it.message?.contains("needs something in it") == true }
            .verify()
    }

    @Test
    fun `a comment can be changed by whoever wrote it, and says that it was`() {
        val id = file("The reply is late")
        val comment = graphQlTester.document(
            """mutation { commentOnIssue(id: $id, content: "Looking") { comments { id editedAt mine } } }""",
        ).execute()
            .path("commentOnIssue.comments[0].mine").entity(Boolean::class.java).isEqualTo(true)
            .path("commentOnIssue.comments[0].editedAt").valueIsNull()
            .path("commentOnIssue.comments[0].id").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation { editIssueComment(id: $comment, content: "Looking, and fixed") { comments { content editedAt } } }""",
        ).execute()
            .path("editIssueComment.comments[0].content").entity(String::class.java).isEqualTo("Looking, and fixed")
            // Marked, so a reader is told it changed.
            .path("editIssueComment.comments[0].editedAt").hasValue()
    }

    @Test
    fun `somebody else's comment is not yours to change`() {
        // Bob's from the start, which is what a second person is - written
        // through the repository because the API only ever writes as whoever
        // is signed in, and that is the whole point of this test.
        val held = issues.save(
            Issue(
                workspaceId = workspaceId,
                number = 1,
                title = "The reply is late",
                reporter = "bob",
                comments = mutableListOf(IssueComment(author = "bob", content = "Mine, not yours")),
            ),
        )
        val comment = requireNotNull(held.comments.first().id)

        graphQlTester.document(
            """mutation { editIssueComment(id: $comment, content: "Not mine") { id } }""",
        ).execute()
            .errors().expect { it.message?.contains("whoever wrote it") == true }
            .verify()
    }

    @Test
    fun `an assignee that is not in this workspace is refused`() {
        val id = file("The reply is late")

        graphQlTester.document(
            """mutation { updateIssue(id: $id, input: { assigneeKind: AGENT, assigneeId: "9999" }) { id } }""",
        ).execute()
            .errors().expect { it.message?.contains("not something in this workspace") == true }
            .verify()
    }

    @Test
    fun `a title is required, and blank does not count`() {
        graphQlTester.document(
            """mutation { createIssue(input: { workspaceId: $workspaceId, title: "   " }) { id } }""",
        ).execute()
            .errors().expect { it.message?.contains("needs a title") == true }
            .verify()
    }
}
