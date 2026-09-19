package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.plugin.PluginDeclarations
import io.mszymanski.orknux.server.plugin.PluginRepository
import org.springframework.stereotype.Service

/**
 * The instruction sets plugins bring, as catalogs an agent can be granted.
 *
 * A plugin declares three surfaces and says who each is for: `functions()` for
 * workflows, `tools()` for a model to call, `skills()` for a model to read. The
 * third is this one. Nothing here runs — a skill is markdown an agent reads
 * before doing something — and a plugin ships it so the knowledge of how its
 * work is meant to be done travels with the code that does it, instead of being
 * retyped into every workspace by hand.
 *
 * **A plugin's skills are a catalog named after the plugin**, which is the whole
 * of the grant model. Skills have always been granted by catalog, and a plugin's
 * are granted the same way, from the same picker, by the same field on the
 * agent. Nothing is automatic: a plugin loaded into this installation hands its
 * skills to nobody until somebody grants the catalog.
 *
 * A plugin switched off offers none, the way its tools stop resolving — the
 * grant naming it stays on the agent, so switching the plugin back on puts
 * things back rather than making somebody grant them again.
 */
@Service
class PluginSkills(
    private val plugins: PluginRepository,
    private val declarations: PluginDeclarations,
) {

    /**
     * Every catalog the loaded plugins offer, by name.
     *
     * A plugin that declares no skills offers no catalog: an empty folder in
     * the picker is a thing somebody grants and then wonders about.
     */
    fun catalogs(): List<PluginSkillCatalog> = plugins.findAllByOrderByNameAsc()
        .filter { it.enabled }
        .mapNotNull { plugin ->
            val held = declarations.readSkills(plugin.declaredSkills)
            if (held.isEmpty()) {
                null
            } else {
                PluginSkillCatalog(
                    name = plugin.key,
                    plugin = plugin.name,
                    skills = held.map {
                        GrantedSkill(
                            name = it.name,
                            description = it.description,
                            catalog = plugin.key,
                            content = it.content,
                        )
                    }.sortedBy { it.name },
                )
            }
        }

    /** The skills in the plugin catalogs among these granted names. */
    fun granted(names: Collection<String>): List<GrantedSkill> {
        if (names.isEmpty()) return emptyList()
        val held = names.toSet()
        return catalogs().filter { it.name in held }.flatMap { it.skills }
    }
}

/** One plugin's skills, offered under the plugin's key. */
data class PluginSkillCatalog(
    /** The plugin's key, which is what goes on an agent's grant list. */
    val name: String,
    /** What the plugin is called on screen, for a picker that shows both. */
    val plugin: String,
    val skills: List<GrantedSkill>,
)

/**
 * One skill an agent may draw on, whoever it came from.
 *
 * The one shape the briefing and the two skill tools work in, so a workspace's
 * own skill and a plugin's are the same thing to everything downstream — which
 * is the point: an agent following a skill should not have to know, and a
 * plugin that ships one should not get a second-class reader.
 */
data class GrantedSkill(
    val name: String,
    val description: String?,
    /** The folder it came from: a workspace catalog's name, or a plugin's key. */
    val catalog: String,
    val content: String,
)
