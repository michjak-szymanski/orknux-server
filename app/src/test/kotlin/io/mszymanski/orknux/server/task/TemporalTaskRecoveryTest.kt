package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.workflow.temporal.TemporalProperties
import io.temporal.testing.TestWorkflowEnvironment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

private const val QUEUE = "task-recovery-test"

/**
 * The net under a Temporal installation, and what stops it running a task twice.
 *
 * Temporal is the installation issue #297 is really about. The inline engine
 * sweeps on the way up, so a stranded task there is recovered by a restart;
 * nothing in a Temporal deployment does anything of the kind, because there is
 * nothing in this process to revive - `begin` is called once, `nudge` does
 * nothing, and a task whose workflow started and could not run leaves a row at
 * QUEUED that nothing will ever look at again. That is not a hypothetical: it
 * is the `Activity Type "AdvanceTask" is not registered` incident written up in
 * [TemporalTaskEngine], and a restart did not help.
 *
 * So [TemporalTaskEngine.recover] starts the workflow again - and the question
 * that decides whether it may is asked here. **A task being carried must not be
 * handed over a second time**, and what answers is the workflow id: it is the
 * task's, so the Temporal server refuses a second start of a run that is still
 * going. That is a promise made by the service rather than by anything this
 * process remembers, which is why it holds across the restart that put the
 * sweep and the workflow in different processes in the first place.
 *
 * A real Temporal, in process, because that promise is the Temporal server's
 * and a stub of it would be this test asserting its own assumption.
 */
class TemporalTaskRecoveryTest {

    private val environment: TestWorkflowEnvironment = TestWorkflowEnvironment.newInstance()

    @AfterEach
    fun close() = environment.close()

    private fun engine(activities: TaskActivities) = TemporalTaskEngine(
        client = environment.workflowClient,
        temporal = TemporalProperties(taskQueue = QUEUE),
        properties = TaskProperties(),
        activities = activities,
    )

    /**
     * A task nothing is carrying is taken back, and a turn is actually taken.
     *
     * The recovery half. Nothing has ever started this task's workflow - which
     * is what a hand-over lost to a restart leaves behind - so the sweep's call
     * is what puts it to work.
     */
    @Test
    fun `a task no workflow is carrying is handed over and worked`() {
        val worker = environment.newWorker(QUEUE)
        val turns = OneTurnThenDone()
        val engine = engine(turns)
        engine.register(worker)
        environment.start()

        assertThat(engine.recover(TASK)).describedAs("nothing was carrying it").isTrue()

        environment.workflowClient
            .newUntypedWorkflowStub(TemporalTaskEngine.temporalWorkflowId(TASK))
            .getResult(Void::class.java)
        assertThat(turns.taken).isEqualTo(1)
    }

    /**
     * And a task whose workflow is still going is left alone.
     *
     * No worker is started here, which is the point rather than a shortcut: the
     * workflow exists and is running, and there is nothing to run it - so the
     * row stays at QUEUED for as long as this test likes, which is exactly the
     * state the sweep meets and cannot tell from a stranded one by looking at
     * the row. The refusal comes from the Temporal server, on the id.
     */
    @Test
    fun `a task whose workflow is already running is not started again`() {
        val engine = engine(OneTurnThenDone())

        engine.begin(TASK)

        assertThat(engine.recover(TASK)).describedAs("somebody already has it").isFalse()
        assertThat(engine.recover(TASK)).describedAs("and asking twice more changes nothing").isFalse()
    }

    /**
     * Ends the loop after one turn; the point is that it is reached at all.
     *
     * One per test rather than shared, so a count read in one is a count that
     * test made.
     */
    private class OneTurnThenDone : TaskActivities {
        var taken = 0
        override fun advanceTask(plan: TaskPlan): TaskTurnReport {
            taken += 1
            return TaskTurnReport(going = false)
        }
    }

    private companion object {
        /** One task, so both tests are about the same workflow id. */
        const val TASK = 7L
    }
}
