package io.mszymanski.orknux.workflow.script

import org.assertj.core.api.Assertions.assertThat
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.junit.jupiter.api.Test

/**
 * The plugin contract: what a plugin declares, and what it is handed.
 *
 * A plugin's parameters are the only thing it knows about the workspace it is
 * running for, so what crosses that line - and what cannot - is written down
 * here rather than left to be true by accident.
 */
class PluginRunnerTest {

    private val runner = PluginRunner(PluginProperties(timeoutMillis = 5_000, statementLimit = 2_000_000))

    private val tracker = """
        export default class Tracker extends OrknuxPlugin {
          id() { return 'tracker'; }
          apiVersion() { return 1; }

          parameters() {
            return [
              new OrknuxParameter({ name: 'baseUrl', description: 'Where it lives.', type: 'string' }),
              new OrknuxParameter({ name: 'token', type: 'string', secret: true }),
              new OrknuxParameter({ name: 'retries', type: 'number', required: false }),
            ];
          }

          functions() {
            return [
              new OrknuxFunction({
                name: 'addressOf',
                params: [{ name: 'issue', type: 'string' }],
                returnType: 'string',
                run: (issue) => this.settings.baseUrl + '/' + issue,
              }),
              new OrknuxFunction({
                name: 'everything',
                returnType: 'map',
                run: () => this.settings,
              }),
            ];
          }
        }
    """.trimIndent()

    @Test
    fun `a plugin says what it has to be told, and whether it can work without it`() {
        val read = runner.inspect(tracker) as PluginInspection.Read

        assertThat(read.parameters).containsExactly(
            DeclaredParameter("baseUrl", "Where it lives.", "string", required = true, secret = false),
            DeclaredParameter("token", null, "string", required = true, secret = true),
            DeclaredParameter("retries", null, "number", required = false, secret = false),
        )
    }

    @Test
    fun `what a workspace set a parameter to arrives as this settings`() {
        val result = runner.call(
            tracker,
            "addressOf",
            listOf(""""ORK-14""""),
            """{"baseUrl":"https://tracker.example"}""",
        )

        assertThat((result as ScriptResult.Returned).json).isEqualTo(""""https://tracker.example/ORK-14"""")
    }

    @Test
    fun `a parameter nothing was set for is absent rather than empty`() {
        val result = runner.call(tracker, "everything", emptyList(), """{"retries":3}""")

        // Absent, so a plugin can ask whether it was told rather than having to
        // tell "set to nothing" apart from "never set".
        assertThat((result as ScriptResult.Returned).json).isEqualTo("""{"retries":3}""")
    }

    @Test
    fun `a plugin cannot change what it was given`() {
        val result = runner.call(
            """
            export default class Sneak extends OrknuxPlugin {
              id() { return 'sneak'; }
              apiVersion() { return 1; }
              parameters() { return [new OrknuxParameter({ name: 'token', type: 'string' })]; }
              functions() {
                return [
                  new OrknuxFunction({
                    name: 'rewrite',
                    returnType: 'string',
                    run: () => { this.settings.token = 'mine'; return this.settings.token; },
                  }),
                ];
              }
            }
            """.trimIndent(),
            "rewrite",
            emptyList(),
            """{"token":"theirs"}""",
        )

        assertThat(result).isInstanceOf(ScriptResult.Failed::class.java)
    }

    @Test
    fun `a function the plugin does not declare is not callable`() {
        val result = runner.call(tracker, "deleteEverything", emptyList())

        assertThat((result as ScriptResult.Failed).reason).contains("deleteEverything")
    }

    @Test
    fun `what a plugin answers with cannot be read back as a mutable host type`() {
        /*
         * A plugin's sandbox says no to the same thing the function one does, and
         * says it in its own file. Checked separately for that reason: the two
         * configurations are meant to drift only when somebody means them to, and
         * a test that only looked at one of them would not notice.
         *
         * HostAccess.NONE leaves these mappings on. What is read off a plugin
         * here is strings, numbers and array elements one at a time, so nothing
         * asked for a Map even while it was allowed - which is what makes this an
         * assertion about the policy rather than about a plugin that could reach
         * something.
         */
        fun readsBackAsAMap(policy: HostAccess): Boolean =
            Context.newBuilder("js")
                .allowHostAccess(policy)
                // As the runners' own engines do; the fallback runtime is
                // expected here and the warning is six lines of it.
                .option("engine.WarnInterpreterOnly", "false")
                .build()
                .use { polyglot ->
                    runCatching { polyglot.eval("js", "({ a: 1 })").`as`(Map::class.java) }.isSuccess
                }

        assertThat(readsBackAsAMap(HostAccess.NONE)).isTrue()
        assertThat(readsBackAsAMap(runner.hostAccess)).isFalse()
    }

    @Test
    fun `a parameter declared without a name is refused rather than half read`() {
        val read = runner.inspect(
            """
            export default class Nameless extends OrknuxPlugin {
              id() { return 'nameless'; }
              apiVersion() { return 1; }
              parameters() { return [new OrknuxParameter({ type: 'string' })]; }
            }
            """.trimIndent(),
        )

        assertThat((read as PluginInspection.Unreadable).reason).contains("needs a name")
    }

    @Test
    fun `a plugin that imports a library cannot even be loaded`() {
        /*
         * Issue #142 asks that a plugin be allowed to carry libraries inside it.
         * Today it may not reach for one: the plugin sandbox is given no
         * filesystem either, so the import is refused while the module is being
         * loaded - before `id()` is ever called. So the failure is not "this
         * plugin needs a permission you have not granted", it is "this is not
         * readable", which is a different sentence and sends whoever wrote it
         * somewhere else.
         *
         * The wording below is GraalJS's and may move under an upgrade; what is
         * being pinned is that the answer is Unreadable, and why.
         */
        val read = runner.inspect(
            """
            import fetchish from "node-fetch";
            export default class Reaching extends OrknuxPlugin {
              id() { return 'reaching'; }
              apiVersion() { return 1; }
            }
            """.trimIndent(),
        )

        assertThat(read).isInstanceOf(PluginInspection.Unreadable::class.java)
        assertThat((read as PluginInspection.Unreadable).reason).contains("not allowed")
    }
}
