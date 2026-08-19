package io.mszymanski.orknux.server.issue

import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.user.AppUserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** An audience for news: what it is, and the name both ends can say. */
data class NewsReader(val kind: AssigneeKind, val name: String, val id: String? = null)

/**
 * The tracker's news desk: who should hear what, and how to wait for it.
 *
 * Both doors into the tracker - the interface and the MCP tools - come through
 * here when they change something, so an issue commented on from a browser and
 * one commented on by an agent are heard about identically.
 *
 * The audience is settled at the moment it happens rather than worked out when
 * somebody reads. Who should have been told about a comment yesterday is a fact
 * about yesterday; an issue handed to somebody else this morning does not
 * rewrite it.
 */
@Service
class IssueNewsDesk(
    private val news: IssueNewsRepository,
    private val reads: IssueNewsReadRepository,
    private val users: AppUserRepository,
    private val agents: AgentRepository,
    private val models: ModelService,
    private val observers: IssueObserverRepository,
) {

    /*
     * Everybody waiting, woken together.
     *
     * One list for the whole server rather than one per workspace: a waiter
     * woken by somebody else's news asks its own question again and goes back
     * to waiting, which costs a query nobody notices, and the bookkeeping of a
     * map costs more than it saves at this size.
     *
     * A promise rather than a lock, because a waiter is a call somebody is
     * holding open and a lock would mean a thread held open with it. Nothing
     * here sleeps; the promise is kept and whoever was waiting on it carries on
     * wherever they choose to.
     */
    private val bells = ConcurrentHashMap.newKeySet<CompletableFuture<Boolean>>()

    /**
     * It was filed. Everybody it concerns hears, except the person it was
     * handed to.
     *
     * Two calls rather than one, and the assignee's exclusion here is why:
     * "assigned to you" is a more useful sentence than "opened" for the one
     * person expected to do something about it, so [assigned] still says that
     * and this says the other thing to everybody else. [write] drops the actor,
     * so whoever filed it is not told about their own doing.
     *
     * Before this, filing an issue told the assignee and nobody else - so an
     * assistant opening an issue and naming the people who should know wrote
     * into an empty room, which is the whole point of having observers.
     */
    @Transactional
    fun opened(issue: Issue, actor: String) {
        val holder = audienceOf(issue)
        val told = watchers(issue).filterNot { reader ->
            holder != null && reader.kind == holder.kind && reader.name.equals(holder.name, ignoreCase = true)
        }
        write(issue, IssueNewsKind.OPENED, actor, says = null, to = told)
    }

    /** It was given to somebody. Only the new owner is told. */
    @Transactional
    fun assigned(issue: Issue, actor: String) {
        val audience = audienceOf(issue) ?: return
        write(issue, IssueNewsKind.ASSIGNED, actor, says = null, to = listOf(audience))
    }

    /** It was closed or reopened. Whoever has it, and whoever filed it. */
    @Transactional
    fun statusChanged(issue: Issue, actor: String) {
        write(issue, IssueNewsKind.STATUS, actor, says = issue.status.name, to = watchers(issue))
    }

    /** Somebody said something. The same audience, and the words with it. */
    @Transactional
    fun commented(issue: Issue, actor: String, said: String) {
        write(issue, IssueNewsKind.COMMENT, actor, says = said, to = watchers(issue))
        // A name written into a comment is addressed to that person whether or
        // not they have anything to do with the issue, which is the whole point
        // of writing it - so it is its own event, and it reaches somebody the
        // watcher list never would.
        write(issue, IssueNewsKind.MENTIONED, actor, says = said, to = mentioned(said))
    }

    /**
     * Whoever was named in a comment.
     *
     * Mentions are stored as the text somebody typed - `@Support responder` -
     * rather than as a marker holding an id, so finding them means matching
     * names against what exists. Longest first, or `@Ann` would match inside
     * `@Anna` and tell the wrong person.
     */
    private fun mentioned(said: String): List<NewsReader> {
        if (!said.contains('@')) return emptyList()
        val found = mutableListOf<NewsReader>()

        val people = users.findAll().sortedByDescending { it.displayName.length }
        for (person in people) {
            val name = person.displayName
            if (said.contains("@$name", ignoreCase = true)) found += NewsReader(AssigneeKind.USER, person.username)
        }
        return found
    }

    /**
     * What is waiting, without saying it has been seen.
     *
     * The bell needs both: a number it can show, and a separate moment when
     * somebody looks. Reading that marked read would make the number clear
     * itself the instant it was asked for, which is a number nobody ever sees.
     */
    @Transactional(readOnly = true)
    fun waiting(workspaceId: Long, reader: NewsReader): List<IssueNewsItem> {
        val mark = reads.forReader(workspaceId, reader.kind, reader.name)
        return news.since(workspaceId, reader.kind, reader.name, mark?.lastId ?: 0)
    }

    /**
     * What this reader has not read, and how far they have now got.
     *
     * Reading marks it read. An assistant that had to say so afterwards would
     * lose everything it was told whenever it was interrupted between the two
     * calls, which is precisely when being told mattered.
     */
    @Transactional
    fun unread(workspaceId: Long, reader: NewsReader, limit: Int): List<IssueNewsItem> {
        val mark = reads.forReader(workspaceId, reader.kind, reader.name)
        val waiting = news.since(workspaceId, reader.kind, reader.name, mark?.lastId ?: 0)
        if (waiting.isEmpty()) return emptyList()

        val told = waiting.take(limit)
        val furthest = told.last().id ?: return told
        if (mark == null) {
            reads.save(IssueNewsRead(workspaceId, reader.kind, reader.name, furthest))
        } else {
            mark.lastId = furthest
            mark.at = OffsetDateTime.now()
            reads.save(mark)
        }
        return told
    }

    /**
     * A promise kept the moment anything is written down.
     *
     * Taken before looking, so news written between the look and the wait rings
     * this rather than falling into the gap between them. The caller completes
     * it themselves when they give up or when their own time is out, and doing
     * so takes it off the list - so a reader that walked away leaves nothing
     * behind.
     *
     * Whatever the caller hangs off it should be hung asynchronously. This is
     * kept from inside `afterCommit` on the thread that wrote the news, and
     * that thread has a request of its own to finish.
     */
    fun nextNews(): CompletableFuture<Boolean> {
        val bell = CompletableFuture<Boolean>()
        bells += bell
        bell.whenComplete { _, _ -> bells.remove(bell) }
        return bell
    }

    private fun write(
        issue: Issue,
        kind: IssueNewsKind,
        actor: String,
        says: String?,
        to: List<NewsReader>,
    ) {
        val issueId = issue.id ?: return
        val told = to.filter { !it.name.equals(actor, ignoreCase = true) }.distinctBy { it.kind to it.name.lowercase() }
        if (told.isEmpty()) return

        news.saveAll(
            told.map { reader ->
                IssueNewsItem(
                    workspaceId = issue.workspaceId,
                    issueId = issueId,
                    issueNumber = issue.number,
                    issueTitle = issue.title,
                    kind = kind,
                    actor = actor,
                    says = says,
                    audienceKind = reader.kind,
                    audienceName = reader.name,
                    audienceId = reader.id,
                )
            },
        )
        wake()
    }

    /**
     * Waking is left until the write is actually there.
     *
     * A waiter woken inside the transaction asks its question, is told nothing
     * has happened, and goes back to sleep for the rest of its timeout - with
     * the news committed a millisecond later and nobody listening.
     */
    private fun wake() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            signal()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = signal()
            },
        )
    }

    /**
     * Copied out before it is walked: keeping a promise takes it off the list,
     * and a set being read while it shrinks is not something to rely on.
     */
    private fun signal() {
        bells.toList().forEach { it.complete(true) }
    }

    /**
     * Somebody now hears about this one, and is told so.
     *
     * Sent to whoever was just added rather than to the issue's audience: the
     * rest of the room already knows the issue exists, and this says nothing
     * about the issue. [write] drops the actor, so adding yourself is silent -
     * which is right, since you were looking at the page when you did it.
     */
    @Transactional
    fun observing(issue: Issue, actor: String, told: List<NewsReader>) {
        write(issue, IssueNewsKind.OBSERVING, actor, says = null, to = told)
    }

    /**
     * Everybody an issue concerns: whoever has it, whoever filed it, and
     * whoever asked to hear.
     *
     * The three are unioned rather than kept apart, and [write] drops the
     * duplicates - so somebody who filed an issue and then observed it, or was
     * observing before it was handed to them, is told once. Nothing here reads
     * the status: an issue that has been closed is exactly the issue an
     * observer wants to hear about when it is reopened, and a subscription that
     * lapsed the moment the work looked finished would go quiet just before the
     * one event worth having it for.
     */
    private fun watchers(issue: Issue): List<NewsReader> =
        listOfNotNull(audienceOf(issue), NewsReader(AssigneeKind.USER, issue.reporter)) + observersOf(issue)

    /**
     * Whoever asked to hear about this one, as names the news can be addressed
     * to.
     *
     * Resolved on every read, like the assignee: an observer whose row has gone
     * is dropped rather than written to, since news addressed to a name nobody
     * can say is news nobody reads. A person is named by their username and not
     * by the name on their card, so a reporter who is also an observer collapses
     * to one reader.
     */
    private fun observersOf(issue: Issue): List<NewsReader> {
        val id = issue.id ?: return emptyList()
        return observers.findByIssueIdOrderByAddedAtAscIdAsc(id).mapNotNull { watching ->
            val name = when (watching.kind) {
                AssigneeKind.USER -> users.findByIdOrNull(watching.observerId.toLongOrNull() ?: -1)?.username
                AssigneeKind.AGENT -> agents.findByIdOrNull(watching.observerId.toLongOrNull() ?: -1)?.name
                // Never stored, and refused on the way in - but an enum has to
                // be answered exhaustively, and "nobody" is the honest answer.
                AssigneeKind.MODEL -> null
            } ?: return@mapNotNull null
            NewsReader(watching.kind, name, watching.observerId)
        }
    }

    /**
     * The assignee as something that can be matched against a caller.
     *
     * A person is named by their username rather than by the name on their
     * card: a token knows the username it was minted for, and one name both
     * ends can say is worth more here than the prettier one.
     */
    private fun audienceOf(issue: Issue): NewsReader? {
        val held = issue.assignee ?: return null
        val kind = held.kind ?: return null
        val id = held.id ?: return null
        val name = when (kind) {
            AssigneeKind.USER -> users.findByIdOrNull(id.toLongOrNull() ?: -1)?.username
            AssigneeKind.AGENT -> agents.findByIdOrNull(id.toLongOrNull() ?: -1)?.name
            AssigneeKind.MODEL -> models.models(issue.workspaceId).firstOrNull { it.id.toString() == id }?.name
        } ?: return null
        return NewsReader(kind, name, id)
    }
}
