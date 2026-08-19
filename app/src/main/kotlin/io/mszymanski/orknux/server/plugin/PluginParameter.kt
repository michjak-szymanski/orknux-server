package io.mszymanski.orknux.server.plugin

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/**
 * What one workspace set one of a plugin's parameters to.
 *
 * A plugin is loaded once for the whole installation, but what it should be
 * pointed at is not the same everywhere: two workspaces using the same issue
 * tracker plugin talk to two projects with two tokens. So the declaration belongs
 * to the plugin and the answer belongs to the workspace, and this row is the
 * answer.
 *
 * Exactly one of [literalValue] and [variableId] is set. A literal is a value
 * somebody typed and is stored as typed, in the clear, because it is shown back to
 * them; anything that should not be stored in the clear is what [variableId] is
 * for, and a parameter the plugin declared as a secret may only be filled in that
 * way.
 *
 * A row for a parameter the plugin no longer declares is possible - a plugin can be
 * loaded again with a different declaration - and it is simply not read. Deleting
 * it on reload would throw away a value that a corrected plugin would want back an
 * hour later.
 */
@Entity
@Table(name = "plugin_parameter")
class PluginParameterSetting(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "plugin_id", nullable = false)
    val pluginId: Long,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    /** The name the plugin declared. Not a foreign key: the declaration is JSON. */
    @Column(nullable = false, length = 64)
    val name: String,

    /** What somebody typed, or null when this points at a variable. */
    @Column(name = "literal_value", columnDefinition = "text")
    var literalValue: String? = null,

    /** Which of the workspace's variables this reads, or null when it was typed in. */
    @Column(name = "variable_id")
    var variableId: Long? = null,

    @Column(name = "last_modified_at", nullable = false)
    var lastModifiedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_by", nullable = false, length = 120)
    var lastModifiedBy: String = "",
)

interface PluginParameterSettingRepository : JpaRepository<PluginParameterSetting, Long> {

    fun findByPluginIdAndWorkspaceId(pluginId: Long, workspaceId: Long): List<PluginParameterSetting>

    fun findByPluginIdAndWorkspaceIdAndName(pluginId: Long, workspaceId: Long, name: String): PluginParameterSetting?
}

/**
 * One of a plugin's parameters as one workspace sees it: what the plugin asked
 * for, and what this workspace answered.
 *
 * [literal] carries a typed-in value back to the form so it can be edited.
 * [variableName] carries only the name of the variable a reference points at -
 * never what it holds. A screen that could read a workspace's secrets by asking
 * a plugin screen for them would be a way around the variables screen, which
 * makes revealing one an audited act.
 */
data class PluginParameterSettingView(
    val name: String,
    val description: String?,
    val type: String,
    val required: Boolean,
    val secret: Boolean,
    val literal: String?,
    val variableId: String?,
    val variableName: String?,
    /** Required, and nothing usable is set for it. What the red mark is drawn from. */
    val missing: Boolean,
)

/**
 * A plugin as one workspace sees it.
 *
 * [missing] is the same answer as the marks on the parameters, gathered up: the
 * list needs one boolean per plugin and the detail needs one per parameter, and
 * they must not be able to disagree, so they are computed together.
 */
data class WorkspacePluginView(
    val plugin: PluginView,
    val parameters: List<PluginParameterSettingView>,
    val missing: List<String>,
)

class PluginParameterUnknownException(name: String, key: String) : RuntimeException(
    "\"$name\" is not a parameter the $key plugin declares. A plugin is given what it asked for and " +
        "nothing else - that is the point of it declaring them.",
)

class PluginParameterAmbiguousException(name: String) : RuntimeException(
    "\"$name\" was given both a value and a variable to read. It is one or the other.",
)

class PluginParameterEmptyException(name: String) : RuntimeException(
    "\"$name\" was given neither a value nor a variable. Clear it instead if that is what you meant.",
)

class PluginParameterNotSecretException(name: String) : RuntimeException(
    "The plugin declares \"$name\" as a secret, so it cannot be typed in here. Keep it as a variable and " +
        "point this at that - a value typed here is stored as typed and shown back on this page.",
)

class PluginParameterNotValueException(name: String, type: String, given: String) : RuntimeException(
    "\"$name\" is a $type, and \"$given\" is not one.",
)

class PluginParameterVariableElsewhereException(name: String) : RuntimeException(
    "That variable belongs to another workspace, so \"$name\" cannot read it.",
)
