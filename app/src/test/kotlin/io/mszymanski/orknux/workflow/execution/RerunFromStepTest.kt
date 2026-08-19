package io.mszymanski.orknux.workflow.execution

import io.mszymanski.orknux.server.OrknuxServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource

/**
 * Running a workflow again from one of its steps.
 *
 * The whole of the feature is that the steps ahead of the chosen one are read
 * out of the earlier run rather than performed a second time - for a node that
 * sends a message or takes a payment, performing it again is not a repeat but a
 * second occurrence. So these are about what the new run inherits: the record
 * of what already happened, the payload the earlier run was holding when it got
 * there, and the branches it took, which is the part nothing else can recover.
 *
 * The inline engine, so a run has finished by the time `start` returns and the
 * assertions can be about what it did.
 */
@SpringBootTest(classes = [OrknuxServer::class])
@Import(ExecutionTestConfig::class)
@TestPropertySource(properties = ["orknux.temporal.enabled=false"])
class RerunFromStepTest(
    @Autowired val engine: ExecutionEngine,
    @Autowired val planner: ExecutionPlanner,
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
    fun `a run started partway down carries what came before and performs the rest`() {
        straightLine()
        val first = run()
        val before = stepsOf(first)
        assertThat(before.map { it.status }).containsOnly(StepStatus.COMPLETED)

        val again = rerun(first, "ok-three")
        val after = stepsOf(again)

        assertThat(again.status).isEqualTo(ExecutionStatus.COMPLETED)
        assertThat(after.map { it.nodeKey }).containsExactly("ok-one", "ok-two", "ok-three")
        assertThat(after.map { it.carriedOver }).containsExactly(true, true, false)

        // The two ahead of the chosen node are the earlier run's work, shown as
        // it was: same status, same input, same output, same times. A copy that
        // said COMPLETED with this run's clock on it would be a claim about work
        // this run never did.
        assertThat(after[0].status).isEqualTo(StepStatus.COMPLETED)
        assertThat(after[0].input).isEqualTo(before[0].input)
        assertThat(after[0].output).isEqualTo(before[0].output)
        assertThat(after[1].startedAt?.toInstant()).isEqualTo(before[1].startedAt?.toInstant())
        assertThat(after[1].finishedAt?.toInstant()).isEqualTo(before[1].finishedAt?.toInstant())

        // And the chosen node actually ran, handed exactly what the earlier run
        // was holding when it reached it: not the input the run started on,
        // which is what a re-run that forgot to carry anything would pass.
        val ran = after[2]
        assertThat(ran.status).isEqualTo(StepStatus.COMPLETED)
        assertThat(ran.output).isEqualTo("ok-three did the work")
        assertThat(ran.input).isEqualTo(before[2].input)
        assertThat(ran.input).isNotEqualTo(first.input)
        assertThat(ran.startedAt?.toInstant()).isAfter(before[2].finishedAt?.toInstant())
    }

    /**
     * The same thing said at the plan, where the two halves are visible: what
     * the run is handed to start with, and what is left for an engine to do.
     */
    @Test
    fun `the plan for a re-run holds the earlier payload and leaves only what is still to run`() {
        straightLine()
        val first = run()
        val before = stepsOf(first)

        val plan = planner.plan(
            workspaceId = WORKSPACE,
            workflowId = WORKFLOW,
            trigger = ExecutionTrigger.MANUAL,
            input = first.input,
            resumeFrom = ResumePoint(requireNotNull(first.id), "ok-three"),
        )

        assertThat(plan.execution.carried).isEqualTo(before[2].input)
        // Only the chosen node onwards; handing an engine a carried-over step
        // would perform it a second time.
        assertThat(plan.steps.map { it.nodeKey }).containsExactly("ok-three")
        assertThat(plan.carried.map { it.nodeKey }).containsExactly("ok-one", "ok-two")
    }

    /**
     * The subtle half. The earlier run's answers are replayed into the gate
     * before anything is walked, so the chosen node is reachable at all and the
     * branch that run refused stays refused.
     */
    @Test
    fun `a re-run behind a condition goes the way the first run went, and does not revive the other`() {
        branching("asks-yes-approved")
        val first = run()
        assertThat(statusesOf(first)).containsExactly(
            StepStatus.COMPLETED,
            StepStatus.COMPLETED,
            StepStatus.COMPLETED,
            StepStatus.SKIPPED,
        )

        val again = rerun(first, "ok-taken")
        val after = stepsOf(again).associateBy { it.nodeKey }

        // The condition is carried over rather than asked again, and the answer
        // it gave comes with it.
        assertThat(after.getValue("asks-yes-approved").carriedOver).isTrue()
        assertThat(after.getValue("asks-yes-approved").branch).isEqualTo(EdgeBranch.YES)

        // So nothing in this run says the condition went YES, and this only ran
        // because the earlier answer was replayed into the gate. Without that it
        // would be a node nothing leads to, and be skipped as unreachable.
        assertThat(after.getValue("ok-taken").carriedOver).isFalse()
        assertThat(after.getValue("ok-taken").status).isEqualTo(StepStatus.COMPLETED)
        assertThat(after.getValue("ok-taken").output).isEqualTo("ok-taken did the work")

        // And the way the first run did not go is still not taken. This is the
        // node a re-run that dropped the branches would quietly perform.
        val refused = after.getValue("ok-refused")
        assertThat(refused.carriedOver).isFalse()
        assertThat(refused.status).isEqualTo(StepStatus.SKIPPED)
        assertThat(refused.output).contains("went the other way")
    }

    @Test
    fun `a condition writes down which way it sent the run`() {
        branching("asks-yes-approved")
        val yes = stepsOf(run()).associateBy { it.nodeKey }

        assertThat(yes.getValue("asks-yes-approved").branch).isEqualTo(EdgeBranch.YES)
        // Nothing else answers anything, so nothing else writes a branch down.
        assertThat(yes.filterKeys { it != "asks-yes-approved" }.values.map { it.branch }).containsOnlyNulls()

        reset()
        branching("asks-no-approved")
        val no = stepsOf(run()).associateBy { it.nodeKey }

        assertThat(no.getValue("asks-no-approved").branch).isEqualTo(EdgeBranch.NO)
        assertThat(no.getValue("ok-taken").status).isEqualTo(StepStatus.SKIPPED)
        assertThat(no.getValue("ok-refused").status).isEqualTo(StepStatus.COMPLETED)
    }

    @Test
    fun `starting at a node the workflow no longer has is refused`() {
        straightLine()
        val first = run()

        assertThatThrownBy { rerun(first, "ok-redrawn-away") }
            .isInstanceOf(StepNotInWorkflowException::class.java)
            .hasMessageContaining("ok-redrawn-away")
    }

    /**
     * A node added since the earlier run has no record behind it, whether it is
     * the one being started at or one ahead of it. Either way the payload this
     * run would carry is missing whatever that node contributes.
     */
    @Test
    fun `starting where the earlier run has no record of a step is refused`() {
        straightLine()
        val first = run()

        graph(
            nodes = listOf(node("ok-zero"), node("ok-one"), node("ok-two"), node("ok-three")),
            edges = listOf(
                GraphEdge("ok-zero", "ok-one"),
                GraphEdge("ok-one", "ok-two"),
                GraphEdge("ok-two", "ok-three"),
            ),
        )

        assertThatThrownBy { rerun(first, "ok-three") }
            .isInstanceOf(StepNotInExecutionException::class.java)
            .hasMessageContaining("ok-zero")

        graph(
            nodes = listOf(node("ok-one"), node("ok-two"), node("ok-three"), node("ok-four")),
            edges = listOf(
                GraphEdge("ok-one", "ok-two"),
                GraphEdge("ok-two", "ok-three"),
                GraphEdge("ok-three", "ok-four"),
            ),
        )

        assertThatThrownBy { rerun(first, "ok-four") }
            .isInstanceOf(StepNotInExecutionException::class.java)
            .hasMessageContaining("ok-four")
    }

    @Test
    fun `starting at a step the earlier run never reached is refused`() {
        graph(
            nodes = listOf(node("ok-one"), node("boom"), node("ok-three")),
            edges = listOf(GraphEdge("ok-one", "boom"), GraphEdge("boom", "ok-three")),
        )
        val first = run()
        assertThat(first.status).isEqualTo(ExecutionStatus.FAILED)
        assertThat(statusesOf(first)).containsExactly(StepStatus.COMPLETED, StepStatus.FAILED, StepStatus.PENDING)

        // Nothing was recorded for it, so there is nothing to carry into a run
        // starting there - the step before it never produced anything.
        assertThatThrownBy { rerun(first, "ok-three") }
            .isInstanceOf(StepNeverRanException::class.java)
            .hasMessageContaining("ok-three")
    }

    @Test
    fun `starting inside the branch the earlier run refused is refused`() {
        branching("asks-yes-approved")
        val first = run()

        assertThatThrownBy { rerun(first, "ok-refused") }
            .isInstanceOf(BranchNotTakenException::class.java)
            .hasMessageContaining("ok-refused")
    }

    /**
     * What every run recorded before the branch column existed looks like: a
     * condition that decided something, with no note of what. Following both
     * ways would revive the path it refused and picking one would be a guess.
     */
    @Test
    fun `starting below a condition whose answer was never recorded is refused`() {
        branching("asks-yes-approved")
        val first = run()

        val condition = requireNotNull(steps.findByExecutionIdAndNodeKey(requireNotNull(first.id), "asks-yes-approved"))
        condition.branch = null
        steps.save(condition)

        assertThatThrownBy { rerun(first, "ok-taken") }
            .isInstanceOf(BranchNotRecordedException::class.java)
            .hasMessageContaining("asks-yes-approved")
    }

    @Test
    fun `starting from a run that has not finished is refused`() {
        straightLine()
        val first = run()

        // A run still going is still deciding, so what it produced is not yet
        // settled - reading half a record would leave two runs walking the same
        // graph with the same payload.
        first.status = ExecutionStatus.RUNNING
        executions.save(first)

        assertThatThrownBy { rerun(first, "ok-three") }
            .isInstanceOf(ExecutionStillRunningException::class.java)
            .hasMessageContaining(requireNotNull(first.id).toString())
    }

    /**
     * A node edited since to read something the earlier run never produced.
     * Running it would substitute nothing at all where the earlier run read a
     * channel, and say it succeeded.
     */
    @Test
    fun `starting at a step that reads what the earlier run never produced is refused`() {
        straightLine()
        val first = run()

        straightLine(mapOf("to" to NodeBinding("channel.id", reference = true)))
        assertThatThrownBy { rerun(first, "ok-one") }
            .isInstanceOf(StepInputMissingException::class.java)
            .hasMessageContaining("channel")

        // A reference the earlier run did produce is no obstacle: the payload
        // checked against is the one that node was handed the first time.
        straightLine(mapOf("to" to NodeBinding("ticket", reference = true)))
        assertThat(rerun(first, "ok-one").status).isEqualTo(ExecutionStatus.COMPLETED)
    }

    /** Three nodes in a row, the first of them reading [mappings]. */
    private fun straightLine(mappings: Map<String, NodeBinding> = emptyMap()) = graph(
        nodes = listOf(node("ok-one", mappings = mappings), node("ok-two"), node("ok-three")),
        edges = listOf(GraphEdge("ok-one", "ok-two"), GraphEdge("ok-two", "ok-three")),
    )

    /** A condition with a node on each side of it; [key] decides which way it goes. */
    private fun branching(key: String) = graph(
        nodes = listOf(node("ok-start"), node(key, NodeKind.CONDITION), node("ok-taken"), node("ok-refused")),
        edges = listOf(
            GraphEdge("ok-start", key),
            GraphEdge(key, "ok-taken", EdgeBranch.YES),
            GraphEdge(key, "ok-refused", EdgeBranch.NO),
        ),
    )

    private fun graph(nodes: List<GraphNode>, edges: List<GraphEdge>) {
        (graphs as FakeWorkflowGraphSource).graphs[WORKFLOW] =
            WorkflowGraph(workflowId = WORKFLOW, name = "Answer the customer", nodes = nodes, edges = edges)
    }

    private fun node(key: String, kind: NodeKind = NodeKind.ACTION, mappings: Map<String, NodeBinding> = emptyMap()) =
        GraphNode(key = key, kind = kind, name = key, mappings = mappings)

    private fun run() = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)

    /** What the mutation does: manual, on the same event, picking up at [nodeKey]. */
    private fun rerun(earlier: WorkflowExecution, nodeKey: String) = engine.start(
        workspaceId = WORKSPACE,
        workflowId = WORKFLOW,
        trigger = ExecutionTrigger.MANUAL,
        input = earlier.input,
        resumeFrom = ResumePoint(requireNotNull(earlier.id), nodeKey),
    )

    private fun stepsOf(execution: WorkflowExecution) =
        steps.findByExecutionIdOrderByOrderAsc(requireNotNull(execution.id))

    private fun statusesOf(execution: WorkflowExecution) = stepsOf(execution).map { it.status }

    private companion object {
        const val WORKSPACE = 7L
        const val WORKFLOW = 1L

        /** An object, so a re-run has fields to check a node's references against. */
        const val INPUT = """{"ticket":"T-1"}"""
    }
}
