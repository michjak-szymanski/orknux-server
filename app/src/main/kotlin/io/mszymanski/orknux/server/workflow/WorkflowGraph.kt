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
    DATA_TASK,
    PUBLISH_TASK,
}

/**
 * One parameter of a node, and where its value comes from.
 *
 * `{{input.x}}` reads what arrived along the edge; anything else is a literal,
 * which is what lets a node be given a fixed value without anything upstream
 * having to produce one.
 */
@Embeddable
class NodeMapping(
    @Column(name = "name", nullable = false, length = 64)
    var name: String = "",

    @Column(name = "expression", nullable = false, columnDefinition = "text")
    var expression: String = "",
)

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
