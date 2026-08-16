package io.mszymanski.orknux.server.integration

import io.mszymanski.orknux.connector.connection.CreateMcpServerInput
import io.mszymanski.orknux.connector.connection.McpServerService
import io.mszymanski.orknux.connector.connection.McpServerView
import io.mszymanski.orknux.connector.connection.UpdateMcpServerInput
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceNotFoundException
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

/** The MCP servers a workspace's agents may connect to; the connection module holds them. */
@Controller
class McpServerAPI(
    private val servers: McpServerService,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun mcpServers(@Argument workspaceId: Long): List<McpServerView> {
        requireWorkspaceAccess(workspaceId)
        return servers.mcpServers(workspaceId)
    }

    @QueryMapping
    fun mcpServer(@Argument id: Long): McpServerView? {
        val server = servers.mcpServer(id) ?: return null
        requireWorkspaceAccess(server.workspaceId)
        return server
    }

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
        val server = servers.mcpServer(id) ?: throw McpServerNotFoundException(id)
        requireWorkspaceAccess(server.workspaceId)

        val updated = servers.updateMcpServer(id, input)
        val message = if (server.name == updated.name) {
            "MCP Server ${updated.name} updated"
        } else {
            "MCP Server ${server.name} renamed to ${updated.name}"
        }
        auditRecorder.record(server.workspaceId, WorkspaceAuditCategory.INTEGRATION, message)
        return updated
    }

    @MutationMapping
    fun removeMcpServer(@Argument id: Long): Boolean {
        val server = servers.mcpServer(id) ?: return false
        requireWorkspaceAccess(server.workspaceId)
        if (!servers.removeMcpServer(id)) return false

        auditRecorder.record(server.workspaceId, WorkspaceAuditCategory.INTEGRATION, "MCP Server ${server.name} removed")
        return true
    }

    /** Hands the stored credentials to the settings form behind the "Reveal" action. */
    @MutationMapping
    fun revealMcpServerSecret(@Argument id: Long): String? {
        val server = servers.mcpServer(id) ?: throw McpServerNotFoundException(id)
        requireWorkspaceAccess(server.workspaceId)

        auditRecorder.record(
            server.workspaceId,
            WorkspaceAuditCategory.INTEGRATION,
            "Credentials for ${server.name} revealed",
        )
        return servers.revealMcpServerSecret(id)
    }

    private fun requireWorkspaceAccess(workspaceId: Long) {
        val workspace = workspaces.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)
    }
}

class McpServerNotFoundException(id: Long) : RuntimeException("No MCP server with id $id")
