package io.mszymanski.gyloli.server.workflow

import io.mszymanski.gyloli.server.team.TeamRepository
import io.mszymanski.gyloli.workflow.execution.GraphEdge
import io.mszymanski.gyloli.workflow.execution.GraphNode
import io.mszymanski.gyloli.workflow.execution.WorkflowGraph as RunnableGraph
import io.mszymanski.gyloli.workflow.execution.WorkflowGraphSource
import io.mszymanski.gyloli.workflow.execution.WorkflowNotFoundException as RunnableWorkflowNotFound
import io.mszymanski.gyloli.workflow.execution.NodeKind as RunnableNodeKind
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
    private val assignments: TeamWorkflowRepository,
    private val nodes: WorkflowNodeRepository,
    private val edges: WorkflowEdgeRepository,
    private val teams: TeamRepository,
) : WorkflowGraphSource {

    override fun graph(teamId: Long, workflowId: Long): RunnableGraph {
        // A workflow runs for a team only if that team has it assigned.
        teams.findByIdOrNull(teamId) ?: throw RunnableWorkflowNotFound(teamId, workflowId)
        if (!assignments.existsByTeamIdAndWorkflowId(teamId, workflowId)) {
            throw RunnableWorkflowNotFound(teamId, workflowId)
        }
        val workflow = workflows.findByIdOrNull(workflowId) ?: throw RunnableWorkflowNotFound(teamId, workflowId)

        return RunnableGraph(
            workflowId = workflowId,
            name = workflow.name,
            nodes = nodes.findByWorkflowId(workflowId).map { node ->
                GraphNode(
                    key = node.nodeKey,
                    kind = RunnableNodeKind.valueOf(node.kind.name),
                    name = node.name,
                    description = node.description,
                    agentClass = node.agentClass,
                    modelProvider = node.modelProvider,
                    actionId = node.actionId,
                    conditionId = node.conditionId,
                    x = node.positionX,
                    y = node.positionY,
                )
            },
            edges = edges.findByWorkflowId(workflowId).map { GraphEdge(it.sourceKey, it.targetKey) },
        )
    }
}
