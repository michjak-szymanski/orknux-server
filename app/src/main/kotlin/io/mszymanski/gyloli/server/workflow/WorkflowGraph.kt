package io.mszymanski.gyloli.server.workflow

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository

enum class WorkflowStatus {
    DRAFT,
    PUBLISHED,
}

enum class NodeKind {
    TRIGGER,
    AGENT,

    /** An instance of one of the team's actions. */
    ACTION,

    /** Asks one of the team's conditions; the run stops when it does not hold. */
    CONDITION,
    DATA_TASK,
    PUBLISH_TASK,
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

    @Column(name = "agent_class")
    var agentClass: String? = null,

    @Column(name = "model_provider")
    var modelProvider: String? = null,

    /**
     * The trigger definition this node is an instance of; only a
     * [NodeKind.TRIGGER] node has one, and it is what wires an arriving event to
     * this workflow. Null while the node names no trigger yet.
     */
    @Column(name = "trigger_id")
    var triggerId: Long? = null,

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
}

interface WorkflowEdgeRepository : JpaRepository<WorkflowEdge, Long> {
    fun findByWorkflowId(workflowId: Long): List<WorkflowEdge>
    fun deleteByWorkflowId(workflowId: Long)
}
