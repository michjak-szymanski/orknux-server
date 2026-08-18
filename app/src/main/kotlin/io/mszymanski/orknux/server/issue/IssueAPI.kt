package io.mszymanski.orknux.server.issue

import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceNotFoundException
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * A workspace's issues.
 *
 * Visible to whoever can see the workspace, like everything else in it: an
 * issue is a note about this workspace's work, and hiding it from the people
 * doing that work would leave them filing it somewhere else.
 *
 * The one thing here that is not obvious is the assignee. It can be a person,
 * an agent or a model, because this is a product where the thing doing the
 * work is often not a person - and what is stored is a kind and an id,
 * resolved to a name on the way out, so a rename reads correctly afterwards
 * and a deletion reads as "no longer here" rather than as a dangling id.
 */
@Controller
class IssueAPI(
    private val issues: IssueRepository,
    private val workspaces: WorkspaceRepository,
    private val users: AppUserRepository,
    private val agents: AgentRepository,
    private val models: ModelService,
    private val audit: WorkspaceAuditRecorder,
    private val access: WorkspaceAccess,
) {

    /*
     * Transactional because describing an issue reads its comments, and the
     * comments are lazy: outside a session that read is an exception rather
     * than a query. Read-only, since nothing here writes.
     */
    @QueryMapping
    @Transactional(readOnly = true)
    fun workspaceIssues(
        @Argument workspaceId: Long,
        @Argument status: IssueStatus?,
        @Argument search: String?,
        @Argument page: Int?,
        @Argument size: Int?,
    ): IssuePageView {
        requireWorkspaceAccess(workspaceId)
        // Newest first: an issue tracker is read from the top.
        val asked = PageRequest.of(page ?: 0, (size ?: 20).coerceIn(1, 100), Sort.by("number").descending())
        val wanted = search?.trim().orEmpty()
        val found = if (status == null) {
            issues.search(workspaceId, wanted, asked)
        } else {
            issues.searchByStatus(workspaceId, status, wanted, asked)
        }
        return IssuePageView(found.totalElements.toInt(), found.content.map(::describe))
    }

    /**
     * One issue, by the number people say.
     *
     * Not by its row id. "#4" is what the page shows, what somebody types in a
     * message and what the address should carry - an address holding the id
     * instead showed `/issues/17` above a page titled `#15`, and two of us
     * spent a while believing the wrong issue had been answered.
     */
    @QueryMapping
    @Transactional(readOnly = true)
    fun workspaceIssue(@Argument workspaceId: Long, @Argument number: Int): IssueView? {
        requireWorkspaceAccess(workspaceId)
        return issues.findByWorkspaceIdAndNumber(workspaceId, number)?.let(::describe)
    }

    /** Every label in use here, so the filter offers what exists rather than a box. */
    @QueryMapping
    fun workspaceIssueLabels(@Argument workspaceId: Long): List<String> {
        requireWorkspaceAccess(workspaceId)
        return issues.labelsIn(workspaceId)
    }

    /**
     * What an issue can be assigned to, by name.
     *
     * One list of three kinds, the way the box that offers it works: somebody
     * typing "sup" wants the support agent or the support desk user and should
     * not have to say which kind first.
     */
    @QueryMapping
    fun issueAssignees(@Argument workspaceId: Long, @Argument search: String?): List<AssigneeView> {
        requireWorkspaceAccess(workspaceId)
        val wanted = search?.trim()?.lowercase().orEmpty()

        val people = users.findAll().map {
            AssigneeView(AssigneeKind.USER, requireNotNull(it.id).toString(), it.displayName, it.username)
        }
        val theAgents = agents.findByWorkspaceId(workspaceId, PageRequest.of(0, MANY, Sort.by("name")))
            .content
            .map { AssigneeView(AssigneeKind.AGENT, requireNotNull(it.id).toString(), it.name, "agent") }
        val theModels = models.models(workspaceId)
            .map { AssigneeView(AssigneeKind.MODEL, it.id.toString(), it.name, it.providerName) }

        return (people + theAgents + theModels)
            .filter { wanted.isEmpty() || it.name.lowercase().contains(wanted) || it.hint.lowercase().contains(wanted) }
            .sortedBy { it.name.lowercase() }
            .take(MANY)
    }

    @MutationMapping
    @Transactional
    fun createIssue(@Argument input: IssueInput): IssueView {
        val workspaceId = requireNotNull(input.workspaceId) { "An issue belongs to a workspace" }
        requireWorkspaceAccess(workspaceId)

        val title = input.title?.trim().orEmpty()
        if (title.isEmpty()) throw IssueTitleInvalidException()

        val made = issues.save(
            Issue(
                workspaceId = workspaceId,
                number = issues.lastNumber(workspaceId) + 1,
                title = title,
                description = input.description?.trim()?.takeIf { it.isNotEmpty() },
                status = input.status ?: IssueStatus.OPEN,
                reporter = currentUser(),
                assignee = assigneeFrom(workspaceId, input),
                labels = cleanLabels(input.labels),
                lastModifiedBy = currentUser(),
            ),
        )
        audit.record(workspaceId, WorkspaceAuditCategory.WORKSPACE, "Issue #${made.number} \"$title\" opened")
        return describe(made)
    }

    @MutationMapping
    @Transactional
    fun updateIssue(@Argument id: Long, @Argument input: IssueInput): IssueView {
        val held = issues.findByIdOrNull(id) ?: throw IssueNotFoundException(id)
        requireWorkspaceAccess(held.workspaceId)

        input.title?.trim()?.let {
            if (it.isEmpty()) throw IssueTitleInvalidException()
            held.title = it
        }
        // An empty description is a description somebody cleared, so it is
        // written; an absent one is a field they did not touch.
        input.description?.let { held.description = it.trim().takeIf { text -> text.isNotEmpty() } }
        input.labels?.let { held.labels = cleanLabels(it) }
        input.status?.let { wanted ->
            if (wanted != held.status) {
                audit.record(
                    held.workspaceId,
                    WorkspaceAuditCategory.WORKSPACE,
                    "Issue #${held.number} ${if (wanted == IssueStatus.CLOSED) "closed" else "reopened"}",
                )
            }
            held.status = wanted
        }
        if (input.assigneeKind != null || input.assigneeId != null) {
            held.assignee = assigneeFrom(held.workspaceId, input)
        }
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = currentUser()
        return describe(issues.save(held))
    }

    /**
     * Changing what you wrote.
     *
     * Only the author, and deliberately not administrators either: what
     * somebody else wrote is what they wrote, and a record that anybody
     * reading it could rewrite is not a record. The edit is marked, so a
     * comment that has changed says so.
     */
    @MutationMapping
    @Transactional
    fun editIssueComment(@Argument id: Long, @Argument content: String): IssueView {
        val issue = issues.findAll().firstOrNull { held -> held.comments.any { it.id == id } }
            ?: throw IssueCommentNotFoundException(id)
        requireWorkspaceAccess(issue.workspaceId)

        val comment = issue.comments.first { it.id == id }
        if (comment.author != currentUser()) throw IssueCommentNotYoursException()

        val said = content.trim()
        if (said.isEmpty()) throw IssueCommentEmptyException()

        comment.content = said
        comment.editedAt = OffsetDateTime.now()
        return describe(issues.save(issue))
    }

    @MutationMapping
    @Transactional
    fun deleteIssue(@Argument id: Long): Boolean {
        val held = issues.findByIdOrNull(id) ?: throw IssueNotFoundException(id)
        requireWorkspaceAccess(held.workspaceId)
        issues.delete(held)
        audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Issue #${held.number} deleted")
        return true
    }

    @MutationMapping
    @Transactional
    fun commentOnIssue(@Argument id: Long, @Argument content: String): IssueView {
        val held = issues.findByIdOrNull(id) ?: throw IssueNotFoundException(id)
        requireWorkspaceAccess(held.workspaceId)

        val said = content.trim()
        if (said.isEmpty()) throw IssueCommentEmptyException()

        held.comments.add(IssueComment(author = currentUser(), content = said))
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = currentUser()
        return describe(issues.save(held))
    }

    /**
     * The assignee an input names, checked against this workspace.
     *
     * Nothing is trusted from the caller past its kind: an id that names no
     * agent here is a mistake worth reporting now rather than an issue
     * assigned to a number.
     */
    private fun assigneeFrom(workspaceId: Long, input: IssueInput): Assignee? {
        val kind = input.assigneeKind ?: return null
        val id = input.assigneeId?.takeIf { it.isNotBlank() } ?: return null

        val known = when (kind) {
            AssigneeKind.USER -> users.findByIdOrNull(id.toLongOrNull() ?: -1) != null
            AssigneeKind.AGENT -> agents.findByIdOrNull(id.toLongOrNull() ?: -1)?.workspaceId == workspaceId
            AssigneeKind.MODEL -> models.models(workspaceId).any { it.id.toString() == id }
        }
        if (!known) throw IssueAssigneeInvalidException("$kind $id")
        return Assignee(kind = kind, id = id)
    }

    /** Trimmed, deduplicated, and never empty strings: a label is a word. */
    private fun cleanLabels(labels: List<String>?): MutableSet<String> =
        labels.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()

    private fun describe(issue: Issue) = IssueView(
        id = requireNotNull(issue.id),
        workspaceId = issue.workspaceId,
        number = issue.number,
        title = issue.title,
        description = issue.description,
        status = issue.status,
        reporter = issue.reporter,
        assignee = nameFor(issue),
        labels = issue.labels.sorted(),
        comments = issue.comments.map {
            IssueCommentView(
                id = requireNotNull(it.id),
                author = it.author,
                content = it.content,
                createdAt = it.createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                editedAt = it.editedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                /*
                 * Whether the person reading this may change it. Answered here
                 * rather than compared in the browser, so the button and the
                 * refusal agree - and so a second window signed in as somebody
                 * else does not offer an edit that would be refused.
                 */
                mine = it.author == currentUser(),
            )
        },
        createdAt = issue.createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        lastModifiedAt = issue.lastModifiedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        lastModifiedBy = issue.lastModifiedBy,
    )

    /**
     * The assignee as a name, looked up now.
     *
     * Stored as an id so a rename reads correctly here; resolved every read so
     * something that has since been removed reads as gone rather than as a
     * number nobody recognises.
     */
    private fun nameFor(issue: Issue): AssigneeView? {
        val held = issue.assignee ?: return null
        val kind = held.kind ?: return null
        val id = held.id ?: return null
        return when (kind) {
            AssigneeKind.USER -> users.findByIdOrNull(id.toLongOrNull() ?: -1)
                ?.let { AssigneeView(kind, id, it.displayName, it.username) }

            AssigneeKind.AGENT -> agents.findByIdOrNull(id.toLongOrNull() ?: -1)
                ?.let { AssigneeView(kind, id, it.name, "agent") }

            AssigneeKind.MODEL -> models.models(issue.workspaceId).firstOrNull { it.id.toString() == id }
                ?.let { AssigneeView(kind, id, it.name, it.providerName) }
        } ?: AssigneeView(kind, id, "No longer here", kind.name.lowercase())
    }

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"

    private fun requireWorkspaceAccess(workspaceId: Long) {
        val workspace = workspaces.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)
    }

    private companion object {
        /** Enough of anything to choose from in a box that also has a search. */
        const val MANY = 200
    }
}

data class IssuePageView(val totalElements: Int, val content: List<IssueView>)

data class IssueView(
    val id: Long,
    val workspaceId: Long,
    val number: Int,
    val title: String,
    val description: String?,
    val status: IssueStatus,
    val reporter: String,
    val assignee: AssigneeView?,
    val labels: List<String>,
    val comments: List<IssueCommentView>,
    val createdAt: String,
    val lastModifiedAt: String,
    val lastModifiedBy: String,
)

data class IssueCommentView(
    val id: Long,
    val author: String,
    val content: String,
    val createdAt: String,
    /** Null until somebody changes it. */
    val editedAt: String?,
    /** Whether the person reading this wrote it. */
    val mine: Boolean,
)

/** Something an issue can be assigned to, as the box shows it. */
data class AssigneeView(
    val kind: AssigneeKind,
    val id: String,
    val name: String,
    /** The second line: a username, a provider, or what kind of thing it is. */
    val hint: String,
)

data class IssueInput(
    val workspaceId: Long?,
    val title: String?,
    val description: String?,
    val status: IssueStatus?,
    val labels: List<String>?,
    val assigneeKind: AssigneeKind?,
    val assigneeId: String?,
)
