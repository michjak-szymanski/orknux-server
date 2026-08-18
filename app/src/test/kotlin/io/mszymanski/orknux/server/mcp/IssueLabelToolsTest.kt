package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.server.issue.Issue
import io.mszymanski.orknux.server.issue.IssueRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser

/**
 * Labels as something the tools can work with.
 *
 * The tracker uses them for priority - `p1` and down - so an assistant that
 * cannot filter by one cannot tell what is urgent, and one that can only
 * replace the whole set will eventually drop somebody else's.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class IssueLabelToolsTest(
    @Autowired val tools: IssueTools,
    @Autowired val issues: IssueRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var scope: OrknuxScope

    @BeforeEach
    fun reset() {
        issues.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true)

        file(1, "The reply is late", "p1", "slack")
        file(2, "Sort the issues", "p3")
        file(3, "Tidy the docs", "p1")
    }

    private fun file(number: Int, title: String, vararg labels: String) {
        issues.save(
            Issue(
                workspaceId = workspaceId,
                number = number,
                title = title,
                reporter = "alice",
                labels = labels.toMutableSet(),
            ),
        )
    }

    @Test
    fun `a label narrows the list, and two labels narrow it further`() {
        assertThat(tools.list(scope, """{"labels": "p1"}""")).contains("The reply is late", "Tidy the docs")
        assertThat(tools.list(scope, """{"labels": "p1"}""")).doesNotContain("Sort the issues")

        // Every label, not any: the urgent Slack one, not everything urgent.
        val both = tools.list(scope, """{"labels": "p1, slack"}""")
        assertThat(both).contains("The reply is late")
        assertThat(both).doesNotContain("Tidy the docs")
    }

    /**
     * Filing one, which the tools could not do.
     *
     * An assistant that finds something and can only describe it in a
     * conversation is one whose findings depend on somebody else writing
     * them down - which is the failure the tracker exists to prevent.
     */
    @Test
    fun `an issue can be opened, numbered after the last`() {
        val answer = tools.open(scope, """{"title": "The webhook answers 500", "labels": "p1, slack"}""")

        assertThat(answer).contains("\"issue\":4")
        val made = issues.findByWorkspaceIdAndNumber(workspaceId, 4)
        assertThat(made?.title).isEqualTo("The webhook answers 500")
        assertThat(made?.reporter).isEqualTo("alice")
        assertThat(made?.labels).containsExactlyInAnyOrder("p1", "slack")
        // Nobody, deliberately: handing work to a person is not an
        // assistant's judgement to make.
        assertThat(made?.assignee).isNull()
    }

    @Test
    fun `an issue with no title is refused rather than filed blank`() {
        assertThat(tools.open(scope, """{"description": "no title here"}""")).contains("error")
        assertThat(issues.findByWorkspaceIdAndNumber(workspaceId, 4)).isNull()
    }

    @Test
    fun `the labels in use are counted, commonest first`() {
        val answer = tools.labels(scope)
        assertThat(answer).contains("\"label\":\"p1\",\"issues\":2")
        assertThat(answer.indexOf("p1")).isLessThan(answer.indexOf("p3"))
    }

    @Test
    fun `a label can be added and taken off without disturbing the rest`() {
        tools.update(scope, """{"issue": 1, "add_labels": "waiting"}""")
        assertThat(issues.findByWorkspaceIdAndNumber(workspaceId, 1)?.labels)
            .containsExactlyInAnyOrder("p1", "slack", "waiting")

        tools.update(scope, """{"issue": 1, "remove_labels": "P1"}""")
        assertThat(issues.findByWorkspaceIdAndNumber(workspaceId, 1)?.labels)
            .containsExactlyInAnyOrder("slack", "waiting")
    }
}
