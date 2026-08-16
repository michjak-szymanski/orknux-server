package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceNotFoundException
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.workspace.pageRequest
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional

@Controller
class AgentAPI(
    private val agents: AgentRepository,
    private val workspaces: WorkspaceRepository,
    private val nodes: WorkflowNodeRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val models: ModelService,
) {

    /** The agent, with what its model is called: the screen shows the name. */
    private fun describe(agent: Agent) = AgentView(agent, agent.modelId?.let { models.model(it)?.name })

    @QueryMapping
    fun workspaceAgents(@Argument workspaceId: Long, @Argument page: Int?, @Argument size: Int?): AgentPage {
        requireWorkspaceAccess(workspaceId)
        return AgentPage(agents.findByWorkspaceId(workspaceId, pageRequest(page, size, Sort.by("name"))), ::describe)
    }

    @QueryMapping
    fun agent(@Argument id: Long): AgentView? {
        val agent = agents.findByIdOrNull(id) ?: return null
        requireWorkspaceAccess(agent.workspaceId)
        return describe(agent)
    }

    @MutationMapping
    @Transactional
    fun createAgent(@Argument input: CreateAgentInput): AgentView {
        val name = input.name.trim()
        if (name.isEmpty()) throw AgentNameInvalidException()
        requireWorkspaceAccess(input.workspaceId)
        if (agents.findByWorkspaceIdAndName(input.workspaceId, name) != null) throw AgentNameTakenException(name)

        val agent = agents.save(
            Agent(
                workspaceId = input.workspaceId,
                name = name,
                type = input.type,
                description = input.description?.trim()?.ifEmpty { null },
                systemPrompt = input.systemPrompt?.trim()?.ifEmpty { null },
            ),
        )
        auditRecorder.record(input.workspaceId, WorkspaceAuditCategory.AGENT, "Agent $name created")
        return describe(agent)
    }

    /** Backs the agent settings form. */
    @MutationMapping
    @Transactional
    fun updateAgent(@Argument id: Long, @Argument input: UpdateAgentInput): AgentView {
        val name = input.name.trim()
        if (name.isEmpty()) throw AgentNameInvalidException()

        val agent = agents.findByIdOrNull(id) ?: throw AgentNotFoundException(id)
        requireWorkspaceAccess(agent.workspaceId)
        if (name != agent.name && agents.findByWorkspaceIdAndName(agent.workspaceId, name) != null) {
            throw AgentNameTakenException(name)
        }

        val previousName = agent.name
        val previousDescription = agent.description
        val previousPrompt = agent.systemPrompt
        val previousServers = agent.mcpServers.toList()
        val previousCatalogs = agent.memoryCatalogs.toList()
        val previousSkillCatalogs = agent.skillCatalogs.toList()
        val previousTools = agent.tools.toList()

        agent.name = name
        agent.description = input.description?.trim()?.ifEmpty { null }
        agent.systemPrompt = input.systemPrompt?.trim()?.ifEmpty { null }
        if (input.type != null) agent.type = input.type
        // A model from another workspace is not this agent's to use.
        val previousModel = agent.modelId
        agent.modelId = input.modelId?.let {
            val model = models.model(it) ?: throw AgentModelUnusableException("That model no longer exists")
            if (model.workspaceId != agent.workspaceId) {
                throw AgentModelUnusableException("That model belongs to another workspace")
            }
            model.id
        }
        if (input.mcpServers != null) {
            // Keep the given order, dropping blanks and repeats.
            agent.mcpServers = input.mcpServers.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()
        }
        if (input.memoryCatalogs != null) {
            agent.memoryCatalogs =
                input.memoryCatalogs.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()
        }
        if (input.skillCatalogs != null) {
            agent.skillCatalogs =
                input.skillCatalogs.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()
        }
        if (input.tools != null) {
            agent.tools = input.tools.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()
        }

        recordChanges(agent, previousName, previousDescription, previousPrompt, previousServers)
        if (agent.modelId != previousModel) {
            val named = agent.modelId?.let { models.model(it)?.name }
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                if (named == null) "Agent ${agent.name} model cleared" else "Agent ${agent.name} model set to $named",
            )
        }
        // A grant is worth an entry of its own: it changes what an agent can read.
        (agent.memoryCatalogs - previousCatalogs.toSet()).forEach { catalog ->
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                "Agent ${agent.name} given memory catalog $catalog",
            )
        }
        (previousCatalogs - agent.memoryCatalogs.toSet()).forEach { catalog ->
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                "Agent ${agent.name} no longer reads memory catalog $catalog",
            )
        }
        (agent.skillCatalogs - previousSkillCatalogs.toSet()).forEach { catalog ->
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                "Agent ${agent.name} given skill catalog $catalog",
            )
        }
        (previousSkillCatalogs - agent.skillCatalogs.toSet()).forEach { catalog ->
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                "Agent ${agent.name} no longer draws on skill catalog $catalog",
            )
        }
        // Worth its own entry above all the others: this one changes what an
        // agent can do, not just what it can read.
        (agent.tools - previousTools.toSet()).forEach { tool ->
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                "Agent ${agent.name} given tool $tool",
            )
        }
        (previousTools - agent.tools.toSet()).forEach { tool ->
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                "Agent ${agent.name} can no longer call tool $tool",
            )
        }
        return describe(agent)
    }

    @MutationMapping
    @Transactional
    fun setAgentEnabled(@Argument id: Long, @Argument enabled: Boolean): AgentView {
        val agent = agents.findByIdOrNull(id) ?: throw AgentNotFoundException(id)
        requireWorkspaceAccess(agent.workspaceId)
        agent.enabled = enabled
        auditRecorder.record(
            agent.workspaceId,
            WorkspaceAuditCategory.AGENT,
            "Agent ${agent.name} ${if (enabled) "enabled" else "disabled"}",
        )
        return describe(agent)
    }

    @MutationMapping
    @Transactional
    /**
     * Refused while a workflow node instances it.
     *
     * The same rule a condition follows, and for the same reason: the node
     * would be left pointing at nothing, and a run reaching it could only report
     * that the agent it was supposed to ask is gone. Better to say which
     * workflows are using it while there is still something to change.
     */
    fun deleteAgent(@Argument id: Long): Boolean {
        val agent = agents.findByIdOrNull(id) ?: return false
        requireWorkspaceAccess(agent.workspaceId)
        agents.delete(agent)
        auditRecorder.record(agent.workspaceId, WorkspaceAuditCategory.AGENT, "Agent ${agent.name} deleted")
        return true
    }

    /** One entry per thing that actually changed, worded as the audit view shows it. */
    private fun recordChanges(
        agent: Agent,
        previousName: String,
        previousDescription: String?,
        previousPrompt: String?,
        previousServers: List<String>,
    ) {
        if (agent.name != previousName) {
            auditRecorder.record(agent.workspaceId, WorkspaceAuditCategory.AGENT, "Agent $previousName renamed to ${agent.name}")
        }
        if (agent.description != previousDescription) {
            auditRecorder.record(agent.workspaceId, WorkspaceAuditCategory.AGENT, "Agent ${agent.name} description updated")
        }
        if (agent.systemPrompt != previousPrompt) {
            auditRecorder.record(agent.workspaceId, WorkspaceAuditCategory.AGENT, "Agent ${agent.name} system prompt updated")
        }
        (agent.mcpServers - previousServers.toSet()).forEach { server ->
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                "MCP Server $server added to ${agent.name}",
            )
        }
        (previousServers - agent.mcpServers.toSet()).forEach { server ->
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                "MCP Server $server removed from ${agent.name}",
            )
        }
    }

    private fun requireWorkspaceAccess(workspaceId: Long) {
        val workspace = workspaces.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)
    }
}

data class CreateAgentInput(
    val workspaceId: Long,
    val name: String,
    val type: AgentType,
    val description: String? = null,
    val systemPrompt: String? = null,
)

data class UpdateAgentInput(
    val name: String,
    val description: String? = null,
    val systemPrompt: String? = null,
    val type: AgentType? = null,
    /** Null leaves the current list alone; an empty list clears it. */
    /** Null clears the model, the way the form sends an unchosen select. */
    val modelId: Long? = null,
    val mcpServers: List<String>? = null,
    /** Same rule: null leaves it alone, an empty list clears it. */
    val memoryCatalogs: List<String>? = null,
    /** Which skill catalogs it may draw on; null leaves the grant alone. */
    val skillCatalogs: List<String>? = null,
    /** Which of the workspace's tools it may call; null leaves the grant alone. */
    val tools: List<String>? = null,
)

data class AgentView(
    val id: Long,
    val workspaceId: Long,
    val name: String,
    val type: AgentType,
    val description: String?,
    val systemPrompt: String?,
    val enabled: Boolean,
    val modelId: Long?,
    /** Null when the model it named has been removed. */
    val modelName: String?,
    val mcpServers: List<String>,
    val memoryCatalogs: List<String>,
    val skillCatalogs: List<String>,
    val tools: List<String>,
) {
    constructor(agent: Agent, modelName: String? = null) : this(
        id = requireNotNull(agent.id),
        workspaceId = agent.workspaceId,
        name = agent.name,
        type = agent.type,
        description = agent.description,
        systemPrompt = agent.systemPrompt,
        enabled = agent.enabled,
        modelId = agent.modelId,
        modelName = modelName,
        mcpServers = agent.mcpServers.toList(),
        memoryCatalogs = agent.memoryCatalogs.toList(),
        skillCatalogs = agent.skillCatalogs.toList(),
        tools = agent.tools.toList(),
    )
}

data class AgentPage(
    val content: List<AgentView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<Agent>, describe: (Agent) -> AgentView = ::AgentView) : this(
        content = page.content.map(describe),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}

class AgentInUseException(name: String, nodes: List<String>) : RuntimeException(
    "$name is used by ${nodes.joinToString(", ")}, so it cannot be deleted",
)

class AgentNotFoundException(id: Long) : RuntimeException("No agent with id $id")

class AgentNameTakenException(name: String) : RuntimeException("An agent named \"$name\" already exists in this workspace")

class AgentNameInvalidException : RuntimeException("An agent name is required")

/** A model chosen for an agent has to be one this workspace can reach. */
class AgentModelUnusableException(message: String) : RuntimeException(message)
