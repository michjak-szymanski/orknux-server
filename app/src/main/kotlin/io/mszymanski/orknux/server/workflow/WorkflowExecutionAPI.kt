package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceNotFoundException
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.monitoring.TemporalLinks
import io.mszymanski.orknux.workflow.execution.ExecutionDetailView
import io.mszymanski.orknux.workflow.execution.ExecutionLogLineView
import io.mszymanski.orknux.workflow.execution.ExecutionPage
import io.mszymanski.orknux.workflow.execution.ExecutionService
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionStepView
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.GraphVersion
import io.mszymanski.orknux.workflow.execution.StartExecutionInput
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

/**
 * Runs of a workspace's workflows. The execution module carries them out and keeps
 * what each one did; this decides who may look, and joins a run to the workflow
 * definition it came from, which is this module's own.
 */
@Controller
class WorkflowExecutionAPI(
    private val runs: ExecutionService,
    private val edges: WorkflowEdgeRepository,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
    private val temporal: TemporalLinks,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun workspaceExecutions(
        @Argument workspaceId: Long,
        @Argument page: Int?,
        @Argument size: Int?,
        @Argument status: ExecutionStatus?,
        @Argument workflowId: Long?,
        @Argument days: Int?,
        @Argument search: String?,
    ): ExecutionPage {
        requireWorkspaceAccess(workspaceId)
        return runs.executions(
            workspaceId = workspaceId,
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
        requireWorkspaceAccess(run.workspaceId)
        return RunDetailView(run, edgesOf(run.workflowId), temporal.forExecution(run.id))
    }

    /**
     * Runs a workflow the workspace has assigned, from the workflows screen.
     *
     * [input] is what the first node is handed, as JSON. A trigger supplies it
     * from whatever fired; a person running a workflow by hand can type it, and
     * leaving it out hands the run nothing.
     */
    @MutationMapping
    fun startExecution(
        @Argument workspaceId: Long,
        @Argument workflowId: Long,
        @Argument input: String?,
    ): RunDetailView {
        requireWorkspaceAccess(workspaceId)
        val started = runs.startExecution(
            StartExecutionInput(
                workspaceId = workspaceId,
                workflowId = workflowId,
                trigger = ExecutionTrigger.MANUAL,
                payload = input?.takeIf { it.isNotBlank() },
            ),
        )
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            "Workflow ${started.workflowName} run started",
        )
        return RunDetailView(started, edgesOf(started.workflowId), temporal.forExecution(started.id))
    }

    /**
     * Runs the workflow again on the same event, with the graph as it stands now.
     *
     * The payload is carried over deliberately. Without it a re-run started from
     * nothing: every reference to the event read blank, so a workflow that answers
     * whoever asked had nobody to answer, and the run looked broken in a way the
     * original was not. Re-running is for trying a changed graph against the
     * thing that happened, which needs the thing that happened.
     *
     * Recorded as [ExecutionTrigger.MANUAL] all the same: a person pressed this,
     * and the run list should not claim Slack sent the message twice.
     */
    @MutationMapping
    fun rerunExecution(@Argument id: Long): RunDetailView {
        val previous = runs.execution(id) ?: throw ExecutionNotFoundException(id)
        requireWorkspaceAccess(previous.workspaceId)

        val started = runs.startExecution(
            StartExecutionInput(
                workspaceId = previous.workspaceId,
                workflowId = previous.workflowId,
                trigger = ExecutionTrigger.MANUAL,
                payload = previous.input,
                /*
                 * The graph the original ran, not whatever is being edited now.
                 *
                 * The rerun is recorded as manual because a person pressed it,
                 * and manual means the draft - so without this, re-running what
                 * a webhook did would run a graph that webhook never touched.
                 */
                version = if (previous.trigger == ExecutionTrigger.MANUAL) {
                    GraphVersion.DRAFT
                } else {
                    GraphVersion.PUBLISHED
                },
            ),
        )
        return RunDetailView(started, edgesOf(started.workflowId), temporal.forExecution(started.id))
    }

    /**
     * A run keeps its own copy of the nodes it ran, but not of the edges between
     * them, so the shape of the graph is read from the definition here.
     */
    private fun edgesOf(workflowId: Long): List<WorkflowEdgeView> =
        edges.findByWorkflowId(workflowId).map(::WorkflowEdgeView)

    private fun requireWorkspaceAccess(workspaceId: Long) {
        val workspace = workspaces.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)
    }
}

/** A run as the detail screen wants it: what the module recorded, plus the edges. */
data class RunDetailView(
    val id: Long,
    val workspaceId: Long,
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
    /**
     * Where Temporal's own screen for this run is, when there is one.
     *
     * What is here is what each node did; what is there is every attempt behind
     * it. Null when Temporal is off, or running without an interface to send
     * anybody to.
     */
    val temporalUrl: String? = null,
) {
    constructor(
        run: ExecutionDetailView,
        edges: List<WorkflowEdgeView>,
        temporalUrl: String?,
    ) : this(
        id = run.id,
        workspaceId = run.workspaceId,
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
        temporalUrl = temporalUrl,
    )
}

class ExecutionNotFoundException(id: Long) : RuntimeException("No execution with id $id")
