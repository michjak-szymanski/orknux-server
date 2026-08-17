package io.mszymanski.orknux.workflow.execution

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

enum class ExecutionStatus {
    RUNNING,
    COMPLETED,
    FAILED,
}

enum class ExecutionTrigger {
    WEBHOOK,
    MANUAL,
    SCHEDULE,
    API,
}

/** Where one node of the graph got to in a run. */
enum class StepStatus {
    /** Never reached, because the run stopped or has not got there yet. */
    PENDING,
    RUNNING,

    /**
     * Parked: the node was asked, is not ready, and said when to come back.
     *
     * Nothing is held while it waits — the delay belongs to whatever is
     * carrying the run, which is a Temporal timer, or the inline engine's own
     * sleep. The step is open, so it has a start and no finish.
     */
    WAITING,
    COMPLETED,
    FAILED,
    SKIPPED,
}

enum class LogLevel {
    INFO,
    SUCCESS,
    ERROR,
}

/**
 * One run of a workflow.
 *
 * The workflow itself lives in orknux-server, so this holds its id and the name
 * it had when the run started rather than a foreign key: the definition can be
 * renamed, or removed, without rewriting what happened.
 */
@Entity
@Table(name = "workflow_execution")
class WorkflowExecution(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(name = "workflow_id", nullable = false)
    val workflowId: Long,

    @Column(name = "workflow_name", nullable = false)
    val workflowName: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: ExecutionStatus,

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 16)
    val trigger: ExecutionTrigger,

    @Column(name = "started_at", nullable = false)
    val startedAt: OffsetDateTime,

    /** Null while the run is still going. */
    @Column(name = "finished_at")
    var finishedAt: OffsetDateTime? = null,

    /** What the run was handed by whatever asked for it. */
    @Column(columnDefinition = "text")
    val input: String? = null,

    /**
     * What the run is carrying now: everything produced so far, under the names
     * the nodes gave it.
     *
     * Kept here rather than passed from step to step because one of the two
     * engines is Temporal, where an activity's arguments and results are written
     * into event history and kept for the life of the run. Handing a growing
     * payload back and forth would record it again at every step — fine for a
     * Slack message, ruinous for anything that moves real data, and bounded by a
     * payload limit that has nothing to do with what a workflow ought to carry.
     * So the payload lives in the database and Temporal carries an id.
     */
    @Column(columnDefinition = "text")
    var carried: String? = null,

    /** Why the run stopped, when it stopped badly. */
    @Column(length = 1000)
    var error: String? = null,

    /**
     * The node that ended the run early, and what it said.
     *
     * A condition that does not hold ends a run without failing it, which would
     * otherwise be indistinguishable from a run that did everything — the steps
     * after it simply never started.
     */
    @Column(name = "stopped_at_node_key", length = 64)
    var stoppedAtNodeKey: String? = null,

    @Column(name = "stopped_reason", length = 500)
    var stoppedReason: String? = null,
)

/**
 * One node of the graph as this run saw it. The name, kind and position are
 * copied when the run starts, so editing the workflow afterwards does not
 * rewrite history.
 */
@Entity
@Table(name = "execution_step")
class ExecutionStep(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "execution_id", nullable = false)
    val executionId: Long,

    @Column(name = "node_key", nullable = false, length = 64)
    val nodeKey: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val kind: NodeKind,

    @Column(nullable = false)
    val name: String,

    @Column(length = 500)
    val description: String? = null,

    /**
     * Which action this step runs, copied when the run started: editing the
     * action afterwards does not change what this run did.
     */
    @Column(name = "action_id")
    val actionId: Long? = null,

    /** Which condition this step asks, copied when the run started. */
    @Column(name = "condition_id")
    val conditionId: Long? = null,

    /** The agent this step runs, when the node is one. */
    @Column(name = "agent_id")
    val agentId: Long? = null,

    /**
     * What the node called what it produces, copied when the run started.
     *
     * With a name, the step's output is an object holding it, so a later step
     * can read `{{input.<name>}}` instead of being handed prose it cannot
     * address. Without one, the output is passed on unchanged.
     */
    @Column(name = "output_name", length = 60)
    val outputName: String? = null,

    /**
     * What this step was told to pass, as JSON of name to expression.
     *
     * The run's own copy: a workflow edited while this is waiting, or replayed
     * later, resolves against what it started with.
     */
    @Column(name = "mappings", columnDefinition = "text")
    val mappings: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: StepStatus = StepStatus.PENDING,

    @Column(name = "position_x", nullable = false)
    val x: Double,

    @Column(name = "position_y", nullable = false)
    val y: Double,

    /** The position in the run order, which is what the detail view reads by. */
    @Column(name = "step_order", nullable = false)
    val order: Int,

    @Column(name = "started_at")
    var startedAt: OffsetDateTime? = null,

    @Column(name = "finished_at")
    var finishedAt: OffsetDateTime? = null,

    @Column(columnDefinition = "text")
    var input: String? = null,

    @Column(columnDefinition = "text")
    var output: String? = null,

    @Column(length = 1000)
    var error: String? = null,

    /**
     * When a waiting step gives up, written the first time it parks.
     *
     * A wait outlives the worker that started it, so the deadline has to be
     * somewhere both of them can read: a step resumed an hour later counts from
     * when it first parked, not from when it was picked up again.
     */
    @Column(name = "wait_until")
    var waitUntil: OffsetDateTime? = null,
)

/** One line of what a run reported. */
@Entity
@Table(name = "execution_log")
class ExecutionLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "execution_id", nullable = false)
    val executionId: Long,

    /** Null for lines about the run itself rather than one step. */
    @Column(name = "node_key", length = 64)
    val nodeKey: String? = null,

    @Column(name = "logged_at", nullable = false)
    val loggedAt: OffsetDateTime,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val level: LogLevel = LogLevel.INFO,

    @Column(nullable = false, length = 2000)
    val message: String,

    /** Keeps lines logged in the same instant in the order they happened. */
    @Column(name = "sequence_no", nullable = false)
    val sequence: Int,
)
