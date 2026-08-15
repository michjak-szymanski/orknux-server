package io.mszymanski.gyloli.server.team

import io.mszymanski.gyloli.connector.connection.TeamLifecycleService
import io.mszymanski.gyloli.server.security.TeamAccess
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional

@Controller
class TeamAPI(
    private val repository: TeamRepository,
    private val auditRecorder: TeamAuditRecorder,
    private val access: TeamAccess,
    private val connections: TeamLifecycleService,
) {

    /**
     * Non-admins only see teams whose directory group they belong to. The filter
     * runs in memory because membership lives on the authentication rather than in
     * the database, and an organization's team count stays small.
     */
    @QueryMapping
    fun teams(@Argument page: Int?, @Argument size: Int?): TeamPage {
        val pageable = pageRequest(page, size, Sort.by("name"))
        if (access.isAdmin()) return TeamPage(repository.findAll(pageable))

        val visible = repository.findAll(Sort.by("name")).filter(access::canSee)
        return TeamPage(PageImpl(visible.page(pageable), pageable, visible.size.toLong()))
    }

    @QueryMapping
    fun team(@Argument id: Long): Team? = repository.findByIdOrNull(id)?.takeIf(access::canSee)

    @MutationMapping
    @Transactional
    fun createTeam(@Argument input: CreateTeamInput): Team {
        access.requireAdmin()
        val name = input.name.trim()
        if (name.isEmpty()) throw TeamNameInvalidException()
        if (repository.findByName(name) != null) throw TeamNameTakenException(name)

        val team = repository.save(Team(name = name, description = input.description?.trim()?.ifEmpty { null }))
        auditRecorder.record(
            teamId = requireNotNull(team.id),
            operationType = TeamOperationType.ADD,
            newTeamName = team.name,
        )
        // The organization's default connections come with the team.
        provision(requireNotNull(team.id), team.name)
        return team
    }

    private fun provision(teamId: Long, name: String) {
        val provisioned = connections.provisionTeamConnections(teamId)
        if (provisioned.isEmpty()) return

        val what = if (provisioned.size == 1) "connection" else "connections"
        auditRecorder.record(
            teamId,
            TeamAuditCategory.INTEGRATION,
            "${provisioned.size} default $what provisioned for $name",
        )
    }

    /** Backs the team settings form: name, description and directory group in one save. */
    @MutationMapping
    @Transactional
    fun updateTeam(@Argument id: Long, @Argument input: UpdateTeamInput): Team {
        access.requireAdmin()
        val newName = input.name.trim()
        if (newName.isEmpty()) throw TeamNameInvalidException()

        val team = repository.findByIdOrNull(id) ?: throw TeamNotFoundException(id)
        val previousName = team.name
        if (newName != previousName && repository.findByName(newName) != null) {
            throw TeamNameTakenException(newName)
        }

        val previousDescription = team.description
        val previousGroup = team.ldapGroup

        team.name = newName
        team.description = input.description?.trim()?.ifEmpty { null }
        team.ldapGroup = input.ldapGroup?.trim()?.ifEmpty { null }

        if (newName != previousName) {
            auditRecorder.record(
                teamId = id,
                operationType = TeamOperationType.RENAME,
                oldTeamName = previousName,
                newTeamName = newName,
            )
        }
        if (team.ldapGroup != previousGroup) {
            auditRecorder.record(id, TeamAuditCategory.TEAM, "Team LDAP group updated")
        }
        if (team.description != previousDescription) {
            auditRecorder.record(id, TeamAuditCategory.TEAM, "Team description updated")
        }
        return team
    }

    @MutationMapping
    @Transactional
    fun deleteTeam(@Argument id: Long): Boolean {
        access.requireAdmin()
        val team = repository.findByIdOrNull(id) ?: return false
        repository.delete(team)
        // team_connection has no foreign key to team — the module owns its own
        // tables — so what was held for this team is dropped explicitly.
        connections.forgetTeam(id)
        auditRecorder.record(
            teamId = id,
            operationType = TeamOperationType.REMOVE,
            oldTeamName = team.name,
        )
        return true
    }
}

data class CreateTeamInput(
    val name: String,
    val description: String? = null,
)

data class UpdateTeamInput(
    val name: String,
    val description: String? = null,
    /** Directory group whose members may see the team, e.g. cn=backend,ou=teams,dc=gyloli,dc=io. */
    val ldapGroup: String? = null,
)

data class TeamPage(
    val content: List<Team>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<Team>) : this(
        content = page.content,
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}

class TeamNotFoundException(id: Long) : RuntimeException("No team with id $id")

class TeamNameTakenException(name: String) : RuntimeException("A team named \"$name\" already exists")

class TeamNameInvalidException : RuntimeException("A team name is required")
