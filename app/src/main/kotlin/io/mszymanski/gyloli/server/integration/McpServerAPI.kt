package io.mszymanski.gyloli.server.integration

import io.mszymanski.gyloli.connector.connection.CreateMcpServerInput
import io.mszymanski.gyloli.connector.connection.McpServerService
import io.mszymanski.gyloli.connector.connection.McpServerView
import io.mszymanski.gyloli.connector.connection.UpdateMcpServerInput
import io.mszymanski.gyloli.server.security.TeamAccess
import io.mszymanski.gyloli.server.team.TeamAuditCategory
import io.mszymanski.gyloli.server.team.TeamAuditRecorder
import io.mszymanski.gyloli.server.team.TeamNotFoundException
import io.mszymanski.gyloli.server.team.TeamRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

/** The MCP servers a team's agents may connect to; the connection module holds them. */
@Controller
class McpServerAPI(
    private val servers: McpServerService,
    private val teams: TeamRepository,
    private val access: TeamAccess,
    private val auditRecorder: TeamAuditRecorder,
) {

    @QueryMapping
    fun mcpServers(@Argument teamId: Long): List<McpServerView> {
        requireTeamAccess(teamId)
        return servers.mcpServers(teamId)
    }

    @QueryMapping
    fun mcpServer(@Argument id: Long): McpServerView? {
        val server = servers.mcpServer(id) ?: return null
        requireTeamAccess(server.teamId)
        return server
    }

    @MutationMapping
    fun createMcpServer(@Argument input: CreateMcpServerInput): McpServerView {
        requireTeamAccess(input.teamId)
        val created = servers.createMcpServer(input)
        auditRecorder.record(input.teamId, TeamAuditCategory.INTEGRATION, "MCP Server ${created.name} added")
        return created
    }

    /** Backs the MCP server settings form; a null secret keeps the stored one. */
    @MutationMapping
    fun updateMcpServer(@Argument id: Long, @Argument input: UpdateMcpServerInput): McpServerView {
        val server = servers.mcpServer(id) ?: throw McpServerNotFoundException(id)
        requireTeamAccess(server.teamId)

        val updated = servers.updateMcpServer(id, input)
        val message = if (server.name == updated.name) {
            "MCP Server ${updated.name} updated"
        } else {
            "MCP Server ${server.name} renamed to ${updated.name}"
        }
        auditRecorder.record(server.teamId, TeamAuditCategory.INTEGRATION, message)
        return updated
    }

    @MutationMapping
    fun removeMcpServer(@Argument id: Long): Boolean {
        val server = servers.mcpServer(id) ?: return false
        requireTeamAccess(server.teamId)
        if (!servers.removeMcpServer(id)) return false

        auditRecorder.record(server.teamId, TeamAuditCategory.INTEGRATION, "MCP Server ${server.name} removed")
        return true
    }

    /** Hands the stored credentials to the settings form behind the "Reveal" action. */
    @MutationMapping
    fun revealMcpServerSecret(@Argument id: Long): String? {
        val server = servers.mcpServer(id) ?: throw McpServerNotFoundException(id)
        requireTeamAccess(server.teamId)

        auditRecorder.record(
            server.teamId,
            TeamAuditCategory.INTEGRATION,
            "Credentials for ${server.name} revealed",
        )
        return servers.revealMcpServerSecret(id)
    }

    private fun requireTeamAccess(teamId: Long) {
        val team = teams.findByIdOrNull(teamId) ?: throw TeamNotFoundException(teamId)
        access.requireVisible(team)
    }
}

class McpServerNotFoundException(id: Long) : RuntimeException("No MCP server with id $id")
