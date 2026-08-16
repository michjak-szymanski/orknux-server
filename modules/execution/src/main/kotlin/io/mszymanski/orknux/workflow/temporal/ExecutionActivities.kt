package io.mszymanski.orknux.workflow.temporal

import io.mszymanski.orknux.workflow.execution.StepRunner
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import org.springframework.stereotype.Component

/**
 * The parts of a run that touch the world: they read and write the database and
 * call whatever a node does. Nothing here may be called from the workflow's own
 * code path other than through the stub, which is what lets Temporal replay a
 * run without doing any of it twice.
 */
@ActivityInterface
interface ExecutionActivities {

    /**
     * Carries out one step, or as much of it as it is ready to do: a step that
     * is waiting for something reports that and says when to ask again, rather
     * than holding this activity until it is ready. Throwing is how a step
     * reports it could not.
     */
    @ActivityMethod
    fun runStep(command: RunStepCommand): StepReport

    @ActivityMethod
    fun failRun(command: FailRunCommand)

    @ActivityMethod
    fun finishRun(command: FinishRunCommand)
}

/**
 * Delegates to [StepRunner], which is also what the inline engine uses: a step
 * has to do the same thing and record the same thing whichever engine is
 * driving it, or the two would drift.
 */
@Component
class ExecutionActivitiesImpl(private val steps: StepRunner) : ExecutionActivities {

    override fun runStep(command: RunStepCommand): StepReport {
        val outcome = steps.runStep(command.executionId, command.nodeKey, command.input)
        return StepReport(
            outcome.status,
            outcome.output,
            outcome.halt,
            // A timer is the granularity Temporal deals in, so anything under a
            // second becomes one: sleeping for none of it would only spin.
            outcome.resumeAfter?.toSeconds()?.coerceAtLeast(1),
        )
    }

    override fun failRun(command: FailRunCommand) {
        steps.failRun(command.executionId, command.nodeKey, command.reason, command.unreached)
    }

    override fun finishRun(command: FinishRunCommand) {
        steps.finishRun(command.executionId, command.stoppedAt, command.reason)
    }
}
