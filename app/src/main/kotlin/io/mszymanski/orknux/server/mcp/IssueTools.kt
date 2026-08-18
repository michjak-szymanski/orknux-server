package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.issue.AssigneeKind
import io.mszymanski.orknux.server.issue.Issue
import io.mszymanski.orknux.server.issue.IssueComment
import io.mszymanski.orknux.server.issue.IssueNewsDesk
import io.mszymanski.orknux.server.issue.IssueRepository
import io.mszymanski.orknux.server.issue.IssueStatus
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
                "labels" to held.labels.sorted(),
                "comments" to held.comments.map {
                    mapOf("author" to it.author, "said" to it.content, "at" to it.createdAt.toString())
                },
                "url" to issueLink(scope.workspaceId, held.number),
            ),
        )
    }

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

        val moved = held.status != wanted
        held.status = wanted
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = currentUser()
        issues.save(held)
        if (moved) newsDesk.statusChanged(held, currentUser())
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

    /** Whoever is asking: a token carries its owner, so this is a real name. */
    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "orknux"

}
