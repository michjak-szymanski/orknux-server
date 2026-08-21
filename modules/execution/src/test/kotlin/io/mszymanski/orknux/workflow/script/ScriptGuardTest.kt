package io.mszymanski.orknux.workflow.script

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * What a script costs the server, as opposed to what it is allowed to say.
 *
 * The sandbox tests next door are about the ways out — Java, files, the network,
 * a loop that never ends. These are about the one way through: a script that
 * reaches nothing at all and still takes the installation down, by holding the
 * heap that every other thread is about to want.
 *
 * Every one of these has to actually try it. A cap with a test that stops short
 * of it is a cap nobody has measured, so the scripts here allocate until they
 * are killed, spin past the deadline and pile up against the permit count — and
 * every one of them ends by asking whether the server is still answering.
 *
 * **How the heap bound is reached in a test.** The guard stops a run when the
 * heap is over its mark *and* the run has allocated enough to be the reason. A
 * suite cannot fill a build machine's heap to eighty-five per cent to prove that,
 * and should not try. So the tests move the mark instead of moving the heap:
 * with the pressure percentage set low, the condition the guard evaluates is the
 * real one — the same post-collection reading, the same allocation counter, the
 * same cancellation — reached in a fraction of a second on a heap of any size.
 */
class ScriptGuardTest {

    /**
     * A runner whose heap mark is already behind it, so what decides is whether
     * the script allocates enough to be blamed for it.
     */
    private val strict = ScriptRunner(
        ScriptProperties(
            timeoutMillis = 10_000,
            statementLimit = 500_000_000,
            heapPressurePercent = 1,
            suspectAfterBytes = 64L * 1024 * 1024,
            concurrency = 2,
            queueMillis = 300,
            resultLimitChars = 100_000,
        ),
    )

    /** A runner with room to spare, for the half of a cap that has to let work through. */
    private val relaxed = ScriptRunner(
        ScriptProperties(
            timeoutMillis = 10_000,
            statementLimit = 500_000_000,
            heapPressurePercent = 100,
            suspectAfterBytes = 64L * 1024 * 1024,
            concurrency = 2,
            queueMillis = 300,
        ),
    )

    private val fill = """
        export default function eat() {
          const held = [];
          for (let i = 0; i < 1000000; i++) held.push(new Array(100000).fill(i));
          return held.length;
        }
    """.trimIndent()

    /** The whole point of all of it: the server is still there afterwards. */
    private fun stillAnswering(runner: ScriptRunner = strict) {
        val after = runner.call("""export default function ok() { return 1 + 1; }""", "ok", emptyList())
        assertThat((after as ScriptResult.Returned).json).isEqualTo("2")
    }

    @Test
    fun `a script that allocates until it dies is stopped, and the server outlives it`() {
        /*
         * The gradual fill, which is the shape that actually takes a server down.
         * Not one enormous request — the JVM refuses those outright, on the
         * asking thread, harming nobody — but a great many ordinary ones, held so
         * they cannot be collected, until the heap is gone and the thread that
         * runs out is whichever asks next. That thread is almost never the
         * script's: it is a request, a connection pool, the scheduler.
         */
        val result = strict.call(fill, "eat", emptyList())

        assertThat(result).isInstanceOf(ScriptResult.Failed::class.java)
        val failed = result as ScriptResult.Failed
        assertThat(failed.reason).contains("heap")
        // Stopped by the heap, not by the clock running out.
        assertThat(failed.durationMillis).isLessThan(10_000)
        // And worth trying again, because the reason was how much room there was.
        assertThat(failed.settled).isFalse()

        stillAnswering()
    }

    @Test
    fun `an innocent thread survives a script that is trying to take the heap`() {
        /*
         * The assertion the whole change exists for. Before the guard, this
         * thread — a stand-in for every request handler, pool and timer in the
         * process — died with an OutOfMemoryError while the script itself came
         * back with a tidy failure. The script losing was never the problem.
         */
        val died = AtomicReference<Throwable?>(null)
        val served = AtomicInteger()
        val stop = AtomicReference(false)
        val innocent = Thread {
            try {
                while (!stop.get()) {
                    val chunk = ByteArray(64 * 1024)
                    chunk[0] = 1
                    served.incrementAndGet()
                    Thread.sleep(1)
                }
            } catch (failure: Throwable) {
                died.set(failure)
            }
        }
        innocent.isDaemon = true
        innocent.start()

        val result = strict.call(fill, "eat", emptyList())

        stop.set(true)
        innocent.join(2_000)

        assertThat(result).isInstanceOf(ScriptResult.Failed::class.java)
        assertThat(died.get()).describedAs("the rest of the server").isNull()
        assertThat(served.get()).describedAs("and it kept working throughout").isGreaterThan(0)

        stillAnswering()
    }

    @Test
    fun `a script that keeps nothing is not blamed for a heap it did not fill`() {
        /*
         * The other half, and the reason the guard asks two questions rather than
         * one. Community GraalJS runs in the interpreter, where arithmetic boxes:
         * this loop allocates hundreds of megabytes and keeps not one byte of it,
         * and a bound on allocation alone would kill it while the real culprit
         * carried on. Here the heap is fine, so the churn is nobody's business.
         */
        val result = relaxed.call(
            """
            export default function work(n) {
              let total = 0;
              for (let i = 0; i < n; i++) total += (i * 7) % 13;
              return total;
            }
            """.trimIndent(),
            "work",
            listOf("3000000"),
        )

        assertThat(result).isInstanceOf(ScriptResult.Returned::class.java)
        stillAnswering(relaxed)
    }

    @Test
    fun `a small script beside somebody else's heap trouble is left alone`() {
        /*
         * `strict` is configured as though the heap were already over its mark —
         * which, for a script that has allocated nothing to speak of, is exactly
         * the situation of an innocent bystander. It must still run.
         */
        val result = strict.call(
            """export default function small(x) { return { seen: x.id }; }""",
            "small",
            listOf("""{"id":9}"""),
        )

        assertThat((result as ScriptResult.Returned).json).isEqualTo("""{"seen":9}""")
    }

    @Test
    fun `a script that loops past the deadline is stopped by the clock, and says so`() {
        /*
         * The two bounds have to stay told apart. A spin loop holds nothing, so if
         * this came back talking about the heap the guard would be blaming the
         * wrong thing, and whoever wrote the script would go looking for an
         * allocation that was never there.
         */
        val impatient = ScriptRunner(
            ScriptProperties(
                timeoutMillis = 400,
                statementLimit = 5_000_000_000L,
                heapPressurePercent = 100,
                concurrency = 2,
                queueMillis = 300,
            ),
        )

        val started = System.nanoTime()
        val result = impatient.call("""export default function spin() { while (true) { } }""", "spin", emptyList())
        val took = (System.nanoTime() - started) / 1_000_000

        assertThat((result as ScriptResult.Failed).reason).contains("longer than 400 ms")
        assertThat(result.reason).doesNotContain("heap")
        // Stopped rather than merely slow: without the watchdog it would not have
        // come back at all.
        assertThat(took).isLessThan(30_000)

        assertThat((impatient.call("""export default function ok() { return 1; }""", "ok", emptyList()) as ScriptResult.Returned).json)
            .isEqualTo("1")
    }

    @Test
    fun `a script cannot hand back more JSON than the server will carry`() {
        /*
         * Nowhere near the heap — a few hundred kilobytes of string costs a few
         * hundred kilobytes to make — and still far more than belongs in a step's
         * row, in the tree it is parsed into on the way there, and in the input of
         * whatever node reads it next. Three copies of whatever a script feels
         * like returning is its own way of taking the server, and the cheap place
         * to refuse it is where the string has just been made.
         */
        val result = strict.call(
            """export default function flood() { return 'x'.repeat(500000); }""",
            "flood",
            emptyList(),
        )

        assertThat(result).isInstanceOf(ScriptResult.Failed::class.java)
        assertThat((result as ScriptResult.Failed).reason)
            .contains("characters of JSON")
            .contains("100000")

        // And a result inside the bound still comes back whole.
        val fine = strict.call("""export default function ok() { return 'x'.repeat(50000); }""", "ok", emptyList())
        assertThat((fine as ScriptResult.Returned).json).hasSize(50_002)
    }

    @Test
    fun `only as many scripts run at once as the server allows`() {
        /*
         * The heap bound applies to a run; this is what applies to the
         * installation. Without a permit count, thirty concurrent calls are
         * thirty runs' worth of live data, and the sum of them is the heap again
         * — arrived at by scripts none of which broke a rule of its own.
         *
         * Six callers against two permits and a three-hundred-millisecond queue.
         * The two that get in hold their permits for longer than that, so the ones
         * behind are turned away. What matters as much as the refusal is that it
         * comes back *unsettled*: that is a workflow step retrying in a moment,
         * not a function reported to its author as broken.
         */
        val crowded = ScriptRunner(
            ScriptProperties(
                timeoutMillis = 30_000,
                statementLimit = 5_000_000_000L,
                heapPressurePercent = 100,
                concurrency = 2,
                queueMillis = 300,
            ),
        )

        val callers = Executors.newFixedThreadPool(6)
        val ready = CountDownLatch(6)
        val admitted = AtomicInteger()
        val turnedAway = AtomicInteger()
        val wrongly = AtomicReference<String?>(null)

        try {
            val calls = (1..6).map {
                callers.submit {
                    ready.countDown()
                    ready.await()
                    val result = crowded.call(
                        // Holds its permit for longer than the queue allows, and
                        // does it by watching the clock rather than by keeping
                        // anything, so the permit count is the only bound in play.
                        """
                        export default function slow() {
                          const until = Date.now() + 500;
                          while (Date.now() < until) { }
                          return 'done';
                        }
                        """.trimIndent(),
                        "slow",
                        emptyList(),
                    )
                    when (result) {
                        is ScriptResult.Returned -> admitted.incrementAndGet()
                        is ScriptResult.Failed ->
                            if (result.reason.contains("at once")) {
                                turnedAway.incrementAndGet()
                                if (result.settled) wrongly.set("a full server was reported as settled")
                            } else {
                                wrongly.set(result.reason)
                            }
                    }
                }
            }
            calls.forEach { it.get(120, TimeUnit.SECONDS) }
        } finally {
            callers.shutdownNow()
        }

        assertThat(wrongly.get()).isNull()
        assertThat(turnedAway.get())
            .describedAs("six callers, two permits: some had to be turned away")
            .isGreaterThan(0)
        assertThat(admitted.get())
            .describedAs("and the ones holding the permits still finished")
            .isGreaterThan(0)
        assertThat(admitted.get() + turnedAway.get()).isEqualTo(6)

        assertThat((crowded.call("""export default function ok() { return 1; }""", "ok", emptyList()) as ScriptResult.Returned).json)
            .isEqualTo("1")
    }

    @Test
    fun `a source that takes the heap while being checked is stopped before it is stored`() {
        /*
         * `arity` is not a spectator: counting a function's parameters means
         * evaluating its module, so the top-level code runs — and this entry point
         * is reached from the editor, by anybody who may write a function, before
         * anyone has agreed to save it. A source that fills the heap in its module
         * body used to fill it right here, on a request thread, without ever
         * becoming a function at all.
         */
        val counted = strict.arity(
            """
            const held = [];
            for (let i = 0; i < 1000000; i++) held.push(new Array(100000).fill(i));
            export default function never(a, b) { return a + b; }
            """.trimIndent(),
        )

        assertThat(counted).isInstanceOf(ScriptArity.Unreadable::class.java)

        stillAnswering()
        // And a source that behaves is still counted, by the same guarded path.
        assertThat(strict.arity("""export default function two(a, b) { return a + b; }"""))
            .isEqualTo(ScriptArity.Counted(2))
    }

    @Test
    fun `fifty stopped runs leave nothing behind`() {
        /*
         * Every stopped run holds a permit, a context and a scheduled sampler. If
         * any of the three outlived its run, the server would be worn down by the
         * scripts it had successfully stopped — which would make the guard a
         * slower way of losing rather than a way of not losing.
         */
        repeat(50) { strict.call(fill, "eat", emptyList()) }

        stillAnswering()
    }
}
