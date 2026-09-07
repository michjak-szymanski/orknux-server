package io.mszymanski.orknux.server.workflow

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
 * Handing back a picture an image node drew.
 *
 * REST rather than GraphQL for the reason [io.mszymanski.orknux.server.task.TaskPictureAPI]
 * is: what crosses here is bytes, and the link that asks for them is an
 * `<img src>` on the run graph and a download beside it.
 *
 * Nothing writes here. A picture arrives when a run reaches an image node, which
 * is not a request anybody made - there is no browser on the other end of a
 * workflow run - so there is no upload half.
 */
@RestController
class ExecutionPictureAPI(
    private val pictures: ExecutionPictureRepository,
    private val store: AttachmentStore,
    private val access: WorkspaceAccess,
    private val downloads: AttachmentDownloads,
) {

    /**
     * To anybody who can see the workspace the run is in, the same bar as reading
     * the run itself. A picture in a workspace the caller cannot see is answered
     * as one that is not there, so a plain number over HTTP cannot be used to
     * count what other teams have produced - the rule task pictures already keep.
     */
    @GetMapping("/api/execution-pictures/{id}")
    fun download(@PathVariable id: Long): ResponseEntity<InputStreamResource> {
        val picture = pictures.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw ExecutionPictureNotFoundException(id)

        // A row whose bytes have gone is answered as a file that is not here: this
        // URL is only ever reached from an <img>, and a 404 is what the interface
        // says one line about, where a 500 draws a broken-image icon.
        if (!store.exists(picture.location)) throw ExecutionPictureNotFoundException(id)

        return downloads.serve(
            filename = picture.filename,
            contentType = picture.contentType,
            sizeBytes = picture.sizeBytes,
            location = picture.location,
        )
    }
}
