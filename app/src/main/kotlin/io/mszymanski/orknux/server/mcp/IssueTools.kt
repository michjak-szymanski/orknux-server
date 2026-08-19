package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.issue.AssigneeKind
import io.mszymanski.orknux.server.issue.Issue
import io.mszymanski.orknux.server.issue.IssueComment
import io.mszymanski.orknux.server.issue.IssueHistoryRecorder
import io.mszymanski.orknux.server.issue.IssueNewsDesk
import io.mszymanski.orknux.server.issue.IssueObserver
import io.mszymanski.orknux.server.issue.IssueObserverRepository
import io.mszymanski.orknux.server.issue.IssueRepository
import io.mszymanski.orknux.server.issue.IssueStatus
import io.mszymanski.orknux.server.issue.NewsReader
import io.mszymanski.orknux.server.security.WebProperties
import io.mszymanski.orknux.server.user.AppUserRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * The tracker, as something an assistant can work rather than read about.
 *
 * Its own service for a reason that is not tidiness: writing on an issue
 * touches its comments, which are lazy, so those calls need a transaction
 * around them - and a private method calling another private method inside one
 * class never gets one. Here each is a bean method, and each says whether it
 * writes.
 *
 * Everything is addressed by the number people say. "#4" is what the page
 * shows, what somebody types in a message, and what the address carries.
 */
@Service
class IssueTools(
    private val issues: IssueRepository,
    private val users: AppUserRepository,
    private val agents: AgentRepository,
    private val newsDesk: IssueNewsDesk,
    private val history: IssueHistoryRecorder,
    private val observers: IssueObserverRepository,
    private val models: ModelService,
    private val web: WebProperties,
    private val mapper: ObjectMapper,
) {

    /** Where an issue can be opened, sent back with the issue itself. */
    private fun issueLink(workspaceId: Long, number: Int): String {
        val base = web.allowedOrigins.firstOrNull()?.trimEnd('/').orEmpty()
        return "$base/workspace/$workspaceId/issues/$number"
    }

    /** Says what went wrong in the shape every other tool answers in. */
    private fun refuse(reason: String): String = mapper.writeValueAsString(mapOf("error" to reason))

    private fun text(arguments: String, name: String): String? = runCatching {
        mapper.readTree(arguments).path(name).stringValue()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun number(arguments: String, name: String): Long? = runCatching {
        val node = mapper.readTree(arguments).path(name)
        if (node.isNumber) node.asLong() else node.stringValue()?.trim()?.toLongOrNull()
    }.getOrNull()

    private companion object {
        /** One page, big enough that a workspace's whole tracker fits in it. */
        const val MANY = 200
    }

    /**
     * The tracker as a list, filtered the way somebody would ask for it.
     *
     * "Assigned to me" is the question this exists for, and it is asked by
     * name rather than by id: an assistant knows it is called Claude, not that
     * it is user 5. The name is matched against what the assignee resolves to,
     * so a person and an agent are found the same way.
     */
    @Transactional(readOnly = true)
    fun list(scope: OrknuxScope, arguments: String): String {
        val wanted = text(arguments, "status")?.let { asked ->
            IssueStatus.entries.firstOrNull { it.name.equals(asked, ignoreCase = true) }
                ?: return refuse("There is no issue status called $asked")
        }
        val assignee = text(arguments, "assignee")?.lowercase()
        val search = text(arguments, "search").orEmpty()
        /*
         * Every one of them, not any: "p1, slack" asks for the urgent Slack
         * issues, not for everything urgent and everything about Slack.
         */
        val wantedLabels = text(arguments, "labels")
            ?.split(',')
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val newestFirst = PageRequest.of(0, MANY, Sort.by("number").descending())

        val page = if (wanted == null) {
            issues.search(scope.workspaceId, search, newestFirst)
        } else {
            issues.searchByStatus(scope.workspaceId, wanted, search, newestFirst)
        }

        val found = page.content.filter { held ->
            (assignee == null || nameOf(held)?.lowercase()?.contains(assignee) == true) &&
                wantedLabels.all { wanted -> held.labels.any { it.equals(wanted, ignoreCase = true) } }
        }

        return mapper.writeValueAsString(
            mapOf(
                "issues" to found.map {
                    mapOf(
                        "number" to it.number,
                        "title" to it.title,
                        "status" to it.status,
                        "reporter" to it.reporter,
                        "assignee" to nameOf(it),
                        "labels" to it.labels.sorted(),
                        "url" to issueLink(scope.workspaceId, it.number),
                    )
                },
            ),
        )
    }

    /**
     * The labels this workspace uses, with how many issues carry each.
     *
     * Worth its own tool rather than a note in the list: a label only works as
     * a filter if you know it exists, and "p1" is not something anybody can
     * guess from the outside.
     */
    @Transactional(readOnly = true)
    fun labels(scope: OrknuxScope): String {
        val all = issues.search(scope.workspaceId, "", PageRequest.of(0, MANY)).content
        val counted = all.flatMap { it.labels }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        return mapper.writeValueAsString(
            mapOf("labels" to counted.map { (label, count) -> mapOf("label" to label, "issues" to count) }),
        )
    }

    @Transactional(readOnly = true)
    fun one(scope: OrknuxScope, arguments: String): String {
        val held = issueIn(scope, arguments) ?: return refuse("Which issue? Give its number.")
        return mapper.writeValueAsString(
            mapOf(
                "number" to held.number,
                "title" to held.title,
                "description" to held.description,
                "status" to held.status,
                "reporter" to held.reporter,
                "assignee" to nameOf(held),
                // Worth reading before writing a comment: it says who the next
                // thing said here will actually reach.
                "observers" to observerNames(held),
                "labels" to held.labels.sorted(),
                "comments" to held.comments.map {
                    mapOf("author" to it.author, "said" to it.content, "at" to it.createdAt.toString())
                },
                "url" to issueLink(scope.workspaceId, held.number),
            ),
        )
    }

    /**
     * Opening one.
     *
     * The tools could read, comment, relabel and close, and not file - so an
     * assistant that found something had to describe it in a conversation and
     * hope somebody wrote it down. Which is the failure the tracker exists to
     * prevent, one layer up.
     *
     * Filed under whoever is asking, and assigned to nobody by default:
     * deciding who should look at a thing is somebody else's judgement, and an
     * assistant that assigned its own findings to a person would be handing
     * out work.
     *
     * Observed by somebody, though, and that is the other half of the same
     * thought. Assigning is handing out work; observing is saying "this exists,
     * you should see it", which is precisely what an assistant that has found
     * something is entitled to say. An issue filed with neither reached an
     * audience of one - the assistant itself, whose own doing is not news to it
     * - so ten careful reports about security went to nobody at all, and the
     * silence was how anybody found out.
     */
    @Transactional
    fun open(scope: OrknuxScope, arguments: String): String {
        if (!scope.mayWrite) return refuse("This conversation may read issues, but not open them")
        val title = text(arguments, "title") ?: return refuse("What should the issue be called?")

        val named = text(arguments, "observers")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val found = named.map { it to observerNamed(scope, it) }
        val missing = found.filter { it.second == null }.map { it.first }
        /*
         * Names that match nothing are reported rather than filed around. A
         * report that says it went to somebody who was never told is worse than
         * one that says it went nowhere, because only the second gets fixed.
         */
        if (missing.isNotEmpty()) {
            return refuse("There is nobody called ${missing.joinToString(", ")} in this workspace to observe an issue")
        }
        val watching = found.mapNotNull { it.second }.ifEmpty { administrators() }

        val made = issues.save(
            Issue(
                workspaceId = scope.workspaceId,
                number = issues.lastNumber(scope.workspaceId) + 1,
                title = title.trim(),
                description = text(arguments, "description")?.trim(),
                reporter = currentUser(),
                labels = text(arguments, "labels")
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.toMutableSet()
                    ?: mutableSetOf(),
                lastModifiedBy = currentUser(),
            ),
        )
        watching.forEach {
            observers.save(
                IssueObserver(
                    issueId = requireNotNull(made.id),
                    kind = it.kind,
                    observerId = requireNotNull(it.id),
                    addedBy = currentUser(),
                ),
            )
        }
        // Opened rather than observing: at creation the news worth having is
        // that the issue exists. "You are now an observer" is the right sentence
        // for somebody added to an issue that was already there, and that is where
        // it still gets said.
        newsDesk.opened(made, currentUser())

        return mapper.writeValueAsString(
            mapOf(
                "issue" to made.number,
                "title" to made.title,
                // Said back plainly, so a report that reached nobody reads as
                // one that reached nobody rather than as a success.
                "observers" to watching.map { it.name },
                "url" to issueLink(scope.workspaceId, made.number),
            ),
        )
    }

    /**
     * Somebody in this workspace by the name an assistant would write.
     *
     * People by username or by the name on their card, agents by their own -
     * one lookup over both, the way the assignee box searches both, because
     * "put Michal on this" does not come with a kind attached. Never a model:
     * observing is a statement about who reads, and nothing reads a model's
     * news.
     */
    private fun observerNamed(scope: OrknuxScope, name: String): NewsReader? {
        val person = users.findAll().firstOrNull {
            it.username.equals(name, ignoreCase = true) || it.displayName.equals(name, ignoreCase = true)
        }
        if (person != null) return NewsReader(AssigneeKind.USER, person.username, requireNotNull(person.id).toString())

        val agent = agents.findNamed(scope.workspaceId, name) ?: return null
        return NewsReader(AssigneeKind.AGENT, agent.name, requireNotNull(agent.id).toString())
    }

    /**
     * Who hears about a finding nobody was named for.
     *
     * The argument against a default is the honest one: a subscription nobody
     * asked for is a subscription somebody has to go and cancel, and a machine
     * guessing who cares is a machine deciding for people. The argument for it
     * is what actually happened. An assistant filed ten security issues,
     * assigned them to nobody because handing out work is not its judgement,
     * wrote carefully on each, and told no one who could act - and the way that
     * came to light was somebody noticing the silence. A default that can be
     * overridden by naming anybody costs an administrator one click to undo;
     * having no default cost a fortnight of reports nobody read.
     *
     * Whoever is asking is left out. Observing an issue you filed yourself
     * subscribes you to your own doing, which the news desk drops anyway.
     */
    private fun administrators(): List<NewsReader> =
        users.findAll()
            .filter { person -> person.roles.any { it.administers } }
            .filterNot { it.username.equals(currentUser(), ignoreCase = true) }
            .map { NewsReader(AssigneeKind.USER, it.username, requireNotNull(it.id).toString()) }

    /**
     * Saying something on an issue, under whoever is asking.
     *
     * A token carries its owner, so a comment written through this is signed
     * by a real name and lands where everybody else is already reading -
     * rather than in a chat window nobody else can see.
     */
    @Transactional
    fun comment(scope: OrknuxScope, arguments: String): String {
        if (!scope.mayWrite) return refuse("This conversation may read issues, but not write on them")
        val held = issueIn(scope, arguments) ?: return refuse("Which issue? Give its number.")
        val said = text(arguments, "content") ?: return refuse("What should the comment say?")

        held.comments.add(IssueComment(author = currentUser(), content = said.trim()))
        held.lastCommentAt = OffsetDateTime.now()
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = currentUser()
        issues.save(held)
        newsDesk.commented(held, currentUser(), said.trim())
        return mapper.writeValueAsString(
            mapOf("said" to true, "issue" to held.number, "url" to issueLink(scope.workspaceId, held.number)),
        )
    }

    @Transactional
    fun setStatus(scope: OrknuxScope, arguments: String): String {
        if (!scope.mayWrite) return refuse("This conversation may read issues, but not change them")
        val held = issueIn(scope, arguments) ?: return refuse("Which issue? Give its number.")
        val asked = text(arguments, "status") ?: return refuse("Open or closed?")
        val wanted = IssueStatus.entries.firstOrNull { it.name.equals(asked, ignoreCase = true) }
            ?: return refuse("There is no issue status called $asked")

        val was = held.status
        val moved = held.status != wanted
        held.status = wanted
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = currentUser()
        issues.save(held)
        if (moved) newsDesk.statusChanged(held, currentUser())
        // Written here as well as in the controller, because this is the other
        // door into the tracker and a history with a hole exactly where the
        // agents worked is worse than no history at all.
        history.statusChanged(held, was, wanted, currentUser())
        return mapper.writeValueAsString(mapOf("issue" to held.number, "status" to wanted))
    }

    /**
     * Title, description and labels, each left alone unless given.
     *
     * Labels arrive as one comma-separated string rather than an array,
     * because "slack, timing" is what a model actually writes and insisting on
     * JSON here buys nothing.
     */
    @Transactional
    fun update(scope: OrknuxScope, arguments: String): String {
        if (!scope.mayWrite) return refuse("This conversation may read issues, but not change them")
        val held = issueIn(scope, arguments) ?: return refuse("Which issue? Give its number.")

        // Copied rather than aliased: the three ways labels change below all
        // work on the set the entity holds, and a reference to it would compare
        // the result with itself.
        val labelsWere = held.labels.toSet()
        text(arguments, "title")?.let { held.title = it.trim() }
        text(arguments, "description")?.let { given ->
            held.description = given.trim().takeIf { it.isNotEmpty() }
        }
        text(arguments, "labels")?.let { given ->
            held.labels = given.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        }
        /*
         * Adding one and taking one away, beside replacing them all.
         *
         * Replacing was the only way, which meant reading the labels, adding
         * yours to them and writing all of them back - three steps in which
         * somebody else's label can be lost, and it will be the one that
         * mattered.
         */
        text(arguments, "add_labels")?.let { given ->
            given.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { wanted ->
                if (held.labels.none { it.equals(wanted, ignoreCase = true) }) held.labels.add(wanted)
            }
        }
        text(arguments, "remove_labels")?.let { given ->
            given.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { unwanted ->
                held.labels.removeIf { it.equals(unwanted, ignoreCase = true) }
            }
        }
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = currentUser()
        issues.save(held)
        history.labelsChanged(held, labelsWere, held.labels.toSet(), currentUser())
        return mapper.writeValueAsString(
            mapOf(
                "issue" to held.number,
                "title" to held.title,
                "labels" to held.labels.sorted(),
                "url" to issueLink(scope.workspaceId, held.number),
            ),
        )
    }

    /** By its number, which is what people say, and only in this workspace. */
    private fun issueIn(scope: OrknuxScope, arguments: String): Issue? {
        val number = number(arguments, "issue")?.toInt() ?: return null
        return issues.findByWorkspaceIdAndNumber(scope.workspaceId, number)
    }

    /**
     * Who has it, as a name.
     *
     * Resolved on every read, like everywhere else: a renamed agent reads
     * correctly afterwards and one that has been removed reads as gone rather
     * than as an id nobody recognises.
     */
    private fun nameOf(issue: Issue): String? {
        val held = issue.assignee ?: return null
        val kind = held.kind ?: return null
        val id = held.id ?: return null
        return when (kind) {
            AssigneeKind.USER -> users.findByIdOrNull(id.toLongOrNull() ?: -1)?.displayName
            AssigneeKind.AGENT -> agents.findByIdOrNull(id.toLongOrNull() ?: -1)?.name
            AssigneeKind.MODEL -> models.models(issue.workspaceId).firstOrNull { it.id.toString() == id }?.name
        }
    }

    /**
     * Who is watching this one, as names.
     *
     * Resolved on every read, like the assignee: somebody who has been removed
     * is dropped rather than read as an id nobody recognises.
     */
    private fun observerNames(issue: Issue): List<String> {
        val id = issue.id ?: return emptyList()
        return observers.findByIssueIdOrderByAddedAtAscIdAsc(id).mapNotNull { watching ->
            when (watching.kind) {
                AssigneeKind.USER -> users.findByIdOrNull(watching.observerId.toLongOrNull() ?: -1)?.displayName
                AssigneeKind.AGENT -> agents.findByIdOrNull(watching.observerId.toLongOrNull() ?: -1)?.name
                AssigneeKind.MODEL -> null
            }
        }
    }

    /** Whoever is asking: a token carries its owner, so this is a real name. */
    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "orknux"

}
