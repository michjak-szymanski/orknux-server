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
import java.time.OffsetDateTime

/**
 * Somebody who wants to hear about an issue without being given it.
 *
 * An issue's news reached exactly two audiences: whoever has it and whoever
 * filed it. That is the right pair for work somebody has been handed, and it is
 * nobody at all for work that has not - an assistant filing what it found,
 * assigned to no one because handing out work is not its judgement, produced a
 * tracker whose every comment was addressed to an empty room. An observer is
 * how somebody says "tell me about this one" without anybody having to be given
 * it.
 *
 * A person or an agent, and deliberately not a model. Observing is a statement
 * about who reads, and a model has nowhere to read: the news tools resolve a
 * caller to a person by their token and to an agent by name, and there is no
 * third door. A model is a thing work is given to, not a thing that asks what
 * happened.
 *
 * The issue is not nullable, for the same reason a link's is not: an observer
 * is chosen on an issue that already exists, so there is never a moment where
 * the row is here and the issue is not.
 */
@Entity
@Table(name = "workspace_issue_observer")
class IssueObserver(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "issue_id", nullable = false)
    val issueId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "observer_kind", nullable = false, length = 16)
    val kind: AssigneeKind,

    /**
     * The row it points at, not the name on it.
     *
     * Unlike the reporter beside it, which is kept as a name because who filed
     * an issue stays true after their row is gone. An observer is a live
     * subscription, and one pointing at somebody who is no longer here is a
     * subscription to nothing - so it is resolved on every read, and something
     * that has since been removed reads as gone rather than as a stale name.
     */
    @Column(name = "observer_id", nullable = false, length = 120)
    val observerId: String,

    @Column(name = "added_at", nullable = false)
    val addedAt: OffsetDateTime = OffsetDateTime.now(),

    /** Themselves, in the ordinary case, or the administrator who decided. */
    @Column(name = "added_by", nullable = false, length = 120)
    val addedBy: String = "",
)

interface IssueObserverRepository : JpaRepository<IssueObserver, Long> {

    /**
     * Oldest first, which is the order they arrived in and are read in.
     *
     * The id breaks the tie, because two observers added in the same instant
     * sort arbitrarily otherwise - and a list that reorders itself between two
     * reads of the same issue is a list nobody trusts.
     */
    fun findByIssueIdOrderByAddedAtAscIdAsc(issueId: Long): List<IssueObserver>

    fun findByIssueIdAndKindAndObserverId(issueId: Long, kind: AssigneeKind, observerId: String): IssueObserver?
}

/**
 * Somebody tried to have an issue watched by something that cannot watch.
 *
 * Said plainly, because the two ways to get here are different mistakes and
 * both are worth reporting now rather than storing: a model, which has nowhere
 * to read its news, or an id that names nothing in this workspace.
 */
class IssueObserverInvalidException(what: String) :
    RuntimeException("$what is not something in this workspace that can observe an issue")
