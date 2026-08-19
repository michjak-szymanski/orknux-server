package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.server.agent.AgentTool
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.ObjectMapper

/**
 * What a model can find out about a workspace's tools.
 *
 * The same ground as [OrknuxFunctionToolsTest] and for the same reasons, with
 * one that is particular to tools: before these existed, a question asked on the
 * tool editor was answered by hunting through the functions and then refusing,
 * because the only code these tools could read was a function's. So what is
 * checked here is that a tool is readable at all, that what comes back is the
 * TypeScript somebody wrote, and that the workspace is a boundary.
 */
@SpringBootTest
class OrknuxToolToolsTest(
    @Autowired val tools: OrknuxTools,
    @Autowired val agentTools: AgentToolRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private var elsewhereId: Long = 0

    @BeforeEach
    fun reset() {
        agentTools.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        elsewhereId = requireNotNull(workspaces.save(Workspace(name = "billing")).id)
    }

    private fun scope() = OrknuxScope(workspaceId = workspaceId)

    private fun store(workspace: Long, name: String) = agentTools.save(
        AgentTool(
            workspaceId = workspace,
            name = name,
            description = "Raises the ticket when somebody has to do something.",
            source = "export default async function $name(input) { return { key: input.summary }; }",
            typescript =
            "export default async function $name(input: { summary: string }) { return { key: input.summary }; }",
        ),
    )

    @Test
    fun `the list says what each tool is for`() {
        store(workspaceId, "raiseJiraIssue")
        store(elsewhereId, "chargeCard")

        val answer = tools.run(scope(), "orknux_tools", "{}")

        assertThat(answer).contains("raiseJiraIssue").contains("somebody has to do something")
        // Another workspace's code is not this workspace's business.
        assertThat(answer).doesNotContain("chargeCard")
    }

    @Test
    fun `one tool comes back with the source somebody wrote`() {
        store(workspaceId, "raiseJiraIssue")

        val answer = tools.run(scope(), "orknux_tool", """{"tool":"raiseJiraIssue"}""")

        // The annotated half: what the editor holds, and what a suggestion has
        // to be written against.
        assertThat(answer).contains("summary: string")
        assertThat(answer).contains("typescript")
    }

    @Test
    fun `a tool is found by the id the address carries`() {
        val held = store(workspaceId, "raiseJiraIssue")

        // The case the reporter hit: standing on /tools/<id> and asking for
        // help, which used to be answered by looking for a function of that id.
        val answer = tools.run(scope(), "orknux_tool", """{"tool":"${held.id}"}""")

        assertThat(answer).contains("raiseJiraIssue")
    }

    @Test
    fun `suggesting a change to a tool is only offered where somebody can accept it`() {
        val watched = tools.specs(OrknuxScope(workspaceId = workspaceId, mayWrite = true, watched = true))
        val unwatched = tools.specs(OrknuxScope(workspaceId = workspaceId, mayWrite = true))

        assertThat(watched.map { it.name }).contains("orknux_suggest_tool_code")
        // An agent inside a workflow has nobody to ask.
        assertThat(unwatched.map { it.name }).doesNotContain("orknux_suggest_tool_code")
        // Reading one is not an offer, so it is there either way.
        assertThat(unwatched.map { it.name }).contains("orknux_tool", "orknux_tools")
    }

    @Test
    fun `a tool suggestion names the tool and carries the code, and saves nothing`() {
        val held = store(workspaceId, "raiseJiraIssue")
        val proposed = "export default async function raiseJiraIssue(input: { summary: string }) { return null; }"

        val scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true, watched = true)
        val arguments = mapper.writeValueAsString(
            mapOf("tool" to "raiseJiraIssue", "code" to proposed, "note" to "Raises nothing."),
        )

        val offered = tools.toolSuggestionIn(scope, arguments)
        assertThat(offered?.toolId).isEqualTo(held.id)
        assertThat(offered?.code).isEqualTo(proposed)
        assertThat(offered?.note).isEqualTo("Raises nothing.")

        val told = tools.run(scope, "orknux_suggest_tool_code", arguments)
        assertThat(told).contains("shown").contains("raiseJiraIssue")

        // The point of the whole thing: the tool is untouched until somebody
        // accepts it.
        assertThat(agentTools.findById(requireNotNull(held.id)).get().typescript).isEqualTo(held.typescript)
    }

    @Test
    fun `a tool in another workspace is not there to be read or rewritten`() {
        val other = store(elsewhereId, "chargeCard")
        val scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true, watched = true)

        assertThat(tools.run(scope(), "orknux_tool", """{"tool":"chargeCard"}"""))
            .contains("There is no tool called chargeCard here")
        assertThat(tools.run(scope(), "orknux_tool", """{"tool":"${other.id}"}"""))
            .contains("There is no tool called ${other.id} here")
        assertThat(tools.toolSuggestionIn(scope, """{"tool":"chargeCard","code":"export default 1;"}""")).isNull()
    }
}
