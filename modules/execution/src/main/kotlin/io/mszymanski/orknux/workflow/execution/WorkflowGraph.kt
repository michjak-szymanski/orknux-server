package io.mszymanski.orknux.workflow.execution

/** The node kinds orknux-server's editor produces; kept in step with its schema. */
enum class NodeKind {
    TRIGGER,
    AGENT,

    /** An instance of one of the workspace's actions; what it does is the action's. */
    ACTION,

    /** Asks one of the workspace's conditions, and ends the run when it does not hold. */
    CONDITION,

    /** Makes an object out of what the run is carrying, and hands it on. */
    OBJECT,
}

/**
 * What fills one parameter of a node.
 *
 * Either something written, used as it stands, or a field the run is carrying,
 * read when the step runs. Which one is recorded rather than inferred from the
 * text: a value that happens to look like a field name is still a value, and a
 * reference to a field that turns out to be missing is a reference that failed
 * rather than a piece of text that was sent.
 */
data class NodeBinding(
    /** The written value, or the name of the field to read. */
    val expression: String,
    val reference: Boolean = false,
    /** Which node produces the field, on a reference. Carried for the record. */
    val from: String? = null,
)

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
     * What the node calls what it produces, so a later node can name it.
     *
     * Null hands the output on unchanged; a name wraps it in an object with that
     * one key, which is what makes `{{input.<name>}}` resolvable downstream.
     */
    val outputName: String? = null,
    /**
     * What the node passes to its action: parameter name to what fills it.
     *
     * Carried here because it belongs to the node rather than to the action,
     * and a run has to keep what it was given — the editor may say something
     * else by the time this finishes.
     */
    val mappings: Map<String, NodeBinding> = emptyMap(),
    val x: Double = 0.0,
    val y: Double = 0.0,
)

/**
 * Which way out of a condition an edge leaves by.
 *
 * The engine's own copy of the word: a run walks the graph it was handed, and
 * the graph says which edges answer YES and which answer NO.
 */
enum class EdgeBranch {
    YES,
    NO,
}

/**
 * One edge, and which answer it carries.
 *
 * Null for everything that is not leaving a condition, which is most edges and
 * every edge drawn before branches existed.
 */
data class GraphEdge(val source: String, val target: String, val branch: EdgeBranch? = null)

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
