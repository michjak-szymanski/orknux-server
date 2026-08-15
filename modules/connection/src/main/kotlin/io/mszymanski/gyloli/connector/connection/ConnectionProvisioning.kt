package io.mszymanski.gyloli.connector.connection

import org.springframework.stereotype.Service

/**
 * Gives a team its copy of an organization default connection, so the team only
 * has to add credentials. New teams get every default; existing teams are left
 * alone unless the administrator asks for a default to be handed to them too.
 *
 * The connector has no team table, so a backfill asks gyloli-server which teams
 * exist. A new team is provisioned when the server says it was created — see
 * `provisionTeamConnections` on [TeamConnectionAPI].
 */
@Service
class ConnectionProvisioning(
    private val connections: ConnectionRepository,
    private val teamConnections: TeamConnectionRepository,
    private val teams: TeamDirectory,
) {

    fun provisionDefaults(teamId: Long): List<TeamConnection> =
        connections.findAll().mapNotNull { default -> provision(teamId, default) }

    /** Returns how many teams took the connection; those already holding the name keep theirs. */
    fun provisionToExistingTeams(default: Connection): Int = teams.teamIds()
        .mapNotNull { teamId -> provision(teamId, default) }
        .size

    private fun provision(teamId: Long, default: Connection): TeamConnection? {
        // A team that already has a connection under this name keeps its own.
        if (teamConnections.findByTeamIdAndName(teamId, default.name) != null) return null

        return teamConnections.save(
            TeamConnection(
                teamId = teamId,
                connectionId = default.id,
                name = default.name,
                type = default.type,
                url = default.url,
            ),
        )
    }
}
