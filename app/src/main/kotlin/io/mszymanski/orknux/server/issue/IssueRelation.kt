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
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime

/**
 * What one issue has to do with another.
 *
 * Three relations and five names, because two of the three read differently
 * from each end. [BLOCKS] and [BLOCKED_BY] are one fact seen from its two
 * sides, and so are [DUPLICATES] and [DUPLICATED_BY]; [RELATES_TO] is its own
 * opposite, which is what makes it the cheap one.
 *
 * Three rather than more. [RELATES_TO] promises nothing beyond "read that one
 * too", and is the right answer far more often than a tracker's link menu
 * usually admits. [BLOCKS] is the one relation that changes what somebody - or
 * an agent taking work off the queue - should do next, which is the whole
 * reason to write it down. [DUPLICATES] is a statement about the record rather
 * than about the work: two people reported the same thing, and the tracker
 * keeps that promise by showing it on both, so nobody answers it twice.
 *
 * Parent and child are deliberately not here. A parent is a promise about
 * aggregation - a status read off its children, a tree to walk and draw, a
 * depth to guard against - and this tracker has a flat list and a number. A
 * parent link that was only ever drawn as a line would be [RELATES_TO] wearing
 * a word that makes people expect a roll-up that never arrives.
 */
enum class IssueRelationKind {
    RELATES_TO,
    BLOCKS,
    BLOCKED_BY,
    DUPLICATES,
    DUPLICATED_BY;

    /**
     * The same fact, read from the other issue.
     *
     * [RELATES_TO] answers itself, which is not a special case to work around
     * but what makes it symmetric.
     */
    val opposite: IssueRelationKind
        get() = when (this) {
            RELATES_TO -> RELATES_TO
            BLOCKS -> BLOCKED_BY
            BLOCKED_BY -> BLOCKS
            DUPLICATES -> DUPLICATED_BY
            DUPLICATED_BY -> DUPLICATES
        }

    /**
     * Whether a row is written facing this way.
     *
     * One of each pair is, and it is always the one that reads actively: the
     * blocker blocks, the copy duplicates the original. Somebody who says "this
     * is blocked by 4" says the same thing about the same two issues, and what
     * is stored is the sentence whose subject makes the row's two columns mean
     * something on their own.
     */
    val stored: Boolean
        get() = this == RELATES_TO || this == BLOCKS || this == DUPLICATES

    /**
     * The relation as the middle of a sentence about the issue reading it.
     *
     * "#7 is blocked by #4", not "#7 BLOCKED_BY #4". Here rather than at each
     * place that writes a sentence, because mail and the tools both write one
     * and two copies of these five phrases would drift the day a sixth arrives.
     */
    val reads: String
        get() = when (this) {
            RELATES_TO -> "relates to"
            BLOCKS -> "blocks"
            BLOCKED_BY -> "is blocked by"
            DUPLICATES -> "duplicates"
            DUPLICATED_BY -> "is duplicated by"
        }
}

/**
 * A link between two issues, kept once.
 *
 * The tracker could already hang a web address on an issue, and the address
 * people most wanted to hang was another issue in this same tracker - which as
 * a URL says nothing until it is clicked, points at a number that belongs to
 * something else once the issue is moved, and is invisible from the other end.
 * A thing reported twice was two reports neither of which knew about the other,
 * and "we cannot start this until the connector lands" lived in a comment
 * nobody scrolled to.
 *
 * One row for the whole link rather than one row per end. Two rows are two
 * things that can disagree - one deleted and one left behind, and an issue
 * claiming to block something that has never heard of it - and there is no
 * moment at which half a link is a state anybody wanted. Stored once, it cannot
 * be made by halves, and taking it off from either end is the same delete.
 *
 * Which of the two issues is [issueId] is decided by the relation and not by
 * who happened to be looking: see [IssueRelationKind.stored], and
 * [IssueRelations] for the one case - a symmetric relation, where both
 * sentences are the same sentence - that has to be settled some other way.
 *
 * Row ids rather than the numbers people say, for the reason the history is
 * keyed by the id: a number is per workspace, and an issue that moves is given
 * a free one where it lands. A link keyed by numbers would follow whatever is
 * filed next.
 */
@Entity
@Table(name = "workspace_issue_relation")
class IssueRelation(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "issue_id", nullable = false)
    val issueId: Long,

    @Column(name = "other_issue_id", nullable = false)
    val otherIssueId: Long,

    /** Never one of the two that read backwards: only [IssueRelationKind.stored] ones are written. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val kind: IssueRelationKind,

    @Column(name = "linked_at", nullable = false)
    val linkedAt: OffsetDateTime = OffsetDateTime.now(),

    /** Who said so, kept as a name so it stays true after their row is gone. */
    @Column(name = "linked_by", nullable = false, length = 120)
    val linkedBy: String = "",
)

interface IssueRelationRepository : JpaRepository<IssueRelation, Long> {

    /**
     * Every link one issue is an end of, near or far, oldest first.
     *
     * Both columns are asked about because the row is the whole link: an issue
     * that is blocked has no row of its own saying so, it is the far end of the
     * blocker's. The id breaks the tie for the reason it does everywhere else in
     * the tracker - a list that reorders itself between two reads of the same
     * issue is a list nobody trusts.
     */
    @Query(
        "select r from IssueRelation r where r.issueId = :issueId or r.otherIssueId = :issueId " +
            "order by r.linkedAt asc, r.id asc",
    )
    fun touching(@Param("issueId") issueId: Long): List<IssueRelation>

    /**
     * The link between two issues if there is one, whichever way round it runs.
     *
     * The unique index refuses the pair one way; this is how the other way is
     * refused, and it is also what lets linking the same pair the same way twice
     * quietly do nothing rather than fail on a constraint.
     */
    @Query(
        "select r from IssueRelation r where (r.issueId = :one and r.otherIssueId = :other) " +
            "or (r.issueId = :other and r.otherIssueId = :one)",
    )
    fun between(@Param("one") one: Long, @Param("other") other: Long): IssueRelation?
}

class IssueRelationNotFoundException(id: Long) : RuntimeException("No link with id $id")

/** An issue has nothing to say about itself that a link could carry. */
class IssueRelationToItselfException : RuntimeException("An issue cannot be linked to itself")

/**
 * The other end is not somewhere a link could point.
 *
 * A link is between two issues in one workspace. The number is what people say
 * and what the page draws - a number written into a comment is already turned
 * into a link by the tracker - and a number means one thing per workspace, so a
 * link reaching across would draw a reference that reads as this workspace's #4
 * and is not. The reader would also, quite often, not be allowed to open it.
 */
class IssueRelationElsewhereException :
    RuntimeException("Issues can only be linked to other issues in the same workspace")

/**
 * These two are already linked, some other way.
 *
 * Two issues are connected in one way or not at all. A pair that both blocked
 * and merely related would be one line contradicting the other, and the reader
 * would have to guess which one the tracker meant. Said rather than quietly
 * replaced, because changing what one issue says about another is a thing
 * somebody should do on purpose.
 */
class IssueRelationAlreadyException(said: String) :
    RuntimeException("These two are already linked: $said. Take that link off first.")

/**
 * Which way round a link is written, and how it reads from either end.
 *
 * Kept out of the controller because both doors into the tracker have to agree
 * about it exactly. A link stored the wrong way round is not a bug that arrives
 * as an error; it is a bug that arrives as the tracker calmly saying the
 * opposite of what somebody typed.
 */
object IssueRelations {

    /**
     * The row two issues make, whoever named them and in whichever order.
     *
     * Two things are settled here. A relation that reads backwards is turned
     * around, so that only the active sentence is ever stored - "blocked by" is
     * kept as the other issue blocking this one. And a symmetric relation, where
     * both sentences are the same sentence and neither end is the subject, is
     * stored with the lower id first: without a rule the same pair could be
     * written twice wearing opposite ends, and a unique index over two columns
     * would happily let it.
     */
    fun of(issueId: Long, otherId: Long, kind: IssueRelationKind, by: String): IssueRelation {
        val (from, to, facing) =
            if (kind.stored) Triple(issueId, otherId, kind) else Triple(otherId, issueId, kind.opposite)
        val symmetric = facing == facing.opposite
        return IssueRelation(
            issueId = if (symmetric) minOf(from, to) else from,
            otherIssueId = if (symmetric) maxOf(from, to) else to,
            kind = facing,
            linkedBy = by,
        )
    }

    /**
     * How a link is written down where only one field is free, and how to read
     * it back.
     *
     * The relation and the other issue's number, separated by a space. Both the
     * history and the news have one text field to say what happened in, and both
     * are read by something that has to put it in a sentence. Safe to encode
     * with a separator, unlike a label, because neither half is anything a
     * person typed: one is an enum name and the other is digits.
     */
    fun said(kind: IssueRelationKind, number: Int) = "${kind.name} #$number"

    /** "is blocked by #4" from what [said] wrote, or null where it says nothing. */
    fun reading(said: String?): String? {
        val kind = said?.substringBefore(' ')?.let { name ->
            IssueRelationKind.entries.firstOrNull { it.name == name }
        } ?: return null
        return "${kind.reads} ${said.substringAfter(' ')}"
    }

    /** The relation as the issue at one end reads it, and which issue is at the other. */
    fun seenFrom(relation: IssueRelation, issueId: Long): Pair<IssueRelationKind, Long> =
        if (relation.issueId == issueId) {
            relation.kind to relation.otherIssueId
        } else {
            relation.kind.opposite to relation.issueId
        }
}
