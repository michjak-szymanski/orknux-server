package io.mszymanski.orknux.server.chat

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelSpeechClient
import io.mszymanski.orknux.connector.model.Speech
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceNotFoundException
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Reads text aloud, so an answer can be listened to rather than read.
 *
 * The mirror of [TranscriptionAPI], scoped and refused the same way. REST
 * rather than GraphQL for the reason that one is: what comes back is audio, and
 * a JSON field would mean base64 for bytes the browser is about to play.
 *
 * The text is sent rather than named by message id, which is deliberate. An
 * answer that has just finished streaming is on screen before it has an id the
 * interface knows, and a speaker that worked on yesterday's messages but not on
 * the one somebody just read would be the wrong way round.
 */
@RestController
class SpeechAPI(
    private val workspaces: WorkspaceRepository,
    private val models: LlmModelRepository,
    private val speaker: ModelSpeechClient,
    private val access: WorkspaceAccess,
) {

    @PostMapping("/api/workspaces/{workspaceId}/speech")
    fun speak(
        @PathVariable workspaceId: Long,
        @RequestBody said: SpeechRequest,
    ): ResponseEntity<Any> {
        val workspace = workspaces.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        val modelId = workspace.speechModelId
            ?: return refuse(HttpStatus.CONFLICT, "This workspace has no speech model.")

        val text = said.text.trim()
        if (text.isEmpty()) return refuse(HttpStatus.BAD_REQUEST, "There is nothing to read.")
        if (text.length > MAX_CHARS) {
            return refuse(HttpStatus.PAYLOAD_TOO_LARGE, "That answer is too long to read in one piece.")
        }

        val voice = models.findByIdOrNull(modelId)?.voice

        return when (val spoken = speaker.speak(modelId, text, voice)) {
            is Speech.Spoke -> ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(spoken.contentType))
                .header("Content-Length", spoken.audio.size.toString())
                // Listened to once, and never worth a second copy on disk.
                .header("Cache-Control", "no-store")
                .body(spoken.audio)

            // Said plainly: whoever pressed the speaker is the person who can
            // fix the model behind it.
            is Speech.Failed -> refuse(HttpStatus.BAD_GATEWAY, spoken.reason)
        }
    }

    private fun refuse(status: HttpStatus, says: String): ResponseEntity<Any> =
        ResponseEntity.status(status).body(mapOf("error" to says))

    private companion object {
        /**
         * As much as can actually be read before the request gives up.
         *
         * Measured against a local reader: 1,500 characters took about 17
         * seconds to synthesise, so the 120-second request timeout is reached
         * somewhere near 10,000. A cap above that is not a cap — it is a
         * promise the timeout breaks first, and a timeout says nothing about
         * why. Under it, an answer too long to read says so.
         *
         * Long answers are exactly the ones somebody wants read to them, so
         * this is as generous as it can be and still be true.
         */
        const val MAX_CHARS = 8_000
    }
}

/**
 * What to read.
 *
 * Annotated the way every other request body here is, and with no default on
 * the property. A default makes Kotlin emit a second, no-argument constructor,
 * and Jackson then finds two creators annotated the same way and refuses the
 * type outright — every request answering 500 rather than the text arriving.
 * Before that it was quieter and worse: it built through the no-argument path
 * and left `text` empty, so a perfectly good request came back as "there is
 * nothing to read".
 */
data class SpeechRequest @JsonCreator constructor(
    @JsonProperty("text") val text: String,
)
