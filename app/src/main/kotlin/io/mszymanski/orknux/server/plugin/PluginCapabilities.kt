package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.workflow.script.PluginCapability
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * What a plugin asked the server to do for it, what somebody agreed to, and what
 * is therefore available.
 *
 * The same three-way separation [PluginPermissions] keeps, for the same reason,
 * and a second class rather than a second method on that one — because the two
 * lists must never be able to satisfy each other. A permission turns on a
 * language builtin and reaches nothing; a capability is the server making a call
 * on the plugin's behalf. Sharing a column, a validator or an acceptance between
 * them is how the smaller grant ends up covering the larger. Issue #316.
 */
@Component
class PluginCapabilities(private val mapper: ObjectMapper) {

    /**
     * What the plugin's own answer comes to, or a refusal.
     *
     * @throws PluginCapabilityUnknownException if it names one this server does
     *   not have. Refused rather than dropped: dropping would load a plugin
     *   having granted it less than it asked for, and it would then fail in the
     *   middle of a run for a reason nobody had been told.
     */
    fun validated(declared: List<String>): Set<PluginCapability> = declared.map { asked ->
        PluginCapability.named(asked) ?: throw PluginCapabilityUnknownException(asked)
    }.toSet()

    /** The set as it is stored: names, in the enumeration's order, so it is stable. */
    fun write(capabilities: Set<PluginCapability>): String =
        mapper.writeValueAsString(PluginCapability.entries.filter { it in capabilities }.map { it.name })

    /**
     * The set back out of a column.
     *
     * A name this server no longer has is dropped, which is the one place
     * dropping is right: the capability does not exist any more, so there is
     * nothing to offer and nothing anybody can do about it — and refusing to read
     * the row would take a working installation down after an upgrade.
     */
    fun read(json: String): Set<PluginCapability> =
        mapper.readTree(json).values().mapNotNull { PluginCapability.named(it.asString("")) }.toSet()

    /**
     * What this plugin may actually ask the server for.
     *
     * Accepted **and** still declared. The second half is not redundant: what is
     * offered should never be wider than what the loaded plugin says it needs,
     * and taking the intersection here means that stays true however the two
     * columns came to hold what they hold.
     */
    fun grantedTo(plugin: Plugin): Set<PluginCapability> =
        read(plugin.acceptedCapabilities) intersect read(plugin.declaredCapabilities)

    /** For a screen, and for the list somebody is asked to accept. */
    fun viewOf(capabilities: Set<PluginCapability>): List<PluginCapabilityView> =
        PluginCapability.entries
            .filter { it in capabilities }
            .map { PluginCapabilityView(name = it.name, summary = it.summary) }

    /**
     * What was sent with a load, as the set it names.
     *
     * The same comma-separated field the permissions come in on, read the same
     * way - and a name this server does not have is refused rather than dropped,
     * because accepting something nobody can name is not an acceptance.
     *
     * The two share the field: a load that agrees to both sends both, and each
     * of these reads only the names it knows. What keeps them apart is that
     * neither *set* can satisfy the other's check, not that the wire is split.
     */
    fun accepted(field: String?): Set<PluginCapability> = field.orEmpty()
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { PluginCapability.named(it) }
        .toSet()
}

/** One capability, as somebody deciding whether to accept a plugin reads it. */
data class PluginCapabilityView(val name: String, val summary: String)

class PluginCapabilityUnknownException(what: String) : RuntimeException(
    "This server has no capability called \"$what\". A plugin may only ask for the ones it has.",
)

/**
 * The plugin asks the server to do something nobody has agreed to yet.
 *
 * Its own refusal rather than the permissions one, so the sentence names what is
 * actually being asked for. "This plugin needs TEXT_ENCODING" and "this plugin
 * needs to read your Slack" are not decisions of the same size, and a screen
 * that showed them as one list would be asking for the second under cover of the
 * first.
 */
class PluginCapabilitiesNotAcceptedException(val needed: List<PluginCapabilityView>) : RuntimeException(
    "This plugin asks the server to do things on its behalf: " +
        "${needed.joinToString(", ") { "${it.name} (${it.summary.lowercase()})" }}. " +
        "Load it again accepting them to allow it.",
)
