package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.server.action.RunFunctionInput
import io.mszymanski.orknux.server.action.FunctionAPI
import io.mszymanski.orknux.server.action.FunctionArgumentInput
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
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
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A plugin that ships libraries, from the archive to a running call.
 *
 * Four claims carry the feature, and each is a test: the files somebody was
 * never asked about do not load - the refusal is the list; a declaration and
 * an archive that disagree are refused as the lie one of them is; an accepted
 * bundle runs, its imports reaching the shipped code; and the set already
 * allowed is not re-asked on a re-upload.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class PluginLibrariesTest(
    @Autowired val upload: PluginUploadAPI,
    @Autowired val plugins: PluginRepository,
    @Autowired val libraries: PluginLibraryRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val functionApi: FunctionAPI,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        plugins.deleteAll()
        functions.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    private val pluginSource = """
        import { greet } from './lib/format.js';

        export default class Zipped extends OrknuxPlugin {
          id() { return 'shipped'; }
          apiVersion() { return 1; }
          libraries() { return ['lib/format.js', 'lib/names.js']; }
          functions() {
            return [new OrknuxFunction({
              name: 'greet',
              params: [{ name: 'who', type: 'string' }],
              returnType: 'string',
              run: (who) => greet(who),
            })];
          }
        }
    """.trimIndent()

    private val format = "import { NAME } from './names.js';\nexport function greet(who) { return 'hello, ' + who + ' from ' + NAME; }"
    private val names = "export const NAME = 'the library';"

    private fun zipped(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((path, content) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun load(archive: ByteArray, accepting: String? = null) =
        upload.upload(MockMultipartFile("file", "shipped.zip", "application/zip", archive), null, accepting)

    @Test
    fun `the first load is refused with the list, and nothing is stored`() {
        val archive = zipped("plugin.js" to pluginSource, "lib/format.js" to format, "lib/names.js" to names)

        val failure = runCatching { load(archive) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(PluginAgreementNeededException::class.java)
        assertThat((failure as PluginAgreementNeededException).libraries)
            .containsExactly("lib/format.js", "lib/names.js")
        assertThat(plugins.findAll()).isEmpty()
    }

    @Test
    fun `an accepted bundle runs, its imports reaching the shipped code`() {
        val archive = zipped("plugin.js" to pluginSource, "lib/format.js" to format, "lib/names.js" to names)
        load(archive, accepting = "lib/format.js,lib/names.js")

        val stored = plugins.findByKey("shipped")!!
        assertThat(libraries.findByPluginIdOrderByPositionAsc(requireNotNull(stored.id)).map { it.path })
            .containsExactly("lib/format.js", "lib/names.js")

        val function = functions.findAll().single { it.name == "shipped_greet" }
        val run = functionApi.runFunction(
            RunFunctionInput(
                workspaceId = workspaceId,
                functionId = requireNotNull(function.id),
                arguments = listOf(FunctionArgumentInput(name = "who", json = "\"dana\"")),
            ),
        )

        assertThat(run.error).isNull()
        assertThat(run.returned).isEqualTo("\"hello, dana from the library\"")
    }

    /**
     * The declaration is what somebody allows, so it has to agree with what
     * arrived - in both directions, and each is refused by the rule that
     * catches it first. A file smuggled into the archive is caught as the
     * dead weight it is; a declared file that never arrived is caught as the
     * declaration's lie.
     */
    @Test
    fun `an archive and a declaration that disagree are refused as the lie one of them is`() {
        val smuggling = zipped(
            "plugin.js" to pluginSource,
            "lib/format.js" to format,
            "lib/names.js" to names,
            "lib/extra.js" to "export const x = 1;",
        )
        val smuggled = runCatching { load(smuggling, accepting = "lib/format.js,lib/names.js,lib/extra.js") }
            .exceptionOrNull()
        assertThat(smuggled).isInstanceOf(PluginContractException::class.java)
        assertThat(smuggled!!.message).contains("nothing imports lib/extra.js")

        val promising = zipped(
            "plugin.js" to pluginSource.replace(
                "['lib/format.js', 'lib/names.js']",
                "['lib/format.js', 'lib/names.js', 'lib/promised.js']",
            ),
            "lib/format.js" to format,
            "lib/names.js" to names,
        )
        val promised = runCatching { load(promising, accepting = "lib/format.js,lib/names.js,lib/promised.js") }
            .exceptionOrNull()
        assertThat(promised).isInstanceOf(PluginContractException::class.java)
        assertThat(promised!!.message).contains("declared and not shipped").contains("lib/promised.js")
    }

    @Test
    fun `a set already allowed is not asked about again`() {
        val archive = zipped("plugin.js" to pluginSource, "lib/format.js" to format, "lib/names.js" to names)
        load(archive, accepting = "lib/format.js,lib/names.js")

        // The same bundle again, with no acceptance: nothing new is being
        // handed over, so nothing is asked.
        load(archive)

        assertThat(plugins.findByKey("shipped")).isNotNull
    }
}
