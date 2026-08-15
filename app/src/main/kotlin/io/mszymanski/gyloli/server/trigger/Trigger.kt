package io.mszymanski.gyloli.server.trigger

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

/** What kind of event a trigger definition waits for. */
enum class TriggerType {
    /** An event arriving on one of the team's connections. */
    INCOMING_CONNECTION,

    /** The clock, on a cron expression. */
    SCHEDULED,
}

/** The event on a connection that starts the workflow. */
enum class TriggerAction {
    MENTION,
    REPLY,
    MESSAGE,
    ISSUE_CREATED,
    ISSUE_UPDATED,
}

/**
 * One entry in a team's trigger catalogue: an event, described once.
 *
 * It names no workflow. A workflow points a trigger node at a definition, and
 * that node is the instance — so one definition can start several workflows, and
 * a definition nobody points at starts none.
 */
@Entity
@Table(name = "workflow_trigger")
class WorkflowTrigger(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "team_id", nullable = false)
    val teamId: Long,

    @Column(nullable = false)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var type: TriggerType,

    /** The team connection an incoming event arrives on. */
    @Column(name = "connection_id")
    var connectionId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    var action: TriggerAction? = null,

    @Column(length = 120)
    var cron: String? = null,

    @Column(length = 64)
    var timezone: String? = null,

    /**
     * JSON handed to the run this starts, as an object.
     *
     * A scheduled trigger is the reason this exists: the clock carries no data,
     * so what a workflow works on has to be said here. An incoming trigger adds
     * it underneath what arrived, so the event wins where both name a field.
     */
    @Column(columnDefinition = "text")
    var payload: String? = null,

    @Column(nullable = false)
    var enabled: Boolean = true,

    /**
     * When a scheduled trigger last fired, which is what the tick compares its
     * cron against. Null until it has, and a trigger that has never fired starts
     * from the tick that finds it rather than replaying the schedule it missed.
     */
    @Column(name = "last_fired_at")
    var lastFiredAt: OffsetDateTime? = null,
)

interface WorkflowTriggerRepository : JpaRepository<WorkflowTrigger, Long> {

    fun findByTeamId(teamId: Long, pageable: Pageable): Page<WorkflowTrigger>

    fun findByTeamIdAndName(teamId: Long, name: String): WorkflowTrigger?

    /** What an arriving event asks: who is waiting for this, on this connection? */
    fun findByConnectionIdAndActionAndEnabledTrue(
        connectionId: Long,
        action: TriggerAction,
    ): List<WorkflowTrigger>

    /** What the scheduler's tick asks: which definitions run on a clock? */
    fun findByTypeAndEnabledTrue(type: TriggerType): List<WorkflowTrigger>
}

class TriggerNotFoundException(id: Long) : RuntimeException("No trigger with id $id")

class TriggerNameTakenException(name: String) :
    RuntimeException("A trigger named \"$name\" already exists in this team")

class TriggerNameInvalidException : RuntimeException("A trigger name is required")

class TriggerConnectionRequiredException :
    RuntimeException("An incoming connection trigger needs a connection and an event")

class TriggerScheduleRequiredException :
    RuntimeException("A scheduled trigger needs a cron expression")

class TriggerScheduleInvalidException(cron: String) :
    RuntimeException("\"$cron\" is not a cron expression this can schedule")

class TriggerPayloadInvalidException :
    RuntimeException("The payload has to be a JSON object, so its fields can be read as input")
