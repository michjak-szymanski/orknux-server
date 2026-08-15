package io.mszymanski.gyloli.server.team

import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/**
 * Writes the audit trail. The user id is the LDAP uid of the authenticated
 * caller, so every entry is attributable.
 */
@Service
class TeamAuditRecorder(
    private val repository: TeamAuditRepository,
) {

    /** Team lifecycle: keeps the names either side so the org view can read them. */
    fun record(
        teamId: Long,
        operationType: TeamOperationType,
        oldTeamName: String? = null,
        newTeamName: String? = null,
    ): TeamAudit = repository.save(
        TeamAudit(
            teamId = teamId,
            category = TeamAuditCategory.TEAM,
            message = teamMessage(operationType, oldTeamName, newTeamName),
            oldTeamName = oldTeamName,
            newTeamName = newTeamName,
            operationType = operationType,
            date = OffsetDateTime.now(),
            userId = currentUserId(),
        ),
    )

    /**
     * Anything else that happened, already worded for display. A null [teamId]
     * records an organization-wide change, which only the org audit log shows.
     */
    fun record(teamId: Long?, category: TeamAuditCategory, message: String): TeamAudit = repository.save(
        TeamAudit(
            teamId = teamId,
            category = category,
            message = message,
            date = OffsetDateTime.now(),
            userId = currentUserId(),
        ),
    )

    /**
     * Something the system did with nobody asking: an event arriving on a
     * connection and starting a workflow. [actor] stands where a user id
     * normally does, so the log says what set it off instead of naming a person
     * who was not there.
     */
    fun recordAutomated(
        teamId: Long?,
        category: TeamAuditCategory,
        message: String,
        actor: String,
    ): TeamAudit = repository.save(
        TeamAudit(
            teamId = teamId,
            category = category,
            message = message,
            date = OffsetDateTime.now(),
            userId = actor,
        ),
    )

    private fun teamMessage(
        operationType: TeamOperationType,
        oldTeamName: String?,
        newTeamName: String?,
    ): String = when (operationType) {
        TeamOperationType.ADD -> "Team $newTeamName created"
        TeamOperationType.RENAME -> "Team $oldTeamName renamed to $newTeamName"
        TeamOperationType.REMOVE -> "Team $oldTeamName deleted"
    }

    private fun currentUserId(): String {
        val authentication = SecurityContextHolder.getContext().authentication
        check(
            authentication != null &&
                authentication.isAuthenticated &&
                authentication !is AnonymousAuthenticationToken,
        ) {
            "No authenticated user to attribute this change to"
        }
        return authentication.name
    }
}
