package io.mszymanski.gyloli.server.integration

import io.mszymanski.gyloli.connector.connection.TeamDirectory
import io.mszymanski.gyloli.server.team.TeamRepository
import org.springframework.stereotype.Service

/**
 * Teams belong to this module, so it is what answers when the connection module
 * asks which ones exist.
 */
@Service
class AppTeamDirectory(private val teams: TeamRepository) : TeamDirectory {

    override fun teamIds(): List<Long> = teams.findAll().mapNotNull { it.id }
}
