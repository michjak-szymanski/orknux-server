package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.server.plugin.PluginParameters
import io.mszymanski.orknux.server.plugin.PluginPermissions
import io.mszymanski.orknux.server.plugin.PluginRepository
import io.mszymanski.orknux.server.variable.VariableArguments
import io.mszymanski.orknux.workflow.script.PluginRunner
import io.mszymanski.orknux.workflow.script.ScriptResult
import io.mszymanski.orknux.workflow.script.ScriptRunner
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

/**
 * Calls one of a workspace's functions.
 *
 * Everything between "here is a function and its arguments" and "here is what it
 * answered" — the imports assembled by the host, the workspace's variables
 * appended after the declared parameters, the plugin sandbox for a function a
 * plugin declared — lives here and nowhere else.
 *
 * It is one class because there is now more than one door onto it. A workflow
 * node calls a function at a point the graph fixed; the editor's Run calls the
 * same function to find out what it does. If those were two pieces of code they
 * would be two behaviours, and the second one — the one somebody uses to decide
 * whether a function works — would be the one that is not what runs. A test run
 * that resolves grants differently, or skips an import, proves nothing.
 *
 * What it deliberately does not do is decide anything about *whether* to call.
 * Access, auditing and what to make of the answer belong to the caller: a node
 * turns a failure into a step that failed and may retry, and the editor turns
 * the same failure into a sentence on a panel.
 */
@Service
class FunctionCaller(
    private val scripts: ScriptRunner,
    private val scriptImports: ScriptImports,
    private val pluginRunner: PluginRunner,
    private val plugins: PluginRepository,
    private val pluginParameters: PluginParameters,
    private val pluginPermissions: PluginPermissions,
    private val externals: VariableArguments,
) {

    /**
     * Runs [function] and says what came back.
     *
     * @param declared JSON for each parameter the function declares, in the order
     *   it declares them. The workspace's variables are appended here rather than
     *   asked for, because they are not the caller's to supply: a grant belongs to
     *   the function that declared it, and a caller that could pass one could pass
     *   something else instead.
     * @param context what the script may know about where it is running, as JSON.
     * @param workspaceId which workspace is asking. Only a plugin's function reads
     *   it — its settings and the permissions somebody agreed to are per workspace.
     *
     * Never throws for anything the script did. A broken import and a plugin that
     * has not been configured come back as [ScriptResult.Failed] with `settled`
     * set, because neither would answer differently on a second attempt — which is
     * exactly what the node runner used to say by throwing a permanent failure.
     */
    fun call(
        function: WorkflowFunction,
        declared: List<String>,
        context: String,
        workspaceId: Long,
    ): ScriptResult {
        val arguments = declared + externals.of(function)

        /*
         * A plugin's function is not this workspace's JavaScript, and its source
         * column holds a note saying so rather than code. It runs in the plugin's
         * own sandbox, out of the plugin's own text, and it is handed what this
         * workspace answered the plugin's parameters with.
         */
        if (function.scope == FunctionScope.PLUGIN) return callPlugin(function, arguments, workspaceId)

        /*
         * What it imports is assembled before it runs, because the sandbox resolves
         * nothing itself. An import that no longer resolves is settled: nothing
         * about running it again would find the function somebody deleted.
         */
        return when (val resolved = scriptImports.resolve(function.imports, function.libraries)) {
            is ScriptImportsResult.Broken -> ScriptResult.Failed(resolved.reason, 0)

            is ScriptImportsResult.Resolved -> scripts.call(
                function.source,
                function.name,
                arguments,
                context,
                resolved.modules,
                resolved.imports,
            )
        }
    }

    /** The names the workspace's variables arrive under, for anything that has to say so. */
    fun grantsOf(function: WorkflowFunction): List<String> = externals.namesOf(function)

    /**
     * Runs a function one of the plugins declared.
     *
     * A required parameter nobody answered stops it before the plugin is loaded,
     * and stops it settled: what is missing is a piece of configuration, and
     * configuration does not appear because something was tried a second time. The
     * workspace's plugin page marks the same parameters, so the sentence here and
     * the red mark there are the same fact.
     */
    private fun callPlugin(function: WorkflowFunction, arguments: List<String>, workspaceId: Long): ScriptResult {
        val plugin = function.pluginId?.let { plugins.findByIdOrNull(it) }
            ?: return ScriptResult.Failed("is declared by a plugin that is no longer loaded", 0)

        val missing = pluginParameters.missingFor(plugin, workspaceId)
        if (missing.isNotEmpty()) {
            return ScriptResult.Failed(
                "cannot run: the ${plugin.key} plugin has not been told " + missing.joinToString(", ") +
                    ". Set it on this workspace's plugins page.",
                0,
            )
        }

        // The name the plugin gave it, not the prefixed one a workspace picks it
        // by: the prefix exists so two plugins can both declare `send`, and the
        // plugin never agreed to answer to it.
        val declared = function.name.removePrefix("${plugin.key}_")
        return pluginRunner.call(
            plugin.source,
            declared,
            arguments,
            pluginParameters.settingsFor(plugin, workspaceId),
            // What a person accepted for this plugin, and nothing else. Read per
            // call from this plugin's row, so one plugin's agreement cannot reach
            // another's context.
            pluginPermissions.grantedTo(plugin),
        )
    }
}
