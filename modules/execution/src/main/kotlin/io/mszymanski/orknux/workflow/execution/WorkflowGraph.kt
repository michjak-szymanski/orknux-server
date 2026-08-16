package io.mszymanski.orknux.workflow.execution

/** The node kinds orknux-server's editor produces; kept in step with its schema. */
enum class NodeKind {
    TRIGGER,
    AGENT,

    /** An instance of one of the workspace's actions; what it does is the action's. */
    ACTION,

    /** Asks one of the workspace's conditions, and ends the run when it does not hold. */
    CONDITION,
    DATA_TASK,
    PUBLISH_TASK,
}

data class GraphNode(
    /** Stable within a workflow; what edges refer to. */
    val key: String,
    val kind: NodeKind,
    val name: String,
    val description: String? = null,
    /** The agent an agent node instances; null on every other kind. */
    val agentId: Long? = null,
    /** The action an [NodeKind.ACTION] node instances. */
    val actionId: Long? = null,
    /** The condition a [NodeKind.CONDITION] node asks. */
    val conditionId: Long? = null,
    /**
     * What the node passes to its action: parameter name to expression.
     *
     * Carried here because it belongs to the node rather than to the action,
     * and a run has to keep what it was given — the editor may say something
     * else by the time this finishes.
     */
    val mappings: Map<String, String> = emptyMap(),
    val x: Double = 0.0,
    val y: Double = 0.0,
)

data class GraphEdge(val source: String, val target: String)

/** A workflow as it stood when a run picked it up. */
data class WorkflowGraph(
    val workflowId: Long,
    val name: String,
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
)

/**
 * Where a graph to run comes from. orknux-server owns workflow definitions, so
 * the only implementation asks it over GraphQL; the interface is what keeps the
 * engine from depending on that, and what a cache or a replay would slot into.
 */
interface WorkflowGraphSource {

    /** Throws if the workflow is not there, or if the source cannot be reached. */
    fun graph(workspaceId: Long, workflowId: Long): WorkflowGraph
}

class WorkflowNotFoundException(workspaceId: Long, workflowId: Long) :
    RuntimeException("Workflow $workflowId is not assigned to workspace $workspaceId")

class WorkflowGraphEmptyException(workflowId: Long) :
    RuntimeException("Workflow $workflowId has no nodes to run")

class WorkflowGraphCyclicException(workflowId: Long) :
    RuntimeException("Workflow $workflowId has a cycle, so there is no order to run it in")

class ExecutionNotFoundException(id: Long) : RuntimeException("Execution $id was not found")

/**
 * The order the nodes run in: every node after the ones that feed it, and nodes
 * with nothing between them in the order they were drawn, so two runs of an
 * unchanged graph produce the same step order.
 *
 * Edges pointing at a key that no node has are ignored — a graph orknux-server
 * accepted should not have them, and dropping the edge still runs the nodes.
 */
fun WorkflowGraph.runOrder(): List<GraphNode> {
    if (nodes.isEmpty()) throw WorkflowGraphEmptyException(workflowId)

    val byKey = nodes.associateBy { it.key }
    val known = edges.filter { it.source in byKey && it.target in byKey }
    val incoming = nodes.associate { it.key to known.count { edge -> edge.target == it.key } }.toMutableMap()

    val ready = ArrayDeque(nodes.filter { incoming.getValue(it.key) == 0 })
    val ordered = mutableListOf<GraphNode>()
    while (ready.isNotEmpty()) {
        val node = ready.removeFirst()
        ordered += node
        known.filter { it.source == node.key }
            .forEach { edge ->
                val left = incoming.getValue(edge.target) - 1
                incoming[edge.target] = left
                if (left == 0) ready += byKey.getValue(edge.target)
            }
    }

    // Anything left has an unsatisfied dependency, which for a finite graph means
    // it depends on itself.
    if (ordered.size != nodes.size) throw WorkflowGraphCyclicException(workflowId)
    return ordered
}
