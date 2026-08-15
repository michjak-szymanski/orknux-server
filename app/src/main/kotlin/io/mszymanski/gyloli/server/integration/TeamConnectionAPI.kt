package io.mszymanski.gyloli.server.integration

import io.mszymanski.gyloli.connector.connection.CreateTeamConnectionInput
import io.mszymanski.gyloli.connector.connection.TeamConnectionService
import io.mszymanski.gyloli.connector.connection.TeamConnectionView
import io.mszymanski.gyloli.connector.connection.UpdateTeamConnectionInput
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

/**
 * The connections one team holds. The connection module holds them and the
 * credentials; this checks the caller may see the team and records what they
 * did — including that a credential was revealed, which is a person's action.
 */
@Controller
class TeamConnectionAPI(
    private val connections: TeamConnectionService,
    private val teams: TeamRepository,
    private val access: TeamAccess,
    private val auditRecorder: TeamAuditRecorder,
) {

    @QueryMapping
    fun teamConnections(@Argument teamId: Long): List<TeamConnectionView> {
        requireTeamAccess(teamId)
        return connections.teamConnections(teamId)
    }

    @QueryMapping
    fun teamConnection(@Argument id: Long): TeamConnectionView? {
        val connection = connections.teamConnection(id) ?: return null
        requireTeamAccess(connection.teamId)
        return connection
    }

    @MutationMapping
    fun createTeamConnection(@Argument input: CreateTeamConnectionInput): TeamConnectionView {
        requireTeamAccess(input.teamId)
        val created = connections.createTeamConnection(input)
        auditRecorder.record(input.teamId, TeamAuditCategory.INTEGRATION, "Connection ${created.name} added")
        return created
    }

    /**
     * Backs the connection settings form. An inherited connection keeps the
     * organization's name, type and URL; everything else is the team's to set.
     */
    @MutationMapping
    fun updateTeamConnection(@Argument id: Long, @Argument input: UpdateTeamConnectionInput): TeamConnectionView {
        val connection = connections.teamConnection(id) ?: throw ConnectionNotFoundException(id)
        requireTeamAccess(connection.teamId)

        val updated = connections.updateTeamConnection(id, input)
        auditRecorder.record(
            connection.teamId,
            TeamAuditCategory.INTEGRATION,
            "Connection ${updated.name} settings updated",
        )
        return updated
    }

    /**
     * Clears the team's credentials. A connection the team added itself has
     * nothing to fall back on, so it goes; an inherited one returns to the
     * organization default.
     */
    @MutationMapping
    fun disconnectTeamConnection(@Argument id: Long): Boolean {
        val connection = connections.teamConnection(id) ?: return false
        requireTeamAccess(connection.teamId)
        if (!connections.disconnectTeamConnection(id)) return false

        auditRecorder.record(
            connection.teamId,
            TeamAuditCategory.INTEGRATION,
            "Connection ${connection.name} disconnected",
        )
        return true
    }

    /** Calls the service and keeps what came back, which is what status reports. */
    @MutationMapping
    fun testTeamConnection(@Argument id: Long): TeamConnectionView {
        val connection = connections.teamConnection(id) ?: throw ConnectionNotFoundException(id)
        requireTeamAccess(connection.teamId)

        val checked = connections.testTeamConnection(id)
        auditRecorder.record(
            checked.teamId,
            TeamAuditCategory.INTEGRATION,
            "Connection ${checked.name} checked: ${checked.status.name.lowercase().replace('_', ' ')}",
        )
        return checked
    }

    /** Hands the stored credentials to the settings form behind the "Reveal" action. */
    @MutationMapping
    fun revealTeamConnectionSecret(@Argument id: Long): String? {
        val connection = connections.teamConnection(id) ?: throw ConnectionNotFoundException(id)
        requireTeamAccess(connection.teamId)

        auditRecorder.record(
            connection.teamId,
            TeamAuditCategory.INTEGRATION,
            "Credentials for ${connection.name} revealed",
        )
        return connections.revealTeamConnectionSecret(id)
    }

    private fun requireTeamAccess(teamId: Long) {
        val team = teams.findByIdOrNull(teamId) ?: throw TeamNotFoundException(teamId)
        access.requireVisible(team)
    }
}

class ConnectionNotFoundException(id: Long) : RuntimeException("No connection with id $id")
