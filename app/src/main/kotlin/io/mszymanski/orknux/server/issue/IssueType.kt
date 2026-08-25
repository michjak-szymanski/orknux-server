package io.mszymanski.orknux.server.issue

import io.mszymanski.orknux.server.graphql.Refusal
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/**
 * What kind of thing an issue is: a bug, a feature, or whatever else a
 * workspace decides it files.
 *
 * A catalogue of its own rather than a label with a reserved prefix, which is
 * the cheaper answer and the wrong one. Three things separate them.
 *
 * **One at a time.** [Issue.labels] is a set, and a set cannot say "exactly
 * one". Nothing would stop `type:bug` and `type:feature` sitting on the same
 * issue, so the list, the filter, the tools and a person reading the page would
 * each have to decide what an issue with two types means - and they would
 * decide differently, which is how a field stops being worth filtering on.
 *
 * **It exists while nothing carries it.** The label list is derived:
 * [IssueRepository.labelsIn] is `select distinct`, so a label exists exactly as
 * long as an issue holds it, taking the last issue off one deletes it, and a
 * workspace that has filed nothing has no labels to show. What was asked for is
 * a settings page where a workspace decides which types it has, and a page
 * listing something that cannot exist until somebody uses it is a page that
 * opens empty on a new workspace and cannot be filled.
 *
 * **Its name can change.** Renaming a label means rewriting every row holding
 * the string, and no two callers would ever agree on whether that is a rename
 * or two edits. A type is one row: rename it and every issue carrying it now
 * reads the new word, and its history still says what it was called at the
 * time, because the history stores the name and not the id.
 *
 * Labels are untouched by any of this. A label is what a workspace says *about*
 * an issue, as many things at once as it likes; the type is what the issue *is*.
 */
@Entity
@Table(name = "workspace_issue_type")
class IssueType(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /**
     * Which workspace's catalogue it is in - and not moved between them.
     *
     * An issue can be moved; the type it carried cannot follow it, because a
     * type is the destination's own row or it is nothing there. What the move
     * does about that is in [IssueMoveAPI.refuseWhatCannotFollow].
     */
    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(nullable = false, length = 60)
    var name: String,
)

/** One type and how many issues in the workspace carry it. */
data class IssueTypeCount(val typeId: Long, val issues: Long)

interface IssueTypeRepository : JpaRepository<IssueType, Long> {

    /**
     * A workspace's catalogue, alphabetically.
     *
     * By name rather than by an order somebody arranges. A tracker has a
     * handful of types and `bug` before `feature` is what alphabetical gives
     * anyway, so a position column would be a column to maintain, a migration
     * to reorder and a drag handle to build, in exchange for nothing anybody
     * asked for.
     */
    fun findByWorkspaceIdOrderByNameAsc(workspaceId: Long): List<IssueType>

    /**
     * The one called this, whatever case it was typed in.
     *
     * Case-insensitive because that is what the unique index promises and what
     * a person means: `Bug` and `bug` are one type, and two rows a reader
     * cannot tell apart would collect issues between them by accident. It is
     * also what lets an agent write `type: Bug` and be understood, and what
     * lets a moved issue find its counterpart where it lands.
     */
    @Query("select t from IssueType t where t.workspaceId = :workspaceId and lower(t.name) = lower(:name)")
    fun named(workspaceId: Long, name: String): IssueType?
}

/**
 * A type a workspace does not have, named as it was asked for.
 *
 * The known ones are listed in the message on purpose: whoever reads this
 * typed a word - in the browser it cannot happen, but an agent calling
 * `orknux_open_issue` is writing one - and being told which words work is the
 * difference between a refusal and a correction.
 */
class IssueTypeUnknownException(val name: String, val known: List<String>) : RuntimeException(
    if (known.isEmpty()) {
        "There is no issue type called $name here, and this workspace has none"
    } else {
        "There is no issue type called $name here. This workspace has ${known.joinToString(", ")}"
    },
), Refusal {

    override val arguments get() = mapOf("name" to name, "known" to known)
}

class IssueTypeNotFoundException(val id: Long) : RuntimeException("No issue type with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

class IssueTypeNameInvalidException : RuntimeException("An issue type needs a name")

class IssueTypeNameTakenException(val name: String) :
    RuntimeException("This workspace already files issues as $name"), Refusal {

    override val arguments get() = mapOf("name" to name)
}

/**
 * A type that issues are carrying, refused with the count.
 *
 * The count rather than the issues, and that is a deliberate difference from
 * `ActionInUseException` and the rest, which name what is using the thing. A
 * function is used by three nodes and naming them is a short sentence somebody
 * can act on; a type is carried by two hundred and eleven issues, and a
 * refusal that tried to list them would be a refusal nobody reads. The number
 * is what tells an administrator whether they meant to do this, and the filter
 * is one click away from showing them which.
 */
class IssueTypeInUseException(val name: String, val issues: Long) : RuntimeException(
    "$name is on ${if (issues == 1L) "1 issue" else "$issues issues"}, so it cannot be deleted. " +
        "Retype those issues, then delete it.",
), Refusal {

    override val arguments get() = mapOf("name" to name, "issues" to issues)
}

