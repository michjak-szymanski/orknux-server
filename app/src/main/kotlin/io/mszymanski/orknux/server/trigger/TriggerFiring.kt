package io.mszymanski.orknux.server.trigger

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/**
 * What became of one firing.
 *
 * The values are the answers somebody staring at a trigger that "does not work"
 * needs: it started something, nothing instances it, the condition turned it
 * down, the condition could not be decided, or starting failed.
 */
enum class FiringOutcome {
    STARTED,
    NO_INSTANCE,
    CONDITION_DID_NOT_HOLD,
    UNDECIDED,
    FAILED,

    /**
     * A webhook call that could not prove it was allowed to make one.
     *
     * Recorded rather than dropped: a webhook whose caller has the wrong secret
     * looks exactly like a webhook nobody is calling, and the difference is the
     * whole of what somebody debugging it needs to know.
     */
    UNAUTHENTICATED,
}

/**
 * One line in a trigger's log.
 *
 * Written whether or not a run came of it — the entries worth having are mostly
 * the ones where none did, because those are invisible everywhere else.
 */
@Entity
@Table(name = "trigger_firing")
class TriggerFiring(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "trigger_id", nullable = false)
    val triggerId: Long,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(nullable = false)
    val at: OffsetDateTime = OffsetDateTime.now(),

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val outcome: FiringOutcome,

    /** Worded as the screen shows it, so nothing has to be reassembled to read it. */
    @Column
    val detail: String? = null,

    @Column(name = "runs_started", nullable = false)
    val runsStarted: Int = 0,
)

interface TriggerFiringRepository : JpaRepository<TriggerFiring, Long> {

    fun findByTriggerIdOrderByAtDesc(triggerId: Long, pageable: Pageable): Page<TriggerFiring>

    /** Everything that has fired in one workspace, newest first. */
    fun findByWorkspaceIdOrderByAtDesc(workspaceId: Long, pageable: Pageable): Page<TriggerFiring>

    /** The one line the list shows against each trigger. */
    fun findFirstByTriggerIdOrderByAtDesc(triggerId: Long): TriggerFiring?
}
