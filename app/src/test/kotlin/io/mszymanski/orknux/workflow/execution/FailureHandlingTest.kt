package io.mszymanski.orknux.workflow.execution

import io.mszymanski.orknux.server.OrknuxServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource

/**
 * What a run does when a step cannot do its work.
 *
 * Two answers, and both of them are the node's: attempt it again, for a failure
 * that might come out differently, and leave by a different edge, for one that
 * will not. A node given neither behaves exactly as it always has, which is the
 * property most of these are really about.
 *
 * The inline engine, so a run has finished by the time `start` returns and the
 * assertions can be about what it did.
 */
@SpringBootTest(classes = [OrknuxServer::class])
@Import(ExecutionTestConfig::class)
@TestPropertySource(properties = ["orknux.temporal.enabled=false"])
class FailureHandlingTest(
    @Autowired val engine: ExecutionEngine,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val graphs: WorkflowGraphSource,
) {

    @BeforeEach
    fun reset() {
        logs.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        (graphs as FakeWorkflowGraphSource).graphs.clear()
    }

    @Test
    fun `a failure the graph has an answer for takes the failure edge and the run finishes`() {
        withFallback("boom")
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)

        assertThat(run.status).isEqualTo(ExecutionStatus.COMPLETED)
        assertThat(run.error).isNull()

        val recorded = stepsBy(run)
        // The step is still failed and still says why: handling a failure is
        // not pretending it did not happen.
        assertThat(recorded.getValue("boom").status).isEqualTo(StepStatus.FAILED)
        assertThat(recorded.getValue("boom").error).contains("boom has no answer")
        // And the way it went is written down, so a re-run can tell this from a
        // path that was never reached.
        assertThat(recorded.getValue("boom").branch).isEqualTo(EdgeBranch.FAILURE)

        assertThat(recorded.getValue("ok-rescue").status).isEqualTo(StepStatus.COMPLETED)
        // The happy path is shut, the same way the side a condition refused is.
        assertThat(recorded.getValue("ok-onwards").status).isEqualTo(StepStatus.SKIPPED)
    }

    /**
     * The same graph without the failure edge. Nothing about failing changed
     * for a node nobody has told to handle it.
     */
    @Test
    fun `a failure with nowhere to go stops the run where it stood`() {
        graph(
            nodes = listOf(node("boom"), node("ok-onwards")),
            edges = listOf(GraphEdge("boom", "ok-onwards")),
        )
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)

        assertThat(run.status).isEqualTo(ExecutionStatus.FAILED)
        assertThat(run.error).contains("boom has no answer")

        val recorded = stepsBy(run)
        assertThat(recorded.getValue("boom").status).isEqualTo(StepStatus.FAILED)
        assertThat(recorded.getValue("boom").branch).isNull()
        // Never reached rather than refused, which is what pending has always meant.
        assertThat(recorded.getValue("ok-onwards").status).isEqualTo(StepStatus.PENDING)
    }

    /**
     * What a node handling its own failure is handed.
     *
     * The step that failed produced nothing, so what reaches the fallback is
     * what reached the node that failed - which is the only honest thing to
     * give it.
     */
    @Test
    fun `the node handling a failure is given what the failed one was given`() {
        withFallback("boom")
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)

        val recorded = stepsBy(run)
        assertThat(recorded.getValue("ok-rescue").input).isEqualTo(recorded.getValue("boom").input)
        assertThat(recorded.getValue("ok-rescue").input).isEqualTo(INPUT)
    }

    @Test
    fun `a step that fails and then works within its attempts completes, and so does the run`() {
        graph(
            nodes = listOf(retrying("flaky-post", attempts = 3), node("ok-onwards")),
            edges = listOf(GraphEdge("flaky-post", "ok-onwards")),
        )
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)

        assertThat(run.status).isEqualTo(ExecutionStatus.COMPLETED)

        val flaky = stepsBy(run).getValue("flaky-post")
        assertThat(flaky.status).isEqualTo(StepStatus.COMPLETED)
        assertThat(flaky.attempts).isEqualTo(3)
        assertThat(flaky.output).isEqualTo("flaky-post did the work on attempt 3")
        // What the last attempt produced is what the run carries on with, and
        // nothing behind it was skipped.
        assertThat(stepsBy(run).getValue("ok-onwards").status).isEqualTo(StepStatus.COMPLETED)

        // The attempts that failed are in the log rather than only in the count.
        assertThat(linesOf(run)).anyMatch { it.contains("failed on attempt 1 of 3") }
        assertThat(linesOf(run)).anyMatch { it.contains("failed on attempt 2 of 3") }
    }

    @Test
    fun `a step whose attempts run out fails the run, having spent all of them`() {
        graph(nodes = listOf(retrying("boom", attempts = 3)), edges = emptyList())
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)

        assertThat(run.status).isEqualTo(ExecutionStatus.FAILED)
        assertThat(stepsBy(run).getValue("boom").attempts).isEqualTo(3)
    }

    /**
     * The flag the whole distinction rests on. A runner that has already worked
     * out that trying again cannot help is not made to prove it twice more.
     */
    @Test
    fun `a failure the runner called final is not attempted again whatever the policy says`() {
        graph(nodes = listOf(retrying("settled-channel", attempts = 5)), edges = emptyList())
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)

        assertThat(run.status).isEqualTo(ExecutionStatus.FAILED)
        assertThat(stepsBy(run).getValue("settled-channel").attempts).isEqualTo(1)
    }

    /** Attempts left where they were: a node with no policy gets one go. */
    @Test
    fun `a node with no policy is attempted once`() {
        graph(nodes = listOf(node("boom")), edges = emptyList())
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)

        assertThat(stepsBy(run).getValue("boom").attempts).isEqualTo(1)
    }

    /** Both together: it is tried three times, and then the fallback catches it. */
    @Test
    fun `a node that retries and then still fails leaves by its failure edge`() {
        graph(
            nodes = listOf(retrying("boom", attempts = 3), node("ok-rescue"), node("ok-onwards")),
            edges = listOf(
                GraphEdge("boom", "ok-onwards"),
                GraphEdge("boom", "ok-rescue", EdgeBranch.FAILURE),
            ),
        )
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)

        assertThat(run.status).isEqualTo(ExecutionStatus.COMPLETED)
        val recorded = stepsBy(run)
        assertThat(recorded.getValue("boom").attempts).isEqualTo(3)
        assertThat(recorded.getValue("boom").branch).isEqualTo(EdgeBranch.FAILURE)
        assertThat(recorded.getValue("ok-rescue").status).isEqualTo(StepStatus.COMPLETED)
        assertThat(recorded.getValue("ok-onwards").status).isEqualTo(StepStatus.SKIPPED)
    }

    /**
     * The curve, as far as a run can show it: what the second wait is.
     *
     * Seconds, because this engine spends the wait on the thread carrying the
     * run - so the numbers are the smallest ones that can still be told apart,
     * and the arithmetic itself is asserted in RetryPolicyTest where it costs
     * nothing.
     */
    @Test
    fun `a doubling node waits longer before each attempt than before the last`() {
        graph(nodes = listOf(doubling("boom", attempts = 3, seconds = 1)), edges = emptyList())
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)

        assertThat(run.status).isEqualTo(ExecutionStatus.FAILED)
        assertThat(stepsBy(run).getValue("boom").attempts).isEqualTo(3)
        // The log says what was actually waited, not what is written on the node.
        assertThat(linesOf(run)).anyMatch { it.contains("attempt 1 of 3") && it.contains("Trying again in 1s") }
        assertThat(linesOf(run)).anyMatch { it.contains("attempt 2 of 3") && it.contains("Trying again in 2s") }
    }

    /** A node whose failure is handled, with an ordinary node on its happy path. */
    private fun withFallback(key: String) = graph(
        nodes = listOf(node(key), node("ok-onwards"), node("ok-rescue")),
        edges = listOf(
            GraphEdge(key, "ok-onwards"),
            GraphEdge(key, "ok-rescue", EdgeBranch.FAILURE),
        ),
    )

    private fun graph(nodes: List<GraphNode>, edges: List<GraphEdge>) {
        (graphs as FakeWorkflowGraphSource).graphs[WORKFLOW] =
            WorkflowGraph(workflowId = WORKFLOW, name = "Answer the customer", nodes = nodes, edges = edges)
    }

    private fun node(key: String) = GraphNode(key = key, kind = NodeKind.ACTION, name = key)

    /** No wait between attempts: what is being tested is the count, not the clock. */
    private fun retrying(key: String, attempts: Int) =
        GraphNode(key = key, kind = NodeKind.ACTION, name = key, retryAttempts = attempts, retryBackoffSeconds = 0)

    /** The same, on the doubling curve, with a wait small enough to sit out. */
    private fun doubling(key: String, attempts: Int, seconds: Int) = GraphNode(
        key = key,
        kind = NodeKind.ACTION,
        name = key,
        retryAttempts = attempts,
        retryBackoffSeconds = seconds,
        retryBackoff = RetryBackoff.EXPONENTIAL,
    )

    private fun stepsBy(execution: WorkflowExecution) =
        steps.findByExecutionIdOrderByOrderAsc(requireNotNull(execution.id)).associateBy { it.nodeKey }

    private fun linesOf(execution: WorkflowExecution) =
        logs.findByExecutionIdOrderBySequenceAsc(requireNotNull(execution.id)).map { it.message }

    private companion object {
        const val WORKSPACE = 7L
        const val WORKFLOW = 1L
        const val INPUT = """{"ticket":"T-1"}"""
    }
}
