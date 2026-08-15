package io.mszymanski.gyloli.connector.connection

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * What gyloli-server tells the connector about the lifetime of a team.
 *
 * Teams live in the server's database, so there is no foreign key from
 * `team_connection` to them and no cascade behind a delete. Both ends of a
 * team's life have to be reported, or the connector keeps rows — and
 * credentials — for a team that no longer exists.
 */
@Service
class TeamLifecycleService(
    private val provisioning: ConnectionProvisioning,
    private val teamConnections: TeamConnectionRepository,
    private val mcpServers: McpServerRepository,
) {

    /** Called when a team is created. Returns the copies it was given. */
    @Transactional
    fun provisionTeamConnections(teamId: Long): List<TeamConnectionView> =
        provisioning.provisionDefaults(teamId).map(::TeamConnectionView)

    /**
     * Called when a team is deleted. Returns how many rows went, so the caller
     * can say so; running it for an unknown team is a no-op, which makes it
     * safe to retry.
     */
    @Transactional
    fun forgetTeam(teamId: Long): Int =
        (teamConnections.deleteByTeamId(teamId) + mcpServers.deleteByTeamId(teamId)).toInt()
}
