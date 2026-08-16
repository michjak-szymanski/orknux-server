package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.workflow.script.ScriptResult
import io.mszymanski.orknux.workflow.script.ScriptRunner
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * Calling the workspace's own JavaScript, as an agent does it.
 *
 * The skills and memory tools are built in because they read tables a sandbox
 * cannot reach. This is the opposite case: a tool *is* the workspace's code, and
 * the sandbox is exactly where it belongs. Nothing here widens it — the same
 * [ScriptRunner] runs it, with the same limits, and everything crossing the
 * boundary is JSON text.
 *
 * What an agent may call is what it was granted. That matters more here than for
 * the reading tools: a skill is a page an agent reads, and a tool does something.
 */
@Service
class WorkspaceToolCaller(
    private val tools: AgentToolRepository,
    private val scripts: ScriptRunner,
    private val mapper: ObjectMapper,
) {

    /**
     * The tools this agent may call, in the order it was given them.
     *
     * A granted name that matches no tool is dropped rather than failing, the
     * way a granted catalog is: a rename should cost an agent one grant, not
     * every call it makes. A tool switched off is out of reach here as anywhere.
     */
    fun granted(agent: Agent): List<AgentTool> {
        if (agent.tools.isEmpty()) return emptyList()
        return agent.tools
            .mapNotNull { tools.findByWorkspaceIdAndName(agent.workspaceId, it) }
            .filter { it.enabled }
    }

    /**
     * Runs one, handing it whatever the model composed.
     *
     * One argument rather than named parameters, because a tool declares none:
     * it is a default export taking what it is given. The model is told to put
     * everything in `input`, and the tool's own description is what tells it
     * what belongs there.
     *
     * A script that failed comes back as a result rather than an exception. The
     * model can apologise, try another way, or answer without it — all of which
     * beat the conversation dying because a tool threw.
     */
    fun call(agent: Agent, tool: AgentTool, input: String): String {
        val result = scripts.call(
            source = tool.source,
            functionName = tool.name,
            arguments = listOf(input.ifBlank { "{}" }),
            context = context(agent, tool),
        )
        return when (result) {
            is ScriptResult.Returned -> result.json ?: mapper.writeValueAsString(mapOf("result" to null))
            is ScriptResult.Failed -> {
                log.warn("Tool {} failed for agent {}: {}", tool.name, agent.name, result.reason)
                mapper.writeValueAsString(mapOf("error" to result.reason))
            }
        }
    }

    /** What the script is told about where it is running. */
    private fun context(agent: Agent, tool: AgentTool): String = mapper.writeValueAsString(
        mapOf("workspaceId" to agent.workspaceId, "agent" to agent.name, "tool" to tool.name),
    )

    private companion object {
        val log = LoggerFactory.getLogger(WorkspaceToolCaller::class.java)
    }
}
