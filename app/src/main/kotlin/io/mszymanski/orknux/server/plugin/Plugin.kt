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
 * A plugin loaded into this installation.
 *
 * Nothing here says what a plugin *does*. It is the text somebody uploaded, what
 * it is called, and who put it there — the record of a thing that has been
 * loaded, not a description of an interface it implements. Entry points,
 * capabilities and grants all belong to work that has not been done yet, and
 * inventing columns for them now would be guessing.
 *
 * Installation-level, so there is no workspace on the row. An operator loads a
 * plugin once; which workspaces may use it is a later question.
 */
@Entity
@Table(name = "plugin")
class Plugin(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /**
     * What the plugin calls itself, and the namespace for what it declares.
     *
     * The plugin's identity rather than the file's: loading the same key again
     * replaces what is loaded, whatever the file was named. Its functions arrive as
     * `key_name`, which is why it is short.
     */
    @Column(name = "plugin_key", nullable = false, length = 32)
    var key: String,

    /** From the filename. What it is called on screen; the key is what it *is*. */
    @Column(nullable = false, length = 200)
    var name: String,

    @Column(nullable = false, length = 255)
    var filename: String,

    @Column(nullable = false, columnDefinition = "text")
    var source: String,

    /**
     * What it was written in, or null when it was written in JavaScript.
     *
     * Never evaluated — [source] is what runs, always. This is kept so the plugin can
     * be downloaded as the thing somebody actually wrote: hand back the compiled
     * output instead and the annotations are gone, with no way to recover them.
     */
    @Column(columnDefinition = "text")
    var typescript: String? = null,

    @Column(name = "size_bytes", nullable = false)
    var sizeBytes: Long,

    /**
     * The plugin API this plugin says it uses, as it answered when it was
     * uploaded. Only versions this server knows are ever stored.
     */
    @Column(name = "api_version", nullable = false)
    var apiVersion: Int,

    /** Of the source text, so an unchanged re-upload is recognisable. */
    @Column(nullable = false, length = 64)
    var sha256: String,

    /**
     * What the plugin answered when asked which functions it offers, as JSON.
     *
     * The declaration, not the registration: these become callable when they are
     * materialised as `workflow_function` rows, and that is a separate step
     * because a function belongs to a workspace and a plugin does not.
     */
    @Column(name = "declared_functions", nullable = false, columnDefinition = "text")
    var declaredFunctions: String = "[]",

    /**
     * What the plugin answered when asked what it has to be told, as JSON.
     *
     * The plugin's half of the bargain: it says what it needs and a workspace says
     * what those come to. Kept on the plugin rather than beside the settings
     * because it is the plugin's statement, and it is replaced wholesale every time
     * the plugin is loaded — the same way its functions are.
     */
    @Column(name = "declared_parameters", nullable = false, columnDefinition = "text")
    var declaredParameters: String = "[]",

    /**
     * What JavaScript the plugin says it needs, as JSON, as of this load.
     *
     * The plugin's claim, replaced wholesale every time it is loaded — the same
     * way its functions and its parameters are. It grants nothing on its own.
     */
    @Column(name = "declared_permissions", nullable = false, columnDefinition = "text")
    var declaredPermissions: String = "[]",

    /**
     * What a person agreed to, as JSON. The only thing that is ever relaxed.
     *
     * Kept apart from [declaredPermissions] because they are two different facts,
     * and the whole design turns on that. A plugin edited to need more declares
     * more; what was accepted still names only what it was accepted for, so the
     * new one is not covered and loading is refused until somebody accepts it
     * afresh. An escalation cannot inherit an acceptance because the acceptance
     * is a list rather than a yes.
     */
    @Column(name = "accepted_permissions", nullable = false, columnDefinition = "text")
    var acceptedPermissions: String = "[]",

    /** Null for a plugin that asks for nothing: there was nothing to accept. */
    @Column(name = "permissions_accepted_at")
    var permissionsAcceptedAt: OffsetDateTime? = null,

    /**
     * Who accepted it.
     *
     * Here rather than only in the audit log, because the question somebody asks
     * later is "what is this plugin allowed to do, and who said so" — and an
     * answer that has to be assembled out of a log is an answer nobody assembles.
     */
    @Column(name = "permissions_accepted_by", length = 120)
    var permissionsAcceptedBy: String? = null,

    @Column(name = "uploaded_at", nullable = false)
    var uploadedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "uploaded_by", nullable = false, length = 120)
    var uploadedBy: String = "",
)

interface PluginRepository : JpaRepository<Plugin, Long> {

    fun findAllByOrderByNameAsc(): List<Plugin>

    /** By what the plugin calls itself, which is what a re-upload replaces. */
    fun findByKey(key: String): Plugin?
}

/**
 * What the screen is told about a plugin.
 *
 * The source is not on it. A list of what is loaded does not need the text, and
 * a plugin can be a few hundred kilobytes — sending every one of them to draw a
 * table is a waste, and it will still be a waste when there are twenty.
 */
data class PluginView(
    val id: String,
    /** What the plugin calls itself, and the prefix on everything it declares. */
    val key: String,
    val name: String,
    val filename: String,
    val sizeBytes: Double,
    val apiVersion: Int,
    val declaredFunctions: List<PluginFunctionView>,
    /** What it says it has to be told before it can work. */
    val declaredParameters: List<PluginParameterView>,
    /** What it says it needs, and what was agreed to. Equal, for a loaded plugin. */
    val permissions: List<PluginPermissionView>,
    /** ISO-8601. Null for a plugin that asked for nothing; there was nothing to accept. */
    val permissionsAcceptedAt: String?,
    val permissionsAcceptedBy: String?,
    val sha256: String,
    val uploadedAt: String,
    val uploadedBy: String,
)

/**
 * One function a plugin says it offers.
 *
 * Carries the signature ready to read, the way a workspace's own functions do —
 * the same string in the same shape, so a list can show either without knowing
 * which it has.
 */
data class PluginFunctionView(
    val name: String,
    val description: String?,
    val params: List<PluginFunctionParamView>,
    val returnType: String,
    val signature: String,
)

data class PluginFunctionParamView(val name: String, val type: String)

/**
 * One thing a plugin says it has to be told, as the screen shows it.
 *
 * [secret] is the plugin's own claim about what it is asking for, not a promise
 * about how it is kept: the server refuses to store a secret parameter as a
 * literal, so the only way to fill one in is to point at a variable.
 */
data class PluginParameterView(
    val name: String,
    val description: String?,
    val type: String,
    val required: Boolean,
    val secret: Boolean,
)

/**
 * One piece of JavaScript a plugin was granted, as the screen shows it.
 *
 * Shown to whoever looks at the plugin list, not only to whoever accepted it. A
 * decision about what code may do that lives in a dialog somebody clicked through
 * last month is a decision nobody can audit.
 */
data class PluginPermissionView(
    val name: String,
    /** What it gives, in the words the person accepting it was shown. */
    val summary: String,
)

/**
 * The plugin API versions this server knows.
 *
 * A plugin says which one it was written against and is refused if that is not on
 * this list. The list is here rather than inline so that adding a version, or
 * dropping support for an old one, is one edit in one place.
 *
 * Named `PluginApiVersions` rather than the obvious `PluginApi`: the controller
 * beside it is `PluginAPI`, and on a case-insensitive filesystem those two are
 * the same class file. The JVM notices at load time, not at compile time, so it
 * fails as a `NoClassDefFoundError` on the first request rather than in the build.
 */
object PluginApiVersions {

    /** What a plugin written today should answer. */
    const val CURRENT = 1

    /** Every version this server can still run. */
    val SUPPORTED = setOf(1)
}

/**
 * The declarations are passed in rather than parsed here: reading them needs the
 * application's JSON mapper, and a view of a row should not be reaching for a
 * bean of its own to build itself.
 */
fun Plugin.view(
    declared: List<PluginFunctionView>,
    parameters: List<PluginParameterView> = emptyList(),
    permissions: List<PluginPermissionView> = emptyList(),
): PluginView = PluginView(
    id = requireNotNull(id).toString(),
    key = key,
    name = name,
    filename = filename,
    // GraphQL has no long; the rest of this schema reports sizes as floats too,
    // and a plugin is nowhere near where a double stops counting exactly.
    sizeBytes = sizeBytes.toDouble(),
    apiVersion = apiVersion,
    declaredFunctions = declared,
    declaredParameters = parameters,
    permissions = permissions,
    permissionsAcceptedAt = permissionsAcceptedAt?.toString(),
    permissionsAcceptedBy = permissionsAcceptedBy,
    sha256 = sha256,
    uploadedAt = uploadedAt.toString(),
    uploadedBy = uploadedBy,
)
