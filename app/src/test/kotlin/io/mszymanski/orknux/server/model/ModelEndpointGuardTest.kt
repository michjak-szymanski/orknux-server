package io.mszymanski.orknux.server.model

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.LlmModel
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.connector.model.ModelKind
import io.mszymanski.orknux.connector.model.ModelProvider
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.connector.model.ModelSpeechClient
import io.mszymanski.orknux.connector.model.ModelTranscriptionClient
import io.mszymanski.orknux.connector.model.ModelUsageRepository
import io.mszymanski.orknux.connector.model.Speech
import io.mszymanski.orknux.connector.model.Transcription
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where a provider's endpoint is allowed to take a request.
 *
 * The endpoint is whatever somebody typed into the provider form, and the stored
 * API key goes out on every call made to it - so the question of whether that
 * host may be called belongs on the calls themselves, not only behind the "Test
 * provider" button. A check that only happens when a button is pressed is a
 * check that mostly does not happen, and the chat, the reading and the dictation
 * all reach a provider without ever pressing it.
 *
 * The stub is a real server on the loopback address and it records what it was
 * asked. The refused endpoint is `0.0.0.0` on that same port: a host the guard
 * will not call, and a socket that would otherwise land on the stub. So an empty
 * record means the request was stopped rather than merely lost.
 */
@SpringBootTest
class ModelEndpointGuardTest(
    @Autowired val chat: ModelChatClient,
    @Autowired val speech: ModelSpeechClient,
    @Autowired val transcription: ModelTranscriptionClient,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val usage: ModelUsageRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer

    /** What the stub was asked for. Empty means nothing was sent. */
    private val asked = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun start() {
        usage.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        asked.clear()

        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            asked += exchange.requestURI.path
            answer(
                exchange,
                """{"choices":[{"message":{"content":"Hello."}}],"usage":{"prompt_tokens":11,"completion_tokens":3}}"""
                    .toByteArray(StandardCharsets.UTF_8),
                "application/json",
            )
        }
        server.createContext("/audio/speech") { exchange ->
            asked += exchange.requestURI.path
            answer(exchange, byteArrayOf(0x49, 0x44, 0x33, 0x04), "audio/mpeg")
        }
        server.createContext("/audio/transcriptions") { exchange ->
            asked += exchange.requestURI.path
            answer(exchange, """{"text":"Hello."}""".toByteArray(StandardCharsets.UTF_8), "application/json")
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `a chat endpoint the guard refuses is refused rather than called`() {
        val modelId = model(provider("Local", refusedEndpoint()), ModelKind.CHAT)

        val answered = chat.complete(modelId, listOf(ChatTurn(role = "user", content = "Hi")))

        assertThat(answered).isInstanceOf(ChatCompletion.Failed::class.java)
        // The provider is named and the reason is a sentence, because this is
        // what the chat screen puts in front of whoever set the endpoint.
        assertThat((answered as ChatCompletion.Failed).reason)
            .startsWith("Local cannot be called:")
            .contains("link-local")
        assertThat(asked).isEmpty()
    }

    /** The streamed path is the one the chat screen actually uses. */
    @Test
    fun `the streamed path refuses it too`() {
        val modelId = model(provider("Local", refusedEndpoint()), ModelKind.CHAT)

        val answered = chat.stream(modelId, listOf(ChatTurn(role = "user", content = "Hi"))) { }

        assertThat(answered).isInstanceOf(ChatCompletion.Failed::class.java)
        assertThat((answered as ChatCompletion.Failed).reason).startsWith("Local cannot be called:")
        assertThat(asked).isEmpty()
    }

    @Test
    fun `reading aloud and dictation refuse the same endpoint`() {
        val providerId = provider("Local", refusedEndpoint())

        val spoken = speech.speak(model(providerId, ModelKind.SPEECH), "Read this", voice = null)
        val heard = transcription.transcribe(
            model(providerId, ModelKind.TRANSCRIPTION),
            audio = byteArrayOf(1, 2, 3),
            filename = "clip.webm",
            contentType = "audio/webm",
        )

        // Both answer the browser with their reason, so a refusal shows up as
        // the error on the button rather than as a line in a log.
        assertThat(spoken).isInstanceOf(Speech.Failed::class.java)
        assertThat((spoken as Speech.Failed).reason).startsWith("Local cannot be called:")
        assertThat(heard).isInstanceOf(Transcription.Failed::class.java)
        assertThat((heard as Transcription.Failed).reason).startsWith("Local cannot be called:")
        // Neither the sentence nor the recording left this machine.
        assertThat(asked).isEmpty()
    }

    /**
     * The half that matters most: a guard refusing everything would pass every
     * test above and break every installation.
     */
    @Test
    fun `an ordinary endpoint is still called by all three`() {
        val providerId = provider("Local", "http://${server.address.hostString}:${server.address.port}")

        val answered = chat.complete(model(providerId, ModelKind.CHAT), listOf(ChatTurn("user", "Hi")))
        val spoken = speech.speak(model(providerId, ModelKind.SPEECH), "Read this", voice = null)
        val heard = transcription.transcribe(
            model(providerId, ModelKind.TRANSCRIPTION),
            audio = byteArrayOf(1, 2, 3),
            filename = "clip.webm",
            contentType = "audio/webm",
        )

        assertThat(answered).isInstanceOf(ChatCompletion.Answered::class.java)
        assertThat((answered as ChatCompletion.Answered).content).isEqualTo("Hello.")
        assertThat(spoken).isInstanceOf(Speech.Spoke::class.java)
        assertThat(heard).isInstanceOf(Transcription.Heard::class.java)
        assertThat((heard as Transcription.Heard).text).isEqualTo("Hello.")
        assertThat(asked).containsExactlyInAnyOrder("/chat/completions", "/audio/speech", "/audio/transcriptions")
    }

    /**
     * The stub's own port under a host the guard will not call. `0.0.0.0` is the
     * unspecified address, which a client sends to this machine - so it is the
     * same server by another name, and the check is the only thing in the way.
     */
    private fun refusedEndpoint(): String = "http://0.0.0.0:${server.address.port}"

    private fun provider(name: String, endpoint: String): Long = requireNotNull(
        providers.save(
            ModelProvider(workspaceId = workspaceId, name = name, endpoint = endpoint, secret = "sk-test"),
        ).id,
    )

    private fun model(providerId: Long, kind: ModelKind): Long = requireNotNull(
        models.save(
            LlmModel(providerId = providerId, name = "Stub ${kind.name}", modelId = "stub-model", kind = kind),
        ).id,
    )

    private fun answer(exchange: HttpExchange, body: ByteArray, contentType: String) {
        exchange.requestBody.use { it.readBytes() }
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
        exchange.close()
    }
}
