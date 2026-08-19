package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.OffsetDateTime

/**
 * What a plugin is told, for one workspace.
 *
 * A plugin declares what it needs; a workspace answers each one with a value it
 * types or with one of its own variables; this turns the pair into the object the
 * plugin is handed. That object is the whole of what a plugin knows about the
 * workspace it is running for, which is the useful part of asking for parameters
 * at all - what a plugin can reach is a list somebody can read.
 *
 * Nothing here logs a resolved value. A parameter is as likely to be an API token
 * as a hostname, and the two are indistinguishable by the time they are here.
 */
@Service
class PluginParameters(
    private val settings: PluginParameterSettingRepository,
    private val variables: WorkspaceVariableRepository,
    private val declarations: PluginDeclarations,
    private val mapper: ObjectMapper,
) {

    /**
     * What this workspace's copy of the plugin is handed, as a JSON object.
     *
     * A parameter nothing usable is set for is left out rather than sent as null,
     * so a plugin can ask `if (this.settings.token === undefined)` and get the
     * answer it expects. A parameter the plugin does not declare is left out too,
     * whatever a row says: the declaration decides what crosses, not the settings
     * table, so a stale row cannot smuggle anything in.
     */
    fun settingsFor(plugin: Plugin, workspaceId: Long): String {
        val stored = byName(plugin, workspaceId)
        val json: ObjectNode = mapper.createObjectNode()

        declarations.readParameters(plugin.declaredParameters).forEach { parameter ->
            val setting = stored[parameter.name] ?: return@forEach
            val value = resolved(parameter, setting) ?: return@forEach
            json.set(parameter.name, mapper.readTree(value))
        }
        return mapper.writeValueAsString(json)
    }

    /**
     * The required parameters this workspace has not answered.
     *
     * "Not answered" covers more than an empty form: a reference whose variable has
     * been deleted, or which has never been given a value, is a parameter the
     * plugin will not receive, and saying it is set would be saying something
     * untrue about what will happen when it runs.
     */
    fun missingFor(plugin: Plugin, workspaceId: Long): List<String> {
        val stored = byName(plugin, workspaceId)
        return declarations.readParameters(plugin.declaredParameters)
            .filter { it.required }
            .filter { parameter -> resolved(parameter, stored[parameter.name]) == null }
            .map { it.name }
    }

    /** The plugin, its parameters and what they are set to, as one workspace sees it. */
    fun viewOf(plugin: Plugin, workspaceId: Long): WorkspacePluginView {
        val stored = byName(plugin, workspaceId)
        val declared = declarations.readParameters(plugin.declaredParameters)

        val parameters = declared.map { parameter ->
            val setting = stored[parameter.name]
            val variable = setting?.variableId?.let { variables.findByIdOrNull(it) }
            PluginParameterSettingView(
                name = parameter.name,
                description = parameter.description,
                type = parameter.type,
                required = parameter.required,
                secret = parameter.secret,
                literal = setting?.literalValue,
                variableId = variable?.id?.toString(),
                // The name only. What it holds is read on the variables screen,
                // where reading it is recorded as something somebody did.
                variableName = variable?.name,
                missing = parameter.required && resolved(parameter, setting) == null,
            )
        }

        return WorkspacePluginView(
            plugin = plugin.view(
                declarations.read(plugin.declaredFunctions),
                declarations.readParameters(plugin.declaredParameters),
            ),
            parameters = parameters,
            missing = parameters.filter { it.missing }.map { it.name },
        )
    }

    /**
     * Sets one parameter to a value somebody typed, or to one of the workspace's
     * variables. Never both, and never neither.
     *
     * A name the plugin does not declare is refused rather than kept. Keeping it
     * would mean the settings table quietly held a second, larger idea of what the
     * plugin can be told than the plugin's own declaration does, and the whole
     * reason for declaring parameters is that the two agree.
     */
    @Transactional
    fun set(
        plugin: Plugin,
        workspaceId: Long,
        name: String,
        literal: String?,
        variableId: Long?,
        by: String,
    ): PluginParameterSetting {
        val parameter = declarations.readParameters(plugin.declaredParameters).firstOrNull { it.name == name }
            ?: throw PluginParameterUnknownException(name, plugin.key)

        if (literal != null && variableId != null) throw PluginParameterAmbiguousException(name)
        if (literal == null && variableId == null) throw PluginParameterEmptyException(name)

        if (literal != null) {
            // A plugin asking for a secret is asking for something that should not
            // be sitting in a column somebody can read off this page.
            if (parameter.secret) throw PluginParameterNotSecretException(name)
            if (asJson(parameter.type, literal) == null) {
                throw PluginParameterNotValueException(name, parameter.type.lowercase(), literal)
            }
        }

        if (variableId != null) {
            val variable = variables.findByIdOrNull(variableId)
                ?: throw PluginParameterVariableElsewhereException(name)
            if (variable.workspaceId != workspaceId) throw PluginParameterVariableElsewhereException(name)
        }

        val existing = settings.findByPluginIdAndWorkspaceIdAndName(requireNotNull(plugin.id), workspaceId, name)
        val row = existing?.apply {
            this.literalValue = literal
            this.variableId = variableId
            this.lastModifiedAt = OffsetDateTime.now()
            this.lastModifiedBy = by
        } ?: PluginParameterSetting(
            pluginId = requireNotNull(plugin.id),
            workspaceId = workspaceId,
            name = name,
            literalValue = literal,
            variableId = variableId,
            lastModifiedBy = by,
        )
        return settings.save(row)
    }

    /** Unsets one parameter. A required one goes back to being reported as missing. */
    @Transactional
    fun clear(plugin: Plugin, workspaceId: Long, name: String) {
        settings.findByPluginIdAndWorkspaceIdAndName(requireNotNull(plugin.id), workspaceId, name)
            ?.let(settings::delete)
    }

    private fun byName(plugin: Plugin, workspaceId: Long): Map<String, PluginParameterSetting> =
        settings.findByPluginIdAndWorkspaceId(requireNotNull(plugin.id), workspaceId).associateBy { it.name }

    /**
     * What one parameter comes to, as JSON, or null when it comes to nothing.
     *
     * The parameter's declared type decides how the text is written, not the
     * variable's: the plugin said what it wanted, and a variable holding "8080" is
     * a usable answer to a parameter declared as a number.
     */
    private fun resolved(parameter: PluginParameterView, setting: PluginParameterSetting?): String? {
        if (setting == null) return null

        setting.literalValue?.let { return asJson(parameter.type, it) }

        val variableId = setting.variableId ?: return null
        val variable = variables.findByIdOrNull(variableId)
        if (variable == null) {
            // Named, not valued: this is the one place a warning about a secret
            // could accidentally carry it.
            log.warn("A plugin parameter reads a variable that no longer exists: {}", parameter.name)
            return null
        }
        return asJson(parameter.type, variable.value)
    }

    /** The text as the type says it should be written, or null when it is not that. */
    private fun asJson(type: String, held: String?): String? {
        val text = held?.trim() ?: return null
        if (text.isEmpty()) return null
        return when (type.uppercase()) {
            "STRING" -> mapper.writeValueAsString(held)
            "NUMBER" -> text.toBigDecimalOrNull()?.toString()
            "BOOLEAN" -> when (text.lowercase()) {
                "true" -> "true"
                "false" -> "false"
                else -> null
            }

            else -> null
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(PluginParameters::class.java)
    }
}
