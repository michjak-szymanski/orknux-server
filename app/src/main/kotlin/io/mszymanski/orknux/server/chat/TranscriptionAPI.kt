package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ModelTranscriptionClient
import io.mszymanski.orknux.connector.model.Transcription
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * Turns a recording into text, so a chat can be spoken rather than typed.
 *
 * REST rather than GraphQL because what arrives is a file: a multipart upload is
 * what every browser produces from a recorder, and putting the same bytes
 * through a JSON field would mean base64 in and base64 out for no gain.
 *
 * The model is the workspace's, not the caller's to choose — the microphone
 * appears only where one has been set, and it is a property of what this
 * installation is running rather than of the sentence somebody is saying.
 */
@RestController
class TranscriptionAPI(
    private val workspaces: WorkspaceRepository,
    private val transcriber: ModelTranscriptionClient,
    private val access: WorkspaceAccess,
) {

    @PostMapping("/api/workspaces/{workspaceId}/transcription")
    fun transcribe(
        @PathVariable workspaceId: Long,
        @RequestParam("audio") audio: MultipartFile,
    ): ResponseEntity<Map<String, Any>> {
        val workspace = access.requireVisible(workspaceId)

        val modelId = workspace.transcriptionModelId
            ?: return refuse(HttpStatus.CONFLICT, "This workspace has no transcription model.")
        if (audio.isEmpty) return refuse(HttpStatus.BAD_REQUEST, "Nothing was recorded.")
        if (audio.size > MAX_BYTES) {
            return refuse(HttpStatus.PAYLOAD_TOO_LARGE, "That recording is too long to send in one piece.")
        }

        val heard = transcriber.transcribe(
            modelId = modelId,
            audio = audio.bytes,
            // What the browser called it, since the extension is how most of
            // these servers pick a decoder; a default rather than a refusal,
            // because a recorder that names nothing still recorded something.
            filename = audio.originalFilename?.ifBlank { null } ?: "speech.webm",
            contentType = audio.contentType?.ifBlank { null } ?: "audio/webm",
        )

        return when (heard) {
            is Transcription.Heard -> ResponseEntity.ok(mapOf("text" to heard.text, "millis" to heard.millis))
            // What went wrong is said plainly: whoever is looking at the
            // microphone is the person who can fix the model behind it.
            is Transcription.Failed -> refuse(HttpStatus.BAD_GATEWAY, heard.reason)
        }
    }

    private fun refuse(status: HttpStatus, says: String): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(status).body(mapOf("error" to says))

    private companion object {
        /** Around ten minutes of speech at the bitrate a browser records at. */
        const val MAX_BYTES = 25L * 1024 * 1024
    }
}
