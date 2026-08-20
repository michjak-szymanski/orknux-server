package io.mszymanski.orknux.server.workflow

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository

enum class WorkflowStatus {
    DRAFT,
    PUBLISHED,
}

/**
 * Which side of a node its input and output sit on.
 *
 * Named for where the work goes rather than for an angle: "left to right" is
 * what somebody means, and a number of degrees would have to be translated
 * back into that at every reading.
 */
enum class NodeOrientation {
    LEFT_TO_RIGHT,
    TOP_TO_BOTTOM,
    RIGHT_TO_LEFT,
    BOTTOM_TO_TOP,
}

enum class NodeKind {
    TRIGGER,
    AGENT,

    /** An instance of one of the workspace's actions. */
    ACTION,

    /** Asks one of the workspace's conditions; the run stops when it does not hold. */
    CONDITION,

    /** Makes an object out of what the run is carrying, and hands it on. */
    OBJECT,

    /**
     * Names an LLM session, for the agent nodes wired to it to talk into.
     *
     * A declaration rather than a step: nothing runs it, and it produces
     * nothing a later node could read. It holds the two parameters a session is
     * identified by - `sessionKey`, and the optional `sessionKeyPrefix` it is
     * filed under - and every agent node an edge leads from it to writes into
     * that one conversation. Two agents sharing a session is two edges from one
     * of these, rather than the same key typed into both.
     *
     * Its parameters are resolved where they are used, in the agent, so a key
     * read off what the run is carrying still reads what that agent was handed.
     */
    SESSION,
}

/**
 * One parameter of a node, and where its value comes from.
 *
 * Either the value itself, used exactly as written, or the name of a field the
 * run is carrying, read when the step runs. Which of the two it is, is the
 * mode — not something guessed from how the text looks.
 */
@Embeddable
class NodeMapping(
    @Column(name = "name", nullable = false, length = 64)
    var name: String = "",

    /**
     * The value itself, or the field it is read from.
     *
     * Which one depends on [mode]. A written value is used as it stands; a
     * reference names a field the run is carrying — `reply`, `message.channel` —
     * and is read when the step runs.
     */
    @Column(name = "expression", nullable = false, columnDefinition = "text")
    var expression: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 16)
    var mode: MappingMode = MappingMode.VALUE,

    /**
     * Which node produces the referenced field, on a reference.
     *
     * Not used to read the value — the run carries everything under one set of
     * names, so the field name is enough — but it is what lets the canvas draw a
     * line from the node that made it, and what makes a reference to a node that
     * has been deleted something we can point at rather than a name that quietly
     * resolves to nothing.
     */
    @Column(name = "source_node_key", length = 64)
    var sourceNodeKey: String? = null,
)

/** Whether a parameter holds something written or something read. */
enum class MappingMode {
    VALUE,
    REFERENCE,
}

@Entity
@Table(name = "workflow_node")
class WorkflowNode(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workflow_id", nullable = false)
    val workflowId: Long,

    /** Stable within a workflow; what edges refer to. */
    @Column(name = "node_key", nullable = false, length = 64)
    val nodeKey: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var kind: NodeKind,

    @Column(nullable = false)
    var name: String,

    @Column(length = 500)
    var description: String? = null,

    /**
     * The agent an agent node instances; ignored on any other kind.
     *
     * The agent supplies the model, the instructions and the catalogs it was
     * granted, which is why nothing else about it is stored here.
     */
    @Column(name = "agent_id")
    var agentId: Long? = null,

    /**
     * The trigger definition this node is an instance of; only a
     * [NodeKind.TRIGGER] node has one, and it is what wires an arriving event to
     * this workflow. Null while the node names no trigger yet.
     */
    @Column(name = "trigger_id")
    var triggerId: Long? = null,

    /**
     * What this node calls what it produces, so a later node can ask for it by
     * name.
     *
     * An agent answers with prose, which has no fields to address. Naming the
     * answer is what a later node points a reference at: the step's output
     * becomes an object with this one key. Null means the answer is
     * handed on as it always was, unwrapped, which is what a node drawn before
     * this existed still does.
     */
    @Column(name = "output_name", length = 60)
    var outputName: String? = null,

    /**
     * Which way round the node faces on the canvas.
     *
     * Layout rather than meaning: it moves where the handles sit and nothing
     * else, so a graph can run down a screen instead of off the side of it.
     * Null is the way it always was, left to right.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    var orientation: NodeOrientation? = null,

    /**
     * Which icon the canvas draws on this node.
     *
     * A name from the interface's own set rather than a file or a URL: a graph
     * is read at a glance, and a node that draws whatever someone pasted is a
     * node that can draw nothing, or something enormous. Null keeps the plain
     * node the kind already gives.
     */
    @Column(name = "icon", length = 40)
    var icon: String? = null,

    /**
     * What this node passes to the thing it points at, one entry per parameter.
     *
     * Seeded from the catalogue entry when the node first points at one, and
     * authoritative afterwards: nothing downstream reads the action's own
     * mappings, so editing a node cannot change a definition that other nodes
     * are using.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "workflow_node_mapping", joinColumns = [JoinColumn(name = "workflow_node_id")])
    @OrderColumn(name = "position")
    var mappings: MutableList<NodeMapping> = mutableListOf(),

    /**
     * The action this node is an instance of; only a [NodeKind.ACTION] node has
     * one, and it is what the node does when the run reaches it.
     */
    @Column(name = "action_id")
    var actionId: Long? = null,

    /** The condition a [NodeKind.CONDITION] node asks of the run it is in. */
    @Column(name = "condition_id")
    var conditionId: Long? = null,

    /**
     * The shape an object node makes, when it uses one the workspace has saved.
     *
     * Null is a shape of the node's own: its fields are whatever it holds. A
     * saved one fixes the field names, the way an action fixes its parameters,
     * and the node only decides what goes in them.
     */
    @Column(name = "object_id")
    var objectId: Long? = null,

    @Column(name = "position_x", nullable = false)
    var positionX: Double,

    @Column(name = "position_y", nullable = false)
    var positionY: Double,

    /**
     * What this node's two ways out are called.
     *
     * Null means the default, which the interface supplies: "Yes" and "No" for
     * a condition, and "If works" and "If fails" for an action that handles its
     * own failure. A question like "is it urgent" reads better as "Escalate"
     * and "File it", and those words are most of what makes a graph legible at
     * a glance, so they belong to the node rather than to the edges.
     *
     * One pair for both, rather than a second pair for actions, because there
     * is only ever one question a node answers: which of my two exits did this
     * run leave by.
     */
    @Column(name = "yes_label", length = 40)
    var yesLabel: String? = null,

    @Column(name = "no_label", length = 40)
    var noLabel: String? = null,

    /**
     * Whether this action has a second way out for the case where it fails.
     *
     * Kept on the node rather than inferred from an edge carrying
     * [EdgeBranch.FAILURE], because the handle has to be there for somebody to
     * draw from before any such edge exists — and because turning it off should
     * be a decision recorded on the node, not a graph that quietly loses its
     * fallback when the last edge is deleted.
     */
    @Column(name = "fallback_enabled", nullable = false)
    var fallbackEnabled: Boolean = false,

    /**
     * How many times in all a run may attempt this action; null is once.
     *
     * Attempts rather than retries, so the number on the node is the number of
     * times the work is performed at worst. A failure the runner has already
     * called final is never one of them: nothing about a channel that does not
     * exist changes between one attempt and the next.
     */
    @Column(name = "retry_attempts")
    var retryAttempts: Int? = null,

    /** How long to leave a failed attempt alone before the next; null is none. */
    @Column(name = "retry_backoff_seconds")
    var retryBackoffSeconds: Int? = null,
)

@Entity
@Table(name = "workflow_edge")
class WorkflowEdge(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workflow_id", nullable = false)
    val workflowId: Long,

    @Column(name = "source_key", nullable = false, length = 64)
    val sourceKey: String,

    @Column(name = "target_key", nullable = false, length = 64)
    val targetKey: String,

    /**
     * Which way out of its node this edge leaves by, or null for an edge that
     * is not answering anything.
     *
     * Null is what every edge between two ordinary nodes is, and what every
     * edge was before branches existed - so a graph drawn last week means
     * exactly what it meant then.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    val branch: EdgeBranch? = null,
)

/** The ways out of a node, as the edges leaving it are labelled. */
enum class EdgeBranch {
    /** A condition's two answers. */
    YES,
    NO,

    /**
     * The exit an action takes when it could not do its work.
     *
     * Only the exception is marked. An action's happy path stays the unmarked
     * edge it has always been, so enabling a fallback adds an edge instead of
     * rewriting the one already drawn, and a graph saved without this is
     * untouched by it.
     */
    FAILURE,
}

interface WorkflowNodeRepository : JpaRepository<WorkflowNode, Long> {
    fun findByWorkflowId(workflowId: Long): List<WorkflowNode>
    fun deleteByWorkflowId(workflowId: Long)

    /** What an arriving event asks: which workflows instance this trigger? */
    fun findByTriggerId(triggerId: Long): List<WorkflowNode>

    fun findByActionId(actionId: Long): List<WorkflowNode>

    fun findByConditionId(conditionId: Long): List<WorkflowNode>

    /** Which nodes run this agent, so it cannot be deleted from under them. */
    fun findByAgentId(agentId: Long): List<WorkflowNode>
}

interface WorkflowEdgeRepository : JpaRepository<WorkflowEdge, Long> {
    fun findByWorkflowId(workflowId: Long): List<WorkflowEdge>
    fun deleteByWorkflowId(workflowId: Long)
}
