package io.mszymanski.orknux.server.issue

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.net.URI
import java.net.URISyntaxException
import java.time.OffsetDateTime

/**
 * A web address somebody hung on an issue.
 *
 * Half of what a report points at is somewhere else - the pull request that
 * caused it, the dashboard that showed it, the page that will not load - and
 * pasting those into the description buries them in prose. Rows of their own so
 * they can be listed, seen at a glance and taken off one at a time.
 *
 * The issue is not nullable here, unlike an attachment's. A file is uploaded
 * while the report is still being written, because the bytes take time to
 * travel; an address is typed, so there is never a moment where the link exists
 * and the issue does not.
 *
 * The workspace is not on the row either, for the same reason: an attachment
 * carries one because it decides who may read the bytes and where on the disk
 * they are filed, and a link has no bytes. Who may see it is whoever may see
 * the issue, read through it.
 */
@Entity
@Table(name = "workspace_issue_link")
class IssueLink(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "issue_id", nullable = false)
    val issueId: Long,

    /** As it was given, not as it was tidied: see [IssueLinks.clean]. */
    @Column(nullable = false, length = 2000)
    val url: String,

    /** What whoever added it called it, or null when they let the address speak. */
    @Column(length = 200)
    var title: String? = null,

    @Column(name = "added_at", nullable = false)
    val addedAt: OffsetDateTime = OffsetDateTime.now(),

    /** Whoever added it, and so the only person who may take it off again. */
    @Column(name = "added_by", nullable = false, length = 120)
    val addedBy: String = "",
)

interface IssueLinkRepository : JpaRepository<IssueLink, Long> {

    /**
     * Oldest first, which is the order they were added in and are read in.
     *
     * The id breaks the tie, because two links added in the same instant sort
     * arbitrarily otherwise - and a list that reorders itself between two reads
     * of the same issue is a list nobody trusts.
     */
    fun findByIssueIdOrderByAddedAtAscIdAsc(issueId: Long): List<IssueLink>
}

class IssueLinkNotFoundException(id: Long) : RuntimeException("No link with id $id")

/**
 * Somebody tried to remove a link that is not theirs.
 *
 * The same rule as editing a comment and as removing a file, for the same
 * reason: what somebody else put on an issue is part of the record, and one
 * that anybody reading it could quietly thin out is not a record. Not
 * administrators either - one who needs a link gone can delete the issue, which
 * is a thing that shows.
 */
class IssueLinkNotYoursException :
    RuntimeException("A link can only be removed by whoever added it")

/** Said in the words the person who typed the address needs to read. */
class IssueLinkInvalidException(why: String) : RuntimeException(why)

/**
 * What counts as an address worth putting on an issue.
 *
 * Checked here rather than trusted, because a link is rendered as an anchor on
 * a page other people read: `javascript:` in an href is a script somebody else
 * runs by clicking what looks like a reference, and `data:` is a page of the
 * author's choosing wearing this workspace's address bar. Only http and https
 * go on an issue - everything else a browser knows how to follow is either that
 * hazard or something no reader could open anyway.
 */
object IssueLinks {

    /** As long as the column, which is long enough for what people paste. */
    private const val MAX_URL = 2000

    /** Long enough to name a page, short enough to sit on one line beside it. */
    const val MAX_TITLE = 200

    private val ALLOWED = setOf("http", "https")

    /**
     * The address as it will be stored, or the reason it will not be.
     *
     * Kept as typed rather than rebuilt from the parsed parts. A URI that is
     * put back together loses the things that make some addresses work - the
     * exact escaping of a query string, a fragment a single-page application
     * routes on - and an address that has been quietly rewritten is worse than
     * one that was refused.
     */
    fun clean(url: String): String {
        val given = url.trim()
        if (given.isEmpty()) throw IssueLinkInvalidException("A link needs an address")
        if (given.length > MAX_URL) throw IssueLinkInvalidException("That address is too long to keep")

        /*
         * The scheme read off the front, before anything is parsed.
         *
         * `data:text/html,<script>alert(1)</script>` is exactly what this check
         * exists to refuse, and it never reaches the parser: the angle brackets
         * are illegal in a URI, so it fails as unparseable and the person who
         * pasted it is told it is not a web address - which is not the reason,
         * and reads as a typo they might try to correct. What is wrong with it
         * is the scheme, so the scheme is looked at first.
         */
        declared(given)?.let { if (it !in ALLOWED) throw IssueLinkInvalidException("A link has to be http or https") }

        val parsed = try {
            URI(given)
        } catch (_: URISyntaxException) {
            throw IssueLinkInvalidException("That is not a web address")
        }

        val scheme = parsed.scheme?.lowercase()
            ?: throw IssueLinkInvalidException("That is not a web address")
        if (scheme !in ALLOWED) throw IssueLinkInvalidException("A link has to be http or https")
        /*
         * A host as well as a scheme. `http:///issues` parses happily and names
         * nowhere, and an anchor pointing at nowhere is a link that reads as
         * real until somebody clicks it.
         */
        if (parsed.host.isNullOrBlank()) throw IssueLinkInvalidException("That is not a web address")

        return given
    }

    /**
     * The scheme an address announces, or null where it announces none.
     *
     * By the grammar rather than by the colon alone: a letter, then letters,
     * digits and a few punctuation marks. Without that, `not a link` would be a
     * scheme called "not a link" and `example.com/x` one called
     * `example.com/x`, and both would be refused as the wrong protocol instead
     * of as what they are - something that is not an address at all.
     */
    private fun declared(url: String): String? {
        val head = url.substringBefore(':', missingDelimiterValue = "")
        if (head.isEmpty() || !head.first().isLetter()) return null
        if (!head.all { it.isLetterOrDigit() || it in "+-." }) return null
        return head.lowercase()
    }
}
