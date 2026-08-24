package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.server.action.FunctionScope
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.condition.ConditionEvaluator
import io.mszymanski.orknux.server.condition.ConditionType
import io.mszymanski.orknux.server.condition.WorkflowCondition
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.script.PluginPermission
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithMockUser

/**
 * A plugin saying what JavaScript it needs, and somebody having to agree to it.
 *
 * The whole arrangement rests on four claims, and each of them is a test here:
 * nothing is relaxed that nobody accepted; what was accepted is relaxed for that
 * plugin and no other; a plugin edited to need more is asked again rather than
 * inheriting the old agreement; and what was accepted is readable afterwards by
 * somebody who was not the one who accepted it.
 *
 * Driven through the upload endpoint and then through a real run, because the
 * interesting failure would be a permission stored correctly and not handed to the
 * sandbox — which a test asserting on the row would not notice.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class PluginPermissionTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val upload: PluginUploadAPI,
    @Autowired val plugins: PluginRepository,
    @Autowired val permissions: PluginPermissions,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val conditions: WorkflowConditionRepository,
    @Autowired val evaluator: ConditionEvaluator,
    @Autowired val settings: PluginParameterSettingRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        conditions.deleteAll()
        settings.deleteAll()
        plugins.deleteAll()
        functions.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    /**
     * Nothing is relaxed by default, and the refusal is the list.
     *
     * The plugin is not stored. Somebody has to be shown what it wants before it
     * is anywhere near the installation, which is the point of refusing rather
     * than loading it unrelaxed and letting it fail later.
     */
    @Test
    fun `a plugin that needs something is refused until the list is accepted`() {
        val failure = runCatching { load("needy", declaring = "['INTL']") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(PluginPermissionsNotAcceptedException::class.java)
        assertThat(failure?.message).contains("INTL")
        assertThat(plugins.findAll()).isEmpty()

        load("needy", declaring = "['INTL']", accepting = "INTL")
        assertThat(plugins.findByKey("needy")).isNotNull()
    }

    /** Accepting one thing does not accept another that was asked for beside it. */
    @Test
    fun `accepting the wrong list is not accepting`() {
        val failure = runCatching {
            load("needy", declaring = "['INTL', 'TEXT_ENCODING']", accepting = "INTL")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(PluginPermissionsNotAcceptedException::class.java)
        assertThat(plugins.findAll()).isEmpty()
    }

    /**
     * What was accepted is what is turned on, and it reaches the sandbox.
     *
     * Run through a condition rather than by calling the runner directly, so a
     * permission stored on the row and never handed over would fail this.
     */
    @Test
    fun `what was accepted is relaxed for that plugin when it runs`() {
        load("dates", declaring = "['INTL']", accepting = "INTL")

        assertThat(asks("dates")).isTrue()
    }

    /**
     * And nothing is relaxed for a plugin that did not ask.
     *
     * The two are loaded side by side on purpose: the relaxation is built per
     * call, from one plugin's row, so one plugin's acceptance cannot leak into
     * another's context or into the engine they share.
     */
    @Test
    fun `a plugin that asked for nothing gets nothing, even beside one that did`() {
        load("dates", declaring = "['INTL']", accepting = "INTL")
        load("plain", declaring = "[]")

        assertThat(asks("dates")).isTrue()
        assertThat(asks("plain")).isFalse()
    }

    /**
     * An escalation asks again. This is the one that matters most.
     *
     * The acceptance names the permissions it was given for, so a plugin edited to
     * need one more is not covered by it — and what is stored is untouched by the
     * refused load, so the old plugin goes on running with the old grant.
     */
    @Test
    fun `a plugin edited to need more is asked again rather than inheriting`() {
        load("dates", declaring = "['INTL']", accepting = "INTL")

        val failure = runCatching {
            load("dates", declaring = "['INTL', 'TEXT_ENCODING']")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(PluginPermissionsNotAcceptedException::class.java)
        assertThat(failure?.message).contains("TEXT_ENCODING")

        val stored = requireNotNull(plugins.findByKey("dates"))
        assertThat(permissions.grantedTo(stored)).containsExactly(PluginPermission.INTL)
    }

    /** Loading the same plugin again, asking for no more, does not ask again. */
    @Test
    fun `an unchanged plugin loads again without being accepted again`() {
        load("dates", declaring = "['INTL']", accepting = "INTL")
        load("dates", declaring = "['INTL']")

        assertThat(permissions.grantedTo(requireNotNull(plugins.findByKey("dates"))))
            .containsExactly(PluginPermission.INTL)
    }

    /**
     * A plugin asking for what dropped off its list stops being granted it.
     *
     * The stored grant is what the loaded plugin declares, never what an older
     * version of it declared: a permission nobody is asking for any more is one
     * nothing should still be able to use.
     */
    @Test
    fun `a plugin that stops asking stops being granted`() {
        load("dates", declaring = "['INTL']", accepting = "INTL")
        load("dates", declaring = "[]")

        val stored = requireNotNull(plugins.findByKey("dates"))
        assertThat(permissions.grantedTo(stored)).isEmpty()
        assertThat(stored.permissionsAcceptedBy).isNull()
        assertThat(asks("dates")).isFalse()
    }

    /**
     * The vocabulary is closed, and it cannot express a way out of the sandbox.
     *
     * There is no name for reading a file, so a plugin asking for one is refused
     * with the list of what there are names for.
     */
    @Test
    fun `a plugin asking for something that is not on the list is refused`() {
        val failure = runCatching { load("greedy", declaring = "['FILES']") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(PluginPermissionUnknownException::class.java)
        assertThat(failure?.message).contains("INTL")
        assertThat(plugins.findAll()).isEmpty()
    }

    /** What was accepted is on the plugin, for anybody who reads the list later. */
    @Test
    fun `what was accepted is visible afterwards, with who accepted it`() {
        load("dates", declaring = "['INTL']", accepting = "INTL")

        val answered = graphQlTester.document(
            """query { plugins { key permissions { name summary } permissionsAcceptedBy permissionsAcceptedAt } }""",
        ).execute()

        answered.path("plugins[0].permissions[0].name").entity(String::class.java).isEqualTo("INTL")
        answered.path("plugins[0].permissions[0].summary").entity(String::class.java)
            .isEqualTo(PluginPermission.INTL.summary)
        answered.path("plugins[0].permissionsAcceptedBy").entity(String::class.java).isEqualTo("alice")
        assertThat(answered.path("plugins[0].permissionsAcceptedAt").entity(String::class.java).get()).isNotBlank()
    }

    /**
     * The template describes the contract that will actually judge a plugin.
     *
     * It is generated from the same enumeration the granting is done from, so a
     * permission added to this build appears in the template's union without
     * anybody remembering to write it there — which is the failure a template
     * kept by hand always eventually has.
     */
    @Test
    fun `the template offers exactly the permissions this build can grant`() {
        val written = requireNotNull(upload.template().body)

        PluginPermission.entries.forEach { assertThat(written).contains("'${it.name}'") }
        // Nothing left unsubstituted, and the indentation survived it.
        assertThat(written).doesNotContain("@PERMISSION")
        assertThat(written.lineSequence().first()).isEqualTo("/*")
    }

    /**
     * Whether a plugin's function can see `Intl`, asked through a real run.
     *
     * A condition, because it is the shortest path from a plugin's function to the
     * sandbox that does not stand anything in for: the evaluator loads the plugin,
     * looks up what it was granted, and hands it over.
     */
    private fun asks(key: String): Boolean {
        val function = functions.findAll()
            .single { it.scope == FunctionScope.PLUGIN && it.name == "${key}_hasIntl" }
        val condition = conditions.save(
            WorkflowCondition(
                workspaceId = workspaceId,
                name = "asks$key",
                type = ConditionType.FUNCTION,
                functionId = requireNotNull(function.id),
            ),
        )
        return evaluator.holds(condition, null)
    }

    /**
     * Loads a plugin declaring [declaring], accepting [accepting].
     *
     * The source is written here rather than kept as a fixture, because what makes
     * these tests worth anything is that the declaration in the file and the list
     * on the row are the same list — a hand-written declaration would go on passing
     * the day the reading broke.
     */
    private fun load(key: String, declaring: String, accepting: String? = null) {
        val source = """
            export default class Plugin extends OrknuxPlugin {
              id() { return '$key'; }
              apiVersion() { return 1; }
              permissions() { return $declaring; }
              functions() {
                return [new OrknuxFunction({
                  name: 'hasIntl',
                  returnType: 'boolean',
                  run: () => typeof Intl !== 'undefined',
                })];
              }
            }
        """.trimIndent()
        upload.upload(MockMultipartFile("file", "$key.js", "text/plain", source.toByteArray()), null, accepting)
    }
}
