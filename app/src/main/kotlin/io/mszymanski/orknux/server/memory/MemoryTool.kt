package io.mszymanski.orknux.server.memory

import io.mszymanski.orknux.server.agent.Agent
import org.springframework.stereotype.Service

/**
 * Looking things up in the workspace's memory, as an agent does it.
 *
 * A built-in rather than one of the workspace's tools, because a workspace tool
 * is JavaScript in a sandbox with no IO — it cannot read a table, and widening
 * the sandbox so it could would be a hole opened for one feature. So the lookup
 * is implemented here and offered to agents as something they may call.
 *
 * What an agent may read is what it was granted: [Agent.memoryCatalogs] names
 * the catalogs, and an agent granted none reads nothing. That is the point of
 * the grant — everything the workspace knows is rarely what one agent should be
 * given, and an agent that can read every catalog by default makes the grant a
 * decoration.
 */
@Service
class MemoryTool(
    private val catalogs: MemoryCatalogRepository,
    private val memories: MemoryRepository,
) {

    /** What this looks like to an agent choosing whether to call it. */
    fun descriptor(): ToolDescriptor = DESCRIPTOR

    /**
     * The catalogs this agent may read, as the screen would name them.
     *
     * A granted name that no longer matches a catalog is dropped rather than
     * failing: catalogs are granted by name, and a rename should cost an agent
     * one grant, not every call it makes.
     */
    fun catalogsFor(agent: Agent): List<MemoryCatalogView> {
        if (agent.memoryCatalogs.isEmpty()) return emptyList()
        val granted = agent.memoryCatalogs.toSet()
        return catalogs.findByWorkspaceIdOrderByNameAsc(agent.workspaceId)
            .filter { it.name in granted }
            .map { MemoryCatalogView(
                id = requireNotNull(it.id),
                workspaceId = it.workspaceId,
                name = it.name,
                memoryCount = memories.countByCatalogId(requireNotNull(it.id)).toInt(),
                createdAt = it.createdAt.toString(),
                createdBy = it.createdBy,
            ) }
    }

    /**
     * Searches the catalogs this agent holds, newest change first.
     *
     * A blank query returns what is there rather than nothing: an agent asking
     * "what do you know about this catalog" is a reasonable first move. The
     * limit is capped, because what comes back is going into a prompt.
     */
    fun search(agent: Agent, query: String?, catalog: String?, limit: Int = DEFAULT_LIMIT): List<MemoryResult> {
        val allowed = catalogsFor(agent)
            .filter { catalog == null || it.name.equals(catalog, ignoreCase = true) }
        if (allowed.isEmpty()) return emptyList()

        val wanted = query?.trim()?.ifEmpty { null }?.lowercase()
        val byName = allowed.associateBy { it.id }
        return memories.findByCatalogIdInOrderByLastModifiedAtDesc(byName.keys)
            .filter { memory ->
                wanted == null ||
                    memory.title.lowercase().contains(wanted) ||
                    memory.content.lowercase().contains(wanted)
            }
            .take(limit.coerceIn(1, MAX_LIMIT))
            .map { memory ->
                MemoryResult(
                    catalog = byName.getValue(memory.catalogId).name,
                    title = memory.title,
                    content = memory.content,
                    addedBy = memory.createdBy,
                )
            }
    }

    private companion object {
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 50

        val DESCRIPTOR = ToolDescriptor(
            name = "memory_search",
            description = "Search what this workspace has written down. " +
                "Only the memory catalogs this agent has been given are searched. " +
                "Call with no query to see what a catalog holds.",
            parameters = listOf(
                ToolParameter("query", "What to look for, in the title or the body. Optional.", required = false),
                ToolParameter("catalog", "Restrict to one catalog by name. Optional.", required = false),
            ),
        )
    }
}

/** One memory, in the shape an agent is handed it. */
data class MemoryResult(
    val catalog: String,
    val title: String,
    val content: String,
    val addedBy: String,
)

/**
 * What a built-in tool is, from the outside.
 *
 * Deliberately small: a name, what it is for, and what it takes. Enough for an
 * agent to be told it exists, and enough for a screen to list it beside the
 * workspace's own tools.
 */
data class ToolDescriptor(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>,
)

data class ToolParameter(
    val name: String,
    val description: String,
    val required: Boolean,
)
