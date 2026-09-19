package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentSkill
import io.mszymanski.orknux.server.agent.AgentSkillRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.agent.PluginSkills
import io.mszymanski.orknux.server.agent.SkillCatalog
import io.mszymanski.orknux.server.agent.SkillCatalogRepository
import io.mszymanski.orknux.server.agent.SkillTool
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithMockUser

/**
 * A plugin that brings skills, from the file to the agent that reads one.
 *
 * Five claims carry the feature, and each is a test: a plugin's skills are
 * read and kept; they are offered as a catalog named after the plugin and not
 * handed to anybody who was not granted it; a granted agent sees them beside
 * the workspace's own and can load one in full; a plugin switched off offers
 * none; and a plugin that writes plain markdown has the frontmatter written
 * for it rather than being refused for stating its name twice.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class PluginSkillsTest(
    @Autowired val upload: PluginUploadAPI,
    @Autowired val plugins: PluginRepository,
    @Autowired val declarations: PluginDeclarations,
    @Autowired val fromPlugins: PluginSkills,
    @Autowired val skillTool: SkillTool,
    @Autowired val agents: AgentRepository,
    @Autowired val catalogs: SkillCatalogRepository,
    @Autowired val skills: AgentSkillRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        plugins.deleteAll()
        agents.deleteAll()
        skills.deleteAll()
        catalogs.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    /** A plugin whose whole point is the two skills it teaches. */
    private val source = """
        export default class Deploys extends OrknuxPlugin {
          id() { return 'deploys'; }
          apiVersion() { return 1; }
          skills() {
            return [
              new OrknuxSkill({
                name: 'Rolling back a deploy',
                description: 'What to do when a release is bad.',
                content: '---\nname: Rolling back a deploy\ndescription: What to do when a release is bad.\n---\n\n# Rolling back\n\n- Stop the rollout first.',
              }),
              new OrknuxSkill({
                name: 'Reading the deploy log',
                description: 'Where the useful lines are.',
                content: 'The log is written newest last. Start at the bottom.',
              }),
            ];
          }
        }
    """.trimIndent()

    private fun load(text: String = source, name: String = "deploys.js") =
        upload.upload(MockMultipartFile("file", name, "text/javascript", text.toByteArray()), null, null)

    private fun agent(vararg granted: String): Agent = agents.save(
        Agent(
            workspaceId = workspaceId,
            name = "releaser",
            type = AgentType.LLM,
            skillCatalogs = granted.toMutableList(),
        ),
    )

    @Test
    fun `a plugin's skills are read from the code and kept`() {
        load()

        val stored = plugins.findByKey("deploys")!!
        val held = declarations.readSkills(stored.declaredSkills)
        assertThat(held.map { it.name })
            .containsExactly("Rolling back a deploy", "Reading the deploy log")
        assertThat(held.first().description).isEqualTo("What to do when a release is bad.")
        assertThat(held.first().content).contains("Stop the rollout first.")
    }

    /**
     * A plugin that wrote markdown and named the skill in its declaration has
     * said both facts once. Refusing it for not saying them twice would be a
     * trap, so the block is written from what it declared.
     */
    @Test
    fun `plain markdown is given the frontmatter a skill needs`() {
        load()

        val second = declarations.readSkills(plugins.findByKey("deploys")!!.declaredSkills)[1]
        assertThat(second.content).startsWith("---\nname: Reading the deploy log\n")
        assertThat(second.content).contains("description: Where the useful lines are.")
        assertThat(second.content).endsWith("The log is written newest last. Start at the bottom.")
    }

    @Test
    fun `they are offered as a catalog named after the plugin, and only to an agent granted it`() {
        load()

        val offered = fromPlugins.catalogs().single()
        assertThat(offered.name).describedAs("the plugin's key is the grant").isEqualTo("deploys")
        assertThat(offered.skills).hasSize(2)

        // Granted nothing: a catalog nobody gave it does not appear and cannot
        // be loaded by guessing the name.
        val ungranted = agent()
        assertThat(skillTool.list(ungranted)).isEmpty()
        assertThat(skillTool.load(ungranted, "Rolling back a deploy")).isNull()
    }

    @Test
    fun `a granted agent reads them beside the workspace's own`() {
        load()
        val catalog = catalogs.save(SkillCatalog(workspaceId = workspaceId, name = "house style"))
        skills.save(
            AgentSkill(
                workspaceId = workspaceId,
                catalogId = requireNotNull(catalog.id),
                name = "Writing a changelog",
                description = "How we word one.",
                content = "---\nname: Writing a changelog\ndescription: How we word one.\n---\n\nPast tense.",
            ),
        )

        val granted = agent("house style", "deploys")

        assertThat(skillTool.list(granted).map { it.name })
            .containsExactlyInAnyOrder("Writing a changelog", "Rolling back a deploy", "Reading the deploy log")
        assertThat(skillTool.list(granted).single { it.name == "Rolling back a deploy" }.catalog)
            .isEqualTo("deploys")

        val loaded = skillTool.load(granted, "rolling back a deploy")
        assertThat(loaded).describedAs("found however it was spelled").isNotNull
        assertThat(loaded!!.content).contains("Stop the rollout first.")
    }

    /**
     * Off keeps everything and offers nothing, the way it does for tools. The
     * grant stays on the agent, so switching the plugin back on puts things
     * back rather than making somebody grant them again.
     */
    @Test
    fun `a plugin switched off teaches nobody, and the grant survives it`() {
        load()
        val granted = agent("deploys")
        assertThat(skillTool.list(granted)).hasSize(2)

        val plugin = plugins.findByKey("deploys")!!
        plugin.enabled = false
        plugins.save(plugin)

        assertThat(fromPlugins.catalogs()).isEmpty()
        assertThat(skillTool.list(granted)).isEmpty()
        assertThat(agents.findById(requireNotNull(granted.id)).get().skillCatalogs)
            .describedAs("the grant is about the plugin, not about this moment")
            .containsExactly("deploys")

        plugin.enabled = true
        plugins.save(plugin)
        assertThat(skillTool.list(granted)).hasSize(2)
    }
}
