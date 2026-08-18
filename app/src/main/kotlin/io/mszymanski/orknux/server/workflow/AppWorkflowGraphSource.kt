package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.EdgeBranch
import io.mszymanski.orknux.workflow.execution.GraphEdge
import io.mszymanski.orknux.workflow.execution.GraphNode
import io.mszymanski.orknux.workflow.execution.NodeBinding
import io.mszymanski.orknux.workflow.execution.WorkflowGraph as RunnableGraph
import io.mszymanski.orknux.workflow.execution.WorkflowGraphSource
import io.mszymanski.orknux.workflow.execution.WorkflowNotFoundException as RunnableWorkflowNotFound
import io.mszymanski.orknux.workflow.execution.NodeKind as RunnableNodeKind
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

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
) : WorkflowGraphSource {

    override fun graph(workspaceId: Long, workflowId: Long): RunnableGraph {
        // A workflow runs for a workspace only if that workspace has it assigned.
        workspaces.findByIdOrNull(workspaceId) ?: throw RunnableWorkflowNotFound(workspaceId, workflowId)
        if (!assignments.existsByWorkspaceIdAndWorkflowId(workspaceId, workflowId)) {
            throw RunnableWorkflowNotFound(workspaceId, workflowId)
        }
        val workflow = workflows.findByIdOrNull(workflowId) ?: throw RunnableWorkflowNotFound(workspaceId, workflowId)

        return RunnableGraph(
            workflowId = workflowId,
            name = workflow.name,
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
