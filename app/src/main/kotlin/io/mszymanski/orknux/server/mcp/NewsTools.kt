package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.issue.AssigneeKind
import io.mszymanski.orknux.server.issue.IssueNewsDesk
import io.mszymanski.orknux.server.issue.IssueNewsItem
import io.mszymanski.orknux.server.issue.IssueNewsKind
import io.mszymanski.orknux.server.issue.NewsReader
import io.mszymanski.orknux.server.security.WebProperties
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * What has happened on the issues that concern you, with the option of waiting
 * for it.
 *
 * The tracker exists so that work is discussed where everybody can read it, and
 * that only works if the people in it find out. A person has a page they can
 * look at; an assistant has nothing, so until now somebody had to say "I
 * replied on #6" by hand - which is the message the tracker was supposed to
 * replace.
 *
 * **Waiting is a parameter, not a protocol.** MCP can push, over an event
 * stream a client holds open; this server speaks plain request and answer, and
 * every client that would have to hold that stream open is instead perfectly
 * happy to hold one call open. So the call blocks until something happens or
 * the time runs out. Nothing to reconnect, nothing to resume, and it works
 * through anything that passes HTTP.
 *
 * **Reading marks it read**, and the mark is kept here rather than handed back
 * as a cursor. An assistant restarted between sessions remembers nothing, and a
 * cursor it forgets means either a week of events repeated or a day of them
 * silently skipped.
 */
@Service
class NewsTools(
    private val desk: IssueNewsDesk,
    private val agents: AgentRepository,
    private val web: WebProperties,
    private val mapper: ObjectMapper,
) {

    /**
     * @param arguments `wait` in seconds, and `as` to read an agent's news
     *   instead of your own.
     */
    fun news(scope: OrknuxScope, arguments: String): String {
        val asked = mapper.readTree(arguments)
        val onBehalfOf = asked.path("as").let { if (it.isString) it.stringValue() else null }?.takeIf { it.isNotBlank() }
        val reader = readerFor(scope, onBehalfOf)
            ?: return mapper.writeValueAsString(
                mapOf("error" to "There is nobody called $onBehalfOf in this workspace to read the news of"),
            )

        val seconds = secondsIn(asked.path("wait")).coerceIn(0, LONGEST_WAIT)
        val waiting = desk.unread(scope.workspaceId, reader, MANY).ifEmpty {
            if (seconds == 0) emptyList() else waitFor(scope, reader, seconds)
        }

        return mapper.writeValueAsString(
            mapOf(
                "reading" to reader.name,
                "news" to waiting.map(::described),
                // Said plainly, because "nothing" and "nothing yet" are
                // different answers and only one of them is worth asking again.
                "waited" to seconds,
            ),
        )
    }

    /**
     * Sleep, look again, sleep again, until something arrives or the time is up.
     *
     * The loop is not decoration: everybody waiting is woken by anybody's news,
     * so most wakings are somebody else's and the answer is to look and go back
     * to sleep for what is left.
     */
    private fun waitFor(scope: OrknuxScope, reader: NewsReader, seconds: Int): List<IssueNewsItem> {
        val until = System.currentTimeMillis() + seconds * 1000L
        while (true) {
            val left = until - System.currentTimeMillis()
            if (left <= 0) return emptyList()
            desk.awaitNews(minOf(left, LOOK_AGAIN_MS))
            val arrived = desk.unread(scope.workspaceId, reader, MANY)
            if (arrived.isNotEmpty()) return arrived
        }
    }

    /**
     * Whose news this is.
     *
     * Yours by default - a token carries the person it was minted for. Naming
     * an agent reads that agent's instead, which is how something acting for an
     * agent finds out that an issue has been handed to it: the agent is not a
     * client and has nobody to hold a call open on its behalf.
     */
    private fun readerFor(scope: OrknuxScope, name: String?): NewsReader? {
        if (name == null) {
            val me = SecurityContextHolder.getContext().authentication?.name ?: return null
            return NewsReader(AssigneeKind.USER, me)
        }
        val agent = agents.findNamed(scope.workspaceId, name) ?: return null
        return NewsReader(AssigneeKind.AGENT, agent.name, agent.id?.toString())
    }

    private fun described(item: IssueNewsItem): Map<String, Any?> = mapOf(
        "what" to when (item.kind) {
            IssueNewsKind.ASSIGNED -> "assigned to you"
            IssueNewsKind.STATUS ->
                if (item.says == "CLOSED") "closed" else "reopened"
            IssueNewsKind.COMMENT -> "commented"
            IssueNewsKind.MENTIONED -> "mentioned you"
        },
        "issue" to item.issueNumber,
        "title" to item.issueTitle,
        "by" to item.actor,
        "at" to item.at.toString(),
        // The comment itself, so the answer to "what happened" does not need a
        // second call to be worth reading.
        "said" to item.says.takeIf { item.kind == IssueNewsKind.COMMENT || item.kind == IssueNewsKind.MENTIONED },
        "url" to link(item.workspaceId, item.issueNumber),
    )

    /** A number however it was sent: models write "60" as often as 60. */
    private fun secondsIn(node: tools.jackson.databind.JsonNode): Int = when {
        node.isNumber -> node.asInt()
        node.isString -> node.stringValue().trim().toIntOrNull() ?: 0
        else -> 0
    }

    private fun link(workspaceId: Long, number: Int): String {
        val base = web.allowedOrigins.firstOrNull()?.trimEnd('/').orEmpty()
        return "$base/workspace/$workspaceId/issues/$number"
    }

    private companion object {
        /** As long as a client will hold a call open, and no longer. */
        const val LONGEST_WAIT = 300

        /** How long one sleep lasts before looking again regardless. */
        const val LOOK_AGAIN_MS = 5_000L

        /** Everything waiting, in one answer: an inbox is not paginated. */
        const val MANY = 50
    }
}
