package io.mszymanski.gyloli.server.workflow

import io.mszymanski.gyloli.server.security.TeamAccess
import io.mszymanski.gyloli.server.team.TeamAuditCategory
import io.mszymanski.gyloli.server.team.TeamAuditRecorder
import io.mszymanski.gyloli.server.team.TeamNotFoundException
import io.mszymanski.gyloli.server.team.TeamRepository
import io.mszymanski.gyloli.server.team.pageRequest
import io.mszymanski.gyloli.server.trigger.TriggerType
import io.mszymanski.gyloli.server.trigger.WorkflowTriggerRepository
import io.mszymanski.gyloli.server.trigger.sixField
import io.mszymanski.gyloli.workflow.execution.ExecutionService
import io.mszymanski.gyloli.workflow.execution.ExecutionStatus
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
    private val assignments: TeamWorkflowRepository,
    private val runs: ExecutionService,
    private val nodes: WorkflowNodeRepository,
    private val triggers: WorkflowTriggerRepository,
    private val teams: TeamRepository,
    private val access: TeamAccess,
    private val auditRecorder: TeamAuditRecorder,
) {

    @QueryMapping
    fun teamWorkflows(@Argument teamId: Long, @Argument page: Int?, @Argument size: Int?): TeamWorkflowPage {
        requireTeamAccess(teamId)
        return TeamWorkflowPage(
            assignments.findByTeamId(teamId, pageRequest(page, size, Sort.by("workflow.name"))),
        ) { assignment ->
            val workflowId = requireNotNull(assignment.workflow.id)
            lastRunOf(teamId, workflowId) to nextRunOf(workflowId)
        }
    }

    /** Creates the definition and assigns it to the team in one step. */
    @MutationMapping
    @Transactional
    fun createWorkflow(@Argument input: CreateWorkflowInput): TeamWorkflowView {
        val name = input.name.trim()
        if (name.isEmpty()) throw WorkflowNameInvalidException()
        requireTeamAccess(input.teamId)
        if (workflows.findByName(name) != null) throw WorkflowNameTakenException(name)

        val workflow = workflows.save(Workflow(name = name, description = input.description?.trim()?.ifEmpty { null }))
        val assignment = assignments.save(TeamWorkflow(teamId = input.teamId, workflow = workflow, enabled = true))
        auditRecorder.record(input.teamId, TeamAuditCategory.WORKFLOW, "Workflow $name created")
        return TeamWorkflowView(assignment)
    }

    /** Backs the workflow settings form. The definition is shared, so a rename is org-wide. */
    @MutationMapping
    @Transactional
    fun updateWorkflow(@Argument id: Long, @Argument input: UpdateWorkflowInput): TeamWorkflowView {
        val name = input.name.trim()
        if (name.isEmpty()) throw WorkflowNameInvalidException()

        val assignment = assignments.findByIdOrNull(id) ?: throw WorkflowNotFoundException(id)
        requireTeamAccess(assignment.teamId)

        val workflow = assignment.workflow
        val previousName = workflow.name
        if (name != previousName && workflows.findByName(name) != null) throw WorkflowNameTakenException(name)

        workflow.name = name
        workflow.description = input.description?.trim()?.ifEmpty { null }

        if (name != previousName) {
            auditRecorder.record(
                assignment.teamId,
                TeamAuditCategory.WORKFLOW,
                "Workflow $previousName renamed to $name",
            )
        } else {
            auditRecorder.record(assignment.teamId, TeamAuditCategory.WORKFLOW, "Workflow $name updated")
        }
        return TeamWorkflowView(assignment)
    }

    @MutationMapping
    @Transactional
    fun setWorkflowEnabled(@Argument id: Long, @Argument enabled: Boolean): TeamWorkflowView {
        val assignment = assignments.findByIdOrNull(id) ?: throw WorkflowNotFoundException(id)
        requireTeamAccess(assignment.teamId)
        assignment.enabled = enabled
        auditRecorder.record(
            assignment.teamId,
            TeamAuditCategory.WORKFLOW,
            "Workflow ${assignment.workflow.name} ${if (enabled) "enabled" else "disabled"}",
        )
        return TeamWorkflowView(assignment)
    }

    /** Removes the assignment only; the workflow definition is kept. */
    @MutationMapping
    @Transactional
    fun removeWorkflow(@Argument id: Long): Boolean {
        val assignment = assignments.findByIdOrNull(id) ?: return false
        requireTeamAccess(assignment.teamId)
        assignments.delete(assignment)
        auditRecorder.record(
            assignment.teamId,
            TeamAuditCategory.WORKFLOW,
            "Workflow ${assignment.workflow.name} removed from this team",
        )
        return true
    }

    /** Admins pass for every team; everyone else needs the team's LDAP role. */
    /** The workflows list shows where each one last got to, so a run is visible here. */
    private fun lastRunOf(teamId: Long, workflowId: Long): LastRunView? =
        runs.lastExecution(teamId, workflowId)?.let {
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

    private fun requireTeamAccess(teamId: Long) {
        val team = teams.findByIdOrNull(teamId) ?: throw TeamNotFoundException(teamId)
        access.requireVisible(team)
    }
}

data class CreateWorkflowInput(
    val teamId: Long,
    val name: String,
    val description: String? = null,
)

data class UpdateWorkflowInput(
    val name: String,
    val description: String? = null,
)

data class TeamWorkflowView(
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
    constructor(assignment: TeamWorkflow, lastRun: LastRunView? = null, nextRun: String? = null) : this(
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

data class TeamWorkflowPage(
    val content: List<TeamWorkflowView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(
        page: Page<TeamWorkflow>,
        runs: (TeamWorkflow) -> Pair<LastRunView?, String?>,
    ) : this(
        content = page.content.map { assignment ->
            val (lastRun, nextRun) = runs(assignment)
            TeamWorkflowView(assignment, lastRun, nextRun)
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

class WorkflowNotAssignedException(teamId: Long, workflowId: Long) :
    RuntimeException("Workflow $workflowId is not assigned to team $teamId")
