package io.mszymanski.orknux.workflow.script

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What a function says while it runs, and who decides how much of it is kept.
 *
 * A sandbox with no `console` is a sandbox nobody can debug. `print` and `load`
 * are turned off by name and are staying off - they write to the server's own
 * streams and take whatever they are given - so `orknux.log` is the way to say
 * something: the line is built in the guest, crosses as text, and the server
 * decides where it goes.
 *
 * Four things:
 *
 *   the levels    debug, info, warn and error, and the line says which it was
 *   the threshold decided in the guest, before anything crosses, so tracing
 *                 left in a function costs one comparison when it is turned off
 *   the objects   anything that is not a string is JSON, because the
 *                 alternative is `[object Object]` in the one place somebody
 *                 was trying to read a value
 *   on a failure  what was said before it threw comes back with the failure,
 *                 which is the case the whole thing is for
 */
class ScriptLogTest {

    private fun runner(level: String = "info") = ScriptRunner(
        ScriptProperties(timeoutMillis = 10_000, statementLimit = 2_000_000, logLevel = level),
    )

    @Test
    fun `a function can say something, and the level comes with it`() {
        val answer = runner().call(
            """
            export default function work() {
              orknux.log.info('starting');
              orknux.log.warn('careful');
              orknux.log.error('no');
              return 1;
            }
            """.trimIndent(),
            "work",
            emptyList(),
        )

        assertThat((answer as ScriptResult.Returned).logs)
            .containsExactly("info starting", "warn careful", "error no")
    }

    /** The point of a threshold: what is below it never crosses. */
    @Test
    fun `a line below the level is dropped where it was written`() {
        val answer = runner(level = "warn").call(
            """
            export default function work() {
              orknux.log.debug('noise');
              orknux.log.info('also noise');
              orknux.log.warn('kept');
              return 1;
            }
            """.trimIndent(),
            "work",
            emptyList(),
        )

        assertThat((answer as ScriptResult.Returned).logs).containsExactly("warn kept")
    }

    @Test
    fun `off keeps none of it`() {
        val answer = runner(level = "off").call(
            "export default function work() {\n  orknux.log.error('not even this');\n  return 1;\n}",
            "work",
            emptyList(),
        )

        assertThat((answer as ScriptResult.Returned).logs).isEmpty()
    }

    /** A level nobody has is `info`, rather than a runner that will not start. */
    @Test
    fun `a level that is not a level falls back to info`() {
        val answer = runner(level = "chatty").call(
            "export default function work() {\n  orknux.log.info('kept');\n  orknux.log.debug('not');\n  return 1;\n}",
            "work",
            emptyList(),
        )

        assertThat((answer as ScriptResult.Returned).logs).containsExactly("info kept")
    }

    @Test
    fun `anything that is not a string is logged as JSON`() {
        val answer = runner().call(
            """
            export default function work() {
              orknux.log.info('answered', { status: 200, ok: true }, 7);
              return 1;
            }
            """.trimIndent(),
            "work",
            emptyList(),
        )

        assertThat((answer as ScriptResult.Returned).logs)
            .containsExactly("""info answered {"status":200,"ok":true} 7""")
    }

    /**
     * The case the logging exists for.
     *
     * A function that returned an answer can be read from its answer; one that
     * threw halfway leaves nothing but a sentence about where. What it said on
     * the way is the only account of what it was doing.
     */
    @Test
    fun `what was said before a failure comes back with the failure`() {
        val answer = runner().call(
            """
            export default function work() {
              orknux.log.info('got this far');
              throw new Error('and no further');
            }
            """.trimIndent(),
            "work",
            emptyList(),
        )

        assertThat(answer).isInstanceOf(ScriptResult.Failed::class.java)
        assertThat((answer as ScriptResult.Failed).logs).containsExactly("info got this far")
    }

    /** There is no host here, and logging never needed one. */
    @Test
    fun `logging works on an installation that wires no host at all`() {
        val answer = ScriptRunner(ScriptProperties(timeoutMillis = 10_000, statementLimit = 2_000_000), null).call(
            "export default function work() {\n  orknux.log.info('said');\n  return 1;\n}",
            "work",
            emptyList(),
        )

        assertThat((answer as ScriptResult.Returned).logs).containsExactly("info said")
    }
}
