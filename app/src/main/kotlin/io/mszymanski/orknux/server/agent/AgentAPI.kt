package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.llm.CHARS_PER_TOKEN
import io.mszymanski.orknux.server.llm.ResolvedMemoryBudget
import io.mszymanski.orknux.server.llm.SessionMemoryBudgets
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workflow.WorkflowReferences
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.workspace.pageRequest
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import io.mszymanski.orknux.server.revision.ComponentRevisionRecorder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.security.core.context.SecurityContextHolder
import java.time.OffsetDateTime

@Controller
class AgentAPI(
    private val agents: AgentRepository,
    private val workspaces: WorkspaceRepository,
    private val references: WorkflowReferences,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val models: ModelService,
    private val revisions: ComponentRevisionRecorder,
    private val budgets: SessionMemoryBudgets,
) {

    /** The agent, with what its model is called: the screen shows the name. */
    private fun describe(agent: Agent) = AgentView(agent, agent.modelId?.let { models.model(it)?.name })

    /**
     * What this agent's memory share works out to against the model it uses.
     *
     * A field of its own rather than part of [describe], so the list of a
     * workspace's agents does not resolve a model and a budget per row to fill
     * in something only the settings screen asks for.
     */
    @SchemaMapping(typeName = "Agent", field = "memoryBudget")
    fun memoryBudget(agent: AgentView): SessionMemoryBudgetView =
        SessionMemoryBudgetView(budgets.resolve(agent.memoryShare, agent.modelId))

    /**
     * What a share would work out to, before anybody saves it.
     *
     * Asked of a workspace and a model rather than of an agent, because the
     * screen setting this has an unsaved form in front of it: the model may
     * have been changed in the same edit, and a preview that read the stored
     * agent would answer for the model it used to have.
     *
     * It never fails on a share that cannot work - it reports the refusal that
     * saving would raise, in the same words, so the slider can say why while it
     * is being dragged instead of only once Save has been pressed. The mutation
     * is what actually refuses, from the same calculation.
     */
    @QueryMapping
    fun memoryBudget(
        @Argument workspaceId: Long,
        @Argument modelId: Long?,
        @Argument share: Int?,
    ): SessionMemoryBudgetView {
        requireWorkspaceAccess(workspaceId)
        val model = modelId?.takeIf { models.model(it)?.workspaceId == workspaceId }
        return SessionMemoryBudgetView(budgets.resolve(share, model))
    }

    @QueryMapping
    fun workspaceAgents(@Argument workspaceId: Long, @Argument page: Int?, @Argument size: Int?): AgentPage {
        requireWorkspaceAccess(workspaceId)
        return AgentPage(agents.findByWorkspaceId(workspaceId, pageRequest(page, size, Sort.by("name"))), ::describe)
    }

    @QueryMapping
    fun agent(@Argument id: Long): AgentView? {
        val agent = agents.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return null
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
                icon = input.icon?.trim()?.ifEmpty { null },
                lastModifiedBy = currentUser(),
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

        val agent = agents.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw AgentNotFoundException(id)
        if (name != agent.name && agents.findByWorkspaceIdAndName(agent.workspaceId, name) != null) {
            throw AgentNameTakenException(name)
        }

        // What it is about to stop being. An agent has no draft, so a save is a
        // version; the recorder holds that rule, this door only reports.
        revisions.saved(agent)

        val previousName = agent.name
        val previousDescription = agent.description
        val previousPrompt = agent.systemPrompt
        val previousServers = agent.mcpServers.toList()
        val previousOrknux = agent.orknuxAccess
        val previousShell = agent.shellAccess
        val previousCatalogs = agent.memoryCatalogs.toList()
        val previousSkillCatalogs = agent.skillCatalogs.toList()
        val previousTools = agent.tools.toList()
        val previousShare = agent.memoryShare

        agent.name = name
        agent.description = input.description?.trim()?.ifEmpty { null }
        agent.systemPrompt = input.systemPrompt?.trim()?.ifEmpty { null }
        // Sent whenever the form saves, so null is "no icon" rather than "not
        // mentioned" — which is what lets Clear clear it.
        agent.icon = input.icon?.trim()?.ifEmpty { null }
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

        /*
         * Refused here rather than found out at the provider.
         *
         * Raising this is not free and not obviously bounded: a share too large
         * for the window is a request the provider rejects, on somebody's turn,
         * after the money for the tokens that did fit has been spent. The
         * refusal needs the model, so it is judged after the model is set and
         * against the one being saved rather than the one that was there.
         *
         * Sent whenever the form saves, so null is "the default" rather than
         * "not mentioned" - the same rule the icon follows, and what lets the
         * screen put it back to the default it started on.
         */
        agent.memoryShare = input.memoryShare
        if (input.memoryShare != null) {
            budgets.resolve(input.memoryShare, agent.modelId).refusal
                ?.let { throw AgentMemoryShareUnusableException(it) }
        }

        if (input.mcpServers != null) {
            // Keep the given order, dropping blanks and repeats.
            agent.mcpServers = input.mcpServers.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()
        }
        if (input.orknuxAccess != null) agent.orknuxAccess = input.orknuxAccess
        if (input.shellAccess != null) agent.shellAccess = input.shellAccess
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
        agent.lastModifiedAt = OffsetDateTime.now()
        agent.lastModifiedBy = currentUser()

        recordChanges(
            agent,
            previousName,
            previousDescription,
            previousPrompt,
            previousServers,
            previousOrknux,
            previousShell,
        )
        if (agent.modelId != previousModel) {
            val named = agent.modelId?.let { models.model(it)?.name }
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                if (named == null) "Agent ${agent.name} model cleared" else "Agent ${agent.name} model set to $named",
            )
        }
        /*
         * Its own entry, because it changes what every turn costs.
         *
         * A share raised is more of the window bought on every request this
         * agent makes from then on, and a bill that grew is a question somebody
         * asks the audit log rather than the agent.
         */
        if (agent.memoryShare != previousShare) {
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                agent.memoryShare?.let { "Agent ${agent.name} memory set to $it% of its model's context window" }
                    ?: "Agent ${agent.name} memory reset to the default",
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
        val agent = agents.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw AgentNotFoundException(id)
        // The toggle is a save: it changes what the workspace has.
        revisions.saved(agent)
        agent.enabled = enabled
        agent.lastModifiedAt = OffsetDateTime.now()
        agent.lastModifiedBy = currentUser()
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
     *
     * This paragraph described a guard that was not here. [AgentInUseException]
     * existed, unthrown; `findByAgentId` existed, with a comment saying it was
     * what kept an agent from being deleted from under a node, and nothing
     * called it. So the sentence is now true, and it covers the published copy
     * as well as the drawn one - that is the half a node taken off the canvas
     * cannot reach.
     */
    fun deleteAgent(@Argument id: Long): Boolean {
        val agent = agents.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return false

        val users = references.toAgent(agent.workspaceId, id)
        if (users.isNotEmpty()) throw AgentInUseException(agent.name, users)

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
        previousOrknux: Boolean,
        previousShell: Boolean,
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
        /*
         * The widest grant on this screen, and the only one that reaches outside
         * the application at all. Its own line for that reason: somebody reading
         * the log later needs to know when an agent first became able to run
         * commands on a machine, not merely that its settings were saved.
         */
        if (agent.shellAccess != previousShell) {
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                if (agent.shellAccess) {
                    "Shell access granted to ${agent.name}"
                } else {
                    "Shell access withdrawn from ${agent.name}"
                },
            )
        }
        // Worth a line of its own too: this is the grant that lets an agent start
        // workflows, which is the widest thing an agent can be given inside.
        if (agent.orknuxAccess != previousOrknux) {
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                if (agent.orknuxAccess) {
                    "Orknux access granted to ${agent.name}"
                } else {
                    "Orknux access withdrawn from ${agent.name}"
                },
            )
        }
    }

    private fun requireWorkspaceAccess(workspaceId: Long) {
        access.requireVisible(workspaceId)
    }

    /** Whoever is asking, for the stamp a revision of this state will carry. */
    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"
}

data class CreateAgentInput(
    val workspaceId: Long,
    val name: String,
    val type: AgentType,
    val description: String? = null,
    val systemPrompt: String? = null,
    /** Which icon a node drawn from this starts with; null draws the kind's own. */
    val icon: String? = null,
)

data class UpdateAgentInput(
    val name: String,
    val description: String? = null,
    val systemPrompt: String? = null,
    val type: AgentType? = null,
    /** Null clears the model, the way the form sends an unchosen select. */
    val modelId: Long? = null,
    /** Null leaves the current list alone; an empty list clears it. */
    val mcpServers: List<String>? = null,
    /** Whether it may ask orknux about orknux; null leaves the grant alone. */
    val orknuxAccess: Boolean? = null,
    /** Whether it may open a shell on a machine; null leaves the grant alone. */
    val shellAccess: Boolean? = null,
    /** Same rule: null leaves it alone, an empty list clears it. */
    val memoryCatalogs: List<String>? = null,
    /** Which skill catalogs it may draw on; null leaves the grant alone. */
    val skillCatalogs: List<String>? = null,
    /** Which of the workspace's tools it may call; null leaves the grant alone. */
    val tools: List<String>? = null,
    /** Which icon a node drawn from this starts with; null draws the kind's own. */
    val icon: String? = null,
    /**
     * How much of its model's context window a session may take back, as a
     * percentage.
     *
     * Sent whenever the form saves, so null is the built-in default rather than
     * "leave it alone" - the icon's rule, and what lets the screen put it back.
     */
    val memoryShare: Int? = null,
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
    /** Whether it may ask orknux about orknux. */
    val orknuxAccess: Boolean,
    /** Whether it may open a shell on one of the installation's machines. */
    val shellAccess: Boolean,
    val memoryCatalogs: List<String>,
    val skillCatalogs: List<String>,
    val tools: List<String>,
    /** Which icon a node drawn from this starts with; null draws the kind's own. */
    val icon: String?,
    /** Its share of the model's context window; null is the built-in default. */
    val memoryShare: Int?,
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
        orknuxAccess = agent.orknuxAccess,
        shellAccess = agent.shellAccess,
        memoryCatalogs = agent.memoryCatalogs.toList(),
        skillCatalogs = agent.skillCatalogs.toList(),
        tools = agent.tools.toList(),
        icon = agent.icon,
        memoryShare = agent.memoryShare,
    )
}

/**
 * A memory budget as the API reports it: in tokens, never in characters.
 *
 * The counts are characters everywhere inside, because that is what the
 * recorder can count and what every model agrees on. They are converted here
 * and nowhere else. Whoever sets this is looking at a context window measured
 * in tokens and will read any number beside it as tokens whatever the label
 * says, so a surface that reported characters would be read wrong by a factor
 * of four every time - and being wrong in that direction means asking for four
 * times the memory and paying for it.
 *
 * It is an approximation and says so in the schema. There is no tokeniser here:
 * it would have to be the provider's, it would differ per model, and it would
 * be run over a whole session on every turn to answer a question that only
 * decides where to cut.
 */
data class SessionMemoryBudgetView(
    val share: Int?,
    val contextWindow: Int?,
    val derived: Boolean,
    val totalTokens: Int,
    val conversationTokens: Int,
    val toolResultTokens: Int,
    val longestResultTokens: Int,
    val turns: Int,
    val toolResults: Int,
    val refusal: String?,
) {
    constructor(resolved: ResolvedMemoryBudget) : this(
        share = resolved.share,
        contextWindow = resolved.contextWindow,
        derived = resolved.derived,
        totalTokens = resolved.budget.totalChars / CHARS_PER_TOKEN,
        conversationTokens = resolved.budget.memoryChars / CHARS_PER_TOKEN,
        toolResultTokens = resolved.budget.recallChars / CHARS_PER_TOKEN,
        longestResultTokens = resolved.budget.longestResult / CHARS_PER_TOKEN,
        turns = resolved.budget.turns,
        toolResults = resolved.budget.results,
        refusal = resolved.refusal,
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

/**
 * A share of a context window that could not work, refused where it was set.
 *
 * Carries the sentence `SessionMemoryBudgets` wrote, which names the model, its
 * window and what it reserves for its answer - because "too large" tells nobody
 * what would fit.
 */
class AgentMemoryShareUnusableException(message: String) : RuntimeException(message)
