package io.mszymanski.orknux.workflow.script

import org.assertj.core.api.Assertions.assertThat
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.junit.jupiter.api.Test

/**
 * The sandbox a workspace's JavaScript runs in.
 *
 * Half of this is that a function works; the other half is that the ways out —
 * Java classes, files, the network, threads, a loop that never ends — are all
 * closed. Those are written as tests because a sandbox nobody checks is a
 * sandbox that quietly opens.
 */
class ScriptRunnerTest {

    private val runner = ScriptRunner(ScriptProperties(timeoutMillis = 2_000, statementLimit = 200_000))

    @Test
    fun `calls the default export and answers with JSON`() {
        val result = runner.call(
            """
            export default function transformPayload(input, format) {
              return { id: input.id, format: format, processed: true };
            }
            """.trimIndent(),
            "transformPayload",
            listOf("""{"id":7}""", """"compact""""),
        )

        assertThat(result).isInstanceOf(ScriptResult.Returned::class.java)
        assertThat((result as ScriptResult.Returned).json)
            .isEqualTo("""{"id":7,"format":"compact","processed":true}""")
    }

    @Test
    fun `an async function is awaited`() {
        val result = runner.call(
            """
            export default async function later(value) {
              const doubled = await Promise.resolve(value * 2);
              return doubled;
            }
            """.trimIndent(),
            "later",
            listOf("21"),
        )

        assertThat((result as ScriptResult.Returned).json).isEqualTo("42")
    }

    @Test
    fun `what the function throws comes back as a failure, not as a crash`() {
        val result = runner.call(
            """export default function boom() { throw new Error("no thanks"); }""",
            "boom",
            emptyList(),
        )

        assertThat(result).isInstanceOf(ScriptResult.Failed::class.java)
        assertThat((result as ScriptResult.Failed).reason).contains("no thanks")
    }

    @Test
    fun `a loop that never ends is stopped`() {
        val result = runner.call(
            """export default function spin() { while (true) { } }""",
            "spin",
            emptyList(),
        )

        assertThat(result).isInstanceOf(ScriptResult.Failed::class.java)
        assertThat((result as ScriptResult.Failed).reason).containsAnyOf("statements", "longer than")
    }

    @Test
    fun `a script that threw is settled, and one that ran out of budget is not`() {
        /*
         * What decides whether the engine tries the step again. A script can
         * reach nothing - no clock, no network, no file - so one that threw will
         * throw again and asking twice more only writes the same line in the
         * run's history twice more. Being stopped by the clock is the other
         * case: that was about how busy the machine was, not about the script.
         */
        val threw = runner.call(
            """export default function boom() { throw new Error("no thanks"); }""",
            "boom",
            emptyList(),
        )
        assertThat((threw as ScriptResult.Failed).settled).isTrue()

        val spun = runner.call(
            """export default function spin() { while (true) { } }""",
            "spin",
            emptyList(),
        )
        assertThat((spun as ScriptResult.Failed).settled).isFalse()
    }

    @Test
    fun `a regex that backtracks is stopped by the clock, which the statement limit cannot see`() {
        // The hole the statement limit does not cover: one statement, and the
        // whole budget spent inside it. A backreference is what does it - it
        // takes the engine off the linear matcher it uses for a plain pattern
        // and on to backtracking, where a string of a's costs exponentially. The
        // statement limit counts one statement here however long it runs, so
        // only the wall clock can end this.
        val impatient = ScriptRunner(ScriptProperties(timeoutMillis = 500, statementLimit = 5_000_000))

        val started = System.nanoTime()
        val result = impatient.call(
            """
            export default function backtrack() {
              return /(a+)+\1b/.test('a'.repeat(80));
            }
            """.trimIndent(),
            "backtrack",
            emptyList(),
        )
        val took = (System.nanoTime() - started) / 1_000_000

        assertThat(result).isInstanceOf(ScriptResult.Failed::class.java)
        assertThat((result as ScriptResult.Failed).reason).contains("longer than")
        // Stopped rather than merely slow: without the watchdog this would not
        // have come back at all.
        assertThat(took).isLessThan(30_000)
    }

    @Test
    fun `Java is not reachable`() {
        val result = runner.call(
            """
            export default function escape() {
              return Java.type("java.lang.System").getenv("PATH");
            }
            """.trimIndent(),
            "escape",
            emptyList(),
        )

        assertThat(result).isInstanceOf(ScriptResult.Failed::class.java)
        assertThat((result as ScriptResult.Failed).reason).contains("not allowed")
    }

    @Test
    fun `the host's classes are not reachable through a constructor either`() {
        val result = runner.call(
            """
            export default function escape() {
              return new (Function.prototype.constructor)("return this")().process !== undefined;
            }
            """.trimIndent(),
            "escape",
            emptyList(),
        )

        // `Function` still builds a function — that is JavaScript — but what it
        // reaches is the sandbox's globals, where there is no host to find.
        assertThat((result as ScriptResult.Returned).json).isEqualTo("false")
    }

    @Test
    fun `there is no way to read a file or open a socket`() {
        val absent = runner.call(
            """
            export default function what() {
              return [
                typeof require, typeof load, typeof fetch, typeof XMLHttpRequest,
                typeof Polyglot, typeof Packages, typeof java, typeof process, typeof setTimeout
              ];
            }
            """.trimIndent(),
            "what",
            emptyList(),
        )

        assertThat((absent as ScriptResult.Returned).json)
            .isEqualTo("""["undefined","undefined","undefined","undefined","undefined","undefined","undefined","undefined","undefined"]""")
    }

    @Test
    fun `two runs of the same function cannot see each other`() {
        val source = """
            globalThis.seen = (globalThis.seen || 0) + 1;
            export default function count() { return globalThis.seen; }
        """.trimIndent()

        assertThat((runner.call(source, "count", emptyList()) as ScriptResult.Returned).json).isEqualTo("1")
        assertThat((runner.call(source, "count", emptyList()) as ScriptResult.Returned).json).isEqualTo("1")
    }

    @Test
    fun `a script with no default export is refused`() {
        val result = runner.call("""export function named() { return 1; }""", "named", emptyList())

        assertThat((result as ScriptResult.Failed).reason).contains("no default export")
    }

    @Test
    fun `a guest value cannot be read back as a mutable host type`() {
        /*
         * The one denial that could not be written as a repeated default,
         * because the default is the other way round: HostAccess.NONE leaves the
         * mappings of guest values onto List, Map and the rest switched on, so
         * the policy the runner uses is NONE with those taken off it.
         *
         * Asked of the policy rather than through `call`, and that is not a
         * shortcut. A script's answer leaves the sandbox as a JSON string; no
         * live Value ever reaches this side, which is why the loosening was
         * harmless and also why there is no script that could demonstrate it.
         * The comparison against NONE is what makes this a test rather than a
         * restatement: the two policies disagree, so the line that was added is
         * doing something.
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
    fun `validate reports where the syntax error is`() {
        val broken = runner.validate("export default function ( {")

        assertThat(broken.valid).isFalse()
        assertThat(broken.line).isEqualTo(1)

        assertThat(runner.validate("export default function ok() { return 1; }").valid).isTrue()
    }

    /*
     * What happens today when a workspace's JavaScript tries to import
     * something — issue #142 asks for a module system, and these say what there
     * is instead of one.
     *
     * They are written against the shape of the answer rather than its wording:
     * the sentences below come from GraalJS and would change under an upgrade,
     * so what is asserted is that the run failed and that the reason mentions a
     * denial, not the exact words. The day imports are implemented, these are
     * the tests that go red, which is the point of having them.
     */

    @Test
    fun `a function cannot import another module, by any spelling of the name`() {
        fun importing(specifier: String) = runner.call(
            """
            import helper from "$specifier";
            export default function uses() { return helper; }
            """.trimIndent(),
            "uses",
            emptyList(),
        )

        // A library by bare name, a sibling by relative path, and a URL: three
        // ways of asking, one answer. There is no resolver behind any of them,
        // because the context is given no filesystem at all.
        assertThat(importing("lodash")).isInstanceOf(ScriptResult.Failed::class.java)
        assertThat((importing("lodash") as ScriptResult.Failed).reason).contains("not allowed")
        assertThat((importing("./helper.mjs") as ScriptResult.Failed).reason).contains("not allowed")
        assertThat((importing("https://esm.sh/lodash") as ScriptResult.Failed).reason).contains("not allowed")
    }

    @Test
    fun `waiting until it runs does not find a module either`() {
        // A dynamic import is the same denial arriving as a rejected promise
        // rather than as a failure to load, so it is worth its own line: the two
        // travel back through different code paths in the runner.
        val result = runner.call(
            """export default async function late() { return await import("./helper.mjs"); }""",
            "late",
            emptyList(),
        )

        assertThat((result as ScriptResult.Failed).reason).contains("not allowed")
    }

    @Test
    fun `there is no require either`() {
        val result = runner.call("""export default function ask() { return typeof require; }""", "ask", emptyList())

        assertThat((result as ScriptResult.Returned).json).isEqualTo(""""undefined"""")
    }

    @Test
    fun `validate accepts a script that importing has already doomed`() {
        /*
         * The gap worth knowing about before anybody builds #142: the editor's
         * Validate parses, and parsing a module does not resolve what it
         * imports. So a function with an import in it is called valid, stored,
         * and then fails on its first run with a sentence about an operation
         * not being allowed — which names neither the import nor the reason.
         *
         * This is not asserted because it is right. It is asserted because it
         * is what happens, and a change to it should be a change somebody made
         * on purpose.
         */
        val importing = """
            import helper from "lodash";
            export default function uses() { return helper; }
        """.trimIndent()

        assertThat(runner.validate(importing).valid).isTrue()
        assertThat(runner.call(importing, "uses", emptyList())).isInstanceOf(ScriptResult.Failed::class.java)
    }
}
