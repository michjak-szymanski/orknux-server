package io.mszymanski.orknux.connector.model

import io.mszymanski.orknux.connector.connection.ConnectionProbe
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

/** What came back from a transcription, or why nothing did. */
sealed interface Transcription {
    data class Heard(val text: String, val millis: Long) : Transcription

    data class Failed(val reason: String) : Transcription
}

/**
 * Turns recorded speech into text, using one of the workspace's models.
 *
 * The same shape as the chat client and for the same reason: a provider is an
 * endpoint and a credential, and what differs per provider is the path and the
 * body. Whisper's API — the one faster-whisper, whisper.cpp's server and OpenAI
 * all speak — takes a multipart form with the audio and the model name, and
 * answers `{"text": "..."}`.
 *
 * Written by hand rather than with a client library because a multipart body is
 * a few lines of bytes, and the alternative is a dependency that has to be
 * configured with the same endpoint and credential twice.
 */
@Service
class ModelTranscriptionClient(
    private val providers: ModelProviderRepository,
    private val models: LlmModelRepository,
    private val probe: ModelProviderProbe,
    private val connections: ConnectionProbe,
    private val mapper: ObjectMapper,
    private val proxies: ProxyRouter,
) {

    private val http: HttpClient = proxies.builder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(CONNECT_SECONDS))
        // Said out loud rather than left to the default, because it is the same
        // rule the chat client and the probe state: a redirect can leave the
        // host somebody configured and take the key with it.
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /**
     * @param modelId one of the workspace's models, which has to be a
     *   transcription one: a chat model handed audio answers something, and
     *   what it answers is not a transcript.
     * @param audio what was recorded, as it arrived from the browser.
     * @param filename what to call it in the form; the extension is how most
     *   servers decide which decoder to use.
     */
    fun transcribe(modelId: Long, audio: ByteArray, filename: String, contentType: String): Transcription {
        val model = models.findByIdOrNull(modelId)
            ?: return Transcription.Failed("That model no longer exists")
        if (model.kind != ModelKind.TRANSCRIPTION) {
            return Transcription.Failed("${model.name} is not a transcription model")
        }
        if (!model.enabled) return Transcription.Failed("${model.name} is turned off")

        val provider = providers.findByIdOrNull(model.providerId)
            ?: return Transcription.Failed("The provider ${model.name} belongs to has been removed")

        val endpoint = "${provider.endpoint.trimEnd('/')}/audio/transcriptions"
        val uri = try {
            URI(endpoint)
        } catch (_: Exception) {
            return Transcription.Failed("The provider endpoint is not a usable URL")
        }

        /*
         * Asked before the recording is sent anywhere, and not only behind the
         * "Test provider" button: the credential rides on this request, and a
         * check that only happens when somebody presses something is a check
         * that mostly does not happen. [ConnectionProbe] decides, the same rule
         * the connection probe applies, and the refusal is what the dictation
         * button reports back rather than a line in a log. Before the
         * credential too, so a call that will not be made fetches nothing.
         */
        connections.vet(endpoint)?.let { return Transcription.Failed("${provider.name} cannot be called: $it") }

        val credential = when (val resolved = probe.credentials(provider)) {
            is ModelProviderProbe.Credential.Failed -> return Transcription.Failed(resolved.reason)
            is ModelProviderProbe.Credential.Header -> resolved.header
        }

        val boundary = "orknux-${UUID.randomUUID()}"
        val request = HttpRequest.newBuilder(uri)
            // Generous: a minute of speech takes a while on a small machine,
            // and the alternative to waiting is losing what was said.
            .timeout(Duration.ofSeconds(REQUEST_SECONDS))
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .header(credential.name, credential.value)
            .POST(HttpRequest.BodyPublishers.ofByteArray(form(boundary, model.modelId, audio, filename, contentType)))
            .build()

        val started = System.currentTimeMillis()
        return try {
            val answer = http.send(request, HttpResponse.BodyHandlers.ofString())
            val millis = System.currentTimeMillis() - started
            if (answer.statusCode() !in 200..299) {
                log.warn("Transcription by {} answered {}", model.name, answer.statusCode())
                return Transcription.Failed("${model.name} answered ${answer.statusCode()}")
            }
            heard(answer.body(), millis, model.name)
        } catch (failure: Exception) {
            log.warn("Transcription by {} could not be done", model.name, failure)
            Transcription.Failed(failure.message ?: "The transcription could not be done")
        }
    }

    /**
     * What was said, out of what the server answered.
     *
     * `{"text": "..."}` is the shape every one of these speaks; a server that
     * answered plain text is taken at its word rather than refused, since the
     * transcript is the whole of what was wanted.
     */
    private fun heard(body: String, millis: Long, name: String): Transcription {
        val text = runCatching { mapper.readTree(body).get("text")?.stringValue() }.getOrNull()
            ?: body.trim().takeIf { it.isNotEmpty() && !it.startsWith("{") }
            ?: return Transcription.Failed("$name answered something that was not a transcript")

        return Transcription.Heard(text.trim(), millis)
    }

    /** The multipart body: the audio, and which model to run it through. */
    private fun form(
        boundary: String,
        modelId: String,
        audio: ByteArray,
        filename: String,
        contentType: String,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun write(text: String) = out.write(text.toByteArray(StandardCharsets.UTF_8))

        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
        write("$modelId\r\n")

        // Asked for as plain JSON, because the transcript is all this wants and
        // the segment-by-segment shape is another thing to parse.
        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"response_format\"\r\n\r\n")
        write("json\r\n")

        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n")
        write("Content-Type: $contentType\r\n\r\n")
        out.write(audio)
        write("\r\n--$boundary--\r\n")
        return out.toByteArray()
    }

    private companion object {
        val log = LoggerFactory.getLogger(ModelTranscriptionClient::class.java)

        const val CONNECT_SECONDS = 10L
        const val REQUEST_SECONDS = 120L
    }
}
