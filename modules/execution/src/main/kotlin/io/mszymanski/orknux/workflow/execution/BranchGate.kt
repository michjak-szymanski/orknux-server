package io.mszymanski.orknux.workflow.execution

/**
 * Which nodes still have a reason to run, once a condition has answered.
 *
 * A workflow used to be a straight line: every node ran, and a condition that
 * did not hold ended the whole thing. With branches a run has to be able to go
 * one way and not the other, which means something has to decide, at each step,
 * whether anything that actually happened leads to it.
 *
 * The rule is that an edge is *taken* or it is not. Every edge out of a node
 * that ran is taken, except at a condition with branches, where only the edges
 * carrying the answer it gave are. A node runs when it has no edges in at all -
 * it is a beginning - or when at least one edge into it was taken.
 *
 * One taken path is enough, deliberately. Where two paths meet, "either of
 * these happened" is what somebody drawing a diamond means, it cannot wait
 * forever for a branch that was never going to arrive, and "only when both"
 * is what a second condition is for. Written down here rather than assumed,
 * because the other rule is defensible and this is the one that was chosen.
 *
 * Shared by both engines. The inline one walks the plan on a thread and the
 * Temporal one walks it across activities, and a run that took different paths
 * depending on which engine carried it would be the worst kind of difference.
 */
class BranchGate(edges: List<GraphEdge>) {

    private val incoming: Map<String, List<GraphEdge>> = edges.groupBy { it.target }
    private val outgoing: Map<String, List<GraphEdge>> = edges.groupBy { it.source }

    /** The edges whose answer has been given, and which therefore lead somewhere. */
    private val taken = mutableSetOf<GraphEdge>()

    /** A node nothing points at is a beginning, and beginnings always run. */
    fun mayRun(nodeKey: String): Boolean {
        val into = incoming[nodeKey] ?: return true
        return into.isEmpty() || into.any { it in taken }
    }

    /**
     * What a node's own outcome opens up.
     *
     * A condition that answered YES takes its YES edges; the NO ones are never
     * taken, so anything only they reach is skipped. An edge out of a condition
     * that carries no answer at all is taken either way - it is not part of the
     * question, and treating it as one would silently drop a path somebody drew
     * before branches existed.
     */
    fun follow(nodeKey: String, branch: EdgeBranch?) {
        val out = outgoing[nodeKey] ?: return
        taken += if (branch == null) out else out.filter { it.branch == null || it.branch == branch }
    }

    /** Whether this node's answer decides anything: a condition with branch edges. */
    fun branches(nodeKey: String): Boolean =
        outgoing[nodeKey].orEmpty().any { it.branch != null }
}
