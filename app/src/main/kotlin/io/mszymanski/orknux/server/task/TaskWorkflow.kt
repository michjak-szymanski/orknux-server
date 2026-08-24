package io.mszymanski.orknux.server.task

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * A task, carried durably.
 *
 * It is a loop rather than a walk, which is the whole difference between this
 * and the workflow that runs a graph beside it: there is no plan, no list of
 * steps and no edges, because what happens next is the agent's decision at every
 * turn. What Temporal is here for is exactly what it is there for - the turn is
 * an activity, so a worker that dies takes at most the turn it was on, and the
 * waiting is a timer, so a task parked for a week holds nothing at all.
 *
 * The Temporal SDK converts payloads with Jackson 2 and no Kotlin module, so
 * every creator here is bound explicitly, as everywhere else that crosses this
 * boundary.
 */
@WorkflowInterface
interface TaskWorkflow {

    @WorkflowMethod
    fun run(plan: TaskPlan)
}

/**
 * What the workflow is handed: an id.
 *
 * Deliberately nothing else. An activity's arguments and results are written
 * into Temporal's event history and kept for the life of the run, and a task's
 * state is a growing transcript - carrying any of it here would record the whole
 * conversation again at every turn.
 */
data class TaskPlan @JsonCreator constructor(@JsonProperty("taskId") val taskId: Long)

/** What one turn did, as the workflow needs to know it. */
data class TaskTurnReport @JsonCreator constructor(
    /** True while there is another turn to take. */
    @JsonProperty("going") val going: Boolean,
    /** Set when the task parked: how long before it is asked again. */
    @JsonProperty("askAgainAfterSeconds") val askAgainAfterSeconds: Long? = null,
)

@ActivityInterface
interface TaskActivities {

    /** Takes the task one turn further, or says why it did not. */
    @ActivityMethod
    fun advanceTask(plan: TaskPlan): TaskTurnReport
}

/**
 * The activity, which is one turn and nothing else.
 *
 * A turn is one round of the agent's conversation, so it is bounded by the same
 * start-to-close timeout a workflow step is - which is the right bound: a model
 * that has not answered in five minutes is not going to.
 */
@Component
@ConditionalOnProperty(name = ["orknux.temporal.enabled"], havingValue = "true", matchIfMissing = true)
class TaskActivitiesImpl(private val loop: TaskLoop) : TaskActivities {

    override fun advanceTask(plan: TaskPlan): TaskTurnReport = when (val turn = loop.advance(plan.taskId)) {
        is TaskTurn.Working -> TaskTurnReport(going = true)
        is TaskTurn.Parked -> TaskTurnReport(going = true, askAgainAfterSeconds = turn.after.toSeconds())
        is TaskTurn.Over -> TaskTurnReport(going = false)
    }
}

/**
 * Ask, and sleep for as long as the task says it is not ready.
 *
 * The same shape a parked workflow step has, for the same reasons: the activity
 * answers straight away either way, so no worker is held while a task waits for
 * somebody to approve something, the timer outlives every process involved, and
 * what it costs is one history event per look.
 *
 * There is no signal. An approval could wake this directly, and it deliberately
 * does not: a signal is a second way for a task to move, it can be delivered to a
 * workflow that is not there, and the poll below is what the inline engine's
 * restart already has to be correct without. One mechanism, and it is the row in
 * the database.
 */
class TaskWorkflowImpl : TaskWorkflow {

    private val activities = Workflow.newActivityStub(TaskActivities::class.java)

    override fun run(plan: TaskPlan) {
        while (true) {
            val turn = activities.advanceTask(plan)
            // Nothing is returned. Which of the four endings it reached is on
            // the task's own row, and a second answer here could only ever be
            // the same fact written twice.
            if (!turn.going) return
            turn.askAgainAfterSeconds?.let { Workflow.sleep(Duration.ofSeconds(it)) }
        }
    }
}
