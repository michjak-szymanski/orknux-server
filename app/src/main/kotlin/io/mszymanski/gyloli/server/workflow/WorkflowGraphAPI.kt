package io.mszymanski.gyloli.server.workflow

import io.mszymanski.gyloli.server.security.TeamAccess
import io.mszymanski.gyloli.server.team.TeamAuditCategory
import io.mszymanski.gyloli.server.team.TeamAuditRecorder
import io.mszymanski.gyloli.server.team.TeamNotFoundException
import io.mszymanski.gyloli.server.team.TeamRepository
import io.mszymanski.gyloli.server.action.ActionParamView
import io.mszymanski.gyloli.server.action.WorkflowActionRepository
import io.mszymanski.gyloli.server.condition.WorkflowConditionRepository
import io.mszymanski.gyloli.server.trigger.WorkflowTriggerRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional

@Controller
class WorkflowGraphAPI(
    private val workflows: WorkflowRepository,
    private val assignments: TeamWorkflowRepository,
    private val nodes: WorkflowNodeRepository,
    private val edges: WorkflowEdgeRepository,
    private val triggers: WorkflowTriggerRepository,
    private val actions: WorkflowActionRepository,
    private val conditions: WorkflowConditionRepository,
    private val teams: TeamRepository,
    private val validator: GraphValidator,
    private val access: TeamAccess,
    private val auditRecorder: TeamAuditRecorder,
) {

    @QueryMapping
    fun workflowGraph(@Argument teamId: Long, @Argument workflowId: Long): WorkflowGraphView {
        requireAssignment(teamId, workflowId)
        return graphOf(workflowId)
    }

    /** Replaces the whole graph: the editor always sends the full picture. */
    @MutationMapping
    @Transactional
    fun saveWorkflowGraph(
        @Argument teamId: Long,
        @Argument workflowId: Long,
        @Argument input: WorkflowGraphInput,
    ): WorkflowGraphView {
        requireAssignment(teamId, workflowId)

        val keys = input.nodes.map { it.key }
        require(keys.size == keys.toSet().size) { "Node keys have to be unique within a workflow" }
        input.edges.forEach { edge ->
            require(edge.source in keys && edge.target in keys) {
                "Edge ${edge.source} -> ${edge.target} refers to a node that is not in the graph"
            }
        }

        input.nodes.forEach { requireTriggerBelongsToTeam(teamId, it) }
        input.nodes.forEach { requireActionBelongsToTeam(teamId, it) }
        input.nodes.forEach { requireConditionBelongsToTeam(teamId, it) }

        // A graph is drawn before it is finished, so only the shapes that could
        // never run are refused; everything else comes back as advice.
        val proposed = input.nodes.map { node ->
            WorkflowNode(
                workflowId = workflowId,
                nodeKey = node.key,
                kind = node.kind,
                name = node.name,
                positionX = node.x,
                positionY = node.y,
            )
        }
        val proposedEdges = input.edges.map { WorkflowEdge(workflowId = workflowId, sourceKey = it.source, targetKey = it.target) }
        val refusals = validator.problems(proposed, proposedEdges, hardOnly = true)
        if (refusals.isNotEmpty()) throw GraphInvalidException(refusals)

        nodes.deleteByWorkflowId(workflowId)
        edges.deleteByWorkflowId(workflowId)
        nodes.flush()
        edges.flush()

        nodes.saveAll(
            input.nodes.map { node ->
                WorkflowNode(
                    workflowId = workflowId,
                    nodeKey = node.key,
                    kind = node.kind,
                    name = node.name.trim().ifEmpty { "Untitled node" },
                    description = node.description?.trim()?.ifEmpty { null },
                    agentClass = node.agentClass?.trim()?.ifEmpty { null },
                    modelProvider = node.modelProvider?.trim()?.ifEmpty { null },
                    triggerId = node.triggerId.takeIf { node.kind == NodeKind.TRIGGER },
                    actionId = node.actionId.takeIf { node.kind == NodeKind.ACTION },
                    conditionId = node.conditionId.takeIf { node.kind == NodeKind.CONDITION },
                    positionX = node.x,
                    positionY = node.y,
                )
            },
        )
        edges.saveAll(
            input.edges.map { edge ->
                WorkflowEdge(workflowId = workflowId, sourceKey = edge.source, targetKey = edge.target)
            },
        )

        // Editing puts a published workflow back into draft.
        val workflow = workflows.findByIdOrNull(workflowId) ?: throw WorkflowNotFoundException(workflowId)
        workflow.status = WorkflowStatus.DRAFT

        auditRecorder.record(teamId, TeamAuditCategory.WORKFLOW, "Workflow ${workflow.name} graph updated")
        return graphOf(workflowId)
    }

    @MutationMapping
    @Transactional
    fun publishWorkflow(@Argument teamId: Long, @Argument workflowId: Long): WorkflowGraphView {
        requireAssignment(teamId, workflowId)

        val workflow = workflows.findByIdOrNull(workflowId) ?: throw WorkflowNotFoundException(workflowId)
        if (nodes.findByWorkflowId(workflowId).isEmpty()) throw WorkflowGraphEmptyException()

        workflow.status = WorkflowStatus.PUBLISHED
        auditRecorder.record(teamId, TeamAuditCategory.WORKFLOW, "Workflow ${workflow.name} published")
        return graphOf(workflowId)
    }

    private fun graphOf(workflowId: Long): WorkflowGraphView {
        val workflow = workflows.findByIdOrNull(workflowId) ?: throw WorkflowNotFoundException(workflowId)
        val held = nodes.findByWorkflowId(workflowId)
        val drawn = edges.findByWorkflowId(workflowId)
        return WorkflowGraphView(
            workflowId = workflowId,
            name = workflow.name,
            description = workflow.description,
            status = workflow.status,
            nodes = held.map { node ->
                val ports = validator.portsOf(node)
                WorkflowNodeView(node, ports.inputs, ports.outputs)
            },
            edges = drawn.map(::WorkflowEdgeView),
            problems = validator.problems(held, drawn),
        )
    }

    /**
     * A trigger node is an instance of a definition from the team's catalogue,
     * so the definition has to be one that team holds — otherwise a workflow
     * could listen to another team's connection.
     */
    private fun requireTriggerBelongsToTeam(teamId: Long, node: WorkflowNodeInput) {
        val triggerId = node.triggerId ?: return
        if (node.kind != NodeKind.TRIGGER) return
        val trigger = triggers.findByIdOrNull(triggerId) ?: throw TriggerNotInCatalogueException(triggerId)
        if (trigger.teamId != teamId) throw TriggerNotInCatalogueException(triggerId)
    }

    /** An action node runs one of the team's actions, and only its own team's. */
    private fun requireActionBelongsToTeam(teamId: Long, node: WorkflowNodeInput) {
        val actionId = node.actionId ?: return
        if (node.kind != NodeKind.ACTION) return
        val action = actions.findByIdOrNull(actionId) ?: throw ActionNotInCatalogueException(actionId)
        if (action.teamId != teamId) throw ActionNotInCatalogueException(actionId)
    }

    /** A condition node asks one of the team's conditions, and only its own team's. */
    private fun requireConditionBelongsToTeam(teamId: Long, node: WorkflowNodeInput) {
        val conditionId = node.conditionId ?: return
        if (node.kind != NodeKind.CONDITION) return
        val condition = conditions.findByIdOrNull(conditionId) ?: throw ConditionNotInCatalogueException(conditionId)
        if (condition.teamId != teamId) throw ConditionNotInCatalogueException(conditionId)
    }

    /** The workflow has to be assigned to a team the caller can see. */
    private fun requireAssignment(teamId: Long, workflowId: Long) {
        val team = teams.findByIdOrNull(teamId) ?: throw TeamNotFoundException(teamId)
        access.requireVisible(team)
        if (!assignments.existsByTeamIdAndWorkflowId(teamId, workflowId)) {
            throw WorkflowNotFoundException(workflowId)
        }
    }
}

data class WorkflowNodeInput(
    val key: String,
    val kind: NodeKind,
    val name: String,
    val description: String? = null,
    val agentClass: String? = null,
    val modelProvider: String? = null,
    /** The trigger definition a trigger node instances; ignored on any other kind. */
    val triggerId: Long? = null,
    /** The action an action node instances; ignored on any other kind. */
    val actionId: Long? = null,
    /** The condition a condition node asks; ignored on any other kind. */
    val conditionId: Long? = null,
    val x: Double,
    val y: Double,
)

data class WorkflowEdgeInput(
    val source: String,
    val target: String,
)

data class WorkflowGraphInput(
    val nodes: List<WorkflowNodeInput>,
    val edges: List<WorkflowEdgeInput>,
)

data class WorkflowNodeView(
    val key: String,
    val kind: NodeKind,
    val name: String,
    val description: String?,
    val agentClass: String?,
    val modelProvider: String?,
    val triggerId: Long?,
    val actionId: Long?,
    val conditionId: Long?,
    val x: Double,
    val y: Double,
    /** What the node needs, read off whatever it points at. */
    val inputs: List<ActionParamView> = emptyList(),
    /** What it hands on. */
    val outputs: List<ActionParamView> = emptyList(),
) {
    constructor(
        node: WorkflowNode,
        inputs: List<ActionParamView> = emptyList(),
        outputs: List<ActionParamView> = emptyList(),
    ) : this(
        key = node.nodeKey,
        kind = node.kind,
        name = node.name,
        description = node.description,
        agentClass = node.agentClass,
        modelProvider = node.modelProvider,
        triggerId = node.triggerId,
        actionId = node.actionId,
        conditionId = node.conditionId,
        x = node.positionX,
        y = node.positionY,
        inputs = inputs,
        outputs = outputs,
    )
}

data class WorkflowEdgeView(
    val source: String,
    val target: String,
) {
    constructor(edge: WorkflowEdge) : this(source = edge.sourceKey, target = edge.targetKey)
}

data class WorkflowGraphView(
    val workflowId: Long,
    val name: String,
    val description: String?,
    val status: WorkflowStatus,
    val nodes: List<WorkflowNodeView>,
    val edges: List<WorkflowEdgeView>,
    /** Everything the graph is missing, worst first; empty when it holds together. */
    val problems: List<GraphProblem> = emptyList(),
)

class WorkflowGraphEmptyException : RuntimeException("Add at least one node before publishing")

class TriggerNotInCatalogueException(id: Long) :
    RuntimeException("Trigger $id is not in this team's catalogue")

class ActionNotInCatalogueException(id: Long) :
    RuntimeException("Action $id is not in this team's catalogue")

class ConditionNotInCatalogueException(id: Long) :
    RuntimeException("Condition $id is not in this team's catalogue")
