package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.memory.ToolDescriptor
import io.mszymanski.orknux.server.memory.ToolParameter
import org.springframework.stereotype.Service

/**
 * Reading the workspace's skills, as an agent does it.
 *
 * A built-in for the same reason the memory lookup is: a workspace tool is
 * JavaScript in a sandbox with no IO, so it cannot read a table.
 *
 * Two tools rather than one, and that is the whole design. Skills are long —
 * a page of markdown each — and an agent granted five catalogs would spend most
 * of its context on instructions for work it is not doing. So the briefing lists
 * what is available by name and description, and the agent loads the one that
 * applies. What it may see is what it was granted; a catalog nobody gave it does
 * not appear in the list and cannot be loaded by guessing the name.
 */
@Service
class SkillTool(
    private val catalogs: SkillCatalogRepository,
    private val skills: AgentSkillRepository,
) {

    fun descriptors(): List<ToolDescriptor> = listOf(LIST, LOAD)

    /**
     * What this agent may draw on: one line each, enough to choose from.
     *
     * Deliberately without content — choosing which skill applies is what this
     * is for, and returning the text here would make the load tool pointless.
     */
    fun list(agent: Agent): List<SkillSummary> = granted(agent)
        .map { SkillSummary(it.name, it.description, catalogName(agent, it.catalogId)) }

    /**
     * One skill in full, by name.
     *
     * A name that is not in the granted list reads as absent rather than
     * refused: an agent guessing at a skill it was never given should learn that
     * there is no such skill, not that there is one it may not have.
     */
    fun load(agent: Agent, name: String): AgentSkill? =
        granted(agent).firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * The skills in the catalogs this agent holds.
     *
     * A granted name that matches no catalog is dropped rather than failing:
     * catalogs are granted by name, and a rename should cost an agent one grant
     * rather than every call it makes. A skill switched off is out of reach here
     * as everywhere.
     */
    private fun granted(agent: Agent): List<AgentSkill> {
        if (agent.skillCatalogs.isEmpty()) return emptyList()
        val held = agent.skillCatalogs.toSet()
        return catalogs.findByWorkspaceIdOrderByNameAsc(agent.workspaceId)
            .filter { it.name in held }
            .flatMap { skills.findByCatalogId(requireNotNull(it.id)) }
            .filter { it.enabled }
            .sortedBy { it.name }
    }

    private fun catalogName(agent: Agent, catalogId: Long): String =
        catalogs.findByWorkspaceIdOrderByNameAsc(agent.workspaceId).firstOrNull { it.id == catalogId }?.name.orEmpty()

    private companion object {
        val LIST = ToolDescriptor(
            name = "skill_list",
            description =
                "List the skills you have been given: the name, what each is for, and which catalog it is in. " +
                    "Call this when you want to know how this workspace goes about something.",
            parameters = emptyList(),
        )

        val LOAD = ToolDescriptor(
            name = "skill_load",
            description =
                "Read one skill in full, by its exact name from skill_list. " +
                    "Load a skill before following it rather than guessing at what it says.",
            parameters = listOf(
                ToolParameter(name = "name", description = "The skill's name, as skill_list gave it", required = true),
            ),
        )
    }
}

/** One line about a skill: enough to decide whether to load it. */
data class SkillSummary(val name: String, val description: String?, val catalog: String)
