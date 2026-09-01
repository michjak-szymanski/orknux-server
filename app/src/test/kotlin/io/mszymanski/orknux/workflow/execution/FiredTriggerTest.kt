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
 * Which half of a two-trigger workflow a run is.
 *
 * A trigger node has nothing pointing at it, so every one of them used to be a
 * beginning and every one of them ran: one Slack message arrived and both
 * branches went, both agents were charged, and the message that went out
 * belonged to the trigger that had not fired.
 *
 * The run knows which trigger fired now, and the rest follows from the gate
 * refusing the others. What these are really about is that it follows all the
 * way down — refusing the trigger has to close the branch behind it — and that
 * a run with nothing to go on still behaves exactly as it always did.
 *
 * The inline engine, so a run has finished by the time `start` returns.
 */
@SpringBootTest(classes = [OrknuxServer::class])
@Import(ExecutionTestConfig::class)
@TestPropertySource(properties = ["orknux.temporal.enabled=false"])
class FiredTriggerTest(
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
    fun `only the branch of the trigger that fired runs`() {
        twoTriggers()

        val run = engine.start(
            workspaceId = WORKSPACE,
            workflowId = WORKFLOW,
            trigger = ExecutionTrigger.WEBHOOK,
            input = INPUT,
            firedTriggerId = SLACK,
        )

        assertThat(run.status).isEqualTo(ExecutionStatus.COMPLETED)

        val recorded = stepsBy(run)
        assertThat(recorded.getValue("trigger-slack").status).isEqualTo(StepStatus.SKIPPED)
        assertThat(recorded.getValue("ok-answer-slack").status).isEqualTo(StepStatus.COMPLETED)

        // The whole point: refusing the other trigger has to close what is
        // behind it, or the wrong branch still sends its message.
        assertThat(recorded.getValue("trigger-mail").status).isEqualTo(StepStatus.SKIPPED)
        assertThat(recorded.getValue("ok-answer-mail").status).isEqualTo(StepStatus.SKIPPED)
    }

    @Test
    fun `a refused trigger says it was not the one that fired`() {
        twoTriggers()

        val run = engine.start(
            workspaceId = WORKSPACE,
            workflowId = WORKFLOW,
            trigger = ExecutionTrigger.WEBHOOK,
            input = INPUT,
            firedTriggerId = SLACK,
        )

        // Not "the condition before it went the other way", which is what every
        // skipped step used to say and is a lie about a trigger.
        assertThat(stepsBy(run).getValue("trigger-mail").output).contains("not the trigger that fired")
    }

    @Test
    fun `a run nothing triggered still begins at every trigger`() {
        twoTriggers()

        // Somebody pressed Run. There is no half to prefer, and picking one
        // would be the editor deciding something nobody asked it to.
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.MANUAL, INPUT)

        val recorded = stepsBy(run)
        assertThat(recorded.getValue("ok-answer-slack").status).isEqualTo(StepStatus.COMPLETED)
        assertThat(recorded.getValue("ok-answer-mail").status).isEqualTo(StepStatus.COMPLETED)
    }

    @Test
    fun `repeating a run runs the half that the run it repeats ran`() {
        twoTriggers()

        val first = engine.start(
            workspaceId = WORKSPACE,
            workflowId = WORKFLOW,
            trigger = ExecutionTrigger.WEBHOOK,
            input = INPUT,
            firedTriggerId = SLACK,
        )

        /*
         * A re-run is recorded as manual — a person pressed it — so it arrives
         * with nothing fired, and has to find out which trigger the run it
         * repeats belonged to. Otherwise the repeat sends a message the run it
         * is repeating did not send, which is worse than not repeating at all.
         */
        val again = engine.start(
            workspaceId = WORKSPACE,
            workflowId = WORKFLOW,
            trigger = ExecutionTrigger.MANUAL,
            input = INPUT,
            startedFrom = requireNotNull(first.id),
        )

        assertThat(again.firedTriggerId).isEqualTo(SLACK)

        val recorded = stepsBy(again)
        assertThat(recorded.getValue("ok-answer-slack").status).isEqualTo(StepStatus.COMPLETED)
        assertThat(recorded.getValue("ok-answer-mail").status).isEqualTo(StepStatus.SKIPPED)
    }

    @Test
    fun `a graph whose triggers are not identified runs as it always did`() {
        // What a snapshot published before nodes carried a trigger id reads
        // back as. It cannot say which node fired, so it silences none of them.
        graph(
            nodes = listOf(
                trigger("trigger-slack", null),
                trigger("trigger-mail", null),
                node("ok-answer-slack"),
                node("ok-answer-mail"),
            ),
            edges = listOf(
                GraphEdge("trigger-slack", "ok-answer-slack"),
                GraphEdge("trigger-mail", "ok-answer-mail"),
            ),
        )

        val run = engine.start(
            workspaceId = WORKSPACE,
            workflowId = WORKFLOW,
            trigger = ExecutionTrigger.WEBHOOK,
            input = INPUT,
            firedTriggerId = SLACK,
        )

        val recorded = stepsBy(run)
        assertThat(recorded.getValue("ok-answer-slack").status).isEqualTo(StepStatus.COMPLETED)
        assertThat(recorded.getValue("ok-answer-mail").status).isEqualTo(StepStatus.COMPLETED)
    }

    @Test
    fun `a workflow with one trigger is unaffected by which trigger fired`() {
        graph(
            nodes = listOf(trigger("trigger-slack", SLACK), node("ok-answer-slack")),
            edges = listOf(GraphEdge("trigger-slack", "ok-answer-slack")),
        )

        val run = engine.start(
            workspaceId = WORKSPACE,
            workflowId = WORKFLOW,
            trigger = ExecutionTrigger.WEBHOOK,
            input = INPUT,
            firedTriggerId = SLACK,
        )

        assertThat(stepsBy(run).getValue("ok-answer-slack").status).isEqualTo(StepStatus.COMPLETED)
    }

    /** The shape from the report: two triggers, an answer hanging off each. */
    private fun twoTriggers() = graph(
        nodes = listOf(
            trigger("trigger-slack", SLACK),
            trigger("trigger-mail", MAIL),
            node("ok-answer-slack"),
            node("ok-answer-mail"),
        ),
        edges = listOf(
            GraphEdge("trigger-slack", "ok-answer-slack"),
            GraphEdge("trigger-mail", "ok-answer-mail"),
        ),
    )

    private fun stepsBy(run: WorkflowExecution) =
        steps.findByExecutionIdOrderByOrderAsc(requireNotNull(run.id)).associateBy { it.nodeKey }

    private fun graph(nodes: List<GraphNode>, edges: List<GraphEdge>) {
        (graphs as FakeWorkflowGraphSource).graphs[WORKFLOW] =
            WorkflowGraph(workflowId = WORKFLOW, name = "Answer the customer", nodes = nodes, edges = edges)
    }

    private fun node(key: String) = GraphNode(key = key, kind = NodeKind.ACTION, name = key)

    private fun trigger(key: String, triggerId: Long?) =
        GraphNode(key = key, kind = NodeKind.TRIGGER, name = key, triggerId = triggerId)

    private companion object {
        const val WORKSPACE = 7L
        const val WORKFLOW = 1L
        const val INPUT = """{"ticket":"T-1"}"""

        /** The two trigger definitions the nodes stand for. */
        const val SLACK = 11L
        const val MAIL = 22L
    }
}
