package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ModelImageClient
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.connector.model.Picture
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.ChatAttachment
import io.mszymanski.orknux.server.attachment.ChatAttachmentRepository
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.attachment.PictureFilenames
import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.stereotype.Controller

/**
 * Drawing a picture into a chat.
 *
 * A mutation of its own rather than a branch inside `sendChatMessage`, because
 * it is a different model at a different endpoint: the chat model does not draw
 * and the image model does not converse, and folding them into one door would
 * mean a send that quietly consults the workspace's settings to decide which
 * API it is about to call.
 *
 * GraphQL rather than REST, which is the opposite of the choice
 * [SpeechAPI] and [TranscriptionAPI] made — and for the same reason they made
 * it. Those carry bytes; this one does not. The picture goes into attachment
 * storage and what crosses here is its id, so the request is ordinary JSON and
 * every refusal gets the `extensions.code` that lets the interface say it in
 * Polish.
 *
 * ### Where the picture goes
 *
 * Into attachment storage, as a [ChatAttachment] on this chat, filed under the
 * workspace like every other file. Not a new store, not a column of bytes, not
 * a `data:` URL in the message: those are three ways of saying "this picture
 * lives somewhere the rest of the product does not know about". The one that
 * already exists gives it, in one move, a directory per workspace, a size the
 * installation set, an id that `/api/attachments/{id}` will only hand back to
 * whoever the chat belongs to, and a row that goes when the chat goes.
 *
 * ### What the chat then holds
 *
 * The exchange is written into the thread as the two lines it is: what was asked
 * for, and a markdown image pointing at the attachment. That is what makes a
 * picture survive the response — reopening the chat renders it out of the
 * history, with no second record to keep in step — and it is why nothing new had
 * to be added to `ChatMessageView` for the screen to draw it.
 *
 * A picture whose bytes have since been removed is a link that 404s, and the
 * interface's markdown renders that as one line saying so. Left to itself the
 * browser would draw a broken-image icon, which says the page is broken rather
 * than that the file is gone.
 */
@Controller
class ChatPictureAPI(
    private val chats: ChatService,
    private val models: ModelService,
    private val drawing: ModelImageClient,
    private val workspaces: WorkspaceRepository,
    private val attachments: ChatAttachmentRepository,
    private val store: AttachmentStore,
    private val settings: InstallationSettings,
    private val access: WorkspaceAccess,
    private val ownership: ChatOwnership,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    /**
     * Draws what was described, and puts it in the chat.
     *
     * Every refusal here is a sentence rather than an empty answer, because the
     * picture is the whole of the request: a chat that has just spent thirty
     * seconds and shows nothing has told somebody that the product is broken.
     *
     * The model is asked outside the transaction that writes the result, which
     * is the rule `ChatService.ask` states — a call that takes half a minute
     * must not hold a connection, or on SQLite the one write lock there is.
     */
    @MutationMapping
    fun drawChatPicture(@Argument chatId: Long, @Argument prompt: String): ChatPictureView {
        if (!settings.chatEnabled()) throw ChatDisabledException()

        val session = chats.session(chatId) ?: throw ChatSessionNotFoundException(chatId)
        ownership.requireOwn(session)
        access.requireVisible(session.workspaceId)

        val asked = prompt.trim()
        if (asked.isEmpty()) throw ChatMessageEmptyException()
        if (asked.length > MAX_PROMPT) throw ChatPictureFailedException("That description is too long to draw from.")

        /*
         * Asked before the model is called rather than after it.
         *
         * A drawn picture has nowhere to live in an installation with
         * attachments switched off, and finding that out after the provider has
         * been paid is finding it out too late. The switch is the same one the
         * upload button obeys, so a chat that cannot take a file cannot make one.
         */
        if (!settings.attachmentsEnabled()) throw ChatPictureUnstorableException()

        val workspace = workspaces.findByIdOrNull(session.workspaceId)
            ?: throw ChatSessionNotFoundException(chatId)
        val modelId = workspace.imageModelId ?: throw ChatPictureModelNotChosenException()

        val drawn = when (val picture = drawing.draw(modelId, asked)) {
            is Picture.Failed -> throw ChatPictureFailedException(picture.reason)
            is Picture.Drawn -> picture
        }

        val filename = PictureFilenames.of(asked, drawn.contentType)
        val attachmentId = file(session, filename, drawn.image, drawn.contentType)

        val said = "![${asked.take(ALT_LENGTH)}](/api/attachments/$attachmentId)"
        chats.recordPicture(chatId, asked, said)

        auditRecorder.record(session.workspaceId, WorkspaceAuditCategory.CHAT, "Drew a picture in a chat")

        return ChatPictureView(
            attachmentId = attachmentId,
            prompt = asked,
            said = said,
            millis = drawn.millis,
            // One request, one picture — ModelImageClient asks for exactly one.
            // Null where the model carries no per-image price, never nought: an
            // image model costed on its token prices reports free, and this is
            // the number that would be wrong.
            cost = models.imageCostOf(modelId, images = 1)?.toDouble(),
        )
    }

    /**
     * Files the bytes and then the row.
     *
     * The bytes go down first on purpose: a row pointing at a file that was
     * never written is an attachment nobody can open and nothing can tell apart
     * from one whose file has been deleted, while a file with no row is a wasted
     * block and nothing worse.
     *
     * No transaction is opened around it, and none is wanted. The save is the
     * repository's own, this is called from a method with nothing else open, and
     * an annotation on a private method called from inside the same object goes
     * through no proxy and does nothing at all - which is the shape of bug that
     * reads as a guarantee.
     */
    private fun file(session: ChatSession, filename: String, bytes: ByteArray, contentType: String): Long {
        val location = store.put(session.workspaceId, filename, bytes)
        val saved = attachments.save(
            ChatAttachment(
                workspaceId = session.workspaceId,
                chatSessionId = session.id,
                filename = filename,
                contentType = contentType,
                sizeBytes = bytes.size.toLong(),
                location = location,
                // Whose chat it is. The download checks the chat rather than the
                // uploader for a file that has one, but this keeps the row
                // honest about who caused it.
                uploadedBy = session.userId,
            ),
        )
        return requireNotNull(saved.id)
    }

    private companion object {
        /**
         * As long a description as is worth sending.
         *
         * These providers cap the prompt themselves — around a thousand
         * characters for the older models — and refusing here says so in words,
         * where a provider's own refusal arrives as a 400 about a field name.
         */
        const val MAX_PROMPT = 2_000

        /** As much of the description as belongs in the alt text of one image. */
        const val ALT_LENGTH = 120
    }
}

/**
 * What was drawn, and what it cost.
 *
 * The attachment id is the whole of where the picture is: the screen asks
 * `/api/attachments/{id}` for it, exactly as it does for a file somebody
 * uploaded, and nothing here has to know what storage did with the bytes.
 */
data class ChatPictureView(
    val attachmentId: Long,
    /** What was asked for, trimmed — the line written into the chat as the question. */
    val prompt: String,
    /** The line written into the chat as the answer: a markdown image. */
    val said: String,
    /** How long the provider took, which the screen shows as what it drew for. */
    val millis: Long,
    /**
     * What the picture cost, at the per-image price recorded on the model, or
     * null when it carries none.
     *
     * Null rather than zero, for the reason `ModelPricing` gives everywhere: an
     * image model has no token prices to be costed at and no tokens to cost, so
     * a figure worked out the ordinary way would be `$0.00` for something that
     * was paid for.
     */
    val cost: Double?,
)

/** The workspace has not chosen a model that draws, so there is nothing to ask. */
class ChatPictureModelNotChosenException : RuntimeException(
    "This workspace has no image model. Choose one on the workspace's Chat settings, " +
        "or add one under Models.",
)

/** Attachments are off, so a picture would have nowhere to be kept. */
class ChatPictureUnstorableException : RuntimeException(
    "A drawn picture is kept as an attachment, and attachments are turned off for this installation.",
)

/**
 * The picture could not be drawn, in the provider's own words where it gave any.
 *
 * Carries the reason rather than a category, because the reasons are not alike:
 * a refused description, a provider with no key, a host the guard will not call
 * and a request that ran out of time all arrive here, and only the sentence
 * tells them apart. [Refusal] so the interface can put it in front of somebody
 * with the code beside it.
 */
class ChatPictureFailedException(val reason: String) : RuntimeException(reason), Refusal {

    override val arguments get() = mapOf("reason" to reason)
}
