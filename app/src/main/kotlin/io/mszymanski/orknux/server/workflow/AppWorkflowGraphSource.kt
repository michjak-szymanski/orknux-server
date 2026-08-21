package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.EdgeBranch
import io.mszymanski.orknux.workflow.execution.GraphEdge
import io.mszymanski.orknux.workflow.execution.GraphNode
import io.mszymanski.orknux.workflow.execution.GraphVersion
import io.mszymanski.orknux.workflow.execution.NodeBinding
import io.mszymanski.orknux.workflow.execution.WorkflowGraph as RunnableGraph
import io.mszymanski.orknux.workflow.execution.WorkflowGraphSource
import io.mszymanski.orknux.workflow.execution.WorkflowNotFoundException as RunnableWorkflowNotFound
import io.mszymanski.orknux.workflow.execution.WorkflowNotPublishedException
import io.mszymanski.orknux.workflow.execution.NodeKind as RunnableNodeKind
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

/**
 * Workflow definitions belong to this module, so it is what answers when the
 * execution module asks for a graph to run. The run copies what it is given, so
 * editing the workflow afterwards does not rewrite a run that already happened.
 */
@Service
class AppWorkflowGraphSource(
    private val workflows: WorkflowRepository,
    private val assignments: WorkspaceWorkflowRepository,
    private val nodes: WorkflowNodeRepository,
    private val edges: WorkflowEdgeRepository,
    private val workspaces: WorkspaceRepository,
    private val publications: WorkflowPublicationRepository,
    private val mapper: ObjectMapper,
) : WorkflowGraphSource {

    /**
     * Whether [graph] would find something to run for a trigger.
     *
     * Asked by anything that starts a run without a person watching, so that a
     * workflow somebody is still drawing is an answer rather than an exception.
     * It reads the same two facts [graph] does — the snapshot publishing takes,
     * and the status carried by a workflow that was live before snapshots
     * existed — so the two cannot drift apart and say different things.
     *
     * The reason it is a question at all is that the exception was expensive in
     * a way an exception should not be: thrown out of the transactional method
     * below, it marked the caller's transaction rollback-only, and catching it
     * afterwards did not unmark it. One unpublished workflow was enough to roll
     * back a whole round of scheduled triggers. The boundary is now per-trigger
     * so that can no longer spread, and this keeps the ordinary case from
     * raising anything to be contained in the first place.
     */
    @Transactional(readOnly = true)
    fun published(workflowId: Long): Boolean =
        publications.existsByWorkflowId(workflowId) ||
            workflows.findByIdOrNull(workflowId)?.status == WorkflowStatus.PUBLISHED

    @Transactional
    override fun graph(workspaceId: Long, workflowId: Long, version: GraphVersion): RunnableGraph {
        // A workflow runs for a workspace only if that workspace has it assigned.
        workspaces.findByIdOrNull(workspaceId) ?: throw RunnableWorkflowNotFound(workspaceId, workflowId)
        if (!assignments.existsByWorkspaceIdAndWorkflowId(workspaceId, workflowId)) {
            throw RunnableWorkflowNotFound(workspaceId, workflowId)
        }
        val workflow = workflows.findByIdOrNull(workflowId) ?: throw RunnableWorkflowNotFound(workspaceId, workflowId)

        if (version == GraphVersion.DRAFT) return drafted(workflowId, workflow.name)

        // The newest publication, which is what a restore makes: restoring
        // publishes the old graph again rather than reviving its row, so what
        // runs is always the most recent one here.
        publications.current(workflowId)?.let { held ->
            return WorkflowSnapshot.read(held.graph, mapper)
        }

        /*
         * Published before publishing meant anything.
         *
         * Every workflow that was live when snapshots arrived has a status
         * saying so and no snapshot to go with it, and refusing to run those
         * would take a working installation down at the moment it upgraded. So
         * the first run takes the copy that publishing would have taken, which
         * is exactly what was running a minute earlier, and every run after
         * that reads it like any other.
         */
        if (workflow.status == WorkflowStatus.PUBLISHED) {
            val taken = drafted(workflowId, workflow.name)
            publications.save(
                WorkflowPublication(
                    workflowId = workflowId,
                    publishedBy = "upgrade",
                    graph = WorkflowSnapshot.write(taken, mapper),
                ),
            )
            return taken
        }

        throw WorkflowNotPublishedException(workflowId)
    }

    /** What publishing copies, and what a person pressing Run is looking at. */
    fun drafted(workflowId: Long, name: String): RunnableGraph {
        val held = nodes.findByWorkflowId(workflowId)
        val drawn = edges.findByWorkflowId(workflowId)

        /*
         * The session nodes, by key, and the agents each of them names.
         *
         * A session is a declaration on the canvas rather than a step, so this
         * is where it stops being a node: what it holds is folded into the
         * agents it leads to, and neither it nor its edges reach the engine.
         * Doing it here, rather than in the runner, is what keeps a run honest -
         * this is the copy publishing takes, so a graph redrawn afterwards does
         * not change which conversation an already-published workflow talks into.
         */
        val declared = held.filter { it.kind == NodeKind.SESSION }.associateBy { it.nodeKey }
        val sessionFor = drawn
            .filter { it.sourceKey in declared }
            .associate { it.targetKey to declared.getValue(it.sourceKey) }

        return RunnableGraph(
            workflowId = workflowId,
            name = name,
            nodes = held.filterNot { it.kind == NodeKind.SESSION }.map { node ->
                GraphNode(
                    key = node.nodeKey,
                    kind = RunnableNodeKind.valueOf(node.kind.name),
                    name = node.name,
                    description = node.description,
                    agentId = node.agentId,
                    actionId = node.actionId,
                    conditionId = node.conditionId,
                    outputName = node.outputName,
                    // What this node passes, decided on the node. Seeded from the
                    // action when the node was placed, its own from then on.
                    mappings = bindings(node.mappings) + sessionOf(sessionFor[node.nodeKey]),
                    retryAttempts = node.retryAttempts,
                    retryBackoffSeconds = node.retryBackoffSeconds,
                    retryMultiplier = node.retryMultiplier,
                    retryMaxWaitSeconds = node.retryMaxWaitSeconds,
                    retryJitter = node.retryJitter,
                    retryBudgetSeconds = node.retryBudgetSeconds,
                    x = node.positionX,
                    y = node.positionY,
                )
            },
            edges = drawn
                .filterNot { it.sourceKey in declared || it.targetKey in declared }
                .map {
                    GraphEdge(it.sourceKey, it.targetKey, it.branch?.let { branch -> EdgeBranch.valueOf(branch.name) })
                },
        )
    }

    private fun bindings(mappings: List<NodeMapping>): Map<String, NodeBinding> = mappings.associate {
        it.name to NodeBinding(
            expression = it.expression,
            reference = it.mode == MappingMode.REFERENCE,
            from = it.sourceNodeKey,
        )
    }

    /**
     * The two parameters a session node contributes to the agent it leads to.
     *
     * They arrive under the names the runner already reads, and they are put on
     * *after* the agent's own, so a node still carrying the keys from before
     * session nodes existed is overridden by the session wired to it rather than
     * quietly winning against it. Nothing wired means nothing added, which is
     * what leaves those older nodes running exactly as they did.
     */
    private fun sessionOf(session: WorkflowNode?): Map<String, NodeBinding> =
        session?.mappings.orEmpty()
            .filter { it.name == SESSION_KEY || it.name == SESSION_KEY_PREFIX }
            .let(::bindings)

    private companion object {
        /** Which conversation the agents wired to a session node write into. */
        const val SESSION_KEY = "sessionKey"

        /** What that key is filed under; optional, see `LlmSessionKey`. */
        const val SESSION_KEY_PREFIX = "sessionKeyPrefix"
    }
}
