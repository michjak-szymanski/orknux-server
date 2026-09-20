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
 * Handing back a file an agent saved.
 *
 * REST rather than GraphQL for the reason [ExecutionPictureAPI] is: what
 * crosses here is bytes, and the links that ask for them are an `<img>` and a
 * download on the Artifacts page.
 *
 * Nothing writes here. A saved artifact arrives when an agent calls the tool,
 * which is not a request anybody made with a browser - so there is no upload
 * half, and [SavedArtifacts] is the only door in.
 */
@RestController
class SavedArtifactAPI(
    private val artifacts: SavedArtifactRepository,
    private val store: AttachmentStore,
    private val access: WorkspaceAccess,
    private val downloads: AttachmentDownloads,
) {

    /**
     * To anybody who can see the workspace it is in, the same bar as reading the
     * Artifacts page itself. One in a workspace the caller cannot see is
     * answered as one that is not there, so a plain number over HTTP cannot be
     * used to count what other teams have produced.
     */
    @GetMapping("/api/artifacts/{id}")
    fun download(@PathVariable id: Long): ResponseEntity<InputStreamResource> {
        val artifact = artifacts.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw SavedArtifactNotFoundException(id)

        // A row whose bytes have gone is answered as a file that is not here,
        // which is the one line the interface says about a file that was swept.
        if (!store.exists(artifact.location)) throw SavedArtifactNotFoundException(id)

        return downloads.serve(
            filename = artifact.name,
            contentType = artifact.contentType,
            sizeBytes = artifact.sizeBytes,
            location = artifact.location,
        )
    }
}

/**
 * Asked for an artifact that is not here, or is not this caller's to see.
 *
 * Not a [io.mszymanski.orknux.server.graphql.Refusal], for the reason
 * [ExecutionPictureNotFoundException] is not: the only things that ask for
 * these bytes are an `<img>` and a download link, so it is answered with a
 * status rather than a sentence.
 */
class SavedArtifactNotFoundException(val id: Long) : RuntimeException("No saved artifact with id $id")
