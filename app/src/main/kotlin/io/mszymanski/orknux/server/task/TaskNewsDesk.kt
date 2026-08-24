package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.server.issue.AssigneeKind
import io.mszymanski.orknux.server.issue.IssueNewsDesk
import io.mszymanski.orknux.server.issue.IssueNewsKind
import io.mszymanski.orknux.server.issue.IssueRepository
import io.mszymanski.orknux.server.issue.NewsReader
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

/**
 * Who hears about a task, and what they are told.
 *
 * Who *hears* is decided here; the writing down is the tracker's desk, because
 * there is one place an event becomes news for somebody and the bell and
 * `orknux_news` both read what it wrote.
 *
 * The audience is whoever started the task, plus - where the task was started
 * from an issue - everybody that issue concerns. The second half is not a nicety:
 * #230 puts a "Start by AI" button on an issue, and an issue's observers asked to
 * hear what happens to it. A task working on it is exactly that.
 *
 * A task waiting for somebody is the one notification in this application that
 * is not a courtesy. Nothing at all happens until a person looks, so a task that
 * parked and rang no bell is a task that has silently stopped.
 */
@Service
class TaskNewsDesk(
    private val desk: IssueNewsDesk,
    private val issues: IssueRepository,
) {

    /** It stopped, and this is what it is waiting for. */
    fun waiting(task: Task, request: TaskRequest) {
        tell(task, IssueNewsKind.TASK_WAITING, says(request))
    }

    /** It reached an end, and this is which one. */
    fun finished(task: Task) {
        val says = when (task.status) {
            TaskStatus.DONE -> task.outcome ?: "It is finished."
            else -> "It stopped: ${task.endedBecause ?: "no reason was recorded"}."
        }
        tell(task, IssueNewsKind.TASK_FINISHED, says)
    }

    /**
     * The line a bell shows, which is the whole of what was asked.
     *
     * The words the agent wrote and not a summary of them: somebody deciding
     * whether to get up and approve something is deciding on what it says it
     * needs, and a bell that only said "a task wants permission" would send
     * every one of them to the page to find out.
     */
    private fun says(request: TaskRequest): String = when (request.kind) {
        TaskRequestKind.QUESTION -> request.asks
        TaskRequestKind.PERMISSION -> buildString {
            append(request.capability?.name?.lowercase() ?: "something")
            request.subject?.let { append(' ').append(it) }
            append(" - ").append(request.asks)
        }
    }

    private fun tell(task: Task, kind: IssueNewsKind, says: String) {
        val taskId = task.id ?: return
        desk.aboutTask(
            workspaceId = task.workspaceId,
            taskId = taskId,
            taskTitle = task.title,
            kind = kind,
            /*
             * The task speaks for itself rather than borrowing the name of
             * whoever started it. The desk drops the actor from the audience,
             * so news written under that person's name would never reach the
             * one person who most needs it - the person who asked for the task.
             */
            actor = SPEAKER,
            says = says,
            to = audience(task),
        )
    }

    /**
     * Whoever should hear: the person who asked for it, and the issue's room.
     *
     * De-duplication is the desk's, so somebody who filed the issue and started
     * the task is told once.
     */
    private fun audience(task: Task): List<NewsReader> {
        val started = NewsReader(AssigneeKind.USER, task.createdBy)
        val issue = task.issueId?.let { issues.findByIdOrNull(it) } ?: return listOf(started)
        return listOf(started) + desk.watchers(issue)
    }

    private companion object {
        /** What a task is called in a line of news, so it is never mistaken for a person. */
        const val SPEAKER = "task"
    }
}
