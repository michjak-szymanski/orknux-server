package io.mszymanski.orknux.server.workspace

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime

interface WorkspaceAuditRepository : JpaRepository<WorkspaceAudit, Long>, JpaSpecificationExecutor<WorkspaceAudit> {

    fun findByWorkspaceId(workspaceId: Long, pageable: Pageable): Page<WorkspaceAudit>

    @Query("SELECT DISTINCT a.userId FROM WorkspaceAudit a WHERE a.workspaceId = :workspaceId ORDER BY a.userId")
    fun findUserIds(@Param("workspaceId") workspaceId: Long): List<String>
}

/**
 * Filters for the workspace audit view. Built as a specification rather than JPQL so
 * the optional ones can simply be left out.
 */
fun auditFilter(
    /** Null means every workspace; an empty list means none. */
    workspaceIds: Collection<Long>?,
    category: WorkspaceAuditCategory?,
    userId: String?,
    since: OffsetDateTime?,
    search: String?,
    /**
     * Keeps the admin log to admin-level events: a workspace being
     * created, renamed or removed, and anything that belongs to no workspace.
     * Everything a workspace does inside itself belongs in that workspace's own log.
     */
    adminOnly: Boolean = false,
): Specification<WorkspaceAudit> = Specification { root, _, builder ->
    val predicates = mutableListOf(builder.conjunction())

    workspaceIds?.let { ids ->
        predicates += if (ids.isEmpty()) builder.disjunction() else root.get<Long>("workspaceId").`in`(ids)
    }

    if (adminOnly) {
        predicates += builder.or(
            builder.isNull(root.get<Long>("workspaceId")),
            builder.equal(root.get<WorkspaceAuditCategory>("category"), WorkspaceAuditCategory.WORKSPACE),
        )
    }

    category?.let { predicates += builder.equal(root.get<WorkspaceAuditCategory>("category"), it) }
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
