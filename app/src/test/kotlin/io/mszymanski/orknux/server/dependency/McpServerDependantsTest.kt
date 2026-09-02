package io.mszymanski.orknux.server.dependency

import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * What uses an MCP server, which is the agents granted it.
 *
 * Issue #318. An MCP server was one of the four kinds nothing was allowed to ask
 * about, on the grounds that nothing points at one. That was never true: an
 * agent names a server in its grants, and removing the server takes the
 * capability away from every agent holding it — silently, since the removal
 * un-grants rather than refusing. So the one page that could have said who would
 * lose something said nothing at all.
 *
 * The grant is stored as a name rather than an id, which is what makes this
 * worth pinning rather than obvious. Two of these are about that: a server
 * renamed carries its dependants with it, because the rename follows the grants;
 * and a server whose name another workspace also uses answers for its own
 * workspace only.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class McpServerDependantsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var elsewhereId: Long = 0

    @BeforeEach
    fun seed() {
        agents.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "Support")).id)
        elsewhereId = requireNotNull(workspaces.save(Workspace(name = "Research")).id)
    }

    @Test
    fun `the agents granted a server are what uses it`() {
        val server = mcpServer(workspaceId, "brave-search")
        val granted = agent(workspaceId, "Searcher", "brave-search")
        agent(workspaceId, "Writer")

        val entries = dependants(server)
        entries.path("componentDependants.entries").entityList(Map::class.java).hasSize(1)
        entries.path("componentDependants.entries[0].kind").entity(String::class.java).isEqualTo("AGENT")
        entries.path("componentDependants.entries[0].id").entity(Long::class.java).isEqualTo(granted)
        entries.path("componentDependants.entries[0].name").entity(String::class.java).isEqualTo("Searcher")
    }

    /**
     * A server nobody was granted answers an empty list rather than a refusal.
     *
     * The whole of what changed: before this the question came back as
     * *nothing points at an MCP server*, which is a shrug, and a screen has to
     * be able to tell a shrug from an answer of none.
     */
    @Test
    fun `a server nobody holds answers that nothing uses it`() {
        val server = mcpServer(workspaceId, "unused")
        agent(workspaceId, "Writer")

        dependants(server).path("componentDependants.entries").entityList(Map::class.java).hasSize(0)
    }

    /**
     * The grant is a name, and a rename follows it — so the answer follows too.
     *
     * This is what the name-rather-than-id storage costs and what it is worth:
     * `updateMcpServer` moves every grant onto the new name, so an agent that
     * still means *that server* still holds it. A list that went by the old name
     * would report the agent as having lost it.
     */
    @Test
    fun `renaming a server keeps the agents that hold it`() {
        val server = mcpServer(workspaceId, "brave-search")
        agent(workspaceId, "Searcher", "brave-search")

        graphQlTester.document(
            // The address goes back unchanged, for the reason the agent's name does
            // above: the input is a statement about the whole server.
            """mutation {
                 updateMcpServer(id: $server, input: { name: "brave", address: "http://127.0.0.1:9/rpc" })
                 { name }
               }""",
        ).execute().path("updateMcpServer.name").entity(String::class.java).isEqualTo("brave")

        dependants(server).path("componentDependants.entries[0].name").entity(String::class.java).isEqualTo("Searcher")
    }

    /**
     * The half a name-matched answer would get wrong.
     *
     * Two workspaces may each register a server called `postgres-db`, and an
     * agent in one of them holds its own. Answering across the workspace would
     * name somebody else's agent on this page, which is both wrong and a leak.
     */
    @Test
    fun `an agent elsewhere holding the same name is not an answer here`() {
        val server = mcpServer(workspaceId, "postgres-db")
        mcpServer(elsewhereId, "postgres-db")
        agent(elsewhereId, "Their Analyst", "postgres-db")

        dependants(server).path("componentDependants.entries").entityList(Map::class.java).hasSize(0)
    }

    private fun dependants(serverId: Long) = graphQlTester.document(
        """
        query {
          componentDependants(kind: MCP_SERVER, componentId: $serverId) {
            entries { kind id name workspaceId published }
            hidden
          }
        }
        """,
    ).execute()

    private fun mcpServer(workspaceId: Long, name: String): Long = graphQlTester.document(
        """
        mutation {
          createMcpServer(input: {
            workspaceId: $workspaceId, name: "$name", address: "http://127.0.0.1:9/rpc"
          }) { id }
        }
        """,
    ).execute().path("createMcpServer.id").entity(Long::class.java).get()

    private fun agent(workspaceId: Long, name: String, vararg servers: String): Long {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()

        if (servers.isNotEmpty()) {
            val held = servers.joinToString(", ") { "\"$it\"" }
            graphQlTester.document(
                // The name goes back unchanged: `UpdateAgentInput` requires it, so an
                // edit of one field is still a statement about the whole agent.
                """mutation { updateAgent(id: $id, input: { name: "$name", mcpServers: [$held] }) { mcpServers } }""",
            ).execute().path("updateAgent.mcpServers").entityList(String::class.java).hasSize(servers.size)
        }
        return id
    }
}
