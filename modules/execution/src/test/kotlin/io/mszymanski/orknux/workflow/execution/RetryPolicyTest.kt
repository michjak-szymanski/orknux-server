package io.mszymanski.orknux.workflow.execution

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * How long a node waits before its next attempt.
 *
 * Asserted here rather than through a run, because a run proving a wait of an
 * hour is a test that takes an hour. What a run has to show is that the curve
 * reaches the step at all, and FailureHandlingTest does that with seconds.
 */
class RetryPolicyTest {

    private fun policy(seconds: Long, curve: RetryBackoff) =
        RetryPolicy(attempts = 10, backoff = Duration.ofSeconds(seconds), curve = curve)

    @Test
    fun `a fixed policy waits the same however many attempts have gone`() {
        val fixed = policy(30, RetryBackoff.FIXED)

        assertThat(fixed.waitAfter(1)).isEqualTo(Duration.ofSeconds(30))
        assertThat(fixed.waitAfter(5)).isEqualTo(Duration.ofSeconds(30))
    }

    /**
     * The first retry is the node's number on either curve, which is what makes
     * switching a node to doubling safe: nothing it already did gets later.
     */
    @Test
    fun `doubling starts at the wait that was written on the node`() {
        assertThat(policy(30, RetryBackoff.EXPONENTIAL).waitAfter(1)).isEqualTo(Duration.ofSeconds(30))
    }

    @Test
    fun `each attempt after the first waits twice as long as the one before it`() {
        val doubling = policy(30, RetryBackoff.EXPONENTIAL)

        assertThat(doubling.waitAfter(2)).isEqualTo(Duration.ofSeconds(60))
        assertThat(doubling.waitAfter(3)).isEqualTo(Duration.ofSeconds(120))
        assertThat(doubling.waitAfter(4)).isEqualTo(Duration.ofSeconds(240))
    }

    /**
     * The reason there is a cap at all. Ten attempts doubling off the largest
     * wait the editor takes is three weeks; capped, it is nine hours, and the
     * run is still there to look at.
     */
    @Test
    fun `no single wait passes an hour, however far the doubling would have gone`() {
        val doubling = policy(3600, RetryBackoff.EXPONENTIAL)

        assertThat(doubling.waitAfter(2)).isEqualTo(Duration.ofHours(1))
        assertThat(doubling.waitAfter(10)).isEqualTo(Duration.ofHours(1))
        // Far enough out that the doubling itself would have overflowed.
        assertThat(doubling.waitAfter(500)).isEqualTo(Duration.ofHours(1))
    }

    /** A node told to wait nothing waits nothing, doubling or not. */
    @Test
    fun `doubling nothing is nothing`() {
        assertThat(policy(0, RetryBackoff.EXPONENTIAL).waitAfter(4)).isEqualTo(Duration.ZERO)
    }
}
