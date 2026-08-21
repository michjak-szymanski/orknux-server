package io.mszymanski.orknux.workflow.execution

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.random.Random

/**
 * How long a node waits before its next attempt.
 *
 * Asserted here rather than through a run, because a run proving a wait of an
 * hour is a test that takes an hour. What a run has to show is that the curve
 * reaches the step at all, and FailureHandlingTest does that with seconds.
 */
class RetryPolicyTest {

    private fun policy(
        seconds: Long,
        multiplier: Double = RetryPolicy.NO_GROWTH,
        maxWait: Duration = RetryPolicy.MAX_WAIT,
        jitter: Double = RetryPolicy.NO_JITTER,
    ) = RetryPolicy(
        attempts = 10,
        backoff = Duration.ofSeconds(seconds),
        multiplier = multiplier,
        maxWait = maxWait,
        jitter = jitter,
    )

    @Test
    fun `a policy with no multiplier waits the same however many attempts have gone`() {
        val flat = policy(30)

        assertThat(flat.waitAfter(1)).isEqualTo(Duration.ofSeconds(30))
        assertThat(flat.waitAfter(5)).isEqualTo(Duration.ofSeconds(30))
    }

    /**
     * The first retry is the node's number on every curve, which is what makes
     * steepening a node safe: nothing it already did gets later.
     */
    @Test
    fun `a curve starts at the wait that was written on the node`() {
        assertThat(policy(30, multiplier = 2.0).waitAfter(1)).isEqualTo(Duration.ofSeconds(30))
        assertThat(policy(30, multiplier = 1.5).waitAfter(1)).isEqualTo(Duration.ofSeconds(30))
    }

    /** What the flag used to say, said as a number, and meaning the same thing. */
    @Test
    fun `a multiplier of two doubles the wait each time, as the flag it replaced did`() {
        val doubling = policy(30, multiplier = 2.0)

        assertThat(doubling.waitAfter(2)).isEqualTo(Duration.ofSeconds(60))
        assertThat(doubling.waitAfter(3)).isEqualTo(Duration.ofSeconds(120))
        assertThat(doubling.waitAfter(4)).isEqualTo(Duration.ofSeconds(240))
    }

    /**
     * The whole of what a boolean could not express: a wait that grows, and does
     * not treble by the third retry.
     */
    @Test
    fun `a multiplier between one and two grows the wait without doubling it`() {
        val gentle = policy(20, multiplier = 1.5)

        assertThat(gentle.waitAfter(1)).isEqualTo(Duration.ofSeconds(20))
        assertThat(gentle.waitAfter(2)).isEqualTo(Duration.ofSeconds(30))
        assertThat(gentle.waitAfter(3)).isEqualTo(Duration.ofSeconds(45))
        // 67.5s, and a wait is spent in whole milliseconds.
        assertThat(gentle.waitAfter(4)).isEqualTo(Duration.ofMillis(67_500))
    }

    /** Below one is read as one; a retry that comes back sooner each time is nobody's intent. */
    @Test
    fun `a multiplier under one does not shrink the wait`() {
        assertThat(policy(30, multiplier = 0.5).waitAfter(4)).isEqualTo(Duration.ofSeconds(30))
    }

    @Test
    fun `the node's own ceiling holds every wait under it`() {
        val capped = policy(10, multiplier = 3.0, maxWait = Duration.ofSeconds(60))

        assertThat(capped.waitAfter(1)).isEqualTo(Duration.ofSeconds(10))
        assertThat(capped.waitAfter(2)).isEqualTo(Duration.ofSeconds(30))
        // 90s asked for, 60s allowed.
        assertThat(capped.waitAfter(3)).isEqualTo(Duration.ofSeconds(60))
        assertThat(capped.waitAfter(9)).isEqualTo(Duration.ofSeconds(60))
    }

    /**
     * The reason there is a ceiling at all, and why the engine keeps one of its
     * own above whatever the node says. Ten attempts trebling off the largest
     * wait the editor takes is a month; capped, the run is still there to look at.
     */
    @Test
    fun `no single wait passes an hour, however far the curve would have gone`() {
        val steep = policy(3600, multiplier = 3.0)

        assertThat(steep.waitAfter(2)).isEqualTo(Duration.ofHours(1))
        assertThat(steep.waitAfter(10)).isEqualTo(Duration.ofHours(1))
        // Far enough out that the growth itself has stopped being a number.
        assertThat(steep.waitAfter(500)).isEqualTo(Duration.ofHours(1))
    }

    /** A ceiling above the engine's own is the engine's own. */
    @Test
    fun `a ceiling set past the hour is still the hour`() {
        val asked = policy(3000, multiplier = 2.0, maxWait = Duration.ofDays(1))

        assertThat(asked.waitAfter(3)).isEqualTo(Duration.ofHours(1))
    }

    /** A node told to wait nothing waits nothing, whatever it is multiplied by. */
    @Test
    fun `growing nothing is nothing`() {
        assertThat(policy(0, multiplier = 3.0).waitAfter(4)).isEqualTo(Duration.ZERO)
    }

    /**
     * Jitter takes off and never adds, which is what leaves every other number
     * here an upper bound: a policy with jitter never waits longer than the same
     * policy without it, so the ceiling and the budget go on meaning what they say.
     */
    @Test
    fun `jitter only ever shortens a wait, and never past nothing`() {
        val jittered = policy(60, jitter = 0.25)
        val random = Random(4)

        val drawn = (1..200).map { jittered.waitAfter(1, random) }

        assertThat(drawn).allSatisfy {
            assertThat(it).isBetween(Duration.ofSeconds(45), Duration.ofSeconds(60))
        }
        // And it is actually drawing rather than returning the same wait twice.
        assertThat(drawn.distinct()).hasSizeGreaterThan(1)
    }

    /** All of it: a wait drawn from anywhere up to what the curve asked for. */
    @Test
    fun `full jitter can take a wait down to nothing`() {
        val jittered = policy(60, jitter = RetryPolicy.FULL_JITTER)
        val random = Random(9)

        val drawn = (1..500).map { jittered.waitAfter(1, random) }

        assertThat(drawn).allSatisfy { assertThat(it).isBetween(Duration.ZERO, Duration.ofSeconds(60)) }
        assertThat(drawn.min()).isLessThan(Duration.ofSeconds(6))
        assertThat(drawn.max()).isGreaterThan(Duration.ofSeconds(54))
    }

    /** Jitter shortens the wait the curve arrived at, ceiling included. */
    @Test
    fun `jitter is taken off the capped wait rather than the one asked for`() {
        val jittered = policy(10, multiplier = 4.0, maxWait = Duration.ofSeconds(30), jitter = 0.5)

        val drawn = (1..200).map { jittered.waitAfter(5, Random(11)) }

        assertThat(drawn).allSatisfy {
            assertThat(it).isBetween(Duration.ofSeconds(15), Duration.ofSeconds(30))
        }
    }

    /** No jitter is the wait exactly, and the random is never asked. */
    @Test
    fun `a policy without jitter waits what the curve says to the millisecond`() {
        assertThat(policy(30, multiplier = 2.0).waitAfter(3, Random(1))).isEqualTo(Duration.ofSeconds(120))
    }
}
