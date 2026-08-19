package io.mszymanski.orknux.workflow.execution

import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Starting runs and reading what they did.
 *
 * There is no per-workspace access check here, unlike orknux-server: the caller is a
 * service that has already decided a person may do this. The service token is
 * the whole of the authorization.
 */
@Service
class ExecutionService(
    private val engine: ExecutionEngine,
    private val executions: WorkflowExecutionRepository,
    private val steps: ExecutionStepRepository,
    private val logs: ExecutionLogRepository,
) {

    fun executions(
        workspaceId: Long?,
        workflowId: Long?,
        status: ExecutionStatus?,
        days: Int?,
        search: String?,
        page: Int?,
        size: Int?,
    ): ExecutionPage {
        val pageable = pageRequest(page, size, Sort.by(Sort.Direction.DESC, "startedAt"))
        val since = days?.takeIf { it > 0 }?.let { OffsetDateTime.now().minusDays(it.toLong()) }
        val filter = executionFilter(workspaceId, workflowId, status, since, search?.trim()?.ifEmpty { null })
        return ExecutionPage(executions.findAll(filter, pageable))
    }

    /** Where a workflow last got to, or null if it has never run for this workspace. */
    fun lastExecution(workspaceId: Long, workflowId: Long): ExecutionView? =
        executions.findFirstByWorkspaceIdAndWorkflowIdOrderByStartedAtDesc(workspaceId, workflowId)?.let(::ExecutionView)

    fun execution(id: Long): ExecutionDetailView? {
        val execution = executions.findByIdOrNull(id) ?: return null
        return detailOf(execution)
    }

    /**
     * Starts the workflow and answers with the run as it stands.
     *
     * What that means depends on the engine: the inline one has finished by the
     * time this returns, while Temporal answers as soon as the run is accepted,
     * with every step still pending. Either way the id is real and `execution`
     * follows it from there, so a caller that polls works against both.
     */
    fun startExecution(input: StartExecutionInput): ExecutionDetailView =
        detailOf(
            engine.start(
                workspaceId = input.workspaceId,
                workflowId = input.workflowId,
                trigger = input.trigger,
                input = input.payload,
                version = input.version,
                resumeFrom = input.resumeFrom,
            ),
        )

    private fun detailOf(execution: WorkflowExecution): ExecutionDetailView {
        val id = requireNotNull(execution.id)
        return ExecutionDetailView(
            execution = execution,
            steps = steps.findByExecutionIdOrderByOrderAsc(id),
            logs = logs.findByExecutionIdOrderBySequenceAsc(id),
        )
    }
}

/** What to run, and what to hand the first node. */
data class StartExecutionInput(
    val workspaceId: Long,
    val workflowId: Long,
    val trigger: ExecutionTrigger = ExecutionTrigger.API,
    /** Named `payload` in Kotlin because `input` is the argument holding it. */
    val payload: String? = null,
    /**
     * Which copy of the workflow to run, where what started it does not decide.
     *
     * Re-running is the case. A rerun is recorded as manual - a person pressed
     * it, and the run list should not claim Slack sent the message twice - but
     * that would then run the draft, so re-running a webhook's run would run a
     * graph that webhook never touched. Null keeps the rule: manual means the
     * draft, everything else means what was published.
     */
    val version: GraphVersion? = null,
    /**
     * Where to pick up an earlier run rather than start at the beginning.
     *
     * A run that failed at the last node of six should not have to redo the
     * five that worked - for anything that sends or charges, redoing them is
     * not a repeat but a second occurrence. Null for an ordinary run, which is
     * every run that is not somebody asking for one step again.
     */
    val resumeFrom: ResumePoint? = null,
)

data class ExecutionView(
    val id: Long,
    val workspaceId: Long,
    val workflowId: Long,
    val workflowName: String,
    val status: ExecutionStatus,
    val trigger: ExecutionTrigger,
    val startedAt: String,
    val finishedAt: String?,
    /** Null while the run is still going. */
    val durationSeconds: Int?,
    val error: String?,
    /** Set when a condition ended the run early; null for an ordinary finish. */
    val stoppedReason: String? = null,
) {
    constructor(execution: WorkflowExecution) : this(
        id = requireNotNull(execution.id),
        workspaceId = execution.workspaceId,
        workflowId = execution.workflowId,
        workflowName = execution.workflowName,
        status = execution.status,
        trigger = execution.trigger,
        startedAt = execution.startedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        finishedAt = execution.finishedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        durationSeconds = execution.finishedAt?.let { seconds(execution.startedAt, it) },
        error = execution.error,
        stoppedReason = execution.stoppedReason,
    )
}

data class ExecutionPage(
    val content: List<ExecutionView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<WorkflowExecution>) : this(
        content = page.content.map(::ExecutionView),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}

data class ExecutionStepView(
    val key: String,
    val kind: NodeKind,
    val name: String,
    val description: String?,
    val status: StepStatus,
    val startedAt: String?,
    val finishedAt: String?,
    val durationSeconds: Int?,
    val input: String?,
    val output: String?,
    val error: String?,
    /**
     * Which catalogue entry the step ran, so a run can link back to the action
     * or the condition it was built from.
     */
    val actionId: Long?,
    val conditionId: Long?,
    /** Which way out of a condition this step sent the run; null for the rest. */
    val branch: EdgeBranch?,
    /**
     * Copied from an earlier run rather than performed by this one, which is
     * what every step ahead of the one a re-run started at looks like.
     */
    val carriedOver: Boolean,
    val x: Double,
    val y: Double,
) {
    constructor(step: ExecutionStep) : this(
        key = step.nodeKey,
        kind = step.kind,
        name = step.name,
        description = step.description,
        status = step.status,
        startedAt = step.startedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        finishedAt = step.finishedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        durationSeconds = step.startedAt?.let { started -> step.finishedAt?.let { seconds(started, it) } },
        input = step.input,
        output = step.output,
        error = step.error,
        actionId = step.actionId,
        conditionId = step.conditionId,
        branch = step.branch,
        carriedOver = step.carriedOver,
        x = step.x,
        y = step.y,
    )
}

data class ExecutionLogLineView(
    val id: Long,
    val nodeKey: String?,
    val at: String,
    val level: LogLevel,
    val message: String,
) {
    constructor(line: ExecutionLog) : this(
        id = requireNotNull(line.id),
        nodeKey = line.nodeKey,
        at = line.loggedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        level = line.level,
        message = line.message,
    )
}

data class ExecutionDetailView(
    val id: Long,
    val workspaceId: Long,
    val workflowId: Long,
    val workflowName: String,
    val status: ExecutionStatus,
    val trigger: ExecutionTrigger,
    val startedAt: String,
    val finishedAt: String?,
    val durationSeconds: Int?,
    val error: String?,
    /** What the run was started on, so it can be run again on the same thing. */
    val input: String? = null,
    /** The node that ended the run early, and what it said; null for a full run. */
    val stoppedAtNodeKey: String? = null,
    val stoppedReason: String? = null,
    val steps: List<ExecutionStepView>,
    val logs: List<ExecutionLogLineView>,
) {
    constructor(execution: WorkflowExecution, steps: List<ExecutionStep>, logs: List<ExecutionLog>) : this(
        id = requireNotNull(execution.id),
        workspaceId = execution.workspaceId,
        workflowId = execution.workflowId,
        workflowName = execution.workflowName,
        status = execution.status,
        trigger = execution.trigger,
        startedAt = execution.startedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        finishedAt = execution.finishedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        durationSeconds = execution.finishedAt?.let { seconds(execution.startedAt, it) },
        error = execution.error,
        input = execution.input,
        stoppedAtNodeKey = execution.stoppedAtNodeKey,
        stoppedReason = execution.stoppedReason,
        steps = steps.map(::ExecutionStepView),
        logs = logs.map(::ExecutionLogLineView),
    )
}

private fun seconds(from: OffsetDateTime, to: OffsetDateTime): Int =
    Duration.between(from, to).seconds.toInt()
