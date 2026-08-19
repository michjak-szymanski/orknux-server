package io.mszymanski.orknux.server.issue

import jakarta.persistence.CascadeType
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime

/**
 * Where an issue is in its life.
 *
 * The third one earns its place: "open" covers both nobody having looked at it
 * and somebody being halfway through, and those are the two things a person
 * scanning the list most needs told apart. Anything past three would be a
 * workflow this tracker does not have.
 */
enum class IssueStatus {
    OPEN,
    IN_PROGRESS,
    CLOSED,
}

/**
 * What kind of thing an issue is assigned to.
 *
 * A person is the obvious one and not the only one: this is a product where
 * the thing doing the work is often an agent, and "assigned to the responder
 * agent" is a sentence a workspace wants to write.
 */
enum class AssigneeKind {
    USER,
    AGENT,
    MODEL,
}

/**
 * Who is looking at an issue.
 *
 * A kind and an id rather than three nullable columns: what it points at
 * differs, that it points at exactly one thing does not. Resolved to a name
 * when the issue is read, so a renamed agent reads correctly afterwards.
 */
@jakarta.persistence.Embeddable
class Assignee(
    @Enumerated(EnumType.STRING)
    @Column(name = "assignee_kind", length = 16)
    var kind: AssigneeKind? = null,

    @Column(name = "assignee_id", length = 120)
    var id: String? = null,
)

@Entity
@Table(name = "workspace_issue")
class Issue(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    /** Its number in this workspace: what "#3" means, and what people say. */
    @Column(nullable = false)
    val number: Int,

    @Column(nullable = false, length = 200)
    var title: String,

    @Column(columnDefinition = "text")
    var description: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: IssueStatus = IssueStatus.OPEN,

    /** The username that filed it, kept as a name so it survives them. */
    @Column(nullable = false, length = 120)
    val reporter: String,

    /*
     * Null when nobody is looking at it - which is Hibernate's own answer:
     * an embeddable whose every column is null comes back as null, so the
     * field says so rather than being surprised by it.
     */
    @jakarta.persistence.Embedded
    var assignee: Assignee? = null,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "workspace_issue_label", joinColumns = [JoinColumn(name = "issue_id")])
    @Column(name = "label", nullable = false, length = 60)
    var labels: MutableSet<String> = mutableSetOf(),

    /*
     * Owned by the issue, and the column says so.
     *
     * `nullable = false` is what makes Hibernate write the issue's id in the
     * insert rather than inserting the comment with a null and updating it a
     * moment later - which the not-null column refuses, as it should.
     */
    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "issue_id", nullable = false)
    @OrderBy("createdAt asc")
    var comments: MutableList<IssueComment> = mutableListOf(),

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_at", nullable = false)
    var lastModifiedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_by", nullable = false, length = 120)
    var lastModifiedBy: String = "system",

    /**
     * When somebody last said something here, or null if nobody has.
     *
     * Not the same as [lastModifiedAt], which closing, relabelling or assigning
     * all move - so a list sorted by that puts the housekeeping at the top.
     * Somebody scanning for where the talking is wants this one.
     */
    @Column(name = "last_comment_at")
    var lastCommentAt: OffsetDateTime? = null,
)

@Entity
@Table(name = "workspace_issue_comment")
class IssueComment(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, length = 120)
    val author: String,

    @Column(nullable = false, columnDefinition = "text")
    var content: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    /** When it was last changed, or null if it never was. */
    @Column(name = "edited_at")
    var editedAt: OffsetDateTime? = null,
)

interface IssueRepository : JpaRepository<Issue, Long> {

    fun findByWorkspaceIdAndNumber(workspaceId: Long, number: Int): Issue?

    /** The highest number used here, so the next one follows it. */
    @Query("select coalesce(max(i.number), 0) from Issue i where i.workspaceId = :workspaceId")
    fun lastNumber(workspaceId: Long): Int

    /**
     * The list, filtered the way the page asks.
     *
     * One search reads the title, the description and the labels together -
     * somebody typing "slack" means any of the three, and asking them which
     * field they meant is asking them to know the schema. An empty search
     * matches everything, which is what makes this one query rather than two.
     *
     * The labels are asked about with `exists` rather than joined. A join
     * multiplies an issue by its labels and needs `distinct` to undo it, and
     * Postgres will not order a distinct select by an expression that is not in
     * the select list - so `order by lower(title)` failed outright the moment
     * sorting by name was offered.
     *
     * The search is never null. Postgres cannot type a null parameter inside
     * `lower()` - it guesses bytea and refuses the function - so "no filter" is
     * the empty string rather than a null. Status is the same story told the
     * other way: rather than a nullable parameter, there are two methods, and
     * the caller picks.
     */
    @Query(
        """
        select i from Issue i
        where i.workspaceId = :workspaceId
          and (
            :search = ''
            or lower(i.title) like lower(concat('%', :search, '%'))
            or lower(coalesce(i.description, '')) like lower(concat('%', :search, '%'))
            or exists (
              select 1 from Issue held join held.labels l
              where held = i and lower(l) like lower(concat('%', :search, '%'))
            )
          )
        """,
        countQuery = """
        select count(i) from Issue i
        where i.workspaceId = :workspaceId
          and (
            :search = ''
            or lower(i.title) like lower(concat('%', :search, '%'))
            or lower(coalesce(i.description, '')) like lower(concat('%', :search, '%'))
            or exists (
              select 1 from Issue held join held.labels l
              where held = i and lower(l) like lower(concat('%', :search, '%'))
            )
          )
        """,
    )
    fun search(workspaceId: Long, search: String, pageable: Pageable): Page<Issue>

    @Query(
        """
        select i from Issue i
        where i.workspaceId = :workspaceId
          and i.status = :status
          and (
            :search = ''
            or lower(i.title) like lower(concat('%', :search, '%'))
            or lower(coalesce(i.description, '')) like lower(concat('%', :search, '%'))
            or exists (
              select 1 from Issue held join held.labels l
              where held = i and lower(l) like lower(concat('%', :search, '%'))
            )
          )
        """,
        countQuery = """
        select count(i) from Issue i
        where i.workspaceId = :workspaceId
          and i.status = :status
          and (
            :search = ''
            or lower(i.title) like lower(concat('%', :search, '%'))
            or lower(coalesce(i.description, '')) like lower(concat('%', :search, '%'))
            or exists (
              select 1 from Issue held join held.labels l
              where held = i and lower(l) like lower(concat('%', :search, '%'))
            )
          )
        """,
    )
    fun searchByStatus(workspaceId: Long, status: IssueStatus, search: String, pageable: Pageable): Page<Issue>

    /** Every label in use here, for the filter to offer. */
    @Query("select distinct l from Issue i join i.labels l where i.workspaceId = :workspaceId order by l")
    fun labelsIn(workspaceId: Long): List<String>
}

class IssueNotFoundException(id: Long) : RuntimeException("No issue with id $id")

class IssueTitleInvalidException : RuntimeException("An issue needs a title")

class IssueCommentEmptyException : RuntimeException("A comment needs something in it")

class IssueCommentNotFoundException(id: Long) : RuntimeException("No comment with id $id")

/**
 * Somebody tried to edit a comment that is not theirs.
 *
 * Said plainly because it is not a permission that can be granted: what
 * somebody else wrote is what they wrote, and an issue whose history could be
 * rewritten by anybody reading it is not a record of anything.
 */
class IssueCommentNotYoursException :
    RuntimeException("A comment can only be edited by whoever wrote it")

class IssueAssigneeInvalidException(what: String) :
    RuntimeException("$what is not something in this workspace to assign an issue to")
