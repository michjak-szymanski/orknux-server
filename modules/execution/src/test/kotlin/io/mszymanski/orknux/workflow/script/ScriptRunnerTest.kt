package io.mszymanski.orknux.workflow.script

import org.assertj.core.api.Assertions.assertThat
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
    fun `validate reports where the syntax error is`() {
        val broken = runner.validate("export default function ( {")

        assertThat(broken.valid).isFalse()
        assertThat(broken.line).isEqualTo(1)

        assertThat(runner.validate("export default function ok() { return 1; }").valid).isTrue()
    }
}
