package io.mszymanski.gyloli.server.team

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime

interface TeamAuditRepository : JpaRepository<TeamAudit, Long>, JpaSpecificationExecutor<TeamAudit> {

    fun findByTeamId(teamId: Long, pageable: Pageable): Page<TeamAudit>

    @Query("SELECT DISTINCT a.userId FROM TeamAudit a WHERE a.teamId = :teamId ORDER BY a.userId")
    fun findUserIds(@Param("teamId") teamId: Long): List<String>
}

/**
 * Filters for the team audit view. Built as a specification rather than JPQL so
 * the optional ones can simply be left out.
 */
fun auditFilter(
    /** Null means every team; an empty list means none. */
    teamIds: Collection<Long>?,
    category: TeamAuditCategory?,
    userId: String?,
    since: OffsetDateTime?,
    search: String?,
    /**
     * Keeps the organization log to organization-sized events: a team being
     * created, renamed or removed, and anything that belongs to no team.
     * Everything a team does inside itself belongs in that team's own log.
     */
    organizationOnly: Boolean = false,
): Specification<TeamAudit> = Specification { root, _, builder ->
    val predicates = mutableListOf(builder.conjunction())

    teamIds?.let { ids ->
        predicates += if (ids.isEmpty()) builder.disjunction() else root.get<Long>("teamId").`in`(ids)
    }

    if (organizationOnly) {
        predicates += builder.or(
            builder.isNull(root.get<Long>("teamId")),
            builder.equal(root.get<TeamAuditCategory>("category"), TeamAuditCategory.TEAM),
        )
    }

    category?.let { predicates += builder.equal(root.get<TeamAuditCategory>("category"), it) }
    userId?.let { predicates += builder.equal(root.get<String>("userId"), it) }
    since?.let { predicates += builder.greaterThanOrEqualTo(root.get("date"), it) }
    search?.let {
        val pattern = "%${it.lowercase()}%"
        predicates += builder.or(
            builder.like(builder.lower(root.get("message")), pattern),
            builder.like(builder.lower(root.get("userId")), pattern),
        )
    }

    builder.and(*predicates.toTypedArray())
}
