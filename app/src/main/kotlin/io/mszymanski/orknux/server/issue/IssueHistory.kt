package io.mszymanski.orknux.server.issue

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/**
 * One line in an issue's history.
 *
 * Nine kinds and only seven of them are ever stored. [OPENED] is read off the
 * issue itself and [COMMENT] off the comments table, because both are already
 * recorded faithfully and a second copy of a comment is a copy that goes stale
 * the moment somebody edits it. They are in the same enum because the reader
 * sees one list: what happened to this issue, in order, whatever table it came
 * from.
 */
enum class IssueEventKind {
    /** It was filed. Assembled from the issue's own date and reporter. */
    OPENED,

    /**
     * From here on, changes were written down.
     *
     * Stored once per issue that existed before this table did, by the
     * migration that created it. A history that began yesterday and says
     * nothing about it is a history that claims an issue opened last week had a
     * quiet week, and the one thing a record must never do is imply an absence
     * of events. Nothing writes one of these afterwards, so an issue filed
     * since has no such line and needs none.
     */
    RECORDING,

    /** It was closed, reopened, or picked up. */
    STATUS,

    /** A label was put on or taken off - one row for each, never a set. */
    LABEL,

    /**
     * Somebody said what kind of thing it is, or took that back.
     *
     * Both sides are the name the type had at the time, not its id - the same
     * choice [ASSIGNEE] makes and for the same reason. A type can be renamed,
     * and a history that read the row live would rewrite March to say what the
     * word became in June. Null on either side is untyped, which is a real
     * state an issue can be put back into.
     */
    TYPE,

    /** It changed hands, was handed out, or was put back down. */
    ASSIGNEE,

    /** Somebody started or stopped hearing about it. */
    OBSERVER,

    /**
     * It was linked to another issue, or the link was taken off.
     *
     * Written on both issues, phrased from each one's own side, because a link
     * is one fact about two of them: the history of the issue that was named
     * would otherwise be silent about the day somebody declared it a duplicate.
     */
    LINK,

    /** Somebody said something. Assembled from the comments table. */
    COMMENT,
}

/**
 * Something that happened to an issue, kept as a fact about the issue.
 *
 * Its own table rather than a reading of the workspace audit log, which is the
 * obvious thing to try and does not work. That log is free text keyed by
 * workspace, so finding one issue's lines means matching "Issue #4" inside a
 * sentence - which finds #4 in a workspace where the number has since been
 * handed to something else, misses everything an issue took with it when it was
 * moved, and cannot say what a change was *from*. It also never held two of the
 * four things anybody asks a history for: nothing anywhere recorded a label
 * changing or an issue changing hands, and the tools an agent works the tracker
 * through write no audit lines at all.
 *
 * So this is stored, and it is stored beside the audit log rather than instead
 * of it. The two answer different questions to different readers: the audit log
 * is what a workspace did, read in its settings by somebody looking across
 * everything; this is what happened to one issue, read on the issue by somebody
 * who wants to know why it is closed. Where both are worth writing, both are
 * written - and this one carries the shape the audit line cannot: what it was,
 * what it became, and an id rather than a number, so the row survives the issue
 * being moved to another workspace and goes with it when it is deleted.
 */
@Entity
@Table(name = "workspace_issue_event")
class IssueEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /**
     * The issue's row id, not its number.
     *
     * The number is what people say and what the audit log wrote down, and it
     * is per workspace: an issue moved is given a free number where it lands
     * and the one it had goes to whatever is filed next. A history keyed by the
     * number would follow the wrong issue on both sides of that.
     */
    @Column(name = "issue_id", nullable = false)
    val issueId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val kind: IssueEventKind,

    /** Who did it, always - a history that cannot name anybody is a rumour. */
    @Column(nullable = false, length = 120)
    val actor: String,

    /** What it was: the old status, who it was taken from, the label removed. */
    @Column(columnDefinition = "text")
    val was: String? = null,

    /** What it became: the new status, who it went to, the label added. */
    @Column(columnDefinition = "text")
    val became: String? = null,

    @Column(nullable = false)
    val at: OffsetDateTime = OffsetDateTime.now(),
)

interface IssueEventRepository : JpaRepository<IssueEvent, Long> {

    /**
     * Everything that happened to one issue, oldest first.
     *
     * The id breaks the tie because a save that swaps three labels writes its
     * rows in the same instant, and a list that reorders itself between two
     * reads of the same issue is a list nobody trusts.
     */
    fun findByIssueIdOrderByAtAscIdAsc(issueId: Long): List<IssueEvent>
}

/**
 * Writes an issue's history, wherever the change came from.
 *
 * Called from both doors, exactly as the news desk is: a label added in a
 * browser and one added by an agent through the MCP tools are the same thing
 * happening to the issue, and a history that only knew about the browser would
 * be a history whose gaps line up precisely with the work nobody watched.
 *
 * Every method takes the actor rather than reading the security context, so a
 * caller that already knows who is asking - both of them do - cannot end up
 * writing a different name here than it writes on the change itself.
 */
@Service
class IssueHistoryRecorder(private val events: IssueEventRepository) {

    /** Nothing is written where nothing changed; the callers check that too. */
    fun statusChanged(issue: Issue, was: IssueStatus, became: IssueStatus, actor: String) {
        if (was == became) return
        write(issue, IssueEventKind.STATUS, actor, was.name, became.name)
    }

    /**
     * It was typed, retyped, or untyped. Both sides are names.
     *
     * The name and not the type's id, exactly as [assigneeChanged] writes a
     * name: this is a record of something that happened, and it has to stay
     * true after the type has been renamed or - once nothing carries it -
     * deleted. Null on either side is untyped, which is a state an issue can
     * be put back into and not an absence of information.
     *
     * Nothing is written where nothing changed, which is what makes this safe
     * to call from a save that posts the whole form back unchanged.
     */
    fun typeChanged(issue: Issue, was: String?, became: String?, actor: String) {
        if (was == became) return
        write(issue, IssueEventKind.TYPE, actor, was, became)
    }

    /**
     * One row per label, and never a row holding a set.
     *
     * A joined list would need a separator, and a separator is a character a
     * label may contain - so the encoding would be the thing that decided
     * whether "slack, timing" was one label or two, long after anybody could
     * ask. A row each also reads the way it happened: added this, took that
     * off.
     */
    fun labelsChanged(issue: Issue, was: Set<String>, became: Set<String>, actor: String) {
        (became - was).forEach { write(issue, IssueEventKind.LABEL, actor, null, it) }
        (was - became).forEach { write(issue, IssueEventKind.LABEL, actor, it, null) }
    }

    /**
     * It changed hands. Both sides are names, resolved where it happened.
     *
     * Names rather than the kind and id the issue stores, for the reason the
     * reporter is a name: this is a record of something that happened, and it
     * stays true after the agent it names has been deleted. Null on either side
     * is nobody, which is a real state - an issue can be put back down.
     */
    fun assigneeChanged(issue: Issue, was: String?, became: String?, actor: String) {
        if (was == became) return
        write(issue, IssueEventKind.ASSIGNEE, actor, was, became)
    }

    fun observerAdded(issue: Issue, name: String, actor: String) =
        write(issue, IssueEventKind.OBSERVER, actor, null, name)

    fun observerRemoved(issue: Issue, name: String, actor: String) =
        write(issue, IssueEventKind.OBSERVER, actor, name, null)

    /**
     * Two issues were linked, written down on both of them.
     *
     * Each side gets the sentence that is true of it: the blocker's line says it
     * blocks, the blocked one's says it is blocked by. Two calls rather than one
     * method writing both rows, because the two lines say different things and a
     * caller that could only write one of them is a caller whose next change
     * leaves half the record behind.
     *
     * Written the way [IssueRelations.said] writes it, which is also how the
     * news says it - one encoding rather than two, since both are read by
     * something that has to turn it back into a sentence. The number is the one
     * the other issue had at the time, like the assignee beside it: this is a
     * record of something that happened, not a pointer that has to keep working.
     */
    fun linked(issue: Issue, kind: IssueRelationKind, otherNumber: Int, actor: String) =
        write(issue, IssueEventKind.LINK, actor, null, IssueRelations.said(kind, otherNumber))

    fun unlinked(issue: Issue, kind: IssueRelationKind, otherNumber: Int, actor: String) =
        write(issue, IssueEventKind.LINK, actor, IssueRelations.said(kind, otherNumber), null)

    private fun write(issue: Issue, kind: IssueEventKind, actor: String, was: String?, became: String?) {
        val issueId = issue.id ?: return
        events.save(IssueEvent(issueId = issueId, kind = kind, actor = actor, was = was, became = became))
    }
}
