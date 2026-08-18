package io.mszymanski.orknux.workflow.execution

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What runs after a condition answers.
 *
 * The gate is the whole of branching: both engines ask it the same two
 * questions, so a run takes the same path whichever one carries it. These are
 * the shapes somebody actually draws.
 */
class BranchGateTest {

    /** trigger -> ask -> (yes) shout, (no) file */
    private fun fork() = listOf(
        GraphEdge("trigger", "ask"),
        GraphEdge("ask", "shout", EdgeBranch.YES),
        GraphEdge("ask", "file", EdgeBranch.NO),
    )

    @Test
    fun `a yes runs the yes side and leaves the no side alone`() {
        val gate = BranchGate(fork())

        // A beginning always runs; so does what the trigger leads to.
        assertThat(gate.mayRun("trigger")).isTrue()
        gate.follow("trigger", null)
        assertThat(gate.mayRun("ask")).isTrue()

        gate.follow("ask", EdgeBranch.YES)
        assertThat(gate.mayRun("shout")).isTrue()
        assertThat(gate.mayRun("file")).isFalse()
    }

    @Test
    fun `a no runs the other one`() {
        val gate = BranchGate(fork())
        gate.follow("trigger", null)
        gate.follow("ask", EdgeBranch.NO)

        assertThat(gate.mayRun("shout")).isFalse()
        assertThat(gate.mayRun("file")).isTrue()
    }

    /**
     * A join: both sides lead to the same node.
     *
     * One taken path is enough - which is what somebody drawing a diamond
     * means, and what cannot deadlock waiting for a branch that was never
     * going to arrive.
     */
    @Test
    fun `a node both sides reach runs when either side does`() {
        val gate = BranchGate(fork() + GraphEdge("shout", "log") + GraphEdge("file", "log"))
        gate.follow("trigger", null)
        gate.follow("ask", EdgeBranch.NO)

        assertThat(gate.mayRun("log")).isFalse()
        // Only once the side that was taken has actually run.
        gate.follow("file", null)
        assertThat(gate.mayRun("log")).isTrue()
    }

    /**
     * An edge out of a condition that carries no answer.
     *
     * Every graph drawn before branches existed is made of these, so they run
     * whichever way the condition went - treating them as part of the question
     * would silently drop a path somebody already relies on.
     */
    @Test
    fun `an unlabelled edge out of a condition is taken either way`() {
        val gate = BranchGate(
            listOf(
                GraphEdge("ask", "always"),
                GraphEdge("ask", "onlyYes", EdgeBranch.YES),
            ),
        )

        gate.follow("ask", EdgeBranch.NO)
        assertThat(gate.mayRun("always")).isTrue()
        assertThat(gate.mayRun("onlyYes")).isFalse()
    }

    @Test
    fun `a condition says whether it decides anything`() {
        assertThat(BranchGate(fork()).branches("ask")).isTrue()
        // The old shape: a condition with a plain edge out of it halts instead.
        assertThat(BranchGate(listOf(GraphEdge("ask", "next"))).branches("ask")).isFalse()
    }

    @Test
    fun `a chain behind the branch that was not taken stays shut`() {
        val gate = BranchGate(fork() + GraphEdge("file", "andThen") + GraphEdge("andThen", "later"))
        gate.follow("trigger", null)
        gate.follow("ask", EdgeBranch.YES)

        assertThat(gate.mayRun("file")).isFalse()
        // `file` never runs, so nothing behind it opens either.
        assertThat(gate.mayRun("andThen")).isFalse()
        assertThat(gate.mayRun("later")).isFalse()
    }
}
