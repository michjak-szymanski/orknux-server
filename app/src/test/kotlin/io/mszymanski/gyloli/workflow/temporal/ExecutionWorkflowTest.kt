package io.mszymanski.gyloli.workflow.temporal

import io.mszymanski.gyloli.server.GyloliServer
import io.mszymanski.gyloli.workflow.execution.ExecutionPlanner
import io.mszymanski.gyloli.workflow.execution.ExecutionStatus
import io.mszymanski.gyloli.workflow.execution.ExecutionStepRepository
import io.mszymanski.gyloli.workflow.execution.ExecutionTrigger
import io.mszymanski.gyloli.workflow.execution.ExecutionLogRepository
import io.mszymanski.gyloli.workflow.execution.FakeWorkflowGraphSource
import io.mszymanski.gyloli.workflow.execution.ExecutionTestConfig
import io.mszymanski.gyloli.workflow.execution.GraphEdge
import io.mszymanski.gyloli.workflow.execution.GraphNode
import io.mszymanski.gyloli.workflow.execution.LogLevel
import io.mszymanski.gyloli.workflow.execution.NodeKind
import io.mszymanski.gyloli.workflow.execution.StepStatus
import io.mszymanski.gyloli.workflow.execution.WorkflowExecutionRepository
import io.mszymanski.gyloli.workflow.execution.WorkflowGraph
import io.mszymanski.gyloli.workflow.execution.WorkflowGraphSource
import io.temporal.activity.ActivityOptions
import io.temporal.client.WorkflowOptions
import io.temporal.common.RetryOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.worker.WorkflowImplementationOptions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.time.Duration

/**
 * The interpreter, against Temporal's in-process test service: a real workflow
 * execution, real activities and the real database, without a Temporal service
 * to bring up.
 *
 * Temporal is disabled in the context, so nothing tries to reach one at
 * startup; the client under test is the environment's own.
 */
@SpringBootTest(classes = [GyloliServer::class])
@Import(ExecutionTestConfig::class)
@TestPropertySource(properties = ["gyloli.temporal.enabled=false"])
class ExecutionWorkflowTest(
    @Autowired val planner: ExecutionPlanner,
    @Autowired val activities: ExecutionActivities,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val graphs: WorkflowGraphSource,
) {

    private lateinit var environment: TestWorkflowEnvironment

    @BeforeEach
    fun start() {
        executions.deleteAll()
        (graphs as FakeWorkflowGraphSource).graphs.clear()

        environment = TestWorkflowEnvironment.newInstance()
        val worker = environment.newWorker(QUEUE)
        worker.registerWorkflowImplementationTypes(
            WorkflowImplementationOptions.newBuilder()
                .setDefaultActivityOptions(
                    ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(30))
                        // Two, so a step that keeps failing proves it is tried
                        // again rather than only that it failed.
                        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                        .build(),
                )
                .build(),
            ExecutionWorkflowImpl::class.java,
        )
        worker.registerActivitiesImplementations(activities)
        environment.start()
    }

    @AfterEach
    fun stop() {
        environment.close()
    }

    @Test
    fun `walks the graph, hands each step's output to the next, and finishes the run`() {
        val plan = plan(
            WorkflowGraph(
                workflowId = WORKFLOW,
                name = "Data Processing Pipeline",
                nodes = listOf(node("ok"), node("ok-2"), node("nothing")),
                edges = listOf(GraphEdge("ok", "ok-2"), GraphEdge("ok-2", "nothing")),
            ),
        )

        val status = workflow().run(plan)

        assertThat(status).isEqualTo(ExecutionStatus.COMPLETED)
        val executionId = plan.executionId
        assertThat(executions.findById(executionId).orElseThrow().status).isEqualTo(ExecutionStatus.COMPLETED)

        val recorded = steps.findByExecutionIdOrderByOrderAsc(executionId)
        assertThat(recorded.map { it.nodeKey }).containsExactly("ok", "ok-2", "nothing")
        assertThat(recorded.map { it.status })
            .containsExactly(StepStatus.COMPLETED, StepStatus.COMPLETED, StepStatus.SKIPPED)
        assertThat(recorded[0].input).isEqualTo("start here")
        assertThat(recorded[1].input).isEqualTo("ok did the work")
        // The step with no runtime passed on what it was handed.
        assertThat(recorded[2].input).isEqualTo("ok-2 did the work")
    }

    @Test
    fun `tries a failing step again, then stops the run where it failed`() {
        val plan = plan(
            WorkflowGraph(
                workflowId = WORKFLOW,
                name = "Nightly Report",
                nodes = listOf(node("ok"), node("boom"), node("after")),
                edges = listOf(GraphEdge("ok", "boom"), GraphEdge("boom", "after")),
            ),
        )

        val status = workflow().run(plan)

        assertThat(status).isEqualTo(ExecutionStatus.FAILED)
        val execution = executions.findById(plan.executionId).orElseThrow()
        assertThat(execution.status).isEqualTo(ExecutionStatus.FAILED)
        assertThat(execution.error).isEqualTo("boom has no answer")

        assertThat(steps.findByExecutionIdOrderByOrderAsc(plan.executionId).map { it.status })
            .containsExactly(StepStatus.COMPLETED, StepStatus.FAILED, StepStatus.PENDING)

        // Two attempts at the step, so two failures in the log, and the run's
        // own line after them.
        val failures = logs.findByExecutionIdOrderBySequenceAsc(plan.executionId)
            .filter { it.level == LogLevel.ERROR }
        assertThat(failures.filter { it.nodeKey == "boom" }).hasSize(2)
        assertThat(failures.last().message).contains("stopped at boom with 1 steps unreached")
    }

    private fun workflow() = environment.workflowClient.newWorkflowStub(
        ExecutionWorkflow::class.java,
        WorkflowOptions.newBuilder().setTaskQueue(QUEUE).build(),
    )

    /** Records the run the way [TemporalExecutionEngine] would, then hands it over. */
    private fun plan(graph: WorkflowGraph): RunPlan {
        (graphs as FakeWorkflowGraphSource).graphs[WORKFLOW] = graph
        val planned = planner.plan(TEAM, WORKFLOW, ExecutionTrigger.API, "start here")
        return RunPlan(
            executionId = requireNotNull(planned.execution.id),
            workflowName = planned.execution.workflowName,
            steps = planned.steps.map { it.nodeKey },
            input = "start here",
        )
    }

    private fun node(key: String) = GraphNode(key = key, kind = NodeKind.DATA_TASK, name = key)

    private companion object {
        const val QUEUE = "execution-workflow-test"
        const val TEAM = 7L
        const val WORKFLOW = 1L
    }
}
