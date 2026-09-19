package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.workflow.script.PluginCapability
import io.mszymanski.orknux.workflow.script.PluginPermission
import io.mszymanski.orknux.workflow.script.PluginRunner
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * What a plugin asked for, what somebody agreed to, and what is therefore relaxed.
 *
 * Three separate things, and this class exists to keep them separate. A plugin
 * *declares*; a person *accepts*; the sandbox is handed what was *granted*. Rolling
 * any two of those together is how a plugin ends up with something nobody was
 * shown — which is the failure this whole arrangement is for.
 *
 * The vocabulary is [PluginPermission] and nothing else. A name that is not on it
 * is refused at upload rather than dropped, because dropping it would load a plugin
 * having granted it less than it asked for: it would then fail somewhere in the
 * middle of a run, for a reason nobody had been told.
 */
@Component
class PluginPermissions(private val mapper: ObjectMapper) {

    /**
     * What the plugin's own answer comes to, or a refusal.
     *
     * @throws PluginPermissionUnknownException if it names something this server
     *   has no permission for — which includes anything that would reach outside
     *   the sandbox, because there is no name for those.
     */
    fun validated(declared: List<String>): Set<PluginPermission> = declared.map { asked ->
        PluginPermission.named(asked) ?: throw PluginPermissionUnknownException(asked)
    }.toSet()

    /** The set as it is stored: names, in the enumeration's order, so it is stable. */
    fun write(permissions: Set<PluginPermission>): String =
        mapper.writeValueAsString(PluginPermission.entries.filter { it in permissions }.map { it.name })

    /**
     * The set back out of a column.
     *
     * A name the column holds that this server no longer has is dropped. That is
     * the one place dropping is right: the permission does not exist any more, so
     * there is nothing to relax and nothing anybody can do about it — and refusing
     * to read the row would take a working installation down after an upgrade.
     */
    fun read(json: String): Set<PluginPermission> =
        mapper.readTree(json).values().mapNotNull { PluginPermission.named(it.asString("")) }.toSet()

    /**
     * What may be relaxed for this plugin, as the runners ask for it.
     *
     * Accepted **and** still declared. The second half is not redundant: what is
     * relaxed should never be wider than what the loaded plugin says it needs, and
     * writing the intersection here means that stays true however the two columns
     * came to hold what they hold.
     */
    fun grantedTo(plugin: Plugin): Set<PluginPermission> =
        read(plugin.acceptedPermissions) intersect read(plugin.declaredPermissions)

    /** For a screen, and for the list somebody is asked to accept. */
    fun viewOf(permissions: Set<PluginPermission>): List<PluginPermissionView> =
        PluginPermission.entries.filter { it in permissions }
            .map { PluginPermissionView(it.name, it.summary) }

    /**
     * What an upload said it accepts, as a set.
     *
     * A comma-separated list of names, because it arrives as a form field beside
     * the file. The field is shared with the capabilities, so a capability's name
     * is simply not for this reader — but a name known to neither is refused here
     * as it is anywhere else: accepting something that does not exist is not an
     * acceptance of anything.
     */
    fun accepted(field: String?): Set<PluginPermission> = field.orEmpty()
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        // A library path in the shared field is for the libraries reader, the
        // way a capability's name is for its own.
        .filterNot { PluginRunner.LIBRARY_PATH.matches(it) }
        .mapNotNull { name ->
            PluginPermission.named(name)
                ?: if (PluginCapability.named(name) != null) null else throw PluginPermissionUnknownException(name)
        }
        .toSet()
}

/**
 * The plugin asked for something this server has no permission for.
 *
 * Refused rather than ignored. The list is closed on purpose — see
 * [PluginPermission] — so a name that is not on it is either a typo or a plugin
 * expecting a capability this sandbox does not hand out, and both want saying.
 */
class PluginPermissionUnknownException(asked: String) : RuntimeException(
    "This plugin asks for \"$asked\", which is not something this server can grant. " +
        "It grants ${PluginPermission.names.joinToString(", ")}.",
)

/**
 * The plugin needs something nobody has agreed to yet — permissions, capabilities,
 * or both, each under its own name so neither is agreed to under cover of the
 * other.
 *
 * Not an error so much as the middle of a conversation: the upload is refused, the
 * lists travel back with it, and loading it again while naming exactly those lists
 * is the acceptance. One exception for both kinds because the refusal has to be
 * one question — refused one kind at a time, a plugin asking for both could never
 * be loaded: each refused load stores nothing, so accepting the second list would
 * be refused over the first again.
 */
class PluginAgreementNeededException(
    val permissions: List<PluginPermissionView>,
    val capabilities: List<PluginCapabilityView>,
    /** The library files it ships with, where those are new; paths only. */
    val libraries: List<String> = emptyList(),
) : RuntimeException(
    buildString {
        append("This plugin needs ")
        append(
            (permissions.map { "${it.name} (${it.summary.lowercase()})" } +
                capabilities.map { "${it.name} (${it.summary.lowercase()})" } +
                if (libraries.isEmpty()) emptyList() else listOf("${libraries.size} library file(s) of its own")
                ).joinToString(", "),
        )
        append(". Load it again accepting them to allow it.")
    },
)
