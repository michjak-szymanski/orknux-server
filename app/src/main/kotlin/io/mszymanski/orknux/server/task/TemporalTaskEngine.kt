package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.workflow.temporal.TemporalProperties
import io.mszymanski.orknux.workflow.temporal.TemporalRegistrar
import io.temporal.activity.ActivityOptions
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.common.RetryOptions
import io.temporal.worker.Worker
import io.temporal.worker.WorkflowImplementationOptions
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Tasks carried by Temporal, which is where a task that matters belongs.
 *
 * A task is the thing in this application with the strongest case for durable
 * execution: it runs for an hour, it may then wait a week for somebody to
 * approve something, and the whole of it must survive a deployment. All three
 * are what Temporal already does for a workflow run here.
 *
 * The workflow is started and not awaited, for the same reason a run is: a task
 * outlives the request that asked for it, and the person who asked is looking at
 * a page that polls.
 */
@Service
@ConditionalOnProperty(name = ["orknux.temporal.enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TaskProperties::class)
class TemporalTaskEngine(
    private val client: WorkflowClient,
    private val temporal: TemporalProperties,
    private val properties: TaskProperties,
    private val activities: TaskActivities,
) : TaskEngine, TemporalRegistrar {

    /**
     * Registers the task workflow on the worker the execution module already
     * runs. See [TemporalRegistrar] for why it is not a worker of its own.
     */
    override fun register(worker: Worker) {
        worker.registerWorkflowImplementationTypes(
            WorkflowImplementationOptions.newBuilder()
                .setDefaultActivityOptions(
                    ActivityOptions.newBuilder()
                        // A turn is one model call and the tools it asks for, so
                        // it is bounded by the same timeout a workflow step is.
                        .setStartToCloseTimeout(Duration.ofSeconds(temporal.stepTimeoutSeconds))
                        /*
                         * One attempt. A turn that failed has already written
                         * what it did into the session, and the loop is written
                         * to be re-entered - so retrying here would not repair a
                         * turn, it would take another one, and the count of
                         * turns the task is bounded by would stop meaning
                         * anything. What decides whether there is another go is
                         * the task's own budget.
                         */
                        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                        .build(),
                )
                .build(),
            TaskWorkflowImpl::class.java,
        )
        /*
         * And the activity the workflow calls, which is the half that was
         * missing.
         *
         * Registering the workflow alone is enough for Temporal to accept the
         * start and enough for the worker to begin running it - so a task went
         * to Temporal, came back, and failed on its first turn with "Activity
         * Type \"AdvanceTask\" is not registered with a worker". The row was
         * left at QUEUED, because nothing had got as far as saying otherwise:
         * a task that never worked, on an installation where nothing looked
         * wrong. `registerActivitiesImplementations` is what the execution
         * module does two lines after its own workflow, and this is the same
         * pairing.
         */
        worker.registerActivitiesImplementations(activities)
    }

    override fun begin(taskId: Long) {
        val workflow = client.newWorkflowStub(
            TaskWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue(temporal.taskQueue)
                // One workflow per task, which also means asking twice cannot
                // start it twice.
                .setWorkflowId(temporalWorkflowId(taskId))
                /*
                 * Long enough for the work and for somebody to answer it. Both
                 * halves, because a task that parks has to still be there when
                 * the person gets back from holiday - and a margin, so the
                 * task's own patience is what gives up rather than Temporal
                 * cutting it off a minute earlier and leaving the row saying
                 * WAITING for ever.
                 */
                .setWorkflowExecutionTimeout(properties.workingTime.plus(properties.patience).plusHours(1))
                .build(),
        )
        WorkflowClient.start(workflow::run, TaskPlan(taskId))
        log.debug("Task {} handed to Temporal", taskId)
    }

    /**
     * Nothing to do.
     *
     * The workflow is asleep on a timer and will look at the row when it wakes.
     * Reaching it with a signal would be a second way for a task to move and a
     * delivery that can fail; the poll is the one mechanism, and it is what the
     * inline engine has to be correct without anyway.
     */
    override fun nudge(taskId: Long) = Unit

    companion object {

        /** The workflow's name in Temporal, which is what a link out to it needs. */
        fun temporalWorkflowId(taskId: Long): String = "orknux-task-$taskId"

        private val log = LoggerFactory.getLogger(TemporalTaskEngine::class.java)
    }
}
