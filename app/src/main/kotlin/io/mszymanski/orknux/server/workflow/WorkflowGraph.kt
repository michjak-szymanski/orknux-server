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

enum class NodeKind {
    TRIGGER,
    AGENT,

    /** An instance of one of the workspace's actions. */
    ACTION,

    /** Asks one of the workspace's conditions; the run stops when it does not hold. */
    CONDITION,

    /** Makes an object out of what the run is carrying, and hands it on. */
    OBJECT,
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
)

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
