package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentSkillRepository
import io.mszymanski.orknux.server.agent.SkillCatalogRepository
import org.springframework.stereotype.Service

/**
 * What an agent is told before anything is said to it.
 *
 * An agent is a configuration, not a model: it names the model that answers, the
 * instructions it works under, and the skills it has been granted. This turns
 * that into the one system turn both request shapes already understand, so
 * chatting with an agent needs no new path through [ModelChatClient].
 *
 * Skills are listed here, not spelled out. Each one is a page of markdown, and
 * an agent granted five catalogs would spend most of its context on instructions
 * for work it is not doing — so the briefing gives the names and what each is
 * for, and the agent loads the one that applies with [SkillTool]. Memory is not
 * here at all for the same reason it never was: it is looked up when it turns
 * out to be needed, which is what [MemoryTool] is for.
 */
@Service
class AgentBriefing(
    private val catalogs: SkillCatalogRepository,
    private val skills: AgentSkillRepository,
) {

    /**
     * The system turn for this agent, or null when it has nothing to say — an
     * agent with no prompt and no skills is a model with a name on it, and an
     * empty system turn is worth fewer tokens than it costs.
     */
    fun of(agent: Agent): String? {
        val parts = mutableListOf<String>()
        agent.systemPrompt?.takeIf { it.isNotBlank() }?.let(parts::add)

        val granted = catalogs.findByWorkspaceIdOrderByNameAsc(agent.workspaceId)
            .filter { it.name in agent.skillCatalogs }
        val instructions = granted
            .flatMap { catalog -> skills.findByCatalogId(requireNotNull(catalog.id)) }
            // A skill switched off is defined but out of reach, here as anywhere.
            .filter { it.enabled }
            .sortedBy { it.name }

        if (instructions.isNotEmpty()) {
            parts += buildString {
                append("You have been given these skills, each describing how this workspace goes about ")
                append("something. Load the one that applies with skill_load before following it; ")
                appendLine("what is listed here is only enough to choose from.")
                instructions.forEach { skill ->
                    append("\n- ").append(skill.name)
                    skill.description?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                }
                appendLine()
            }
        }

        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }
}
