package io.mszymanski.orknux.server.issue

import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional

/**
 * What has happened that concerns whoever is reading, across every workspace
 * they can see.
 *
 * The same feed an assistant reads over MCP, deliberately. Two records of "what
 * happened on this issue" would eventually disagree, and the one nobody is
 * looking at would be the one that was right - so there is one desk, and this
 * is the door a browser comes to.
 *
 * Not marked read by reading. The bell has to be able to show a number, and a
 * number that clears itself the moment it is asked for is a number nobody sees.
 * Opening the panel is what says it has been read, and that is a separate ask.
 */
@Controller
class NotificationAPI(
    private val desk: IssueNewsDesk,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
) {

    @QueryMapping
    @Transactional(readOnly = true)
    fun myNotifications(@Argument limit: Int?): List<NotificationView> {
        val me = whoever() ?: return emptyList()
        val mine = workspaces.findAll().filter { access.canSee(it) }.mapNotNull { it.id }

        /*
         * Across every workspace rather than the one on screen. A comment on an
         * issue somewhere else is exactly the thing somebody has not got a page
         * open for, which is what a notification is for in the first place.
         */
        /*
         * What happened, not what is unread. Those were the same call, and the
         * panel emptied itself: opening it is what marks the news read, so from
         * the second look onwards there was nothing left to draw (issue #114).
         *
         * Each one says whether it had been read when it was asked for, so the
         * panel can mark the new ones without the number and the list
         * disagreeing - they are still two questions, and the number is still
         * only the unread.
         */
        return mine
            .flatMap { workspaceId ->
                val (told, readTo) = desk.history(workspaceId, NewsReader(AssigneeKind.USER, me))
                told.map { it to ((it.id ?: 0) > readTo) }
            }
            .sortedByDescending { it.first.id ?: 0 }
            .take((limit ?: MANY).coerceIn(1, MANY))
            .map { (item, fresh) -> NotificationView(item, fresh) }
    }

    /** How many are waiting, which is all the bell itself needs to know. */
    @QueryMapping
    @Transactional(readOnly = true)
    fun myNotificationCount(): Int {
        val me = whoever() ?: return 0
        return workspaces.findAll()
            .filter { access.canSee(it) }
            .mapNotNull { it.id }
            .sumOf { desk.waiting(it, NewsReader(AssigneeKind.USER, me)).size }
    }

    /**
     * Says they have been seen.
     *
     * Everything up to now, rather than the ones that were on screen: somebody
     * who opens the panel has looked at it, and leaving one behind as unread
     * because it arrived while they were reading would make the number lie in
     * the other direction.
     */
    @MutationMapping
    @Transactional
    fun readMyNotifications(): Int {
        val me = whoever() ?: return 0
        val reader = NewsReader(AssigneeKind.USER, me)
        return workspaces.findAll()
            .filter { access.canSee(it) }
            .mapNotNull { it.id }
            .sumOf { desk.unread(it, reader, MANY).size }
    }

    private fun whoever(): String? = SecurityContextHolder.getContext().authentication?.name

    private companion object {
        /** A bell is not a list to page through; past this, open the tracker. */
        const val MANY = 50
    }
}

/**
 * One thing that happened, as the bell shows it.
 *
 * The subject is an issue or a task, and exactly one of the two pairs is filled
 * in. The bell reads whichever is there and links accordingly.
 */
class NotificationView(item: IssueNewsItem, val unread: Boolean) {
    val id: Long = item.id ?: 0
    val workspaceId: Long = item.workspaceId
    val issueNumber: Int? = item.issueNumber
    val issueTitle: String? = item.issueTitle
    val taskId: Long? = item.taskId
    val taskTitle: String? = item.taskTitle
    val kind: IssueNewsKind = item.kind
    val actor: String = item.actor
    val says: String? = item.says
    val at: String = item.at.toString()
}
