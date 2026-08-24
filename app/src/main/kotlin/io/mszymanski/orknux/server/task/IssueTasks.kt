package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.server.issue.AssigneeKind
import io.mszymanski.orknux.server.issue.Issue
import io.mszymanski.orknux.server.issue.IssueHistoryRecorder
import io.mszymanski.orknux.server.issue.IssueNewsDesk
import io.mszymanski.orknux.server.issue.IssueRepository
import io.mszymanski.orknux.server.issue.IssueStatus
import io.mszymanski.orknux.server.issue.auditedAs
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * What an agent is handed when a task is started from an issue.
 *
 * The hard question in #230 is not how to start the task - [TaskService.start]
 * is the door and it takes a prompt - it is *what the prompt says*. An issue is
 * not one field: it has a title, a kind, labels, a description and a thread, and
 * the agent gets no second chance to ask a person what was meant.
 *
 * The decision, and the reasoning for each part of it:
 *
 * - **The title, the number and the kind go in.** The number is how everybody
 *   in this product refers to an issue, so an agent that never sees it cannot
 *   write a sentence anybody can match up to anything. The kind is a one-word
 *   statement of what is wanted - a Bug is to be made to stop happening, a
 *   Feature is to be built - and it is the cheapest useful line in the whole
 *   prompt.
 *
 * - **The labels go in, unread.** They are this workspace's own vocabulary and
 *   nothing here can interpret them, but `p1` and `slack` together tell an agent
 *   more about where to look than a paragraph of description often does.
 *
 * - **The description goes in whole.** It is what the reporter wrote in order to
 *   be read, and summarising it would mean asking a model to decide what matters
 *   before the model that has to do the work has seen any of it.
 *
 * - **The comments go in, and this is the part that had to be argued.** A title
 *   alone is thin: half the trackers in the world have issues whose title is
 *   "doesn't work" and whose meaning is entirely in the thread. But a thread can
 *   also be forty messages of people talking past each other. Leaving them out
 *   makes the agent act on a question that was already answered; putting them in
 *   makes it read some noise. Reading noise is the cheaper mistake, so they go
 *   in - in full, oldest first, with who said what.
 *
 * - **When the thread is too long, the earliest are dropped and the prompt says
 *   so.** A decision about what to do is reached at the end of an argument, not
 *   at the start of it, so an agent handed only the first ten comments of forty
 *   would be acting on a position that was overturned. Dropping from the front is
 *   the only cut that cannot do that, and saying how many were dropped stops the
 *   agent believing it has read everything.
 *
 * There is deliberately **no instruction here about what to do when finished**.
 * `TaskLoop` already tells every task, every turn, to call `task_done` with a
 * summary and what its two other tools are for; a second set of instructions in
 * the prompt is a second set to drift from the first. The prompt is the problem.
 * The briefing is the job.
 */
object IssueTaskPrompt {

    /**
     * How much of the thread is handed over, in characters.
     *
     * A number rather than a count of comments, because what costs the agent its
     * attention is words and not rows - twenty one-line replies are cheaper to
     * read than one pasted stack trace. Generous on purpose: this is a budget
     * for cutting an argument that got out of hand, not a budget for the
     * ordinary issue, which fits several times over.
     */
    const val THREAD_BUDGET = 8_000

    /** The whole issue, in the order somebody reading it would meet it. */
    fun of(issue: Issue): String = buildString {
        appendLine("Issue #${issue.number}: ${issue.title}")
        issue.type?.let { appendLine("Kind: ${it.name}") }
        if (issue.labels.isNotEmpty()) appendLine("Labels: ${issue.labels.sorted().joinToString(", ")}")
        appendLine()

        appendLine("What it says:")
        val said = issue.description?.trim().orEmpty()
        appendLine(said.ifEmpty { "(nothing was written under the title)" })

        thread(issue)?.let { appendLine().append(it) }
    }

    /** The name the task carries, so a list of tasks says which issue each is. */
    fun titleOf(issue: Issue): String = "#${issue.number} ${issue.title}".take(Task.TITLE_LENGTH)

    /**
     * The conversation, newest kept, oldest dropped, or null where there is none.
     *
     * Assembled backwards and reversed at the end, which is what makes the
     * dropping fall on the front: the budget is spent on the latest comments
     * first because those are the ones that decided anything.
     */
    private fun thread(issue: Issue): String? {
        if (issue.comments.isEmpty()) return null

        val kept = mutableListOf<String>()
        var spent = 0
        var left = issue.comments.size
        for (comment in issue.comments.reversed()) {
            val line = "${comment.author}: ${comment.content.trim()}"
            if (spent + line.length > THREAD_BUDGET && kept.isNotEmpty()) break
            kept += line
            spent += line.length
            left -= 1
        }

        return buildString {
            append("What has been said about it, oldest first")
            if (left > 0) append(" (the earliest $left of ${issue.comments.size} are left out)")
            appendLine(":")
            kept.reversed().forEach { appendLine().appendLine(it) }
        }
    }
}

/**
 * "Start by AI": the issue's own agent set to work on the issue.
 *
 * Everything about *starting* is [TaskService.start]'s, which is the door #229
 * left open - this composes the prompt, hands it over with the issue's id, and
 * then does the half that belongs to the tracker: the issue is picked up, its
 * history says so, its room hears, and the workspace's audit log holds one line
 * for the task and one for the issue.
 *
 * All of that is here rather than at the door because this is the one place that
 * knows what actually changed. The controller checks access and calls this; a
 * controller that also wrote the audit would either have to be told whether the
 * status moved or would write a line saying an issue was picked up that was
 * already in progress.
 *
 * The actor is a parameter and is never read from the security context, exactly
 * as [IssueHistoryRecorder] takes one: a caller that already knows who is asking
 * cannot then write a different name here than it wrote on the change itself.
 */
@Service
class IssueTaskStarter(
    private val issues: IssueRepository,
    private val tasks: TaskRepository,
    private val service: TaskService,
    private val history: IssueHistoryRecorder,
    private val newsDesk: IssueNewsDesk,
    private val audit: WorkspaceAuditRecorder,
) {

    /**
     * The task this issue already has going, or null.
     *
     * What makes pressing the button twice safe, and what the page draws instead
     * of the button. One question asked in one place, so the control and the
     * refusal cannot disagree about whether there is a task running - which is
     * the whole of the two-windows problem: both people see the button, both
     * press it, and only one of them may win.
     */
    fun runningOn(issue: Issue): Task? {
        val issueId = issue.id ?: return null
        return tasks.findByIssueIdOrderByCreatedAtDescIdDesc(issueId).firstOrNull { !it.status.over }
    }

    /** Every task this issue started, newest first. The link the other way. */
    fun startedBy(issue: Issue): List<Task> =
        issue.id?.let(tasks::findByIssueIdOrderByCreatedAtDescIdDesc).orEmpty()

    /**
     * Sets the issue's agent to work on it, and records that somebody did.
     *
     * Three refusals, and each of them is something the page has already made
     * unpressable - they are here because a button is a courtesy and a check is
     * a rule, and the second window is the case where the two differ.
     */
    @Transactional
    fun start(issue: Issue, by: String): Task {
        val agentId = agentOn(issue)
            ?: throw TaskNotRunnableException("Issue #${issue.number} is not assigned to an agent")
        if (issue.status == IssueStatus.CLOSED) {
            throw TaskNotRunnableException("Issue #${issue.number} is closed")
        }
        if (runningOn(issue) != null) {
            throw TaskNotRunnableException("Issue #${issue.number} already has a task working on it")
        }

        val task = service.start(
            NewTask(
                workspaceId = issue.workspaceId,
                prompt = IssueTaskPrompt.of(issue),
                title = IssueTaskPrompt.titleOf(issue),
                agentId = agentId,
                issueId = issue.id,
                createdBy = by,
            ),
        )
        audit.record(issue.workspaceId, WorkspaceAuditCategory.TASK, "Task ${task.title} started")
        pickUp(issue, by)
        return task
    }

    /**
     * The agent the issue is assigned to, or null where it is assigned to
     * anything else.
     *
     * A person, a model and nobody are all "no" here, and the kind is what says
     * so - [AssigneeKind] already tells the three apart, so nothing needs to
     * guess from the id.
     *
     * Whether that agent can actually be set to work - it exists, it is this
     * workspace's, it is switched on - is not asked here on purpose.
     * [TaskService.start] asks all three and its refusals name the agent and say
     * which of them failed; a second copy of the question here would answer the
     * same press with a vaguer sentence.
     */
    private fun agentOn(issue: Issue): Long? {
        val assignee = issue.assignee ?: return null
        if (assignee.kind != AssigneeKind.AGENT) return null
        return assignee.id?.toLongOrNull()
    }

    /**
     * The issue moves to in progress, once, and only from open.
     *
     * From open and from nowhere else. An issue already in progress is already
     * where this would put it, and a closed one is not started at all - the
     * refusal above is what stops a button press quietly reopening work somebody
     * had finished.
     *
     * **Nothing moves it back.** A task that fails, runs out of turns or is
     * stopped leaves the issue exactly where the person who pressed the button
     * put it, and that is the conservative answer rather than an oversight: the
     * status is a person's statement about their own tracker, and a machine that
     * reverted it hours later would be editing somebody's board while they were
     * not looking - most often after they had already picked the work up
     * themselves. The issue's room is told what became of the task by
     * [TaskNewsDesk], which is the notification that is actually wanted, and
     * whoever reads it can put the issue back if that is what they mean.
     */
    private fun pickUp(issue: Issue, by: String) {
        val was = issue.status
        if (was != IssueStatus.OPEN) return

        issue.status = IssueStatus.IN_PROGRESS
        issue.lastModifiedAt = OffsetDateTime.now()
        issue.lastModifiedBy = by
        val saved = issues.save(issue)

        audit.record(
            saved.workspaceId,
            WorkspaceAuditCategory.WORKSPACE,
            "Issue #${saved.number} ${saved.status.auditedAs(was)}",
        )
        history.statusChanged(saved, was, saved.status, by)
        newsDesk.statusChanged(saved, by)
    }
}
