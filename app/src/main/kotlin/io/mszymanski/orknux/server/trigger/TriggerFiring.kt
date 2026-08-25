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
     * Every workflow this trigger reaches is switched off in its workspace.
     *
     * Its own value rather than a kind of failure, because nothing went wrong:
     * somebody turned it off and this is the trigger honouring that. It is
     * recorded because the whole complaint about a switch is that a workflow
     * quietly not running looks exactly like a trigger that never fired.
     */
    WORKFLOW_DISABLED,

    /**
     * A webhook call that could not prove it was allowed to make one.
     *
     * Recorded rather than dropped: a webhook whose caller has the wrong secret
     * looks exactly like a webhook nobody is calling, and the difference is the
     * whole of what somebody debugging it needs to know.
     */
    UNAUTHENTICATED,

    /**
     * A reply arrived, and it hangs under a message none of the watched bots
     * wrote.
     *
     * Written **once**, and only while the trigger has no other line to its
     * name. The reasoning either way is the same one [UNAUTHENTICATED] settles:
     * a trigger that is silent because nothing reaches it and a trigger that is
     * silent because what reaches it is not what it asked for look identical
     * from the outside, and the difference is the whole of what somebody
     * debugging one needs. Issue #269 is that difference — a reply trigger set
     * up correctly, watching the right bot, that never fires, with no way to
     * tell whether Slack is delivering at all.
     *
     * Once is the answer to the volume this deliberately used to avoid: a
     * definition matching every thread in every channel the bot can read would
     * fill a log that exists to be readable. One line says Slack is delivering
     * and this is not what was asked for; the second would say nothing the first
     * did not, and the thousandth would bury the firing somebody is looking for.
     */
    NOT_WATCHED,
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

    /** Whether this trigger has anything to its name yet. See [FiringOutcome.NOT_WATCHED]. */
    fun existsByTriggerId(triggerId: Long): Boolean
}
