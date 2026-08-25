package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.McpToolCaller
import io.mszymanski.orknux.server.agent.SkillTool
import io.mszymanski.orknux.server.agent.WorkspaceToolCaller
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.memory.MemoryTool
import io.mszymanski.orknux.server.shell.ShellTools
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
    private val orknux: OrknuxTools,
    private val shells: ShellTools,
    private val mapper: ObjectMapper,
) {

    /**
     * What an agent may reach of orknux: its own workspace, and no wider.
     *
     * Writing is allowed because starting a workflow is most of what an agent
     * granted this is for. The grant is the decision; there is no session here
     * to ask for a second one.
     */
    private fun scopeFor(agent: Agent) = OrknuxScope(workspaceId = agent.workspaceId, mayWrite = true)

    fun specsFor(agent: Agent): List<ToolSpec> = buildList {
        if (agent.skillCatalogs.isNotEmpty()) addAll(skills.descriptors().map(::spec))
        if (agent.memoryCatalogs.isNotEmpty()) add(spec(memories.descriptor()))

        // orknux itself, for an agent granted it. Scoped to the agent's own
        // workspace: the grant is the authorisation, and the workspace is the
        // boundary — there is no session here to ask about anything wider.
        if (agent.orknuxAccess) addAll(orknux.specs(scopeFor(agent)))

        // The machines, for an agent granted them. Unnamed and plural, which is
        // the design: an agent asks for a shell, not for a particular host, and
        // which one it gets is decided when the session opens.
        if (agent.shellAccess) addAll(shells.specs())

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
                        description = tool.description ?: "One of this workspace's tools.",
                        /*
                         * What the tool says it takes. Every tool used to be shown
                         * as taking one optional `input`, whatever it actually
                         * wanted, so the only account of its arguments the model
                         * ever got was the sentence above. A declared parameter is
                         * required: a tool that asked for it is a tool that needs it.
                         */
                        parameters = tool.params.map { param ->
                            ToolParameterSpec(
                                name = param.name,
                                description = meaning(param.type),
                                required = true,
                            )
                        },
                    ),
                )
            }
    }

    /**
     * What to put in one argument, in a sentence.
     *
     * Everything in a tool schema is declared a string on the way out, so the
     * shape a parameter wants can only be said in words. An object parameter is
     * described as a JSON object rather than by the name of the object it
     * points at: that name means something in the editor, where the shape is a
     * click away, and nothing at all to a model that has never seen it.
     */
    private fun meaning(type: ValueType): String = when (type) {
        ValueType.STRING -> "Text."
        ValueType.NUMBER -> "A number."
        ValueType.BOOLEAN -> "true or false."
        ValueType.OBJECT, ValueType.MAP -> "A JSON object."
        ValueType.ARRAY -> "A JSON array."
        // Never stored on a parameter; only a function's return type is nothing.
        ValueType.NONE -> "Nothing."
    }

    /**
     * Runs one call and returns what to hand back, as JSON text.
     *
     * Never throws. A tool that failed is a fact the model should be told, not a
     * failed conversation — it can apologise, try another way, or answer without
     * it, and any of those beats the whole exchange dying because a lookup did.
     */
    fun run(agent: Agent, call: ToolCall): String = try {
        /*
         * orknux's own, and only for an agent granted them.
         *
         * Checked before the rest by the prefix the surface owns, so a model
         * that guessed the name of a tool it was never offered is refused here
         * rather than reaching the workspace through a name it made up.
         */
        if (orknux.handles(call.name)) {
            if (agent.orknuxAccess) {
                orknux.run(scopeFor(agent), call.name, call.arguments)
            } else {
                mapper.writeValueAsString(mapOf("error" to "This agent has not been given access to orknux"))
            }
        } else if (shells.handles(call.name)) {
            /*
             * The shells, checked by name for the same reason orknux is checked
             * by prefix: a model that guessed the name of a tool it was never
             * offered is refused by the thing that would otherwise run it, and
             * not only by the menu it was never shown. ShellTools checks the
             * grant itself and says so in the words the model needs.
             */
            shells.run(agent, call.name, call.arguments)
        } else when (call.name) {
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
                    tool != null -> workspaceTools.call(agent, tool, call.arguments)
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

    /** Arguments arrive as a JSON object in a string, whichever shape asked. */
    private fun argument(call: ToolCall, name: String): String? = runCatching {
        mapper.readTree(call.arguments).path(name).stringValue()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun spec(descriptor: ToolDescriptor) = ToolSpec(
        name = descriptor.name,
        description = descriptor.description,
        parameters = descriptor.parameters.map { ToolParameterSpec(it.name, it.description, it.required) },
    )

    companion object {
        private val log = LoggerFactory.getLogger(AgentTools::class.java)

        /** Its own, because this is asked of text rather than of a running tool. */
        private val reader = ObjectMapper()

        /**
         * Whether what came back is this class saying the tool could not be
         * run, rather than the tool's own answer.
         *
         * Every refusal above is written as `{"error": …}` and nothing else
         * here writes that shape, so the question is answerable from the text —
         * which is what a caller has. Asked here rather than spelled out again
         * by whoever wants to know, so there is one description of a failed
         * call and it sits beside the four places that produce one.
         *
         * A tool of the workspace's own that chooses to answer `{"error": …}`
         * reads as a failure too. That is the right answer rather than a
         * limitation: a tool saying that is a tool reporting it could not do
         * what was asked, and the reader wants to know either way.
         */
        fun failed(result: String): Boolean = runCatching {
            val tree = reader.readTree(result)
            tree.isObject && tree.size() == 1 && tree.has("error")
        }.getOrDefault(false)
    }
}
