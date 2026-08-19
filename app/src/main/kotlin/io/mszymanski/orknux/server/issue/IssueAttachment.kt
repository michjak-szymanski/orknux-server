package io.mszymanski.orknux.server.issue

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/**
 * A file somebody attached to an issue, or to a comment on one.
 *
 * The same arrangement chat attachments already use, and deliberately so: the
 * row says what the file is and where it went, the bytes are wherever the
 * configured storage put them, and the limits and the switch that governs both
 * are the installation's rather than this tracker's. A bug report without the
 * screenshot is half a bug report, and a second way of storing files would be
 * a second place to get the disk wrong.
 *
 * The workspace is on the row rather than read back through the issue, because
 * it is what decides who may open it - and it is how the bytes are filed, which
 * is the promise the storage makes.
 */
@Entity
@Table(name = "workspace_issue_attachment")
class IssueAttachment(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /**
     * Whose it is, and so both who may open it and where on the disk it sits.
     *
     * It changes when an administrator moves the issue, together with the
     * location beneath - the two say the same thing twice and a row where they
     * disagreed would be a file filed under a workspace that cannot reach it.
     */
    @Column(name = "workspace_id", nullable = false)
    var workspaceId: Long,

    /**
     * The issue it belongs to; null while the issue is still being written.
     *
     * A file is uploaded as it is picked rather than when the form is saved, so
     * that a large screenshot travels while the report is still being typed -
     * which leaves a moment where the bytes exist and the issue does not.
     */
    @Column(name = "issue_id")
    var issueId: Long? = null,

    /** The comment it came with, or null when it is on the issue itself. */
    @Column(name = "comment_id")
    var commentId: Long? = null,

    /** What it was called on the machine it came from. */
    @Column(nullable = false, length = 255)
    val filename: String,

    @Column(name = "content_type", nullable = false, length = 120)
    val contentType: String,

    @Column(name = "size_bytes", nullable = false)
    val sizeBytes: Long,

    /**
     * Where the bytes are, as the storage that wrote them understands it.
     *
     * Never sent to a screen: it is the storage's business, and a path is the
     * sort of thing that invites somebody to ask for a different one.
     */
    @Column(name = "location", nullable = false, length = 1000)
    var location: String,

    @Column(name = "uploaded_at", nullable = false)
    val uploadedAt: OffsetDateTime = OffsetDateTime.now(),

    /** Whoever attached it, and so the only person who may take it off again. */
    @Column(name = "uploaded_by", nullable = false, length = 120)
    val uploadedBy: String = "",
)

interface IssueAttachmentRepository : JpaRepository<IssueAttachment, Long> {

    /**
     * Everything on one issue, its comments' files included.
     *
     * One query rather than one per comment: an issue with fifteen comments is
     * ordinary, and reading it should not be fifteen round trips to find that
     * fourteen of them brought nothing.
     */
    fun findByIssueIdOrderByUploadedAtAsc(issueId: Long): List<IssueAttachment>
}

class IssueAttachmentNotFoundException(id: Long) : RuntimeException("No attachment with id $id")

/**
 * Somebody tried to remove a file that is not theirs.
 *
 * The same rule as editing a comment, for the same reason: what somebody else
 * put on an issue is what they put on it, and a record anybody reading it could
 * quietly thin out is not a record. Administrators are not an exception either -
 * an administrator who needs the file gone can delete the issue, which is a
 * thing that shows.
 */
class IssueAttachmentNotYoursException :
    RuntimeException("An attachment can only be removed by whoever attached it")
