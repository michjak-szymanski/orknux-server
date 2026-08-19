package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.workspace.pageRequest
import io.mszymanski.orknux.server.trigger.TriggerType
import io.mszymanski.orknux.server.trigger.WorkflowTriggerRepository
import io.mszymanski.orknux.server.trigger.sixField
import io.mszymanski.orknux.workflow.execution.ExecutionService
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import org.springframework.scheduling.support.CronExpression
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional

@Controller
class WorkflowAPI(
    private val workflows: WorkflowRepository,
    private val assignments: WorkspaceWorkflowRepository,
    private val runs: ExecutionService,
    private val nodes: WorkflowNodeRepository,
    private val triggers: WorkflowTriggerRepository,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun workspaceWorkflows(@Argument workspaceId: Long, @Argument page: Int?, @Argument size: Int?): WorkspaceWorkflowPage {
        requireWorkspaceAccess(workspaceId)
        return WorkspaceWorkflowPage(
            assignments.findByWorkspaceId(workspaceId, pageRequest(page, size, Sort.by("workflow.name"))),
        ) { assignment ->
            val workflowId = requireNotNull(assignment.workflow.id)
            // A workflow that is switched off is not started by the clock, so
            // promising a next run would be the list stating the very thing the
            // switch has just stopped from happening.
            lastRunOf(workspaceId, workflowId) to nextRunOf(workflowId).takeIf { assignment.enabled }
        }
    }

    /** Creates the definition and assigns it to the workspace in one step. */
    @MutationMapping
    @Transactional
    fun createWorkflow(@Argument input: CreateWorkflowInput): WorkspaceWorkflowView {
        val name = input.name.trim()
        if (name.isEmpty()) throw WorkflowNameInvalidException()
        requireWorkspaceAccess(input.workspaceId)
        if (workflows.findByName(name) != null) throw WorkflowNameTakenException(name)

        val workflow = workflows.save(Workflow(name = name, description = input.description?.trim()?.ifEmpty { null }))
        val assignment = assignments.save(WorkspaceWorkflow(workspaceId = input.workspaceId, workflow = workflow, enabled = true))
        auditRecorder.record(input.workspaceId, WorkspaceAuditCategory.WORKFLOW, "Workflow $name created")
        return WorkspaceWorkflowView(assignment)
    }

    /** Backs the workflow settings form. The definition is shared, so a rename is org-wide. */
    @MutationMapping
    @Transactional
    fun updateWorkflow(@Argument id: Long, @Argument input: UpdateWorkflowInput): WorkspaceWorkflowView {
        val name = input.name.trim()
        if (name.isEmpty()) throw WorkflowNameInvalidException()

        val assignment = assignments.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw WorkflowNotFoundException(id)

        val workflow = assignment.workflow
        val previousName = workflow.name
        if (name != previousName && workflows.findByName(name) != null) throw WorkflowNameTakenException(name)

        workflow.name = name
        workflow.description = input.description?.trim()?.ifEmpty { null }

        if (name != previousName) {
            auditRecorder.record(
                assignment.workspaceId,
                WorkspaceAuditCategory.WORKFLOW,
                "Workflow $previousName renamed to $name",
            )
        } else {
            auditRecorder.record(assignment.workspaceId, WorkspaceAuditCategory.WORKFLOW, "Workflow $name updated")
        }
        return WorkspaceWorkflowView(assignment)
    }

    @MutationMapping
    @Transactional
    fun setWorkflowEnabled(@Argument id: Long, @Argument enabled: Boolean): WorkspaceWorkflowView {
        val assignment = assignments.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw WorkflowNotFoundException(id)
        assignment.enabled = enabled
        auditRecorder.record(
            assignment.workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            "Workflow ${assignment.workflow.name} ${if (enabled) "enabled" else "disabled"}",
        )
        return WorkspaceWorkflowView(assignment)
    }

    /** Removes the assignment only; the workflow definition is kept. */
    @MutationMapping
    @Transactional
    fun removeWorkflow(@Argument id: Long): Boolean {
        val assignment = assignments.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return false
        assignments.delete(assignment)
        auditRecorder.record(
            assignment.workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            "Workflow ${assignment.workflow.name} removed from this workspace",
        )
        return true
    }

    /** Admins pass for every workspace; everyone else needs the workspace's LDAP role. */
    /** The workflows list shows where each one last got to, so a run is visible here. */
    private fun lastRunOf(workspaceId: Long, workflowId: Long): LastRunView? =
        runs.lastExecution(workspaceId, workflowId)?.let {
            LastRunView(
                executionId = it.id,
                status = it.status,
                startedAt = it.startedAt,
                durationSeconds = it.durationSeconds,
            )
        }

    /**
     * The soonest a scheduled trigger will start this workflow.
     *
     * A workflow is planned because one of its trigger nodes instances a
     * scheduled definition, so this asks the graph rather than the schedule: a
     * definition nobody instances starts nothing, and should promise nothing.
     */
    private fun nextRunOf(workflowId: Long): String? {
        val triggerIds = nodes.findByWorkflowId(workflowId).mapNotNull { it.triggerId }.distinct()
        if (triggerIds.isEmpty()) return null

        val now = OffsetDateTime.now()
        return triggers.findAllById(triggerIds)
            .filter { it.enabled && it.type == TriggerType.SCHEDULED }
            .mapNotNull { trigger ->
                val cron = trigger.cron ?: return@mapNotNull null
                val zone = runCatching { ZoneId.of(trigger.timezone ?: "UTC") }.getOrDefault(ZoneId.of("UTC"))
                runCatching { CronExpression.parse(sixField(cron)) }.getOrNull()
                    ?.next(now.atZoneSameInstant(zone).toOffsetDateTime())
            }
            .minOrNull()
            ?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }

    private fun requireWorkspaceAccess(workspaceId: Long) {
        access.requireVisible(workspaceId)
    }
}

data class CreateWorkflowInput(
    val workspaceId: Long,
    val name: String,
    val description: String? = null,
)

data class UpdateWorkflowInput(
    val name: String,
    val description: String? = null,
)

data class WorkspaceWorkflowView(
    val id: Long,
    val workflowId: Long,
    val name: String,
    val description: String?,
    val enabled: Boolean,
    /** Where the workflow last got to; null until it has run. */
    val lastRun: LastRunView? = null,
    /**
     * When the clock will start it next, from the scheduled triggers its nodes
     * instance. Null when nothing schedules it — most workflows.
     */
    val nextRun: String? = null,
) {
    constructor(assignment: WorkspaceWorkflow, lastRun: LastRunView? = null, nextRun: String? = null) : this(
        id = requireNotNull(assignment.id),
        workflowId = requireNotNull(assignment.workflow.id),
        name = assignment.workflow.name,
        description = assignment.workflow.description,
        enabled = assignment.enabled,
        lastRun = lastRun,
        nextRun = nextRun,
    )
}

/** The most recent run of a workflow, as the workflows list shows it. */
data class LastRunView(
    val executionId: Long,
    val status: ExecutionStatus,
    /** ISO-8601 offset date-time. */
    val startedAt: String,
    val durationSeconds: Int?,
)

data class WorkspaceWorkflowPage(
    val content: List<WorkspaceWorkflowView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(
        page: Page<WorkspaceWorkflow>,
        runs: (WorkspaceWorkflow) -> Pair<LastRunView?, String?>,
    ) : this(
        content = page.content.map { assignment ->
            val (lastRun, nextRun) = runs(assignment)
            WorkspaceWorkflowView(assignment, lastRun, nextRun)
        },
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}

class WorkflowNotFoundException(id: Long) : RuntimeException("No workflow assignment with id $id")

class WorkflowNameTakenException(name: String) : RuntimeException("A workflow named \"$name\" already exists")

class WorkflowNameInvalidException : RuntimeException("A workflow name is required")

class WorkflowNotAssignedException(workspaceId: Long, workflowId: Long) :
    RuntimeException("Workflow $workflowId is not assigned to workspace $workspaceId")
