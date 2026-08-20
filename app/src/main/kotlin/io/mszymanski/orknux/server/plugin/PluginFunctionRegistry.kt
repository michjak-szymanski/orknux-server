package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.server.action.FunctionParam
import io.mszymanski.orknux.server.action.FunctionScope
import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.action.WorkflowFunction
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.trigger.WorkflowTriggerRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Turns what a plugin declared into functions a workflow can call.
 *
 * The declaration is the plugin's; these rows are the server's record of it. They
 * are scoped to the plugin rather than to a workspace, so every workspace can use
 * them, and nothing but the plugin can change them.
 *
 * Reconciled rather than appended: what the plugin declares now is what exists
 * afterwards. A function it no longer declares goes, one it has renamed arrives as
 * a new name, and loading the same plugin twice leaves the same set behind — which
 * is the whole reason the server asks the plugin rather than letting the plugin
 * announce itself whenever it likes.
 */
@Service
class PluginFunctionRegistry(
    private val functions: WorkflowFunctionRepository,
    private val actions: WorkflowActionRepository,
    private val conditions: WorkflowConditionRepository,
    private val triggers: WorkflowTriggerRepository,
    private val declarations: PluginDeclarations,
) {

    /**
     * Makes the plugin's declarations the set of functions it provides.
     *
     * @return the names now provided, for the audit line and the response.
     */
    @Transactional
    fun reconcile(plugin: Plugin): List<String> {
        val pluginId = requireNotNull(plugin.id)
        val declared = declarations.read(plugin.declaredFunctions)
        val wanted = declared.associateBy { qualified(plugin.key, it.name) }

        val existing = functions.findByPluginId(pluginId).associateBy { it.name }

        /*
         * Gone from the declaration means gone from the workspace — unless
         * something is calling it, in which case the plugin has taken away
         * something in use and that is worth refusing rather than breaking.
         */
        val removed = existing.keys - wanted.keys
        removed.forEach { name ->
            val function = existing.getValue(name)
            val callers = callersOf(requireNotNull(function.id))
            if (callers.isNotEmpty()) throw PluginFunctionInUseException(name, callers)
        }
        functions.deleteAll(removed.map(existing::getValue))

        wanted.forEach { (name, declaration) ->
            val params = declaration.params.map { FunctionParam(it.name, ValueType.valueOf(it.type)) }
            val returnType = ValueType.valueOf(declaration.returnType)

            val function = existing[name]?.apply {
                this.description = declaration.description
                this.returnType = returnType
                this.params = params.toMutableList()
                this.source = explanation(plugin, declaration.name)
                this.lastModifiedAt = OffsetDateTime.now()
                this.lastModifiedBy = "plugin ${plugin.key}"
            } ?: WorkflowFunction(
                workspaceId = null,
                scope = FunctionScope.PLUGIN,
                pluginId = pluginId,
                name = name,
                description = declaration.description,
                source = explanation(plugin, declaration.name),
                returnType = returnType,
                params = params.toMutableList(),
                lastModifiedAt = OffsetDateTime.now(),
                lastModifiedBy = "plugin ${plugin.key}",
            )
            functions.save(function)
        }

        return wanted.keys.sorted()
    }

    /**
     * Whether anything would break if this plugin were unloaded.
     *
     * Unloading cascades its functions away in the database, so the check has to
     * happen before, not during: a workflow pointing at a function that no longer
     * exists is a run that fails at the moment it matters.
     */
    fun inUse(plugin: Plugin): Map<String, List<String>> =
        functions.findByPluginId(requireNotNull(plugin.id))
            .associate { it.name to callersOf(requireNotNull(it.id)) }
            .filterValues { it.isNotEmpty() }

    /**
     * Actions, conditions and webhooks that name this function.
     *
     * The webhooks are here because a webhook may authenticate with one of these,
     * and a gatekeeper that has been cascaded away refuses every caller — the one
     * kind of breakage nobody is watching when it happens.
     */
    private fun callersOf(functionId: Long): List<String> =
        actions.findByFunctionId(functionId).map { it.name } +
            conditions.findAll().filter { it.functionId == functionId }.map { it.name } +
            triggers.findByAuthFunctionId(functionId).map { it.name }

    /**
     * `teammates_isTeammate` — the plugin's id, then the name it declared.
     *
     * Prefixed because a function is called by name and two plugins may reasonably
     * both offer `isTeammate`. The plugin's id is unique and its own names are
     * unique within it, so the pair cannot collide with anything.
     */
    private fun qualified(key: String, name: String): String = "${key}_$name"

    /**
     * What the source column holds for a function nobody wrote here.
     *
     * The column cannot be empty and the editor shows it, so it says what this is
     * instead of pretending to be an implementation. The plugin holds the real one.
     */
    private fun explanation(plugin: Plugin, declared: String): String = """
        // Provided by the "${plugin.name}" plugin (${plugin.key}).
        //
        // Its implementation lives in the plugin, not here, and this function
        // cannot be edited from a workspace. Load the plugin again to change it.
        //
        // Declared as: $declared
    """.trimIndent()
}

class PluginFunctionInUseException(name: String, callers: List<String>) : RuntimeException(
    "The plugin no longer declares \"$name\", but it is still used by " +
        "${callers.joinToString(", ")}. Change those first.",
)

class PluginInUseException(used: Map<String, List<String>>) : RuntimeException(
    "This plugin provides functions that are still in use: " +
        used.entries.joinToString("; ") { (name, callers) -> "$name (${callers.joinToString(", ")})" } +
        ". Change those first.",
)
