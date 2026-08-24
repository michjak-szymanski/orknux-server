package io.mszymanski.orknux.server.issue

import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional

/**
 * The kinds of thing a workspace files, and who may change the list.
 *
 * Per workspace and not per installation, which is what the issue asked for and
 * also the only answer that survives two teams sharing a server: one workspace
 * files bugs and features, the next one files incidents and requests, and an
 * installation-wide list would be a list neither of them recognises. It is also
 * the only reading under which the settings page belongs where it was asked
 * for - a workspace's own settings.
 *
 * Reading is for anybody who can see the workspace, because the filter and the
 * issue page both need the list. Changing it is for whoever administers the
 * workspace, like everything else on that page: the type is what the tracker
 * sorts itself into, and somebody who can file an issue should not be able to
 * invent a fourth category on the way.
 *
 * Its own controller rather than more mutations on [IssueAPI] because it is a
 * different aggregate with a different right to change it - and because the
 * one interesting rule in the tracker's settings, that a type in use cannot be
 * deleted, should be somewhere a reader can find it.
 */
@Controller
class IssueTypeAPI(
    private val types: IssueTypeRepository,
    private val issues: IssueRepository,
    private val audit: WorkspaceAuditRecorder,
    private val access: WorkspaceAccess,
) {

    /**
     * A workspace's types, with how many issues carry each.
     *
     * The count is what makes the settings page honest about deleting: a
     * refusal that arrives only after the button is pressed is a refusal
     * somebody has already decided to be surprised by. Counted by the database
     * rather than by tallying a page of issues, for the reason
     * [IssueRepository.labelCounts] is - a tally of a page counts the page.
     */
    @QueryMapping
    @Transactional(readOnly = true)
    fun workspaceIssueTypes(@Argument workspaceId: Long): List<IssueTypeView> {
        access.requireVisible(workspaceId)
        val carried = issues.typeCounts(workspaceId).associate { it.typeId to it.issues }
        return types.findByWorkspaceIdOrderByNameAsc(workspaceId).map {
            IssueTypeView(requireNotNull(it.id), it.workspaceId, it.name, (carried[it.id] ?: 0L).toInt())
        }
    }

    @MutationMapping
    @Transactional
    fun createIssueType(@Argument workspaceId: Long, @Argument name: String): IssueTypeView {
        access.requireAdministers(workspaceId)
        val wanted = clean(name)
        if (types.named(workspaceId, wanted) != null) throw IssueTypeNameTakenException(wanted)

        val made = types.save(IssueType(workspaceId = workspaceId, name = wanted))
        audit.record(workspaceId, WorkspaceAuditCategory.WORKSPACE, "Issue type $wanted added")
        return IssueTypeView(requireNotNull(made.id), workspaceId, made.name, 0)
    }

    /**
     * Changes what a type is called, and the issues follow.
     *
     * Which is the whole point of it being a row: the word every issue carrying
     * it reads changes at once, and nothing was rewritten. What does not change
     * is the history - an issue typed `bug` in March still says `bug` in March,
     * because [IssueHistoryRecorder.typeChanged] writes the name and not the id,
     * for the reason the assignee does.
     */
    @MutationMapping
    @Transactional
    fun renameIssueType(@Argument id: Long, @Argument name: String): IssueTypeView {
        val held = types.findByIdOrNull(id) ?: throw IssueTypeNotFoundException(id)
        access.requireAdministers(held.workspaceId)

        val wanted = clean(name)
        val was = held.name
        // Its own row is not somebody else's: saving `Bug` over `bug` is a
        // change of case and not a name that is taken.
        types.named(held.workspaceId, wanted)?.takeIf { it.id != held.id }?.let {
            throw IssueTypeNameTakenException(wanted)
        }

        held.name = wanted
        val saved = types.save(held)
        if (was != wanted) {
            audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Issue type $was renamed to $wanted")
        }
        val carried = issues.countByTypeId(id)
        return IssueTypeView(requireNotNull(saved.id), saved.workspaceId, saved.name, carried.toInt())
    }

    /**
     * Takes a type out of the catalogue, or says how many issues are on it.
     *
     * Refused rather than reassigned, which is what the rest of the product
     * does with a thing in use - an action, a function, an agent, a tool - and
     * for the same reason. Quietly untyping two hundred issues is a change
     * nobody asked for, cannot see happen and cannot undo; being told the
     * number is a change somebody can decide about. The way out is the filter:
     * ask for that type, retype what it finds, delete it then.
     */
    @MutationMapping
    @Transactional
    fun deleteIssueType(@Argument id: Long): Boolean {
        val held = types.findByIdOrNull(id) ?: throw IssueTypeNotFoundException(id)
        access.requireAdministers(held.workspaceId)

        val carried = issues.countByTypeId(id)
        if (carried > 0) throw IssueTypeInUseException(held.name, carried)

        types.delete(held)
        audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Issue type ${held.name} deleted")
        return true
    }

    /** Trimmed, and never empty: a type is a word, like a label. */
    private fun clean(name: String): String =
        name.trim().takeIf { it.isNotEmpty() } ?: throw IssueTypeNameInvalidException()

    companion object {
        /**
         * What a workspace files before anybody says otherwise.
         *
         * Here rather than in the migration alone, because a new workspace on
         * SQLite never replays the Postgres history: the migration seeds the
         * workspaces that already existed, this seeds every one made since, and
         * both engines end up saying the same two words. Lower case, as the
         * issue that asked for them wrote them and as labels are read beside
         * them.
         */
        val TO_BEGIN_WITH = listOf("bug", "feature")
    }
}

/**
 * A type as the settings page and the pickers show it.
 *
 * The count is only ever read by the settings page - the filter offers the
 * types without numbers, since a number beside every one of them and none
 * beside "Untyped" is a row that raises a question it does not answer.
 */
data class IssueTypeView(
    val id: Long,
    val workspaceId: Long,
    val name: String,
    /** How many issues in this workspace carry it. */
    val issues: Int,
)
