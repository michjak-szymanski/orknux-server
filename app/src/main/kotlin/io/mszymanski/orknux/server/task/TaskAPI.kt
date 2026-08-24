package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.time.format.DateTimeFormatter

/**
 * Reading tasks, starting one, and deciding what a parked one asked for.
 *
 * The event log is not here. A task's log is an LLM session, so the page reads
 * it through `llmSessionEvents` — the query the Sessions screen already uses —
 * and this hands over the session's id. A second transcript query would be a
 * second thing to keep in step with a screen that already draws one.
 *
 * Visible to whoever can see the workspace, like the agents whose work these
 * are. A task is asked for by its own id, so the check happens on the task's
 * workspace and not on one the caller names.
 */
@Controller
class TaskAPI(
    private val tasks: TaskRepository,
    private val requests: TaskRequestRepository,
    private val grants: TaskGrantRepository,
    private val agents: AgentRepository,
    private val sessions: LlmSessionRepository,
    private val events: LlmSessionEventRepository,
    private val service: TaskService,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    @Transactional(readOnly = true)
    fun workspaceTasks(
        @Argument workspaceId: Long,
        @Argument status: TaskStatus?,
        @Argument page: Int?,
        @Argument size: Int?,
    ): TaskPageView {
        access.requireVisible(workspaceId)
        val asked = PageRequest.of((page ?: 0).coerceAtLeast(0), (size ?: PAGE).coerceIn(1, BIGGEST_PAGE))
        // Two calls rather than a nullable status in one query, the way the
        // tracker's filter is: "no filter" is a decision here and not a value.
        val found = if (status == null) {
            tasks.findByWorkspaceIdOrderByCreatedAtDescIdDesc(workspaceId, asked)
        } else {
            tasks.findByWorkspaceIdAndStatusOrderByCreatedAtDescIdDesc(workspaceId, status, asked)
        }
        return TaskPageView(found.totalElements.toInt(), found.content.map(::describe))
    }

    /** One task. Null when it is not one you can see, the way a workspace is. */
    @QueryMapping
    @Transactional(readOnly = true)
    fun task(@Argument id: Long): TaskView? {
        val task = tasks.findByIdOrNull(id) ?: return null
        if (!access.canSee(task.workspaceId)) return null
        return describe(task)
    }

    /**
     * Starts one.
     *
     * The mutation is a thin thing on purpose: everything it does is
     * [TaskService.start], which is the call #230's button and a Designer module
     * make as well. What is here and not there is the access check and the audit
     * entry, in that order, because a module has no notion of who is asking.
     */
    @MutationMapping
    @Transactional
    fun startTask(@Argument input: StartTaskInput): TaskView {
        access.requireVisible(input.workspaceId)
        val task = service.start(
            NewTask(
                workspaceId = input.workspaceId,
                prompt = input.prompt,
                title = input.title,
                agentId = input.agentId,
                modelId = input.modelId,
                issueId = input.issueId,
                createdBy = whoever(),
            ),
        )
        auditRecorder.record(
            input.workspaceId,
            WorkspaceAuditCategory.TASK,
            "Task ${task.title} started",
        )
        return describe(task)
    }

    /**
     * Gives a parked task the one thing it asked for.
     *
     * Whoever can see the workspace may decide. That is the same bar as starting
     * the task, and deliberately so: what is being approved is bounded by what
     * the agent could have been configured with anyway, it applies to one task,
     * and it is recorded against the name of whoever pressed it.
     */
    @MutationMapping
    @Transactional
    fun approveTaskRequest(@Argument id: Long): TaskView {
        val (request, task) = visible(id)
        val by = whoever()
        val decided = service.approve(requireNotNull(request.id), by)
        auditRecorder.record(
            task.workspaceId,
            WorkspaceAuditCategory.TASK,
            "Task ${task.title} granted ${what(request)}",
        )
        return describe(decided)
    }

    @MutationMapping
    @Transactional
    fun refuseTaskRequest(@Argument id: Long): TaskView {
        val (request, task) = visible(id)
        val decided = service.refuse(requireNotNull(request.id), whoever())
        auditRecorder.record(
            task.workspaceId,
            WorkspaceAuditCategory.TASK,
            "Task ${task.title} refused ${what(request)}",
        )
        return describe(decided)
    }

    /** Answers the question a parked task stopped to ask. */
    @MutationMapping
    @Transactional
    fun answerTaskRequest(@Argument id: Long, @Argument said: String): TaskView {
        val (request, _) = visible(id)
        return describe(service.answer(requireNotNull(request.id), said, whoever()))
    }

    @MutationMapping
    @Transactional
    fun stopTask(@Argument id: Long): TaskView {
        val task = tasks.findByIdOrNull(id) ?: throw TaskNotFoundException(id)
        access.requireVisible(task.workspaceId)
        val stopped = service.stop(id, whoever())
        auditRecorder.record(task.workspaceId, WorkspaceAuditCategory.TASK, "Task ${task.title} stopped")
        return describe(stopped)
    }

    /**
     * Throws one away, its log with it.
     *
     * Only a task that is over. A running one is stopped first, which is a
     * decision somebody should make deliberately rather than as a side effect of
     * tidying up.
     */
    @MutationMapping
    @Transactional
    fun deleteTask(@Argument id: Long): Boolean {
        val task = tasks.findByIdOrNull(id) ?: return false
        if (!access.canSee(task.workspaceId)) return false
        access.requireVisible(task.workspaceId)
        if (!task.status.over) throw TaskNotRunnableException("Stop the task before removing it")

        task.sessionId?.let { sessionId ->
            events.deleteBySessionId(sessionId)
            sessions.findByIdOrNull(sessionId)?.let(sessions::delete)
        }
        tasks.delete(task)
        auditRecorder.record(task.workspaceId, WorkspaceAuditCategory.TASK, "Task ${task.title} removed")
        return true
    }

    /** The request, and the task it belongs to, once the caller may see it. */
    private fun visible(requestId: Long): Pair<TaskRequest, Task> {
        val request = requests.findByIdOrNull(requestId) ?: throw TaskRequestNotFoundException(requestId)
        val task = tasks.findByIdOrNull(request.taskId) ?: throw TaskNotFoundException(request.taskId)
        access.requireVisible(task.workspaceId)
        return request to task
    }

    /** What was asked for, in the words the audit log shows. */
    private fun what(request: TaskRequest): String = buildString {
        append(request.capability?.name?.lowercase() ?: "an answer")
        request.subject?.let { append(' ').append(it) }
    }

    private fun describe(task: Task): TaskView {
        val id = requireNotNull(task.id)
        val asked = requests.findByTaskIdOrderByAskedAtAscIdAsc(id)
        return TaskView(
            id = id,
            workspaceId = task.workspaceId,
            title = task.title,
            prompt = task.prompt,
            agentId = task.agentId,
            agentName = task.agentId?.let { agents.findByIdOrNull(it)?.name },
            modelId = task.modelId,
            status = task.status,
            sessionId = task.sessionId,
            issueId = task.issueId,
            createdBy = task.createdBy,
            createdAt = task.createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            startedAt = task.startedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            finishedAt = task.finishedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            turnsSpent = task.turnsSpent,
            turnsAllowed = task.turnsAllowed,
            workedSeconds = task.workedSeconds.toInt(),
            secondsAllowed = task.secondsAllowed.toInt(),
            waitingUntil = task.waitingUntil?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            outcome = task.outcome,
            endedBecause = task.endedBecause,
            requests = asked.map(::describe),
            grants = grants.findByTaskIdOrderByGrantedAtAscIdAsc(id).map(::describe),
        )
    }

    private fun describe(request: TaskRequest) = TaskRequestView(
        id = requireNotNull(request.id),
        kind = request.kind,
        capability = request.capability,
        subject = request.subject,
        asks = request.asks,
        askedAt = request.askedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        decision = request.decision,
        answer = request.answer,
        decidedBy = request.decidedBy,
        decidedAt = request.decidedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    )

    private fun describe(grant: TaskGrant) = TaskGrantView(
        id = requireNotNull(grant.id),
        capability = grant.capability,
        subject = grant.subject,
        grantedBy = grant.grantedBy,
        grantedAt = grant.grantedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    )

    private fun whoever(): String = SecurityContextHolder.getContext().authentication?.name.orEmpty()

    private companion object {
        const val PAGE = 20
        const val BIGGEST_PAGE = 100
    }
}

/** What starting a task needs. See [NewTask] for what each of these means. */
data class StartTaskInput(
    val workspaceId: Long,
    val prompt: String,
    val title: String? = null,
    val agentId: Long? = null,
    val modelId: Long? = null,
    val issueId: Long? = null,
)

data class TaskView(
    val id: Long,
    val workspaceId: Long,
    val title: String,
    val prompt: String,
    val agentId: Long?,
    /** Its name now, so a list does not have to look one up per row. */
    val agentName: String?,
    val modelId: Long?,
    val status: TaskStatus,
    /** Where the event log is. The page reads it with `llmSessionEvents`. */
    val sessionId: Long?,
    val issueId: Long?,
    val createdBy: String,
    val createdAt: String,
    val startedAt: String?,
    val finishedAt: String?,
    val turnsSpent: Int,
    val turnsAllowed: Int,
    val workedSeconds: Int,
    val secondsAllowed: Int,
    val waitingUntil: String?,
    val outcome: String?,
    val endedBecause: String?,
    /** Everything it has stopped to ask, oldest first. The last is the open one. */
    val requests: List<TaskRequestView>,
    val grants: List<TaskGrantView>,
)

data class TaskRequestView(
    val id: Long,
    val kind: TaskRequestKind,
    val capability: TaskCapability?,
    val subject: String?,
    val asks: String,
    val askedAt: String,
    val decision: TaskDecision?,
    val answer: String?,
    val decidedBy: String?,
    val decidedAt: String?,
)

data class TaskGrantView(
    val id: Long,
    val capability: TaskCapability,
    val subject: String?,
    val grantedBy: String,
    val grantedAt: String,
)

data class TaskPageView(val totalElements: Int, val content: List<TaskView>)
