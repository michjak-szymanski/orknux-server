package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.action.FunctionCaller
import io.mszymanski.orknux.server.action.FunctionScope
import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.action.WorkflowFunction
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.workflow.script.ScriptOrigin
import io.mszymanski.orknux.workflow.script.ScriptResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * Calling a plugin's function, as an agent does it.
 *
 * A plugin's functions were reachable from workflows - an action, a condition,
 * a webhook - and from nothing an agent holds: an agent's grants named
 * workspace tools and only those, so a Slack plugin could answer "who is this
 * mention" to a workflow and not to the agent reading the message. The grant
 * list now reaches these too, under the same rule as everything else on it: a
 * name on the list, resolved when the agent runs, dropped without ceremony
 * where nothing answers to it any more.
 *
 * A workspace tool of the same name wins, the way a built-in wins over a
 * workspace tool: the resolution order is the shadow rule, and a plugin's
 * names are prefixed with its key precisely so this stays theoretical.
 *
 * The call goes down [FunctionCaller], which is the one path a function is
 * called on - so an edited plugin function behaves here exactly as it does in
 * a workflow, and the plugin's settings and capability grants apply unchanged.
 */
@Service
class PluginToolCaller(
    private val functions: WorkflowFunctionRepository,
    private val caller: FunctionCaller,
    private val mapper: ObjectMapper,
) {

    /**
     * The plugin functions this agent may call: its granted names that resolve
     * to one. The caller has already taken the workspace tools off the list,
     * so what reaches this is only what nothing else answered to.
     */
    fun granted(agent: Agent, except: Set<String>): List<WorkflowFunction> =
        agent.tools
            .filterNot { it in except }
            .mapNotNull { functions.findByScopeAndName(FunctionScope.PLUGIN, it) }

    /** One granted plugin function by name, or null - for the dispatch. */
    fun resolve(agent: Agent, name: String): WorkflowFunction? =
        name.takeIf { it in agent.tools }
            ?.let { functions.findByScopeAndName(FunctionScope.PLUGIN, it) }

    /**
     * Runs one, handing it the arguments the model composed - by name in the
     * schema, positionally to the function, the same translation a workspace
     * tool's call makes and under the same two kindnesses.
     */
    fun call(agent: Agent, function: WorkflowFunction, arguments: String): String {
        val result = caller.call(
            function,
            argumentsFor(function, arguments),
            context = mapper.writeValueAsString(
                mapOf("workspaceId" to agent.workspaceId, "agent" to agent.name, "tool" to function.name),
            ),
            workspaceId = agent.workspaceId,
            origin = ScriptOrigin(),
        )
        return when (result) {
            is ScriptResult.Returned -> result.json ?: mapper.writeValueAsString(mapOf("result" to null))
            is ScriptResult.Failed -> {
                log.warn("Plugin function {} failed for agent {}: {}", function.name, agent.name, result.reason)
                mapper.writeValueAsString(mapOf("error" to result.reason))
            }
        }
    }

    /** The same layout rule as a workspace tool's; see [WorkspaceToolCaller.argumentsFor]. */
    private fun argumentsFor(function: WorkflowFunction, arguments: String): List<String> {
        if (function.params.isEmpty()) return emptyList()
        val sent = runCatching { mapper.readTree(arguments) }.getOrNull()

        return function.params.map { param ->
            val given = sent?.path(param.name)
            when {
                given == null || given.isMissingNode || given.isNull ->
                    if (function.params.size == 1) arguments.ifBlank { "{}" } else "null"

                given.isString && param.type != ValueType.STRING -> unwrapped(given.stringValue().orEmpty())
                    ?: mapper.writeValueAsString(given)

                else -> mapper.writeValueAsString(given)
            }
        }
    }

    /** The JSON inside a string a model stringified, or null if it was only a string. */
    private fun unwrapped(text: String): String? {
        val parsed = runCatching { mapper.readTree(text) }.getOrNull() ?: return null
        return if (parsed.isMissingNode || parsed.isNull || parsed.isString) null else text
    }

    private companion object {
        val log = LoggerFactory.getLogger(PluginToolCaller::class.java)
    }
}
