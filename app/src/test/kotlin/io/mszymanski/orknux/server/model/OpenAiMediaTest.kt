package io.mszymanski.orknux.server.model

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.connection.ConnectionProbe
import io.mszymanski.orknux.connector.connection.ConnectionProperties
import io.mszymanski.orknux.connector.model.LlmModel
import io.mszymanski.orknux.connector.model.ModelClients
import io.mszymanski.orknux.connector.model.ModelKind
import io.mszymanski.orknux.connector.model.ModelProvider
import io.mszymanski.orknux.connector.model.ModelProviderProbe
import io.mszymanski.orknux.connector.model.OpenAiMedia
import io.mszymanski.orknux.connector.model.ProviderType
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.connector.proxy.ProxyRuleSource
import io.mszymanski.orknux.connector.security.SecretCipher
import io.mszymanski.orknux.connector.security.SecretReferences
import io.mszymanski.orknux.connector.security.SecretVariables
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Drawing, reading aloud and listening, spoken through the SDK.
 *
 * These three moved off hand-built requests at the same time as the chat did
 * and, unlike the chat, arrived with nothing of their own — they rode on the
 * tests that existed for the screens above them, which say nothing about what
 * goes over the wire. Two of the three had something worth pinning: an
 * `images/generations` body that has to ask for exactly one picture, because
 * one recorded request is what the per-image price is multiplied by; and a
 * multipart transcription body that this used to assemble by hand, boundary
 * string and all, and now does not.
 *
 * The third is the reason the file exists at all. Speech gained a behaviour
 * this could not preserve: the hand-built request left the voice out when
 * nobody had configured one, so each server chose its own, and the SDK makes
 * the field required. What replaced it is a setting, and a setting nobody
 * asserts is a setting that quietly stops being read.
 */
class OpenAiMediaTest {

    private lateinit var server: HttpServer

    /** Every path asked for, and the body that came with it. */
    private val asked = CopyOnWriteArrayList<Pair<String, String>>()

    @BeforeEach
    fun start() {
        asked.clear()
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            asked += exchange.requestURI.path to body
            when {
                exchange.requestURI.path.endsWith("/images/generations") ->
                    reply(exchange, "application/json", """{"created":1,"data":[{"b64_json":"$PIXEL"}]}""")

                exchange.requestURI.path.endsWith("/audio/speech") ->
                    reply(exchange, "audio/mpeg", "ID3-not-really-an-mp3")

                exchange.requestURI.path.endsWith("/audio/transcriptions") ->
                    reply(exchange, "application/json", """{"text":"What was said."}""")

                else -> reply(exchange, "application/json", """{"error":{"message":"no"}}""")
            }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `a picture is asked for one at a time, by the model that was named`() {
        val drawn = media().draw(provider(), model(ModelKind.IMAGE, "gpt-image-1"), "a cat, badly")

        assertThat(drawn).isInstanceOf(OpenAiMedia.Drawn.Bytes::class.java)
        val (path, body) = asked.single()
        assertThat(path).isEqualTo("/images/generations")
        // One, because one recorded request is what the per-image price is
        // multiplied by; and the model in the body, not in the path.
        assertThat(body).contains("\"n\":1").contains("\"model\":\"gpt-image-1\"")
        assertThat(body).contains("a cat, badly")
    }

    @Test
    fun `a picture parked at a URL is handed back as an address rather than fetched here`() {
        server.removeContext("/")
        server.createContext("/") { exchange ->
            asked += exchange.requestURI.path to ""
            reply(exchange, "application/json", """{"created":1,"data":[{"url":"https://pictures.invalid/one.png"}]}""")
        }

        val drawn = media().draw(provider(), model(ModelKind.IMAGE, "gpt-image-1"), "a cat")

        // Following it is a second call to a host the provider chose, so it is
        // the caller's decision and goes through the address guard, not this.
        assertThat(drawn).isEqualTo(OpenAiMedia.Drawn.Elsewhere("https://pictures.invalid/one.png"))
    }

    @Test
    fun `a model's own voice is the one asked for`() {
        val speaking = model(ModelKind.SPEECH, "tts-1").apply { voice = "shimmer" }

        media().speak(provider(), speaking, "Read this.", "shimmer")

        val (path, body) = asked.single()
        assertThat(path).isEqualTo("/audio/speech")
        assertThat(body).contains("\"voice\":\"shimmer\"").contains("\"input\":\"Read this.\"")
        // MP3 because it is the one format all of them produce and every
        // browser plays.
        assertThat(body).contains("\"response_format\":\"mp3\"")
    }

    /**
     * The behaviour that could not be kept, pinned as what replaced it.
     *
     * Sending no voice at all is what this used to do and the SDK will not: the
     * field is required. A speech model carries its own voice, so the only rows
     * that reach this are ones saved before that field was there.
     */
    @Test
    fun `a model saved without a voice still reads, on a last resort`() {
        media().speak(provider(), model(ModelKind.SPEECH, "tts-1"), "Read this.", null)

        assertThat(asked.single().second).contains("\"voice\":")
    }

    @Test
    fun `a blank voice is no voice, and takes the same road`() {
        media().speak(provider(), model(ModelKind.SPEECH, "tts-1"), "Read this.", "   ")

        assertThat(asked.single().second).contains("\"voice\":")
    }

    @Test
    fun `a recording goes over as a form the SDK builds, and comes back as words`() {
        val heard = media().transcribe(provider(), model(ModelKind.TRANSCRIPTION, "whisper-1"), "audio".toByteArray())

        assertThat(heard).isEqualTo(OpenAiMedia.Heard.Words("What was said."))
        val (path, body) = asked.single()
        assertThat(path).isEqualTo("/audio/transcriptions")
        // The multipart this used to write out itself, boundary and all.
        assertThat(body).contains("Content-Disposition: form-data").contains("whisper-1")
    }

    private fun media(): OpenAiMedia {
        val properties = ConnectionProperties()
        val router = ProxyRouter(ProxyRuleSource { emptyList() })
        val probe = ModelProviderProbe(
            ConnectionProbe(properties, router, SecretCipher(TEST_KEY)),
            properties,
            ObjectMapper(),
            SecretReferences(SecretVariables { _, _ -> null }, SecretCipher(TEST_KEY)),
            router,
            ModelClients(router),
        )
        return OpenAiMedia(ModelClients(router), probe)
    }

    private fun provider() = ModelProvider(
        workspaceId = 1,
        name = "Provider",
        type = ProviderType.OPENAI,
        endpoint = "http://${server.address.hostString}:${server.address.port}",
        secret = "sk-test",
    )

    private fun model(kind: ModelKind, id: String) =
        LlmModel(providerId = 1, name = id, modelId = id, kind = kind)

    private fun reply(exchange: HttpExchange, type: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", type)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private companion object {
        const val TEST_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

        /** A one-pixel PNG, as bytes a provider would send back. */
        val PIXEL: String = Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
    }
}
