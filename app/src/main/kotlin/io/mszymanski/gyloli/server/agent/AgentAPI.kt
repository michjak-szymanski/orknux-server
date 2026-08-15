package io.mszymanski.gyloli.server.agent

import io.mszymanski.gyloli.server.security.TeamAccess
import io.mszymanski.gyloli.server.team.TeamAuditCategory
import io.mszymanski.gyloli.server.team.TeamAuditRecorder
import io.mszymanski.gyloli.server.team.TeamNotFoundException
import io.mszymanski.gyloli.server.team.TeamRepository
import io.mszymanski.gyloli.server.team.pageRequest
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
    private val teams: TeamRepository,
    private val access: TeamAccess,
    private val auditRecorder: TeamAuditRecorder,
) {

    @QueryMapping
    fun teamAgents(@Argument teamId: Long, @Argument page: Int?, @Argument size: Int?): AgentPage {
        requireTeamAccess(teamId)
        return AgentPage(agents.findByTeamId(teamId, pageRequest(page, size, Sort.by("name"))))
    }

    @QueryMapping
    fun agent(@Argument id: Long): AgentView? {
        val agent = agents.findByIdOrNull(id) ?: return null
        requireTeamAccess(agent.teamId)
        return AgentView(agent)
    }

    @MutationMapping
    @Transactional
    fun createAgent(@Argument input: CreateAgentInput): AgentView {
        val name = input.name.trim()
        if (name.isEmpty()) throw AgentNameInvalidException()
        requireTeamAccess(input.teamId)
        if (agents.findByTeamIdAndName(input.teamId, name) != null) throw AgentNameTakenException(name)

        val agent = agents.save(
            Agent(
                teamId = input.teamId,
                name = name,
                type = input.type,
                description = input.description?.trim()?.ifEmpty { null },
                systemPrompt = input.systemPrompt?.trim()?.ifEmpty { null },
            ),
        )
        auditRecorder.record(input.teamId, TeamAuditCategory.AGENT, "Agent $name created")
        return AgentView(agent)
    }

    /** Backs the agent settings form. */
    @MutationMapping
    @Transactional
    fun updateAgent(@Argument id: Long, @Argument input: UpdateAgentInput): AgentView {
        val name = input.name.trim()
        if (name.isEmpty()) throw AgentNameInvalidException()

        val agent = agents.findByIdOrNull(id) ?: throw AgentNotFoundException(id)
        requireTeamAccess(agent.teamId)
        if (name != agent.name && agents.findByTeamIdAndName(agent.teamId, name) != null) {
            throw AgentNameTakenException(name)
        }

        val previousName = agent.name
        val previousDescription = agent.description
        val previousPrompt = agent.systemPrompt
        val previousServers = agent.mcpServers.toList()

        agent.name = name
        agent.description = input.description?.trim()?.ifEmpty { null }
        agent.systemPrompt = input.systemPrompt?.trim()?.ifEmpty { null }
        if (input.type != null) agent.type = input.type
        if (input.mcpServers != null) {
            // Keep the given order, dropping blanks and repeats.
            agent.mcpServers = input.mcpServers.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()
        }

        recordChanges(agent, previousName, previousDescription, previousPrompt, previousServers)
        return AgentView(agent)
    }

    @MutationMapping
    @Transactional
    fun setAgentEnabled(@Argument id: Long, @Argument enabled: Boolean): AgentView {
        val agent = agents.findByIdOrNull(id) ?: throw AgentNotFoundException(id)
        requireTeamAccess(agent.teamId)
        agent.enabled = enabled
        auditRecorder.record(
            agent.teamId,
            TeamAuditCategory.AGENT,
            "Agent ${agent.name} ${if (enabled) "enabled" else "disabled"}",
        )
        return AgentView(agent)
    }

    @MutationMapping
    @Transactional
    fun deleteAgent(@Argument id: Long): Boolean {
        val agent = agents.findByIdOrNull(id) ?: return false
        requireTeamAccess(agent.teamId)
        agents.delete(agent)
        auditRecorder.record(agent.teamId, TeamAuditCategory.AGENT, "Agent ${agent.name} deleted")
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
            auditRecorder.record(agent.teamId, TeamAuditCategory.AGENT, "Agent $previousName renamed to ${agent.name}")
        }
        if (agent.description != previousDescription) {
            auditRecorder.record(agent.teamId, TeamAuditCategory.AGENT, "Agent ${agent.name} description updated")
        }
        if (agent.systemPrompt != previousPrompt) {
            auditRecorder.record(agent.teamId, TeamAuditCategory.AGENT, "Agent ${agent.name} system prompt updated")
        }
        (agent.mcpServers - previousServers.toSet()).forEach { server ->
            auditRecorder.record(
                agent.teamId,
                TeamAuditCategory.AGENT,
                "MCP Server $server added to ${agent.name}",
            )
        }
        (previousServers - agent.mcpServers.toSet()).forEach { server ->
            auditRecorder.record(
                agent.teamId,
                TeamAuditCategory.AGENT,
                "MCP Server $server removed from ${agent.name}",
            )
        }
    }

    private fun requireTeamAccess(teamId: Long) {
        val team = teams.findByIdOrNull(teamId) ?: throw TeamNotFoundException(teamId)
        access.requireVisible(team)
    }
}

data class CreateAgentInput(
    val teamId: Long,
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
    val mcpServers: List<String>? = null,
)

data class AgentView(
    val id: Long,
    val teamId: Long,
    val name: String,
    val type: AgentType,
    val description: String?,
    val systemPrompt: String?,
    val enabled: Boolean,
    val mcpServers: List<String>,
) {
    constructor(agent: Agent) : this(
        id = requireNotNull(agent.id),
        teamId = agent.teamId,
        name = agent.name,
        type = agent.type,
        description = agent.description,
        systemPrompt = agent.systemPrompt,
        enabled = agent.enabled,
        mcpServers = agent.mcpServers.toList(),
    )
}

data class AgentPage(
    val content: List<AgentView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<Agent>) : this(
        content = page.content.map(::AgentView),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}

class AgentNotFoundException(id: Long) : RuntimeException("No agent with id $id")

class AgentNameTakenException(name: String) : RuntimeException("An agent named \"$name\" already exists in this team")

class AgentNameInvalidException : RuntimeException("An agent name is required")
