package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.action.ActionParameters
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceNotFoundException
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.action.ActionParamView
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.trigger.WorkflowTriggerRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional

@Controller
class WorkflowGraphAPI(
    private val workflows: WorkflowRepository,
    private val assignments: WorkspaceWorkflowRepository,
    private val nodes: WorkflowNodeRepository,
    private val edges: WorkflowEdgeRepository,
    private val triggers: WorkflowTriggerRepository,
    private val actions: WorkflowActionRepository,
    private val conditions: WorkflowConditionRepository,
    private val workspaces: WorkspaceRepository,
    private val validator: GraphValidator,
    private val parameters: ActionParameters,
    private val agents: AgentRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun workflowGraph(@Argument workspaceId: Long, @Argument workflowId: Long): WorkflowGraphView {
        requireAssignment(workspaceId, workflowId)
        return graphOf(workflowId)
    }

    /**
     * What a node would pass if it were pointed at this action right now.
     *
     * The editor asks when somebody picks an action, so the panel can show the
     * parameters that action has instead of an empty box. It is a suggestion:
     * what the node keeps is whatever is saved on it.
     */
    @QueryMapping
    fun actionParameterDefaults(@Argument workspaceId: Long, @Argument actionId: Long): List<NodeMappingView> {
        val workspace = workspaces.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)
        val action = actions.findByIdOrNull(actionId) ?: throw ActionNotInCatalogueException(actionId)
        if (action.workspaceId != workspaceId) throw ActionNotInCatalogueException(actionId)
        return parameters.defaultsFor(action).map { NodeMappingView(it.name, it.expression) }
    }

    /** Replaces the whole graph: the editor always sends the full picture. */
    @MutationMapping
    @Transactional
    fun saveWorkflowGraph(
        @Argument workspaceId: Long,
        @Argument workflowId: Long,
        @Argument input: WorkflowGraphInput,
    ): WorkflowGraphView {
        requireAssignment(workspaceId, workflowId)

        val keys = input.nodes.map { it.key }
        require(keys.size == keys.toSet().size) { "Node keys have to be unique within a workflow" }
        input.edges.forEach { edge ->
            require(edge.source in keys && edge.target in keys) {
                "Edge ${edge.source} -> ${edge.target} refers to a node that is not in the graph"
            }
        }

        input.nodes.forEach { requireTriggerBelongsToWorkspace(workspaceId, it) }
        input.nodes.forEach { requireActionBelongsToWorkspace(workspaceId, it) }
        input.nodes.forEach { requireConditionBelongsToWorkspace(workspaceId, it) }
        input.nodes.forEach { requireAgentBelongsToWorkspace(workspaceId, it) }

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
                    agentId = node.agentId.takeIf { node.kind == NodeKind.AGENT },
                    triggerId = node.triggerId.takeIf { node.kind == NodeKind.TRIGGER },
                    actionId = node.actionId.takeIf { node.kind == NodeKind.ACTION },
                    conditionId = node.conditionId.takeIf { node.kind == NodeKind.CONDITION },
                    positionX = node.x,
                    positionY = node.y,
                    mappings = mappingsFor(node),
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

        auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKFLOW, "Workflow ${workflow.name} graph updated")
        return graphOf(workflowId)
    }

    @MutationMapping
    @Transactional
    fun publishWorkflow(@Argument workspaceId: Long, @Argument workflowId: Long): WorkflowGraphView {
        requireAssignment(workspaceId, workflowId)

        val workflow = workflows.findByIdOrNull(workflowId) ?: throw WorkflowNotFoundException(workflowId)
        if (nodes.findByWorkflowId(workflowId).isEmpty()) throw WorkflowGraphEmptyException()

        workflow.status = WorkflowStatus.PUBLISHED
        auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKFLOW, "Workflow ${workflow.name} published")
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
     * What this node will pass, resolved against the action it points at.
     *
     * Taken from what was sent where it names a parameter the action actually
     * has, and seeded from the action otherwise. Resolving it this way keeps a
     * node honest when its action changes underneath it: parameters the new
     * action does not have are dropped, ones it gained arrive with the action's
     * own suggestion, and nothing has to remember to tidy up.
     *
     * The action is read here and nowhere else. Once a node holds its mappings
     * they are what runs, so editing them cannot reach back into a definition
     * other nodes are using.
     */
    private fun mappingsFor(node: WorkflowNodeInput): MutableList<NodeMapping> {
        if (node.kind != NodeKind.ACTION) return mutableListOf()
        val action = node.actionId?.let { actions.findByIdOrNull(it) } ?: return mutableListOf()

        val sent = node.mappings.orEmpty().associate { it.name to it.expression }
        return parameters.defaultsFor(action)
            .map { NodeMapping(name = it.name, expression = sent[it.name] ?: it.expression) }
            .toMutableList()
    }

    /**
     * A trigger node is an instance of a definition from the workspace's catalogue,
     * so the definition has to be one that workspace holds — otherwise a workflow
     * could listen to another workspace's connection.
     */
    private fun requireTriggerBelongsToWorkspace(workspaceId: Long, node: WorkflowNodeInput) {
        val triggerId = node.triggerId ?: return
        if (node.kind != NodeKind.TRIGGER) return
        val trigger = triggers.findByIdOrNull(triggerId) ?: throw TriggerNotInCatalogueException(triggerId)
        if (trigger.workspaceId != workspaceId) throw TriggerNotInCatalogueException(triggerId)
    }

    /** An action node runs one of the workspace's actions, and only its own workspace's. */
    private fun requireActionBelongsToWorkspace(workspaceId: Long, node: WorkflowNodeInput) {
        val actionId = node.actionId ?: return
        if (node.kind != NodeKind.ACTION) return
        val action = actions.findByIdOrNull(actionId) ?: throw ActionNotInCatalogueException(actionId)
        if (action.workspaceId != workspaceId) throw ActionNotInCatalogueException(actionId)
    }

    /** An agent node runs one of the workspace's agents, and only its own workspace's. */
    private fun requireAgentBelongsToWorkspace(workspaceId: Long, node: WorkflowNodeInput) {
        val agentId = node.agentId ?: return
        if (node.kind != NodeKind.AGENT) return
        val agent = agents.findByIdOrNull(agentId) ?: throw AgentNotInCatalogueException(agentId)
        if (agent.workspaceId != workspaceId) throw AgentNotInCatalogueException(agentId)
    }

    /** A condition node asks one of the workspace's conditions, and only its own workspace's. */
    private fun requireConditionBelongsToWorkspace(workspaceId: Long, node: WorkflowNodeInput) {
        val conditionId = node.conditionId ?: return
        if (node.kind != NodeKind.CONDITION) return
        val condition = conditions.findByIdOrNull(conditionId) ?: throw ConditionNotInCatalogueException(conditionId)
        if (condition.workspaceId != workspaceId) throw ConditionNotInCatalogueException(conditionId)
    }

    /** The workflow has to be assigned to a workspace the caller can see. */
    private fun requireAssignment(workspaceId: Long, workflowId: Long) {
        val workspace = workspaces.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)
        if (!assignments.existsByWorkspaceIdAndWorkflowId(workspaceId, workflowId)) {
            throw WorkflowNotFoundException(workflowId)
        }
    }
}

data class WorkflowNodeInput(
    val key: String,
    val kind: NodeKind,
    val name: String,
    val description: String? = null,
    /** The agent an agent node instances; ignored on any other kind. */
    val agentId: Long? = null,
    /** The trigger definition a trigger node instances; ignored on any other kind. */
    val triggerId: Long? = null,
    /** The action an action node instances; ignored on any other kind. */
    val actionId: Long? = null,
    /** The condition a condition node asks; ignored on any other kind. */
    val conditionId: Long? = null,
    /**
     * What this node passes to its action. Null leaves it to the action's own
     * suggestions, which is what a node freshly pointed at one wants.
     */
    val mappings: List<NodeMappingInput>? = null,
    val x: Double,
    val y: Double,
)

/** One parameter and where its value comes from: `{{input.x}}`, or a literal. */
data class NodeMappingInput(
    val name: String,
    val expression: String,
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
    /** The agent this node instances, when it is an agent node. */
    val agentId: Long?,
    val triggerId: Long?,
    val actionId: Long?,
    val conditionId: Long?,
    val x: Double,
    val y: Double,
    /** What the node needs, read off whatever it points at. */
    val inputs: List<ActionParamView> = emptyList(),
    /** What it hands on. */
    val outputs: List<ActionParamView> = emptyList(),
    /** What this node passes to its action, one entry per parameter it has. */
    val mappings: List<NodeMappingView> = emptyList(),
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
        agentId = node.agentId,
        triggerId = node.triggerId,
        actionId = node.actionId,
        conditionId = node.conditionId,
        x = node.positionX,
        y = node.positionY,
        inputs = inputs,
        outputs = outputs,
        mappings = node.mappings.map { NodeMappingView(it.name, it.expression) },
    )
}

data class NodeMappingView(val name: String, val expression: String)

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
    RuntimeException("Trigger $id is not in this workspace's catalogue")

class ActionNotInCatalogueException(id: Long) :
    RuntimeException("Action $id is not in this workspace's catalogue")

class AgentNotInCatalogueException(id: Long) :
    RuntimeException("No agent with id $id in this workspace")

class ConditionNotInCatalogueException(id: Long) :
    RuntimeException("Condition $id is not in this workspace's catalogue")
