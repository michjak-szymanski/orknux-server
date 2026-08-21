package io.mszymanski.orknux.server.trigger

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
    /** An event arriving on one of the workspace's connections. */
    INCOMING_CONNECTION,

    /** The clock, on a cron expression. */
    SCHEDULED,

    /** A request arriving at a URL this installation answers on. */
    WEBHOOK,
}

/**
 * How a webhook decides whether the caller may start anything.
 *
 * [NONE] is what every webhook was to begin with: knowing the path and the shape
 * is enough. [FUNCTION] hands the request to one of the workspace's functions
 * and believes what it answers — which is how a signature gets checked against a
 * secret nobody has to paste into a graph.
 */
enum class WebhookAuthType {
    NONE,
    FUNCTION,
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
 * One entry in a workspace's trigger catalogue: an event, described once.
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

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(nullable = false)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var type: TriggerType,

    /** The workspace connection an incoming event arrives on. */
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
     * The second half of the URL a webhook trigger answers on.
     *
     * Unique across the installation rather than per workspace, because the URL
     * is: two workspaces cannot both answer at `/api/webhooks/build`.
     */
    @Column(name = "webhook_path", length = 120)
    var webhookPath: String? = null,

    /**
     * The shape a webhook's request has to have.
     *
     * Both a contract and a filter: what does not match is answered 404 rather
     * than started, and what does match is what the workflow can rely on being
     * handed.
     */
    @Column(name = "object_id")
    var objectId: Long? = null,

    /** How a webhook proves its caller may start anything; see [WebhookAuthType]. */
    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 16)
    var authType: WebhookAuthType = WebhookAuthType.NONE,

    /** The function that answers that question, when one does. */
    @Column(name = "auth_function_id")
    var authFunctionId: Long? = null,

    /**
     * A question asked of the event before anything is started; null fires on
     * everything. Filtering here rather than inside the workflow is what keeps
     * an unwanted event out of the executions list altogether.
     */
    @Column(name = "condition_id")
    var conditionId: Long? = null,

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

    /**
     * Which icon a node drawn from this starts with.
     *
     * A seed, not a rule: the node owns its icon once it has one, the same way
     * it owns the parameters this seeded. Null draws whatever the kind draws.
     */
    @Column(length = 40)
    var icon: String? = null,
)

interface WorkflowTriggerRepository : JpaRepository<WorkflowTrigger, Long> {

    fun findByWorkspaceId(workspaceId: Long, pageable: Pageable): Page<WorkflowTrigger>

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): WorkflowTrigger?

    /** Which triggers ask this question, so it cannot be deleted from under them. */
    fun findByConditionId(conditionId: Long): List<WorkflowTrigger>

    /** Which webhooks this function guards, so unloading a plugin can say so. */
    fun findByAuthFunctionId(authFunctionId: Long): List<WorkflowTrigger>

    /**
     * Which webhooks answer to this shape, so it cannot be deleted from under them.
     *
     * Only a webhook ever holds one: every other kind of trigger leaves the
     * column null, so anything this finds can be named as a webhook.
     */
    fun findByObjectId(objectId: Long): List<WorkflowTrigger>

    /** What an arriving event asks: who is waiting for this, on this connection? */
    fun findByConnectionIdAndActionAndEnabledTrue(
        connectionId: Long,
        action: TriggerAction,
    ): List<WorkflowTrigger>

    /** What the scheduler's tick asks: which definitions run on a clock? */
    fun findByTypeAndEnabledTrue(type: TriggerType): List<WorkflowTrigger>

    /** What a request arriving at a URL is answered by, if anything. */
    fun findByWebhookPath(webhookPath: String): WorkflowTrigger?
}

class TriggerNotFoundException(id: Long) : RuntimeException("No trigger with id $id")

/** An event nothing publishes: a trigger on it would be enabled and silent for ever. */
class TriggerActionUnsupportedException(action: String) : RuntimeException(
    "Nothing delivers $action events yet, so a trigger cannot listen for one",
)

class TriggerNameTakenException(name: String) :
    RuntimeException("A trigger named \"$name\" already exists in this workspace")

class TriggerNameInvalidException : RuntimeException("A trigger name is required")

/**
 * A trigger a workflow starts from is not one to delete.
 *
 * The drawn graph is the whole of it: publishing does not copy a trigger id, and
 * an arriving event finds its workflows by looking for the trigger *node*. So a
 * deleted trigger does not break a run halfway - it stops the workflow being
 * started at all, with nothing anywhere saying why, which is why it is refused
 * here rather than reported later.
 */
class TriggerInUseException(name: String, users: List<String>) : RuntimeException(
    "$name is used by ${users.joinToString(", ")}, so it cannot be deleted",
)

class TriggerConnectionRequiredException :
    RuntimeException("An incoming connection trigger needs a connection and an event")

class TriggerScheduleRequiredException :
    RuntimeException("A scheduled trigger needs a cron expression")

class TriggerWebhookPathRequiredException :
    RuntimeException("A webhook trigger needs a path to answer on")

class TriggerWebhookPathInvalidException(path: String) : RuntimeException(
    "\"$path\" is not a path on this installation. A webhook answers here, so name somewhere " +
        "here — \"build/finished\", not a URL of your own.",
)

class TriggerWebhookPathTakenException(path: String) :
    RuntimeException("Another trigger already answers at /api/webhooks/$path")

class TriggerWebhookAuthFunctionRequiredException :
    RuntimeException("Function authentication needs a function to ask")

class TriggerWebhookAuthFunctionNotBooleanException(name: String) : RuntimeException(
    "$name does not answer true or false. A webhook is authenticated by a function that says yes or no.",
)

class TriggerWebhookShapeRequiredException :
    RuntimeException("A webhook trigger needs an object saying what a request has to contain")

class TriggerScheduleInvalidException(cron: String) :
    RuntimeException("\"$cron\" is not a cron expression this can schedule")

class TriggerPayloadInvalidException :
    RuntimeException("The payload has to be a JSON object, so its fields can be read as input")
