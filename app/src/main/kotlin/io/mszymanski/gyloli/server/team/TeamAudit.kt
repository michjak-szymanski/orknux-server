package io.mszymanski.gyloli.server.team

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

enum class TeamAuditCategory {
    TEAM,
    WORKFLOW,
    AGENT,
    INTEGRATION,
}

@Entity
@Table(name = "team_audit")
class TeamAudit(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /** Null for organization-wide changes, which belong to no single team. */
    val teamId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val category: TeamAuditCategory = TeamAuditCategory.TEAM,

    /** What happened, ready to show: "Agent Research Agent enabled". */
    @Column(nullable = false, length = 500)
    val message: String,

    /** Null for [TeamOperationType.ADD], where there is no previous name. */
    val oldTeamName: String? = null,

    /** Null for [TeamOperationType.REMOVE], where there is no resulting name. */
    val newTeamName: String? = null,

    /** Only set for team lifecycle entries. */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    val operationType: TeamOperationType? = null,

    @Column(nullable = false)
    val date: OffsetDateTime,

    @Column(nullable = false)
    val userId: String,
)
