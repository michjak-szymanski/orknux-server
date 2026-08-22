package io.mszymanski.orknux.server.integration

import io.mszymanski.orknux.connector.connection.CreateMcpServerInput
import io.mszymanski.orknux.connector.connection.McpServerService
import io.mszymanski.orknux.connector.connection.McpServerView
import io.mszymanski.orknux.connector.connection.UpdateMcpServerInput
import io.mszymanski.orknux.server.agent.AgentGrants
import io.mszymanski.orknux.server.revision.ComponentRevisionRecorder
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/** The MCP servers a workspace's agents may connect to; the connection module holds them. */
@Controller
class McpServerAPI(
    private val servers: McpServerService,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val grants: AgentGrants,
    private val revisions: ComponentRevisionRecorder,
) {

    @QueryMapping
    fun mcpServers(@Argument workspaceId: Long): List<McpServerView> {
        requireWorkspaceAccess(workspaceId)
        return servers.mcpServers(workspaceId)
    }

    @QueryMapping
    fun mcpServer(@Argument id: Long): McpServerView? =
        servers.mcpServer(id)?.takeIf { access.canSee(it.workspaceId) }

    @MutationMapping
    fun createMcpServer(@Argument input: CreateMcpServerInput): McpServerView {
        requireWorkspaceAccess(input.workspaceId)
        val created = servers.createMcpServer(input)
        auditRecorder.record(input.workspaceId, WorkspaceAuditCategory.INTEGRATION, "MCP Server ${created.name} added")
        return created
    }

    /** Backs the MCP server settings form; a null secret keeps the stored one. */
    @MutationMapping
    fun updateMcpServer(@Argument id: Long, @Argument input: UpdateMcpServerInput): McpServerView {
        val server = servers.mcpServer(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw McpServerNotFoundException(id)

        val updated = servers.updateMcpServer(id, input)
        val message = if (server.name == updated.name) {
            "MCP Server ${updated.name} updated"
        } else {
            "MCP Server ${server.name} renamed to ${updated.name}"
        }
        auditRecorder.record(server.workspaceId, WorkspaceAuditCategory.INTEGRATION, message)
        return updated
    }

    /**
     * Removes the server, and takes the grant off every agent that held it.
     *
     * The fourth grant by name, and the only one whose delete is not refused.
     * [AgentGrants] carries the argument: what is named here is an address
     * somebody else runs, so "the server is gone, so I removed the entry" is
     * ordinary housekeeping and a refusal would get in its way. What is not
     * ordinary is leaving the name behind on the agents. `McpToolCaller` drops a
     * grant matching no server, so the capability goes quietly — and registering
     * a server under that name again hands every agent still holding it whatever
     * now answers there. Clearing the grants is what closes that, and it is why
     * this is a revocation rather than a warning printed over an unchanged
     * agent.
     *
     * The names of those agents come back so that somebody can see what just
     * lost a capability. A list of names rather than an object, deliberately:
     * the field used to be a `Boolean!` and the browser selects it without a
     * sub-selection, so a scalar list is the shape that can say more without
     * breaking the page that asks. Null is still "nothing was removed".
     *
     * Transactional because it is several agents and a server: a failure part
     * way through must not leave some agents cleaned, some not, and the server
     * gone from under all of them.
     */
    @MutationMapping
    @Transactional
    fun removeMcpServer(@Argument id: Long): List<String>? {
        val server = servers.mcpServer(id)?.takeIf { access.canSee(it.workspaceId) } ?: return null
        if (!servers.removeMcpServer(id)) return null

        auditRecorder.record(server.workspaceId, WorkspaceAuditCategory.INTEGRATION, "MCP Server ${server.name} removed")

        val held = grants.toMcpServer(server.workspaceId, server.name)
        held.forEach { agent ->
            // Losing a grant is a change to the agent, so it is a version of the
            // agent: the recorder is handed what the agent is about to stop
            // being, exactly as the settings form hands it. A history with a
            // hole where the automation worked is the failure the tracker's
            // already had once.
            revisions.saved(agent)
            agent.mcpServers.removeAll { it == server.name }
            agent.lastModifiedAt = OffsetDateTime.now()
            agent.lastModifiedBy = currentUser()
            // Worded as the settings form's own removal is, because it is the
            // same thing happening to the agent.
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                "MCP Server ${server.name} removed from ${agent.name}",
            )
        }
        return held.map { it.name }
    }

    /** Hands the stored credentials to the settings form behind the "Reveal" action. */
    @MutationMapping
    fun revealMcpServerSecret(@Argument id: Long): String? {
        val server = servers.mcpServer(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw McpServerNotFoundException(id)

        auditRecorder.record(
            server.workspaceId,
            WorkspaceAuditCategory.INTEGRATION,
            "Credentials for ${server.name} revealed",
        )
        return servers.revealMcpServerSecret(id)
    }

    private fun requireWorkspaceAccess(workspaceId: Long) {
        access.requireVisible(workspaceId)
    }

    /** Whoever is asking, for the stamp a revision of this state will carry. */
    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"
}

class McpServerNotFoundException(id: Long) : RuntimeException("No MCP server with id $id")
