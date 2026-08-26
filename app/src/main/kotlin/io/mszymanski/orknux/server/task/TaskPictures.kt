package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.connector.model.ModelImageClient
import io.mszymanski.orknux.connector.model.Picture
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.attachment.PictureFilenames
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

/**
 * A task drawing a picture, and the pictures it has drawn.
 *
 * Issue #283. V206 gave the installation a model that draws and gave a chat the
 * button that asks it; a task could reach neither, so an agent told to produce
 * a diagram produced a paragraph describing one. This is the other end of that
 * feature, and it deliberately reuses all of it: the same [ModelImageClient],
 * the same per-workspace choice of which model draws, the same attachment
 * storage under the same installation switch. What is new is only where the
 * picture goes afterwards, because a task has no thread to write a line into.
 *
 * **Where it goes is a [TaskPicture] row, and the outcome is assembled from
 * those rather than written by the model.** A chat can keep the picture as a
 * markdown line in its history because the history is the chat's record; a
 * task's record is a row with an `outcome` column on it, and a model asked to
 * paste a link into its summary is a model that will one day paste it wrongly,
 * or not at all, and leave a picture nobody can find. [outcomeOf] is what puts
 * the two together, and it runs for a task that failed or was stopped as much
 * as for one that finished - three pictures drawn before the turns ran out are
 * three pictures that were paid for.
 */
@Service
class TaskPictures(
    private val workspaces: WorkspaceRepository,
    private val drawing: ModelImageClient,
    private val pictures: TaskPictureRepository,
    private val store: AttachmentStore,
    private val settings: InstallationSettings,
) {

    /**
     * Whether this task can draw at all, which decides whether the tool is
     * offered.
     *
     * Asked before the model is told about the tool rather than answered when
     * it calls one, because `AgentTools` states the rule for every tool in this
     * application: a model is only ever offered tools that will run, and one
     * declared but not implemented is a model told it can do something it
     * cannot - it will believe you, and it will spend a turn finding out.
     *
     * Two things have to be true. The workspace has chosen a model that draws,
     * which is the same setting the chat's picture button obeys; and the
     * installation allows attachments, because a drawn picture is kept as one
     * and there would otherwise be nowhere to put it.
     */
    fun offered(task: Task): Boolean =
        settings.attachmentsEnabled() && modelFor(task) != null

    /**
     * Draws one, and files it.
     *
     * The bytes go down before the row, for the reason `ChatPictureAPI` gives:
     * a row pointing at a file that was never written is a picture nobody can
     * open and nothing can tell apart from one whose file has been deleted,
     * while a file with no row is a wasted block and nothing worse.
     *
     * Filed here rather than handed back for the loop to write, which is where
     * every other thing a task's tools produce is written. The difference is
     * that this one is bytes: a round can be another ten minutes of model calls
     * after the drawing, and a picture held in memory until the loop catches
     * something is a picture lost the moment that round throws - having already
     * been paid for. The task's own row is still written only by the loop.
     */
    fun draw(task: Task, prompt: String): Drawing {
        if (!settings.attachmentsEnabled()) {
            return Drawing.Refused(
                "A drawn picture is kept as an attachment, and attachments are turned off for this installation.",
            )
        }

        val modelId = modelFor(task) ?: return Drawing.Refused(
            "This workspace has no image model. Somebody has to choose one on the workspace's Chat settings " +
                "before anything here can draw.",
        )

        val taskId = requireNotNull(task.id)
        /*
         * A ceiling, and it is the one bound this feature adds.
         *
         * TaskProperties bounds the turns and the seconds and says why it
         * bounds no tokens - a model carries its own usage cap, and a second
         * half-counter would disagree with the bill. That argument holds for
         * what a drawing costs too, but not for how many of them one task can
         * ask for: a turn's tool loop runs up to eight rounds and a task has up
         * to forty turns, so an agent that has decided drawing is the answer can
         * ask three hundred times before anything stops it. Twenty is more
         * pictures than any outcome is, and the refusal is a sentence the model
         * reads and works around rather than a failure.
         */
        if (pictures.countByTaskId(taskId) >= MOST_PICTURES) {
            return Drawing.Refused(
                "This task has already drawn $MOST_PICTURES pictures, which is as many as one task may draw. " +
                    "Finish with the ones you have.",
            )
        }

        val asked = prompt.trim()
        if (asked.isEmpty()) return Drawing.Refused("There is nothing to draw: say what the picture should be of.")
        if (asked.length > MOST_PROMPT) {
            return Drawing.Refused("That description is too long to draw from; say it in under $MOST_PROMPT characters.")
        }

        val drawn = when (val picture = drawing.draw(modelId, asked)) {
            is Picture.Failed -> return Drawing.Refused(picture.reason)
            is Picture.Drawn -> picture
        }

        val filename = PictureFilenames.of(asked, drawn.contentType)
        val location = store.put(task.workspaceId, filename, drawn.image)
        val saved = pictures.save(
            TaskPicture(
                taskId = taskId,
                workspaceId = task.workspaceId,
                prompt = asked,
                filename = filename,
                contentType = drawn.contentType,
                sizeBytes = drawn.image.size.toLong(),
                location = location,
            ),
        )
        return Drawing.Drawn(saved, drawn.millis)
    }

    /** Everything one task drew, oldest first, which is the order it is shown in. */
    fun of(taskId: Long): List<TaskPicture> = pictures.findByTaskIdOrderByDrawnAtAscIdAsc(taskId)

    /**
     * A markdown image pointing at the picture.
     *
     * The same line a chat writes into its thread, at this feature's own
     * endpoint, and the same reason for choosing markdown over a field the
     * screen would have to be taught about: whatever renders what an agent
     * writes already renders this.
     */
    fun linkTo(picture: TaskPicture): String =
        "![${alt(picture.prompt)}](/api/task-pictures/${requireNotNull(picture.id)})"

    /**
     * The outcome as a task's outcome actually is: what the agent said, and
     * what it drew.
     *
     * Composed on the way out rather than written into the column, so that the
     * row keeps saying exactly one thing - the agent's own last words - and
     * every door onto a task shows the pictures without either of them having
     * to remember to. `TaskViews` is the one place a task is assembled for
     * showing, and both the query and the live stream go through it.
     *
     * A picture the summary already links to is not added again. That is not
     * defensive: the tool hands the model the markdown for exactly this reason,
     * so an agent that wants the picture in the middle of its summary can put
     * it there, and appending a second copy underneath would punish it for
     * doing the better thing.
     */
    fun outcomeOf(taskId: Long, said: String?): String? {
        val drawn = of(taskId)
        if (drawn.isEmpty()) return said

        val summary = said?.trim().orEmpty()
        val shown = drawn
            .filterNot { summary.contains("/api/task-pictures/${requireNotNull(it.id)}") }
            .map(::linkTo)
        if (shown.isEmpty()) return said

        return (listOf(summary).filter { it.isNotEmpty() } + shown).joinToString("\n\n")
    }

    /** What this task draws with: whatever its workspace chose, or nothing. */
    private fun modelFor(task: Task): Long? =
        workspaces.findByIdOrNull(task.workspaceId)?.imageModelId

    /**
     * As much of the description as belongs in one image's alt text.
     *
     * Brackets and line breaks are taken out rather than escaped. What is being
     * built is a markdown link and the description is a model's prose, so a `]`
     * in it would close the alt text early and leave the rest of the sentence
     * standing in the outcome as text - the sort of thing that reads as a bug
     * in the page rather than as a bracket in a prompt.
     */
    private fun alt(prompt: String): String = prompt
        .replace(Regex("[\\[\\]\\r\\n]"), " ")
        .replace(Regex(" +"), " ")
        .trim()
        .take(ALT_LENGTH)

    private companion object {
        /** As many pictures as one task may draw. See [draw] for why there is a number at all. */
        const val MOST_PICTURES = 20

        /**
         * As long a description as is worth sending, the same figure the chat's
         * door uses: these providers cap the prompt themselves, and refusing
         * here says so in words where a provider's own refusal arrives as a 400
         * about a field name.
         */
        const val MOST_PROMPT = 2_000

        /** As much of the description as belongs in the alt text of one image. */
        const val ALT_LENGTH = 120
    }
}

/** What came of asking for a picture: one that was drawn and filed, or why not. */
sealed interface Drawing {

    data class Drawn(val picture: TaskPicture, val millis: Long) : Drawing

    /**
     * Why nothing was drawn, in words the model is handed.
     *
     * A sentence rather than a category, because the reasons are not alike: a
     * workspace that has chosen no model, a provider that refused the
     * description, an installation with attachments off and a request that ran
     * out of time all arrive here, and only the sentence tells the agent which
     * of them it can do something about.
     */
    data class Refused(val reason: String) : Drawing
}
