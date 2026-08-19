package io.mszymanski.orknux.server.issue

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.io.Serializable
import java.time.OffsetDateTime

/** What happened to an issue, in the three kinds worth interrupting somebody for. */
enum class IssueNewsKind {
    /** It was filed, and you are one of the people it concerns. */
    OPENED,

    /** It was given to you, or taken away from you. */
    ASSIGNED,

    /** It was closed or reopened. */
    STATUS,

    /** Somebody said something on it. */
    COMMENT,

    /** Somebody wrote your name in a comment. */
    MENTIONED,

    /**
     * You are now hearing about this one.
     *
     * Its own kind rather than silence, because being made an observer is the
     * moment the issue first concerns you and everything after it arrives
     * without explanation otherwise. It covers both ways it happens - an issue
     * filed naming you, and somebody adding you to one that already existed -
     * since what the reader needs to know is the same either way: this exists,
     * and from here on you will hear about it.
     */
    OBSERVING,
}

/**
 * One thing that happened on one issue, addressed to one audience.
 *
 * The tracker records everything in the issue itself; this is the same
 * happening written down again for somebody who is not looking at the page. An
 * assistant working a queue cannot learn that a comment arrived except by being
 * told, and being told is what a person should not have to do by hand.
 *
 * Who it was for is settled when it happens and never recomputed. An issue
 * reassigned tomorrow does not rewrite who should have heard about it today.
 */
@Entity
@Table(name = "issue_news")
class IssueNewsItem(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(name = "issue_id", nullable = false)
    val issueId: Long,

    @Column(name = "issue_number", nullable = false)
    val issueNumber: Int,

    @Column(name = "issue_title", nullable = false, length = 200)
    val issueTitle: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val kind: IssueNewsKind,

    /** Who did it, as a name — never the audience, or this would be an echo. */
    @Column(nullable = false, length = 120)
    val actor: String,

    /** The new status, or what was said. Nothing to add for an assignment. */
    @Column(columnDefinition = "text")
    val says: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "audience_kind", nullable = false, length = 16)
    val audienceKind: AssigneeKind,

    @Column(name = "audience_id", length = 120)
    val audienceId: String? = null,

    @Column(name = "audience_name", nullable = false, length = 120)
    val audienceName: String,

    @Column(nullable = false)
    val at: OffsetDateTime = OffsetDateTime.now(),
)

interface IssueNewsRepository : JpaRepository<IssueNewsItem, Long> {

    /**
     * What one audience has not read yet, oldest first.
     *
     * By name rather than by id, and case-insensitively, because the two ends
     * of this know a person differently: a token knows the username it was
     * minted for, an issue knows the row it points at. The name is the thing
     * both can say.
     */
    @Query(
        "select n from IssueNewsItem n where n.workspaceId = :workspaceId " +
            "and n.audienceKind = :kind and lower(n.audienceName) = lower(:name) " +
            "and n.id > :after order by n.id asc",
    )
    fun since(
        @Param("workspaceId") workspaceId: Long,
        @Param("kind") kind: AssigneeKind,
        @Param("name") name: String,
        @Param("after") after: Long,
    ): List<IssueNewsItem>

    /**
     * The same audience's news whether or not they have read it, newest first.
     *
     * [since] answers "what is new", which is what a number on a bell is. This
     * answers "what happened", which is what the panel behind the bell is - and
     * they are not the same question. Reading with [since] alone meant the panel
     * emptied itself the moment it was opened, because opening it is what marks
     * the news read, so it could only ever show something to somebody who had
     * not looked yet.
     */
    @Query(
        "select n from IssueNewsItem n where n.workspaceId = :workspaceId " +
            "and n.audienceKind = :kind and lower(n.audienceName) = lower(:name) " +
            "order by n.id desc",
    )
    fun latest(
        @Param("workspaceId") workspaceId: Long,
        @Param("kind") kind: AssigneeKind,
        @Param("name") name: String,
    ): List<IssueNewsItem>
}

/** Who has read how far. */
class IssueNewsReadKey(
    val workspaceId: Long = 0,
    val readerKind: AssigneeKind? = null,
    val readerName: String = "",
) : Serializable

/**
 * How far through the news one reader has got.
 *
 * Kept here rather than handed back for the caller to remember: an assistant
 * restarted between sessions remembers nothing, and a cursor it forgets means
 * either a week of events repeated or a day of them silently skipped.
 */
@Entity
@Table(name = "issue_news_read")
@IdClass(IssueNewsReadKey::class)
class IssueNewsRead(
    @Id
    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "reader_kind", nullable = false, length = 16)
    val readerKind: AssigneeKind,

    @Id
    @Column(name = "reader_name", nullable = false, length = 120)
    val readerName: String,

    @Column(name = "last_id", nullable = false)
    var lastId: Long,

    @Column(nullable = false)
    var at: OffsetDateTime = OffsetDateTime.now(),
)

interface IssueNewsReadRepository : JpaRepository<IssueNewsRead, IssueNewsReadKey> {

    @Query(
        "select r from IssueNewsRead r where r.workspaceId = :workspaceId " +
            "and r.readerKind = :kind and lower(r.readerName) = lower(:name)",
    )
    fun forReader(
        @Param("workspaceId") workspaceId: Long,
        @Param("kind") kind: AssigneeKind,
        @Param("name") name: String,
    ): IssueNewsRead?
}
