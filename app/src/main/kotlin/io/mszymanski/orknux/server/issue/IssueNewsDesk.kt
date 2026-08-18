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
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

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
) {

    /*
     * Everybody waiting, woken together.
     *
     * One condition for the whole server rather than one per workspace: a
     * waiter that wakes for somebody else's news asks its own question again
     * and goes back to sleep, which costs a query nobody notices, and the
     * bookkeeping of a map of locks costs more than it saves at this size.
     */
    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()

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
     * Sleep until something is written down, or until the time is up.
     *
     * @return whether anything woke it, which the caller uses only to decide
     *   whether asking again is worth it.
     */
    fun awaitNews(millis: Long): Boolean {
        lock.lock()
        try {
            return arrived.await(millis, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } finally {
            lock.unlock()
        }
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

    private fun signal() {
        lock.lock()
        try {
            arrived.signalAll()
        } finally {
            lock.unlock()
        }
    }

    /** Whoever has it and whoever filed it: the two people an issue concerns. */
    private fun watchers(issue: Issue): List<NewsReader> =
        listOfNotNull(audienceOf(issue), NewsReader(AssigneeKind.USER, issue.reporter))

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
