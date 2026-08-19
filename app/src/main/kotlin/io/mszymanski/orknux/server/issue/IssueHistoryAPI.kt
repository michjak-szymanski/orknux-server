package io.mszymanski.orknux.server.issue

import io.mszymanski.orknux.server.security.WorkspaceAccess
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.time.format.DateTimeFormatter

/**
 * An issue's history, assembled from everything that recorded any of it.
 *
 * Its own query rather than a field on the issue, and that is the whole of the
 * performance story: the issue page fetches one issue with its comments, its
 * files, its links and its observers on every load, and a history hung off that
 * would be read by everybody to be looked at by the few who open the tab. This
 * is asked for when the tab is opened and never before.
 *
 * Its own controller for the reason [IssueMoveAPI] is one: it is the place that
 * has to know every table an issue's story is spread across, and burying that
 * among the ordinary edits would hide it. Three tables answer here - the events
 * [IssueHistoryRecorder] writes, the comments, and the issue's own opening -
 * and the reader is shown one list in one order.
 */
@Controller
class IssueHistoryAPI(
    private val issues: IssueRepository,
    private val events: IssueEventRepository,
    private val access: WorkspaceAccess,
) {

    /**
     * What happened to one issue, oldest first.
     *
     * Oldest first because a history is read as a story and the first thing in
     * it is the issue being filed. A long-lived one is cut from the old end
     * rather than the new: what is asked of a busy issue is what happened
     * lately, and [IssueHistoryView.earlier] says how much was left out rather
     * than letting the list quietly stop.
     *
     * By the number people say, like everything else in the tracker - the row
     * id is what the history is keyed by inside, and it should not have to
     * reach an address.
     */
    @QueryMapping
    @Transactional(readOnly = true)
    fun issueHistory(
        @Argument workspaceId: Long,
        @Argument number: Int,
        @Argument limit: Int?,
    ): IssueHistoryView? {
        if (!access.canSee(workspaceId)) return null
        val issue = issues.findByWorkspaceIdAndNumber(workspaceId, number) ?: return null

        val told = (opening(issue) + recorded(issue) + said(issue)).sortedWith(
            compareBy({ it.at }, { it.rank }, { it.id }),
        )

        val wanted = (limit ?: MANY).coerceIn(1, MOST)
        val shown = told.takeLast(wanted)
        return IssueHistoryView(entries = shown.map { it.view }, earlier = told.size - shown.size)
    }

    /**
     * The issue being filed, which is a fact about the issue rather than a row.
     *
     * Read off the issue itself rather than written down when it happens, so
     * every issue has one - including the ones that were here before anything
     * recorded a history at all. Those are also the ones that get a
     * [IssueEventKind.RECORDING] line above the first real change, and the two
     * together are what makes an old issue's history honest: it opened then, it
     * has been recorded since this date, and here is everything in between that
     * survived - which is its comments, because comments were always kept.
     */
    private fun opening(issue: Issue) = listOf(
        Entry(
            at = issue.createdAt,
            rank = 0,
            id = 0,
            view = IssueEventView(
                id = "opened",
                kind = IssueEventKind.OPENED,
                actor = issue.reporter,
                at = issue.createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            ),
        ),
    )

    private fun recorded(issue: Issue) =
        events.findByIssueIdOrderByAtAscIdAsc(requireNotNull(issue.id)).map { event ->
            Entry(
                at = event.at,
                rank = 1,
                id = requireNotNull(event.id),
                view = IssueEventView(
                    id = "event-${event.id}",
                    kind = event.kind,
                    actor = event.actor,
                    at = event.at.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    was = event.was,
                    became = event.became,
                ),
            )
        }

    /**
     * The comments, as lines in the story rather than as the thread.
     *
     * Shortened, because the whole conversation is on the other tab and a
     * history that reproduces it is two copies of the same page. The text is
     * the comment as it stands now, so one that has been edited says so rather
     * than showing words nobody can find.
     */
    private fun said(issue: Issue) = issue.comments.map { comment ->
        Entry(
            at = comment.createdAt,
            rank = 2,
            id = requireNotNull(comment.id),
            view = IssueEventView(
                id = "comment-${comment.id}",
                kind = IssueEventKind.COMMENT,
                actor = comment.author,
                at = comment.createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                said = excerpt(comment.content),
                edited = comment.editedAt != null,
                commentId = comment.id,
            ),
        )
    }

    /**
     * The first line or so of what was said, on one line.
     *
     * Whitespace collapsed on purpose: a timeline is a column of single-line
     * entries, and a comment holding a fenced code block would otherwise push
     * everything after it off the screen. Whoever wants it as it was written
     * has it, in full, one tab away.
     */
    private fun excerpt(said: String): String {
        val flat = said.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= ENOUGH) flat else flat.take(ENOUGH).trimEnd() + "…"
    }

    /**
     * One line before it is a line: what to sort by, and what to show.
     *
     * The rank is the tie-break between the three sources when they land on the
     * same instant, which the opening and the first event routinely do. Opening
     * first, then what was recorded, then what was said.
     */
    private class Entry(
        val at: java.time.OffsetDateTime,
        val rank: Int,
        val id: Long,
        val view: IssueEventView,
    )

    private companion object {
        /** A tab's worth without asking for a year of a busy issue. */
        const val MANY = 200

        /** As much as anybody may ask for in one go. */
        const val MOST = 1000

        /** Enough of a comment to recognise it by. */
        const val ENOUGH = 240
    }
}

/**
 * What happened to an issue, and what was left out.
 *
 * The count is not decoration. A list that simply stops at two hundred is a
 * list that says the issue was quiet before that, which is the one thing a
 * history must never say.
 */
data class IssueHistoryView(val entries: List<IssueEventView>, val earlier: Int)

data class IssueEventView(
    /**
     * Unique across the three sources, which is why it is a string: an event
     * and a comment can both be row 5, and a list keyed by the number alone
     * would draw one of them twice.
     */
    val id: String,
    val kind: IssueEventKind,
    val actor: String,
    val at: String,
    val was: String? = null,
    val became: String? = null,
    /** What a comment said, shortened. Null for everything that is not one. */
    val said: String? = null,
    /** Whether that comment has been changed since it was written. */
    val edited: Boolean = false,
    /** Which comment it is, so the page can go and show it in full. */
    val commentId: Long? = null,
)
