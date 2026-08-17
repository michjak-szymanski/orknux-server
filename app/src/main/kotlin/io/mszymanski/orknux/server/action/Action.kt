package io.mszymanski.orknux.server.action

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

/** What an action does when a workflow reaches it. */
enum class ActionType {
    /** Performs something and carries on. */
    EXECUTE,

    /** Holds the run until something is true, or until time has passed. */
    WAIT,
}

/** How it does it. Each belongs to one [ActionType] and has its own settings. */
enum class ActionSubtype {
    /** Sends something through one of the workspace's connections. */
    OUTGOING_CONNECTION,
    HTTP_REQUEST,

    /** Calls one of the workspace's functions. */
    FUNCTION,

    /** Waits until an expression written into the action holds. */
    INLINE_CONDITION,

    /** Waits until one of the workspace's conditions holds. */
    CONDITION,

    /** Waits for a fixed time. */
    TIME,
}

/** What is being sent through a connection. */
enum class ConnectionAction {
    SEND_MESSAGE,
    REPLY_IN_THREAD,
    CREATE_ISSUE,
    UPDATE_ISSUE,
}

/** Who a message is addressed to. */
enum class MessageTarget {
    CHANNEL,
    USER,
}

/** One function argument, and what the action hands it. */
@Embeddable
class ArgumentMapping(
    @Column(name = "argument", nullable = false, length = 64)
    var argument: String = "",

    /** What to pass: a value, or the name of a field a node reads. */
    @Column(name = "expression", nullable = false, length = 500)
    var expression: String = "",
)

/**
 * A reusable block a workflow is built from.
 *
 * An action is a definition in the workspace's catalogue, like a trigger: it names no
 * workflow, and a workflow uses one by pointing an action node at it. What the
 * action needs and what it produces are not stored — they are read off its
 * settings, so the arguments of the function it calls are its parameters. See
 * `ActionParameters`.
 */
@Entity
@Table(name = "workflow_action")
class WorkflowAction(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(nullable = false, length = 120)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var type: ActionType,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var subtype: ActionSubtype,

    /** The workspace connection to send through; the connection module owns the row. */
    @Column(name = "connection_id")
    var connectionId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "connection_action", length = 32)
    var connectionAction: ConnectionAction? = null,

    @Column(columnDefinition = "text")
    var content: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    var target: MessageTarget? = null,

    @Column(name = "target_name", length = 120)
    var targetName: String? = null,

    @Column(length = 1000)
    var url: String? = null,

    @Column(length = 8)
    var method: String? = null,

    /** JSON, exactly as typed. */
    @Column(columnDefinition = "text")
    var headers: String? = null,

    @Column(name = "function_id")
    var functionId: Long? = null,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "workflow_action_mapping", joinColumns = [JoinColumn(name = "action_id")])
    @OrderColumn(name = "position")
    var mappings: MutableList<ArgumentMapping> = mutableListOf(),

    @Column(name = "condition_expression", length = 500)
    var conditionExpression: String? = null,

    /** The condition from the catalogue this waits on. */
    @Column(name = "condition_id")
    var conditionId: Long? = null,

    @Column(name = "timeout_seconds")
    var timeoutSeconds: Int? = null,

    @Column(name = "retry_interval_seconds")
    var retryIntervalSeconds: Int? = null,

    @Column(name = "duration_seconds")
    var durationSeconds: Int? = null,

    /**
     * Which icon a node drawn from this starts with.
     *
     * A seed, not a rule: the node owns its icon once it has one, the same way
     * it owns the parameters this seeded. Null draws whatever the kind draws.
     */
    @Column(length = 40)
    var icon: String? = null,
)

interface WorkflowActionRepository : JpaRepository<WorkflowAction, Long> {

    fun findByWorkspaceId(workspaceId: Long, pageable: Pageable): Page<WorkflowAction>

    fun findByWorkspaceId(workspaceId: Long): List<WorkflowAction>

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): WorkflowAction?

    fun findByFunctionId(functionId: Long): List<WorkflowAction>
}

class ActionNotFoundException(id: Long) : RuntimeException("No action with id $id")

class ActionNameTakenException(name: String) :
    RuntimeException("An action named \"$name\" already exists in this workspace")

class ActionNameInvalidException : RuntimeException("An action name is required")

class ActionSettingMissingException(setting: String) :
    RuntimeException("This kind of action needs $setting")

class ActionHoldsPlaceholderException(setting: String) : RuntimeException(
    "$setting is used exactly as written, so {{…}} would be sent as text. " +
        "Leave it empty and let each node say what goes there.",
)

class ActionSubtypeMismatchException(type: ActionType, subtype: ActionSubtype) :
    RuntimeException("A ${type.name.lowercase()} action cannot be ${subtype.name.lowercase().replace('_', ' ')}")
