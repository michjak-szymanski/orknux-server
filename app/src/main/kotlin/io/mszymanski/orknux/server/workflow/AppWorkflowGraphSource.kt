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

    @Transactional
    override fun graph(workspaceId: Long, workflowId: Long, version: GraphVersion): RunnableGraph {
        // A workflow runs for a workspace only if that workspace has it assigned.
        workspaces.findByIdOrNull(workspaceId) ?: throw RunnableWorkflowNotFound(workspaceId, workflowId)
        if (!assignments.existsByWorkspaceIdAndWorkflowId(workspaceId, workflowId)) {
            throw RunnableWorkflowNotFound(workspaceId, workflowId)
        }
        val workflow = workflows.findByIdOrNull(workflowId) ?: throw RunnableWorkflowNotFound(workspaceId, workflowId)

        if (version == GraphVersion.DRAFT) return drafted(workflowId, workflow.name)

        publications.findByIdOrNull(workflowId)?.let { held ->
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
        return RunnableGraph(
            workflowId = workflowId,
            name = name,
            nodes = nodes.findByWorkflowId(workflowId).map { node ->
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
                    mappings = node.mappings.associate {
                        it.name to NodeBinding(
                            expression = it.expression,
                            reference = it.mode == MappingMode.REFERENCE,
                            from = it.sourceNodeKey,
                        )
                    },
                    x = node.positionX,
                    y = node.positionY,
                )
            },
            edges = edges.findByWorkflowId(workflowId).map {
                GraphEdge(it.sourceKey, it.targetKey, it.branch?.let { branch -> EdgeBranch.valueOf(branch.name) })
            },
        )
    }
}
