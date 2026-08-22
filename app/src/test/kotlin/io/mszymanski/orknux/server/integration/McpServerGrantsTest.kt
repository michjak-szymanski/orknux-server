package io.mszymanski.orknux.server.integration

import io.mszymanski.orknux.connector.connection.McpServerRepository
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.revision.ComponentRevisionKind
import io.mszymanski.orknux.server.revision.ComponentRevisionRepository
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
import java.util.concurrent.atomic.AtomicLong

/**
 * The fourth grant by name, and the one whose delete is not refused.
 *
 * A tool, a skill catalog and a memory catalog are all refused while an agent
 * holds them, because the thing named is the workspace's own and the way out is
 * to go and take the grant off. An MCP server is an address somebody else runs:
 * "the server is gone, so I removed the entry" is housekeeping, and refusing it
 * would stand in the way of a tidy-up that is already right.
 *
 * So the removal is allowed — and it takes the grant with it. What is checked
 * here is that second half. A name left behind on an agent is the re-binding
 * the guard exists to stop: register a server under it again and every agent
 * still holding it is handed whatever now answers there, with nobody having
 * asked for that.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class McpServerGrantsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val servers: McpServerRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val revisions: ComponentRevisionRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    /** A workspace of its own per test; every assertion below is scoped to it. */
    @BeforeEach
    fun reset() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "mcp-grants-${counter.incrementAndGet()}")).id)
    }

    /**
     * The removal goes through, says who it cost, and costs them it.
     *
     * The response names the agents rather than counting them, for the reason
     * the refusals name them: somebody has to be able to see what just lost a
     * capability, and "2 agents" does not say which. The grants are gone
     * afterwards, which is the part that keeps a name from being re-bound.
     */
    @Test
    fun `a server the agents hold is removed, and their grants go with it`() {
        val id = server("zendesk")
        val reviewer = agent("Reviewer", "zendesk", "pagerduty")
        val watcher = agent("Watcher", "zendesk")

        val named = graphQlTester.document("""mutation { removeMcpServer(id: $id) }""")
            .execute()
            .path("removeMcpServer").entityList(String::class.java).get()
        assertThat(named).containsExactlyInAnyOrder("Reviewer", "Watcher")

        assertThat(servers.findAll().filter { it.workspaceId == workspaceId }).isEmpty()
        // The other grant is somebody else's name and stays exactly as it was.
        assertThat(agents.findByIdOrNullChecked(reviewer).mcpServers).containsExactly("pagerduty")
        assertThat(agents.findByIdOrNullChecked(watcher).mcpServers).isEmpty()

        val said = messages()
        assertThat(said).contains("MCP Server zendesk removed")
        assertThat(said).contains("MCP Server zendesk removed from Reviewer")
        assertThat(said).contains("MCP Server zendesk removed from Watcher")
    }

    /**
     * And the change is a version of each agent.
     *
     * An agent has no draft, so a save is a version of it — and losing a grant
     * is a change to the agent, whoever made it happen. A history with a hole
     * exactly where the automation worked is the failure this codebase has had
     * once already, so what the recorder is handed is the state the removal
     * displaced: the grant is still in the snapshot.
     */
    @Test
    fun `each agent that lost the grant has the state it lost recorded`() {
        val id = server("zendesk")
        val reviewer = agent("Reviewer", "zendesk")

        graphQlTester.document("""mutation { removeMcpServer(id: $id) }""").execute()

        val history = revisions.findAll()
            .filter { it.workspaceId == workspaceId && it.kind == ComponentRevisionKind.AGENT }
        assertThat(history).hasSize(1)
        val held = history.single()
        assertThat(held.componentId).isEqualTo(reviewer)
        assertThat(held.name).isEqualTo("Reviewer")
        // What it was *before*: the grant it is about to stop holding.
        assertThat(held.snapshot).contains("zendesk")
        // And the live row carries who did it, the way every other door stamps it.
        assertThat(agents.findByIdOrNullChecked(reviewer).lastModifiedBy).isEqualTo("alice")
    }

    /**
     * The question is about the *name*.
     *
     * An agent in the workspace holds a grant, to a different server, and the
     * removal neither names it nor touches it — which is what proves the query
     * is matching the server being removed rather than finding any agent with
     * any grant at all. Nothing is versioned either: an agent that did not
     * change did not get a new version.
     */
    @Test
    fun `an agent holding a different grant is untouched`() {
        server("zendesk")
        val spare = server("pagerduty")
        val reviewer = agent("Reviewer", "zendesk")

        graphQlTester.document("""mutation { removeMcpServer(id: $spare) }""")
            .execute()
            .path("removeMcpServer").entityList(String::class.java).hasSize(0)

        assertThat(agents.findByIdOrNullChecked(reviewer).mcpServers).containsExactly("zendesk")
        assertThat(revisions.findAll().filter { it.workspaceId == workspaceId }).isEmpty()
        assertThat(messages()).doesNotContain("MCP Server pagerduty removed from Reviewer")
    }

    /** A server nobody was granted removes as it always did, and says nobody was. */
    @Test
    fun `a server nobody was granted still removes cleanly`() {
        val id = server("unused")

        graphQlTester.document("""mutation { removeMcpServer(id: $id) }""")
            .execute()
            .path("removeMcpServer").entityList(String::class.java).hasSize(0)

        assertThat(servers.findAll().filter { it.workspaceId == workspaceId }).isEmpty()
        assertThat(messages()).contains("MCP Server unused removed")
    }

    /**
     * A rename carries the grants with it.
     *
     * The other door onto the same defect, and the one nobody expects to be
     * destructive. Removing a server is deliberate; renaming is tidying. A
     * rename that touched only the server would leave every agent holding a
     * name matching nothing, and the capability would go without a word.
     */
    @Test
    fun `a rename moves the grant onto every agent holding it`() {
        val id = server("zendesk")
        val reviewer = agent("Reviewer", "zendesk", "pagerduty")
        val watcher = agent("Watcher", "zendesk")

        rename(id, "zendesk-eu")

        assertThat(agents.findByIdOrNullChecked(reviewer).mcpServers).containsExactly("zendesk-eu", "pagerduty")
        assertThat(agents.findByIdOrNullChecked(watcher).mcpServers).containsExactly("zendesk-eu")
        assertThat(messages()).contains(
            "MCP Server zendesk renamed to zendesk-eu",
            "MCP Server zendesk renamed to zendesk-eu for Reviewer",
            "MCP Server zendesk renamed to zendesk-eu for Watcher",
        )
    }

    /**
     * Where it stood, not at the end.
     *
     * The grant list is ordered and shown in that order, so a server that
     * jumped to the bottom would send somebody looking for what else had
     * changed. A rename is not a re-grant.
     */
    @Test
    fun `the moved grant keeps its place in the list`() {
        val id = server("zendesk")
        val held = agent("Reviewer", "pagerduty", "zendesk", "statuspage")

        rename(id, "zendesk-eu")

        assertThat(agents.findByIdOrNullChecked(held).mcpServers)
            .containsExactly("pagerduty", "zendesk-eu", "statuspage")
    }

    /**
     * An agent already carrying the new name as a stale grant does not end up
     * holding the same server twice.
     *
     * The server's own name is unique in the workspace; a name left behind on
     * an agent is not, so the collision this guards against is a grant from an
     * earlier rename that was never cleaned up.
     */
    @Test
    fun `following a rename onto a stale grant does not grant it twice`() {
        val id = server("zendesk")
        val held = agent("Reviewer", "zendesk", "zendesk-eu")

        rename(id, "zendesk-eu")

        assertThat(agents.findByIdOrNullChecked(held).mcpServers).containsExactly("zendesk-eu")
    }

    /**
     * And the state each agent lost is recorded, as it is for a revocation.
     *
     * The snapshot has to hold the grant as it was, or the history says the
     * agent always pointed where it now points.
     */
    @Test
    fun `each agent whose grant moved has the state it lost recorded`() {
        val id = server("zendesk")
        val reviewer = agent("Reviewer", "zendesk")

        rename(id, "zendesk-eu")

        val written = revisions.findAll().filter {
            it.kind == ComponentRevisionKind.AGENT && it.componentId == reviewer
        }
        assertThat(written).singleElement().satisfies({ assertThat(it.snapshot).contains("zendesk") })
        assertThat(agents.findByIdOrNullChecked(reviewer).lastModifiedBy).isEqualTo("alice")
    }

    /**
     * An agent holding a different server is untouched, which is what proves
     * the question is about the name.
     */
    @Test
    fun `a rename leaves an agent holding a different server alone`() {
        val id = server("zendesk")
        server("pagerduty")
        val held = agent("Reviewer", "pagerduty")

        rename(id, "zendesk-eu")

        assertThat(agents.findByIdOrNullChecked(held).mcpServers).containsExactly("pagerduty")
        assertThat(revisions.findAll().filter { it.componentId == held }).isEmpty()
        assertThat(messages()).noneMatch { it.contains("for Reviewer") }
    }

    /** Everything else about a server is editable without disturbing a grant. */
    @Test
    fun `an edit that is not a rename moves nothing`() {
        val id = server("zendesk")
        val held = agent("Reviewer", "zendesk")

        graphQlTester.document(
            """mutation { updateMcpServer(id: $id, input: {
                 name: "zendesk", address: "https://mcp.example.com/moved"
               }) { id name } }""",
        ).execute().path("updateMcpServer.name").entity(String::class.java).isEqualTo("zendesk")

        assertThat(agents.findByIdOrNullChecked(held).mcpServers).containsExactly("zendesk")
        assertThat(revisions.findAll().filter { it.componentId == held }).isEmpty()
        assertThat(messages()).contains("MCP Server zendesk updated")
    }

    private fun rename(id: Long, to: String) {
        graphQlTester.document(
            """mutation { updateMcpServer(id: $id, input: {
                 name: "$to", address: "https://mcp.example.com/$to"
               }) { id name } }""",
        ).execute().path("updateMcpServer.name").entity(String::class.java).isEqualTo(to)
    }

    /** Everything said about this test's workspace, and nothing else's. */
    private fun messages() = audit.findAll().filter { it.workspaceId == workspaceId }.map { it.message }

    private fun server(name: String): Long = graphQlTester.document(
        """mutation { createMcpServer(input: {
             workspaceId: $workspaceId, name: "$name", address: "https://mcp.example.com/$name"
           }) { id } }""",
    ).execute().path("createMcpServer.id").entity(Long::class.java).get()

    /**
     * An agent holding grants, saved directly.
     *
     * Not through `updateAgent`, which would itself write a version and leave
     * this class unable to say which door recorded what.
     */
    private fun agent(name: String, vararg granted: String): Long = requireNotNull(
        agents.save(
            Agent(
                workspaceId = workspaceId,
                name = name,
                type = AgentType.LLM,
                mcpServers = granted.toMutableList(),
            ),
        ).id,
    )

    private fun AgentRepository.findByIdOrNullChecked(id: Long): Agent = requireNotNull(findById(id).orElse(null))

    private companion object {
        val counter = AtomicLong()
    }
}
