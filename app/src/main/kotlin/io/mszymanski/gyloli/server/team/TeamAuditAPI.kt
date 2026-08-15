package io.mszymanski.gyloli.server.team

import io.mszymanski.gyloli.server.security.TeamAccess
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Controller
class TeamAuditAPI(
    private val repository: TeamAuditRepository,
    private val teams: TeamRepository,
    private val access: TeamAccess,
) {

    /**
     * The organization audit view.
     *
     * It shows what happened to the organization — teams appearing, being
     * renamed, going away, and anything that belongs to no team — and not what
     * a team did inside itself. A workflow saved or a condition renamed is the
     * team's own business, and its own audit log has it.
     *
     * Asking for one team is the exception: that is the team's log, reached
     * through the same query, so it shows everything.
     */
    @QueryMapping
    fun teamAudit(
        @Argument teamId: Long?,
        @Argument page: Int?,
        @Argument size: Int?,
        @Argument search: String?,
        @Argument category: TeamAuditCategory?,
        @Argument userId: String?,
        @Argument days: Int?,
    ): TeamAuditPage {
        val pageable = pageRequest(page, size, Sort.by(Sort.Direction.DESC, "date"))
        val since = days?.takeIf { it > 0 }?.let { OffsetDateTime.now().minusDays(it.toLong()) }
        val term = search?.trim()?.ifEmpty { null }

        val teamIds = when {
            teamId != null -> {
                val team = teams.findByIdOrNull(teamId) ?: throw TeamNotFoundException(teamId)
                access.requireVisible(team)
                listOf(teamId)
            }
            access.isAdmin() -> null
            // Entries for teams that no longer exist can no longer be matched to a
            // directory group, so they stay with administrators.
            else -> teams.findAll().filter(access::canSee).mapNotNull { it.id }
        }

        val filter = auditFilter(
            teamIds = teamIds,
            category = category,
            userId = userId?.ifEmpty { null },
            since = since,
            search = term,
            organizationOnly = teamId == null,
        )
        return TeamAuditPage(repository.findAll(filter, pageable))
    }

    /** The users who appear in the organization audit log, for the filter. */
    @QueryMapping
    fun auditUsers(): List<String> = repository.findAll()
        .filter { entry ->
            // Organization-wide entries carry no team, so they stay with administrators.
            access.isAdmin() || entry.teamId?.let { teams.findByIdOrNull(it)?.let(access::canSee) } == true
        }
        .map { it.userId }
        .distinct()
        .sorted()

    /** The team audit view: everything that happened inside one team. */
    @QueryMapping
    fun teamActivity(
        @Argument teamId: Long,
        @Argument page: Int?,
        @Argument size: Int?,
        @Argument search: String?,
        @Argument category: TeamAuditCategory?,
        @Argument userId: String?,
        @Argument days: Int?,
    ): TeamAuditPage {
        val team = teams.findByIdOrNull(teamId) ?: throw TeamNotFoundException(teamId)
        access.requireVisible(team)

        val pageable = pageRequest(page, size, Sort.by(Sort.Direction.DESC, "date"))
        val since = days?.takeIf { it > 0 }?.let { OffsetDateTime.now().minusDays(it.toLong()) }
        val term = search?.trim()?.ifEmpty { null }

        val filter = auditFilter(listOf(teamId), category, userId?.ifEmpty { null }, since, term)
        return TeamAuditPage(repository.findAll(filter, pageable))
    }

    /** The users who appear in a team's audit log, for the filter. */
    @QueryMapping
    fun teamActivityUsers(@Argument teamId: Long): List<String> {
        val team = teams.findByIdOrNull(teamId) ?: throw TeamNotFoundException(teamId)
        access.requireVisible(team)
        return repository.findUserIds(teamId)
    }
}

data class TeamAuditEntry(
    val id: Long,
    /** Null for organization-wide entries such as default connection changes. */
    val teamId: Long?,
    val category: TeamAuditCategory,
    val message: String,
    val oldTeamName: String?,
    val newTeamName: String?,
    val operationType: TeamOperationType?,
    val date: String,
    val userId: String,
) {
    constructor(audit: TeamAudit) : this(
        id = requireNotNull(audit.id),
        teamId = audit.teamId,
        category = audit.category,
        message = audit.message,
        oldTeamName = audit.oldTeamName,
        newTeamName = audit.newTeamName,
        operationType = audit.operationType,
        date = audit.date.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        userId = audit.userId,
    )
}

data class TeamAuditPage(
    val content: List<TeamAuditEntry>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<TeamAudit>) : this(
        content = page.content.map(::TeamAuditEntry),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}
