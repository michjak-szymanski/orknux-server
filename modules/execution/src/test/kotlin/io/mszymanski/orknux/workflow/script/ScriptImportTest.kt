package io.mszymanski.orknux.workflow.script

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * One script calling another, in a sandbox that resolves nothing.
 *
 * The host does the resolving and hands the modules over already ordered, so what
 * is under test here is the other half: that a module evaluated into the registry
 * is reachable under the name the importer chose, that a module's own imports are
 * there by the time its body runs, and that none of this loosened the walls the
 * rest of [ScriptRunnerTest] holds.
 */
class ScriptImportTest {

    private val runner = ScriptRunner(ScriptProperties(timeoutMillis = 2_000, statementLimit = 200_000))

    @Test
    fun `an imported function is callable under the name the importer chose`() {
        val result = runner.call(
            "export default function shout(word) { return { said: imports.upper(word) }; }",
            "shout",
            listOf(""""hello""""),
            modules = listOf(
                ScriptModule("f1", "toUpper", "export default function toUpper(word) { return word.toUpperCase(); }"),
            ),
            imports = mapOf("upper" to "f1"),
        )

        assertThat(result).isInstanceOf(ScriptResult.Returned::class.java)
        assertThat((result as ScriptResult.Returned).json).isEqualTo("""{"said":"HELLO"}""")
    }

    /**
     * The name is the importer's, not the imported thing's.
     *
     * This is the whole reason an import is stored as an id and a local name rather
     * than as a name: the module below is called `toUpper` and answers to `shout`,
     * which is what lets a rename cost nothing.
     */
    @Test
    fun `the imported module's own name is not what the code says`() {
        val result = runner.call(
            "export default function f() { return { has: typeof toUpper, used: imports.anything('x') }; }",
            "f",
            emptyList(),
            modules = listOf(
                ScriptModule("f1", "toUpper", "export default function toUpper(word) { return word + '!'; }"),
            ),
            imports = mapOf("anything" to "f1"),
        )

        assertThat((result as ScriptResult.Returned).json).isEqualTo("""{"has":"undefined","used":"x!"}""")
    }

    /** A module may import another, and the deeper one is there when it runs. */
    @Test
    fun `an import may itself import`() {
        val result = runner.call(
            "export default function f(word) { return imports.middle(word); }",
            "f",
            listOf(""""a""""),
            modules = listOf(
                ScriptModule("f1", "inner", "export default function inner(w) { return w + '-inner'; }"),
                ScriptModule(
                    "f2",
                    "middle",
                    "export default function middle(w) { return imports.deep(w) + '-middle'; }",
                    imports = mapOf("deep" to "f1"),
                ),
            ),
            imports = mapOf("middle" to "f2"),
        )

        assertThat((result as ScriptResult.Returned).json).isEqualTo(""""a-inner-middle"""")
    }

    /**
     * A module reached by two importers is evaluated once.
     *
     * Not an optimisation. A module's top-level state is one state, and a diamond
     * that evaluated the shared module twice would give the two sides a counter
     * each — which is not what anybody writing `import` means.
     */
    @Test
    fun `a module two others import is evaluated once`() {
        val counter = "let seen = 0; export default function bump() { seen += 1; return seen; }"
        val result = runner.call(
            "export default function f() { return [imports.left(), imports.right()]; }",
            "f",
            emptyList(),
            modules = listOf(
                ScriptModule("f1", "counter", counter),
                ScriptModule("f2", "left", "export default function l() { return imports.c(); }", mapOf("c" to "f1")),
                ScriptModule("f3", "right", "export default function r() { return imports.c(); }", mapOf("c" to "f1")),
            ),
            imports = mapOf("left" to "f2", "right" to "f3"),
        )

        // One module, one counter: 1 then 2. Two copies would answer 1 twice.
        assertThat((result as ScriptResult.Returned).json).isEqualTo("[1,2]")
    }

    /** What was imported cannot be swapped out for something else halfway through. */
    @Test
    fun `the imports object is frozen`() {
        val result = runner.call(
            """
            export default function f() {
              try { imports.upper = () => 'hijacked'; } catch (ignored) { /* strict mode throws */ }
              return imports.upper('x');
            }
            """.trimIndent(),
            "f",
            emptyList(),
            modules = listOf(ScriptModule("f1", "u", "export default function u(w) { return w.toUpperCase(); }")),
            imports = mapOf("upper" to "f1"),
        )

        assertThat((result as ScriptResult.Returned).json).isEqualTo(""""X"""")
    }

    /** A library is an object rather than a function, and imports the same way. */
    @Test
    fun `an imported module may export an object`() {
        val result = runner.call(
            "export default function f() { return { n: imports.lib.add(2, 3), t: imports.lib.tag }; }",
            "f",
            emptyList(),
            modules = listOf(
                ScriptModule("l1", "arith", "export default { tag: 'arith', add: (a, b) => a + b };"),
            ),
            imports = mapOf("lib" to "l1"),
        )

        assertThat((result as ScriptResult.Returned).json).isEqualTo("""{"n":5,"t":"arith"}""")
    }

    /**
     * Importing changes nothing about the walls.
     *
     * The modules go into the same context the importer runs in, so the question
     * worth asking is whether that context is still the one the rest of the suite
     * checks. An imported module that could reach a file would be a way round every
     * denial in [ScriptRunner], reached by a script that imports it.
     */
    @Test
    fun `an imported module is in the same sandbox and reaches nothing`() {
        val result = runner.call(
            "export default function f() { return imports.probe(); }",
            "f",
            emptyList(),
            modules = listOf(
                ScriptModule(
                    "f1",
                    "probe",
                    """
                    export default function probe() {
                      return { polyglot: typeof Polyglot, load: typeof load, print: typeof print };
                    }
                    """.trimIndent(),
                ),
            ),
            imports = mapOf("probe" to "f1"),
        )

        assertThat((result as ScriptResult.Returned).json)
            .isEqualTo("""{"polyglot":"undefined","load":"undefined","print":"undefined"}""")
    }

    /**
     * And the host's classes are as far out of reach from an import as from the
     * importer. Written as its own test because it is the one that would matter:
     * an import that could reach `Java.type` would be a way round every denial in
     * [ScriptRunner], taken by anybody who could write a function.
     */
    @Test
    fun `an imported module cannot reach the host either`() {
        val result = runner.call(
            "export default function f() { return imports.escape(); }",
            "f",
            emptyList(),
            modules = listOf(
                ScriptModule(
                    "f1",
                    "escape",
                    """export default function escape() { return Java.type("java.lang.System").getenv("PATH"); }""",
                ),
            ),
            imports = mapOf("escape" to "f1"),
        )

        assertThat(result).isInstanceOf(ScriptResult.Failed::class.java)
        assertThat((result as ScriptResult.Failed).reason).contains("not allowed")
    }

    /** A script that imports nothing is handed no prelude and is exactly as it was. */
    @Test
    fun `a script with no imports has no imports object`() {
        val result = runner.call(
            "export default function f() { return typeof imports; }",
            "f",
            emptyList(),
        )

        assertThat((result as ScriptResult.Returned).json).isEqualTo(""""undefined"""")
    }
}
