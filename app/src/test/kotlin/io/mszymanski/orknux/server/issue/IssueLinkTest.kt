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
 * Addresses hung on an issue: added, read back as what they are, taken off.
 *
 * Two things here are worth pinning down and the rest is bookkeeping.
 *
 * The first is what is refused. A link is rendered as an anchor on a page other
 * people read, so `javascript:` in an href is a script somebody else runs by
 * clicking what looks like a reference to the bug - which is why the scheme is
 * checked on the way in rather than escaped on the way out, and why a test for
 * it belongs next to the tests for the ordinary case.
 *
 * The second is the GitHub reading, which is by the shape of the address and
 * nothing else: no token, no network call, no cache. So these tests say that
 * `/pull/12` reads as `#12`, and deliberately do not say that pull request 12
 * exists - a number nobody ever opened reads exactly the same, and that is the
 * whole bargain [GitHubAddress] makes.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class IssueLinkTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val links: IssueLinkRepository,
    @Autowired val issues: IssueRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        links.deleteAll()
        issues.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
    }

    private fun file(title: String): Long =
        graphQlTester.document(
            """mutation { createIssue(input: { workspaceId: $workspaceId, title: "$title" }) { id } }""",
        ).execute().path("createIssue.id").entity(Long::class.java).get()

    /** Adds one, and hands back the id of the row it wrote. */
    private fun add(issue: Long, url: String): String {
        graphQlTester.document("""mutation { addIssueLink(id: $issue, url: "$url") { id } }""")
            .execute().path("addIssueLink.id").hasValue()
        return requireNotNull(links.findAll().maxByOrNull { requireNotNull(it.id) }?.id).toString()
    }

    @Test
    fun `an address put on an issue is listed on it, with who added it`() {
        val id = file("The reply is late")

        graphQlTester.document(
            """mutation { addIssueLink(id: $id, url: "https://status.example.com/incidents/4", title: "  The incident  ") {
                 links { url title github addedBy mine }
               } }""",
        ).execute()
            .path("addIssueLink.links[0].url").entity(String::class.java)
            .isEqualTo("https://status.example.com/incidents/4")
            // Trimmed, because a title with a space on each end is a title
            // somebody pasted rather than one they meant.
            .path("addIssueLink.links[0].title").entity(String::class.java).isEqualTo("The incident")
            .path("addIssueLink.links[0].addedBy").entity(String::class.java).isEqualTo("alice")
            // Answered by the server, so the remove button and the refusal agree.
            .path("addIssueLink.links[0].mine").entity(Boolean::class.java).isEqualTo(true)
            // Not GitHub's, so nothing is claimed about it.
            .path("addIssueLink.links[0].github").valueIsNull()

        // And still there when the issue is read again by its number.
        graphQlTester.document("""{ workspaceIssue(workspaceId: $workspaceId, number: 1) { links { title } } }""")
            .execute()
            .path("workspaceIssue.links[0].title").entity(String::class.java).isEqualTo("The incident")

        assertThat(audit.findAll().map { it.message }).anySatisfy { assertThat(it).contains("linked The incident") }
    }

    @Test
    fun `there is no limit on how many an issue carries, and they read in the order they were added`() {
        val id = file("The reply is late")
        (1..5).forEach { add(id, "https://example.com/$it") }

        graphQlTester.document("""{ workspaceIssue(workspaceId: $workspaceId, number: 1) { links { url } } }""")
            .execute()
            .path("workspaceIssue.links").entityList(Any::class.java).hasSize(5)
            .path("workspaceIssue.links[0].url").entity(String::class.java).isEqualTo("https://example.com/1")
            .path("workspaceIssue.links[4].url").entity(String::class.java).isEqualTo("https://example.com/5")
    }

    @Test
    fun `a GitHub address reads the way people say it out loud`() {
        val id = file("The reply is late")

        val said = listOf(
            "https://github.com/anthropics/orknux" to "anthropics/orknux",
            // A clone address is the same repository as the page.
            "https://github.com/anthropics/orknux.git" to "anthropics/orknux",
            "https://github.com/anthropics/orknux/issues/53" to "anthropics/orknux#53",
            // An issue and a pull request read alike because GitHub numbers
            // them from one counter and redirects between the two paths.
            "https://github.com/anthropics/orknux/pull/53" to "anthropics/orknux#53",
            // The tab a pull request was open on is not part of what it is.
            "https://github.com/anthropics/orknux/pull/53/files" to "anthropics/orknux#53",
            "https://github.com/anthropics/orknux/commit/abc1234def5678" to "anthropics/orknux@abc1234",
            "http://www.github.com/anthropics/orknux/issues/7" to "anthropics/orknux#7",
        )
        said.forEach { (url, _) -> add(id, url) }

        val answer = graphQlTester
            .document("""{ workspaceIssue(workspaceId: $workspaceId, number: 1) { links { url github } } }""")
            .execute()
        said.forEachIndexed { at, (url, reads) ->
            answer.path("workspaceIssue.links[$at].url").entity(String::class.java).isEqualTo(url)
            answer.path("workspaceIssue.links[$at].github").entity(String::class.java).isEqualTo(reads)
        }
    }

    @Test
    fun `an address GitHub does not name is left as the address it is`() {
        val id = file("The reply is late")

        // A file inside a repository is not the repository, and calling it
        // "anthropics/orknux" would name the wrong thing; GitHub's own pages
        // are not an account; and another host that copied the layout is
        // another host.
        val unread = listOf(
            "https://github.com/anthropics/orknux/blob/main/README.md",
            "https://github.com/orgs/anthropics/repositories",
            "https://github.com/anthropics",
            "https://gitlab.com/anthropics/orknux/-/issues/53",
            "https://example.com/anthropics/orknux/pull/53",
        )
        unread.forEach { add(id, it) }

        val answer = graphQlTester
            .document("""{ workspaceIssue(workspaceId: $workspaceId, number: 1) { links { url github } } }""")
            .execute()
        unread.forEachIndexed { at, url ->
            answer.path("workspaceIssue.links[$at].url").entity(String::class.java).isEqualTo(url)
            answer.path("workspaceIssue.links[$at].github").valueIsNull()
        }
    }

    @Test
    fun `an address a browser should not be handed is refused`() {
        val id = file("The reply is late")

        // The hazard the check exists for: an anchor on a page other people
        // read, whose href runs their browser rather than going anywhere.
        graphQlTester.document("""mutation { addIssueLink(id: $id, url: "javascript:alert(1)") { id } }""")
            .execute()
            .errors().expect { it.message?.contains("http or https") == true }
            .verify()

        // The same trick with the payload carried inline instead.
        graphQlTester.document("""mutation { addIssueLink(id: $id, url: "data:text/html,<script>alert(1)</script>") { id } }""")
            .execute()
            .errors().expect { it.message?.contains("http or https") == true }
            .verify()

        // Not an address at all, and a hostname on its own is not one either -
        // guessing a scheme onto it would be the tracker deciding where
        // somebody meant to point.
        graphQlTester.document("""mutation { addIssueLink(id: $id, url: "not a link") { id } }""")
            .execute()
            .errors().expect { it.message?.contains("not a web address") == true }
            .verify()

        graphQlTester.document("""mutation { addIssueLink(id: $id, url: "example.com/x") { id } }""")
            .execute()
            .errors().expect { it.message?.contains("not a web address") == true }
            .verify()

        graphQlTester.document("""mutation { addIssueLink(id: $id, url: "   ") { id } }""")
            .execute()
            .errors().expect { it.message?.contains("needs an address") == true }
            .verify()

        // Nothing was written: a refusal that keeps the row is not one.
        assertThat(links.findAll()).isEmpty()
    }

    @Test
    fun `somebody else's link is not yours to remove`() {
        val id = file("The reply is late")
        // Bob's from the start, which is what a second person is - written
        // through the repository because the mutation only ever writes as
        // whoever is signed in, and that is the whole point of this test.
        val his = links.save(
            IssueLink(
                issueId = id,
                url = "https://github.com/anthropics/orknux/pull/53",
                addedBy = "bob",
            ),
        )

        graphQlTester.document("""mutation { removeIssueLink(id: ${his.id}) }""")
            .execute()
            .errors().expect { it.message?.contains("whoever added it") == true }
            .verify()

        // Alice is an administrator here and it made no difference: the link is
        // still on the issue, and shown to her as not hers.
        graphQlTester.document("""{ workspaceIssue(workspaceId: $workspaceId, number: 1) { links { mine } } }""")
            .execute()
            .path("workspaceIssue.links").entityList(Any::class.java).hasSize(1)
            .path("workspaceIssue.links[0].mine").entity(Boolean::class.java).isEqualTo(false)
    }

    @Test
    fun `removing your own link takes it off the issue and says so`() {
        val id = file("The reply is late")
        val link = add(id, "https://github.com/anthropics/orknux/pull/53")

        graphQlTester.document("""mutation { removeIssueLink(id: $link) }""")
            .execute()
            .path("removeIssueLink").entity(Boolean::class.java).isEqualTo(true)

        assertThat(links.findAll()).isEmpty()
        // Said in the words a reader would recognise rather than as the
        // address, which is forty characters of which four matter.
        assertThat(audit.findAll().map { it.message })
            .anySatisfy { assertThat(it).contains("unlinked anthropics/orknux#53") }
    }

    @Test
    fun `deleting an issue takes its links with it`() {
        val id = file("The reply is late")
        add(id, "https://github.com/anthropics/orknux/pull/53")

        graphQlTester.document("""mutation { deleteIssue(id: $id) }""")
            .execute().path("deleteIssue").entity(Boolean::class.java).isEqualTo(true)

        // The database's own cascade rather than anything this code does: a
        // link has no bytes anywhere to tidy up after.
        assertThat(links.findAll()).isEmpty()
    }
}
