package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.workflow.script.PluginPermission
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
     * the file. An unknown name is refused here as it is anywhere else: accepting
     * something that does not exist is not an acceptance of anything.
     */
    fun accepted(field: String?): Set<PluginPermission> = field.orEmpty()
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { PluginPermission.named(it) ?: throw PluginPermissionUnknownException(it) }
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
 * The plugin needs JavaScript nobody has agreed to yet.
 *
 * Not an error so much as the middle of a conversation: the upload is refused, the
 * list travels back with it, and loading it again while naming exactly that list is
 * the acceptance. Saying which permissions is what makes the second request an
 * agreement to *these* rather than a yes to whatever was asked.
 */
class PluginPermissionsNotAcceptedException(val needed: List<PluginPermissionView>) : RuntimeException(
    "This plugin needs ${needed.joinToString(", ") { "${it.name} (${it.summary.lowercase()})" }}. " +
        "Load it again accepting them to allow it.",
)
