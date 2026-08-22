package io.mszymanski.orknux.server.llm

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The arithmetic, on its own.
 *
 * No database and no Spring: this is the shape four numbers are derived in, and
 * what it has to keep being is the thing the five constants in
 * [LlmSessionRecorder] used to be. If the split ever moves, the default moves
 * with it - and that is the one thing here that must not happen quietly, since
 * it is what every installation that sets nothing is running on.
 */
class SessionMemoryBudgetTest {

    /**
     * The default is exactly what was compiled in before there was a setting.
     *
     * Forty turns, twenty-four thousand characters of them, twenty-four
     * lookups, sixteen thousand characters of those and eight thousand of any
     * one. An installation that sets nothing behaves today as it did yesterday,
     * and this is the assertion that says so.
     */
    @Test
    fun `the default is the five numbers that used to be constants`() {
        assertThat(SessionMemoryBudget.DEFAULT).isEqualTo(
            SessionMemoryBudget(
                turns = 40,
                memoryChars = 24_000,
                results = 24,
                recallChars = 16_000,
                longestResult = 8_000,
            ),
        )
    }

    /** And it is a derivation rather than five literals that happen to match. */
    @Test
    fun `the default is what a forty thousand character budget works out to`() {
        assertThat(SessionMemoryBudget.of(DEFAULT_CHARS)).isEqualTo(SessionMemoryBudget.DEFAULT)
    }

    /**
     * One number in, four out, in the proportions that were already there.
     *
     * Three fifths of the allowance to what was said and two fifths to what
     * tools returned, the turn count following the first and the longest single
     * result being half the second - so two ordinary lookups still fit, which
     * is the case worth sizing for.
     */
    @Test
    fun `four of the five follow the one number that is set`() {
        val budget = SessionMemoryBudget.of(80_000)

        assertThat(budget.memoryChars).isEqualTo(48_000)
        assertThat(budget.recallChars).isEqualTo(32_000)
        assertThat(budget.turns).isEqualTo(80)
        assertThat(budget.longestResult).isEqualTo(16_000)
        assertThat(budget.totalChars).isEqualTo(80_000)
    }

    /**
     * And the fifth deliberately does not, because it is not an allowance.
     *
     * How many lookups are read is a ceiling on a query: repeats are dropped
     * after they are read, so an agent that asked one thing five times would
     * recall nothing else if this followed the budget down.
     */
    @Test
    fun `how many lookups are read does not follow the budget`() {
        assertThat(SessionMemoryBudget.of(4_000).results).isEqualTo(SessionMemoryBudget.DEFAULT.results)
        assertThat(SessionMemoryBudget.of(400_000).results).isEqualTo(SessionMemoryBudget.DEFAULT.results)
    }

    /** Nothing comes back as zero of something; a budget that small is refused. */
    @Test
    fun `even an absurd budget keeps room for one of each`() {
        val budget = SessionMemoryBudget.of(10)

        assertThat(budget.turns).isEqualTo(1)
        assertThat(budget.longestResult).isGreaterThan(0)
    }
}
