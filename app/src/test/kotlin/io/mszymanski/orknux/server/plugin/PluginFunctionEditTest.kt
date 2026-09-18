package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.script.PluginInspection
import io.mszymanski.orknux.workflow.script.PluginRunner
import io.mszymanski.orknux.workflow.script.ScriptResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * A plugin's function, edited - and the edit surviving everything that used to
 * erase it.
 *
 * What a plugin declared is a starting point, not a cage. Three promises are
 * pinned here: an edited function runs from its row rather than out of the
 * bundle, a reload of the plugin leaves the edit alone, and the plugin's
 * export carries the edit - as a bundle that actually runs, since an export
 * that merely mentions the edit would be a backup of half the installation.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class PluginFunctionEditTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val plugins: PluginRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val declarations: PluginDeclarations,
    @Autowired val registry: PluginFunctionRegistry,
    @Autowired val runner: PluginRunner,
    @Autowired val overrides: PluginOverrides,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var plugin: Plugin
    private var functionId: Long = 0

    @BeforeEach
    fun reset() {
        functions.deleteAll()
        plugins.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        plugin = load()
        registry.reconcile(plugin)
        functionId = requireNotNull(functions.findByPluginId(requireNotNull(plugin.id)).single().id)
    }

    @Test
    fun `an edited plugin function runs from its row, not out of the bundle`() {
        edit()

        graphQlTester.document(
            """
            mutation {
              runFunction(input: {
                workspaceId: $workspaceId, functionId: $functionId,
                arguments: [{ name: "issue", json: "\"ORK-14\"" }]
              }) { ok returned }
            }
            """,
        ).execute()
            .path("runFunction.ok").entity(Boolean::class.java).isEqualTo(true)
            .path("runFunction.returned").entity(String::class.java).isEqualTo("\"edited/ORK-14\"")
    }

    @Test
    fun `a reload leaves the edit alone, and takes an unedited row with it as before`() {
        edit()
        registry.reconcile(plugin)

        val kept = functions.findByPluginId(requireNotNull(plugin.id)).single()
        assertThat(kept.editedAt).isNotNull()
        assertThat(kept.source).contains("edited/")
    }

    @Test
    fun `the name is the contract and stays, while the rest may move`() {
        graphQlTester.document(
            """mutation { updateFunction(id: $functionId, input: { name: "somethingElse" }) { id } }""",
        ).execute().errors().expect { it.message?.contains("keeps the name the plugin declared") == true }.verify()
    }

    /** The strongest promise: the export is a bundle that runs the edit. */
    @Test
    fun `the export folds the edit over the bundle, and the folded bundle runs it`() {
        edit()

        val folded = requireNotNull(overrides.folded(plugin, overrides.editedOf(plugin)))
        assertThat(folded).contains("Edits made on this installation")

        val read = runner.inspect(folded)
        assertThat(read).isInstanceOf(PluginInspection.Read::class.java)

        val answered = runner.call(folded, "addressOf", listOf("\"ORK-14\""), "{}")
        assertThat((answered as ScriptResult.Returned).json).isEqualTo("\"edited/ORK-14\"")
    }

    private fun edit() {
        val code = "export default function addressOf(issue) { return 'edited/' + issue; }"
        graphQlTester.document(
            """
            mutation {
              updateFunction(id: $functionId, input: {
                source: ${quote(code)}, typescript: ${quote(code)}
              }) { editedAt editedBy }
            }
            """,
        ).execute()
            .path("updateFunction.editedBy").entity(String::class.java).isEqualTo("alice")
    }

    private fun quote(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun load(): Plugin {
        val read = runner.inspect(SOURCE) as PluginInspection.Read
        return plugins.save(
            Plugin(
                key = read.id,
                name = "tracker",
                filename = "tracker.js",
                source = SOURCE,
                sizeBytes = SOURCE.length.toLong(),
                apiVersion = read.apiVersion,
                sha256 = "0".repeat(64),
                declaredFunctions = declarations.validated(read.functions),
                declaredParameters = declarations.validatedParameters(read.parameters),
            ),
        )
    }

    private companion object {
        val SOURCE = """
            export default class Tracker extends OrknuxPlugin {
              id() { return 'tracker'; }
              apiVersion() { return 1; }

              functions() {
                return [
                  new OrknuxFunction({
                    name: 'addressOf',
                    params: [{ name: 'issue', type: 'string' }],
                    returnType: 'string',
                    run: (issue) => 'declared/' + issue,
                  }),
                ];
              }
            }
        """.trimIndent()
    }
}
