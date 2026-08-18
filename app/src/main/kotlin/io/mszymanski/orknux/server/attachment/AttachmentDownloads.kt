package io.mszymanski.orknux.server.attachment

import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Handing an attachment's bytes back to a browser.
 *
 * One class for every kind of attachment there is - a chat's and an issue's -
 * because what is settled here is not presentation. It is which types may be
 * shown rather than downloaded, and the headers that stop a file which is not
 * what it claims to be from running as a page; a second copy of that would
 * drift, and the copy that drifted is the one nobody would be watching.
 *
 * The caller has already decided that whoever is asking may see the workspace
 * the file belongs to. This only writes the answer.
 */
@Component
class AttachmentDownloads(private val store: AttachmentStore) {

    /**
     * A picture is served as itself so a page can show it; everything else is a
     * download. The difference matters: a page that renders whatever was
     * uploaded is a page that will one day render somebody's HTML - and an SVG
     * is HTML with a drawing in it, which is why it is not on the list.
     */
    fun serve(
        filename: String,
        contentType: String,
        sizeBytes: Long,
        location: String,
    ): ResponseEntity<InputStreamResource> {
        val name = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20")
        val shown = contentType.lowercase() in SHOWABLE
        return ResponseEntity.ok()
            .contentType(if (shown) MediaType.parseMediaType(contentType) else MediaType.APPLICATION_OCTET_STREAM)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                if (shown) "inline; filename*=UTF-8''$name" else "attachment; filename*=UTF-8''$name",
            )
            .header(HttpHeaders.CONTENT_LENGTH, sizeBytes.toString())
            // Nothing on this page runs, whatever the type turns out to be.
            .header("Content-Security-Policy", "default-src 'none'; img-src 'self'; sandbox")
            .header("X-Content-Type-Options", "nosniff")
            .body(InputStreamResource(store.open(location)))
    }

    companion object {
        /**
         * What may be shown rather than downloaded.
         *
         * Raster pictures only. SVG is deliberately absent: it is a document
         * that can carry script, and "it is an image" is exactly the reasoning
         * that makes that a problem.
         */
        val SHOWABLE = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp")
    }
}
