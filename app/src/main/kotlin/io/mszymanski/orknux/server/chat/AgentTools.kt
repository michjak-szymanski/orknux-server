package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.McpToolCaller
import io.mszymanski.orknux.server.agent.SkillTool
import io.mszymanski.orknux.server.agent.WorkspaceToolCaller
import io.mszymanski.orknux.server.memory.MemoryTool
import io.mszymanski.orknux.server.memory.ToolDescriptor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * What an agent may call, and what happens when it does.
 *
 * One place that knows every built-in, so the model is offered exactly what will
 * run: a tool declared but not implemented is a model told it can do something
 * it cannot, and it will believe you.
 *
 * What is offered depends on the agent. Memory appears only for an agent granted
 * catalogs, and skills only for an agent granted those — an agent given nothing
 * is handed no tools at all rather than tools that answer "nothing here", which
 * is a round trip spent to learn what the grant already said.
 */
@Service
class AgentTools(
    private val skills: SkillTool,
    private val memories: MemoryTool,
    private val workspaceTools: WorkspaceToolCaller,
    private val mcpTools: McpToolCaller,
    private val mapper: ObjectMapper,
) {

    fun specsFor(agent: Agent): List<ToolSpec> = buildList {
        if (agent.skillCatalogs.isNotEmpty()) addAll(skills.descriptors().map(::spec))
        if (agent.memoryCatalogs.isNotEmpty()) add(spec(memories.descriptor()))

        // The workspace's own code, under its own names. A tool named like a
        // built-in is skipped rather than shadowing it: two tools answering to
        // one name is a call nobody can predict the destination of.
        // What the granted MCP servers say they offer, asked of them now.
        addAll(mcpTools.specsFor(agent))

        val builtIn = map { it.name }.toSet()
        workspaceTools.granted(agent)
            .filterNot { tool ->
                (tool.name in builtIn).also { clash ->
                    if (clash) log.warn("Tool {} is named like a built-in and was not offered", tool.name)
                }
            }
            .forEach { tool ->
                add(
                    ToolSpec(
                        name = tool.name,
                        description = tool.description
                            ?: "One of this workspace's tools. It takes whatever it needs in `input`.",
                        parameters = listOf(
                            ToolParameterSpec(
                                name = "input",
                                description = "A JSON object holding whatever this tool needs.",
                                required = false,
                            ),
                        ),
                    ),
                )
            }
    }

    /**
     * Runs one call and returns what to hand back, as JSON text.
     *
     * Never throws. A tool that failed is a fact the model should be told, not a
     * failed conversation — it can apologise, try another way, or answer without
     * it, and any of those beats the whole exchange dying because a lookup did.
     */
    fun run(agent: Agent, call: ToolCall): String = try {
        when (call.name) {
            "skill_list" -> mapper.writeValueAsString(mapOf("skills" to skills.list(agent)))

            "skill_load" -> {
                val name = argument(call, "name").orEmpty()
                val found = skills.load(agent, name)
                if (found == null) {
                    mapper.writeValueAsString(
                        mapOf("error" to "You have no skill called $name. Call skill_list for the ones you have."),
                    )
                } else {
                    mapper.writeValueAsString(mapOf("name" to found.name, "content" to found.content))
                }
            }

            "memory_search" -> mapper.writeValueAsString(
                mapOf(
                    "results" to memories.search(
                        agent = agent,
                        query = argument(call, "query"),
                        catalog = argument(call, "catalog"),
                    ),
                ),
            )

            // Anything else is the workspace's own, and only if granted: a name
            // the model invented resolves to nothing rather than to code.
            else -> {
                val tool = workspaceTools.granted(agent).firstOrNull { it.name == call.name }
                val remote = mcpTools.resolve(agent, call.name)
                when {
                    tool != null -> workspaceTools.call(agent, tool, inputOf(call))
                    // An MCP tool takes its own named arguments, so the whole
                    // object goes through rather than being unwrapped.
                    remote != null -> mcpTools.call(remote.first, remote.second, call.arguments)
                    else -> mapper.writeValueAsString(mapOf("error" to "There is no tool called ${call.name}"))
                }
            }
        }
    } catch (failure: Exception) {
        log.warn("Tool {} failed for agent {}", call.name, agent.name, failure)
        mapper.writeValueAsString(mapOf("error" to (failure.message ?: "That tool could not be run")))
    }

    /**
     * What to hand a workspace tool.
     *
     * The model is asked to put everything in `input`, and what it puts there is
     * usually an object rather than a string — so the subtree is taken whole
     * rather than read as text. A model that ignored the instruction and sent
     * its arguments flat gets them passed through, which is more useful than
     * handing the tool nothing on a technicality.
     */
    private fun inputOf(call: ToolCall): String = runCatching {
        val given = mapper.readTree(call.arguments).path("input")
        when {
            given.isMissingNode || given.isNull -> call.arguments
            // Some models stringify the object; that string is the JSON.
            given.isString -> given.stringValue().orEmpty()
            else -> mapper.writeValueAsString(given)
        }
    }.getOrDefault(call.arguments)

    /** Arguments arrive as a JSON object in a string, whichever shape asked. */
    private fun argument(call: ToolCall, name: String): String? = runCatching {
        mapper.readTree(call.arguments).path(name).stringValue()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun spec(descriptor: ToolDescriptor) = ToolSpec(
        name = descriptor.name,
        description = descriptor.description,
        parameters = descriptor.parameters.map { ToolParameterSpec(it.name, it.description, it.required) },
    )

    private companion object {
        val log = LoggerFactory.getLogger(AgentTools::class.java)
    }
}
