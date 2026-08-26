package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.server.attachment.AttachmentDownloads
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.security.WorkspaceAccess
import org.springframework.core.io.InputStreamResource
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * Handing back a picture a task drew.
 *
 * REST rather than GraphQL for the reason the two upload endpoints are: what
 * crosses here is bytes, and the link that asks for them is an `<img src>` in
 * the markdown of the task's outcome.
 *
 * Nothing writes here. A picture arrives by an agent calling `task_draw_picture`
 * during a turn, which is not a request anybody made - there is no browser on
 * the other end of a task - so there is no upload half to write and nothing for
 * a person to attach.
 */
@RestController
class TaskPictureAPI(
    private val pictures: TaskPictureRepository,
    private val store: AttachmentStore,
    private val access: WorkspaceAccess,
    private val downloads: AttachmentDownloads,
) {

    /**
     * To anybody who can see the workspace the task is in.
     *
     * The same bar as reading the task itself, and the same bar an issue's
     * attachment sets: what a task produced belongs to the people the task was
     * done for, not to whoever happened to start it. Not the chat's rule, which
     * is stricter - a chat is one person's conversation and a task is the
     * workspace's work.
     *
     * A picture in a workspace the caller cannot see is answered as one that is
     * not there. The refusal used to be worth making specific until somebody
     * noticed that a plain number over HTTP plus a difference between "no" and
     * "not yours" is a way of counting what other teams have produced.
     *
     * What may be shown rather than downloaded, and the headers that stop a
     * file which is not what it claims to be from running as a page, are
     * [AttachmentDownloads]' business - so a chat, an issue and a task cannot
     * disagree about what is safe to render.
     */
    @GetMapping("/api/task-pictures/{id}")
    fun download(@PathVariable id: Long): ResponseEntity<InputStreamResource> {
        val picture = pictures.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw TaskPictureNotFoundException(id)

        /*
         * A row whose bytes have gone is answered as a file that is not here.
         *
         * Left alone the stream is opened inside the response body and throws
         * from there: a 500 with a stack trace, which says the server broke
         * when what happened is that a file was deleted. It matters because
         * this URL is only ever reached from an `<img>`, and a browser handed a
         * 500 draws the broken-image icon, while a 404 is what the interface
         * says one line about.
         */
        if (!store.exists(picture.location)) throw TaskPictureNotFoundException(id)

        return downloads.serve(
            filename = picture.filename,
            contentType = picture.contentType,
            sizeBytes = picture.sizeBytes,
            location = picture.location,
        )
    }
}
