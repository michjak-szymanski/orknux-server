package io.mszymanski.gyloli.server.workflow

import io.mszymanski.gyloli.server.security.TeamAccess
import io.mszymanski.gyloli.server.team.TeamAuditCategory
import io.mszymanski.gyloli.server.team.TeamAuditRecorder
import io.mszymanski.gyloli.server.team.TeamNotFoundException
import io.mszymanski.gyloli.server.team.TeamRepository
import io.mszymanski.gyloli.workflow.execution.ExecutionDetailView
import io.mszymanski.gyloli.workflow.execution.ExecutionLogLineView
import io.mszymanski.gyloli.workflow.execution.ExecutionPage
import io.mszymanski.gyloli.workflow.execution.ExecutionService
import io.mszymanski.gyloli.workflow.execution.ExecutionStatus
import io.mszymanski.gyloli.workflow.execution.ExecutionStepView
import io.mszymanski.gyloli.workflow.execution.ExecutionTrigger
import io.mszymanski.gyloli.workflow.execution.StartExecutionInput
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

/**
 * Runs of a team's workflows. The execution module carries them out and keeps
 * what each one did; this decides who may look, and joins a run to the workflow
 * definition it came from, which is this module's own.
 */
@Controller
class WorkflowExecutionAPI(
    private val runs: ExecutionService,
    private val edges: WorkflowEdgeRepository,
    private val teams: TeamRepository,
    private val access: TeamAccess,
    private val auditRecorder: TeamAuditRecorder,
) {

    @QueryMapping
    fun teamExecutions(
        @Argument teamId: Long,
        @Argument page: Int?,
        @Argument size: Int?,
        @Argument status: ExecutionStatus?,
        @Argument workflowId: Long?,
        @Argument days: Int?,
        @Argument search: String?,
    ): ExecutionPage {
        requireTeamAccess(teamId)
        return runs.executions(
            teamId = teamId,
            workflowId = workflowId,
            status = status,
            days = days,
            search = search?.trim()?.ifEmpty { null },
            page = page,
            size = size,
        )
    }

    /** What one run did: the graph as it ran, per-node outcome, and its log. */
    @QueryMapping
    fun execution(@Argument id: Long): RunDetailView? {
        val run = runs.execution(id) ?: return null
        requireTeamAccess(run.teamId)
        return RunDetailView(run, edgesOf(run.workflowId))
    }

    /**
     * Runs a workflow the team has assigned, from the workflows screen.
     *
     * [input] is what the first node is handed, as JSON. A trigger supplies it
     * from whatever fired; a person running a workflow by hand can type it, and
     * leaving it out hands the run nothing.
     */
    @MutationMapping
    fun startExecution(
        @Argument teamId: Long,
        @Argument workflowId: Long,
        @Argument input: String?,
    ): RunDetailView {
        requireTeamAccess(teamId)
        val started = runs.startExecution(
            StartExecutionInput(
                teamId = teamId,
                workflowId = workflowId,
                trigger = ExecutionTrigger.MANUAL,
                payload = input?.takeIf { it.isNotBlank() },
            ),
        )
        auditRecorder.record(
            teamId,
            TeamAuditCategory.WORKFLOW,
            "Workflow ${started.workflowName} run started",
        )
        return RunDetailView(started, edgesOf(started.workflowId))
    }

    /** Runs the workflow again, with the graph as it stands now. */
    @MutationMapping
    fun rerunExecution(@Argument id: Long): RunDetailView {
        val previous = runs.execution(id) ?: throw ExecutionNotFoundException(id)
        requireTeamAccess(previous.teamId)

        val started = runs.startExecution(
            StartExecutionInput(
                teamId = previous.teamId,
                workflowId = previous.workflowId,
                trigger = ExecutionTrigger.MANUAL,
            ),
        )
        return RunDetailView(started, edgesOf(started.workflowId))
    }

    /**
     * A run keeps its own copy of the nodes it ran, but not of the edges between
     * them, so the shape of the graph is read from the definition here.
     */
    private fun edgesOf(workflowId: Long): List<WorkflowEdgeView> =
        edges.findByWorkflowId(workflowId).map(::WorkflowEdgeView)

    private fun requireTeamAccess(teamId: Long) {
        val team = teams.findByIdOrNull(teamId) ?: throw TeamNotFoundException(teamId)
        access.requireVisible(team)
    }
}

/** A run as the detail screen wants it: what the module recorded, plus the edges. */
data class RunDetailView(
    val id: Long,
    val teamId: Long,
    val workflowId: Long,
    val workflowName: String,
    val status: ExecutionStatus,
    val trigger: ExecutionTrigger,
    val startedAt: String,
    val finishedAt: String?,
    val durationSeconds: Int?,
    /** Why the run stopped, when it stopped badly. */
    val error: String?,
    /**
     * The node that ended the run early and what it said, when a condition
     * decided there was nothing further to do. Null for a run that went all the
     * way through — which is the difference this makes visible.
     */
    val stoppedAtNodeKey: String?,
    val stoppedReason: String?,
    val steps: List<ExecutionStepView>,
    val edges: List<WorkflowEdgeView>,
    val logs: List<ExecutionLogLineView>,
) {
    constructor(run: ExecutionDetailView, edges: List<WorkflowEdgeView>) : this(
        id = run.id,
        teamId = run.teamId,
        workflowId = run.workflowId,
        workflowName = run.workflowName,
        status = run.status,
        trigger = run.trigger,
        startedAt = run.startedAt,
        finishedAt = run.finishedAt,
        durationSeconds = run.durationSeconds,
        error = run.error,
        stoppedAtNodeKey = run.stoppedAtNodeKey,
        stoppedReason = run.stoppedReason,
        steps = run.steps,
        edges = edges,
        logs = run.logs,
    )
}

class ExecutionNotFoundException(id: Long) : RuntimeException("No execution with id $id")
