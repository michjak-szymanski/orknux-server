package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.workflow.temporal.TemporalProperties
import io.temporal.client.WorkflowOptions
import io.temporal.testing.TestWorkflowEnvironment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

private const val QUEUE = "task-registration-test"

/**
 * That the worker is offered both halves of what a task needs.
 *
 * Registering the workflow alone is silently almost enough. Temporal accepts
 * the start, the worker begins running the workflow, and everything looks right
 * until the first turn asks for the activity - which came back as `Activity
 * Type "AdvanceTask" is not registered with a worker`, leaving the task row at
 * QUEUED because nothing had got far enough to say otherwise. A task that never
 * ran, on an installation where nothing else looked wrong.
 *
 * `ExecutionWorkflowTest` could not have caught it: it builds its own worker and
 * registers both halves itself, which is the arrangement being asserted rather
 * than the one the application actually builds. So this drives
 * `TemporalTaskEngine.register` - the production path - and then runs a real
 * workflow through it, because "it was registered" and "a turn can be taken"
 * are the same claim only if something takes one.
 */
class TaskWorkerRegistrationTest {

    private val environment: TestWorkflowEnvironment = TestWorkflowEnvironment.newInstance()

    @AfterEach
    fun close() = environment.close()

    @Test
    fun `a turn can be taken through the worker the engine registers`() {
        val worker = environment.newWorker(QUEUE)
        val engine = TemporalTaskEngine(
            client = environment.workflowClient,
            temporal = TemporalProperties(taskQueue = QUEUE),
            properties = TaskProperties(),
            activities = OneTurnThenDone,
        )
        engine.register(worker)
        environment.start()

        val workflow = environment.workflowClient.newWorkflowStub(
            TaskWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(QUEUE).build(),
        )
        workflow.run(TaskPlan(1L))

        assertThat(OneTurnThenDone.turns).isEqualTo(1)
    }

    /** Ends the loop after one turn; the point is that it is reached at all. */
    private object OneTurnThenDone : TaskActivities {
        var turns = 0
        override fun advanceTask(plan: TaskPlan): TaskTurnReport {
            turns += 1
            return TaskTurnReport(going = false)
        }
    }
}
