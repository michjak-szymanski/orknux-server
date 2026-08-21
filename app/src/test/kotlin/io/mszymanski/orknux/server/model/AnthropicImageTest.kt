package io.mszymanski.orknux.server.model

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.connector.model.ModelUsageRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A picture sent to an Anthropic model actually arrives.
 *
 * It did not. `openAiBody` carried a turn's images as `image_url` parts and
 * `anthropicBody` had no branch for them at all, so a turn with a picture
 * reached an Anthropic provider as words alone. Nothing failed and nothing was
 * logged - the model simply answered, plausibly and at length, about something
 * it had never been shown. An agent whose entire purpose was reading a
 * screenshot appeared to work.
 *
 * That is why this test asserts the bytes on the wire rather than that the call
 * succeeded: succeeding was never the problem. The stub records the request body
 * and the assertions are about what is in it.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class AnthropicImageTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val usage: ModelUsageRepository,
    @Autowired val chat: ModelChatClient,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val mapper: ObjectMapper,
) {

    /** A one-pixel PNG, so the data is real base64 rather than a word. */
    private val png = "data:image/png;base64," +
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

    private var workspaceId: Long = 0
    private lateinit var provider: HttpServer

    /** Every request body the stub was sent. */
    private val sent = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun start() {
        sent.clear()
        usage.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)

        provider = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        provider.createContext("/") { exchange ->
            sent += exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val body = """{"content":[{"type":"text","text":"A picture of nothing much."}],
                "usage":{"input_tokens":11,"output_tokens":7}}"""
                .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        provider.start()
    }

    @AfterEach
    fun stop() = provider.stop(0)

    @Test
    fun `a picture reaches an Anthropic model as an image block`() {
        val modelId = anthropicModel()

        val answer = chat.complete(
            modelId,
            listOf(ChatTurn(role = "user", content = "What is this?", images = listOf(png))),
        )

        assertThat(answer).isInstanceOf(ChatCompletion.Answered::class.java)
        assertThat(sent).hasSize(1)

        val content = mapper.readTree(sent.single()).path("messages").first().path("content")
        assertThat(content.isArray).describedAs("a turn with a picture is blocks, not a string").isTrue()

        // The words are still there, and they come first: a picture with the
        // question after it reads as a different question.
        assertThat(content.first().path("type").asString()).isEqualTo("text")
        assertThat(content.first().path("text").asString()).isEqualTo("What is this?")

        val image = content.last()
        assertThat(image.path("type").asString()).isEqualTo("image")
        assertThat(image.path("source").path("type").asString()).isEqualTo("base64")
        // Taken apart rather than passed through: Anthropic does not read a
        // data: URL, which is exactly why the OpenAI shape could not be reused.
        assertThat(image.path("source").path("media_type").asString()).isEqualTo("image/png")
        assertThat(image.path("source").path("data").asString())
            .isEqualTo(png.substringAfter("base64,"))
    }

    /**
     * The refusal, which is the other half of the fix.
     *
     * A picture that cannot be carried must not be quietly left behind - that is
     * the whole defect, in miniature. So an unusable one fails with a sentence,
     * and the request is never made: a model that is not going to see the
     * picture should not be asked about it at all.
     */
    @Test
    fun `a picture that cannot be carried is refused rather than dropped`() {
        val modelId = anthropicModel()

        val answer = chat.complete(
            modelId,
            listOf(ChatTurn(role = "user", content = "What is this?", images = listOf("data:image/tiff;base64,AAAA"))),
        )

        assertThat(answer).isInstanceOf(ChatCompletion.Failed::class.java)
        assertThat((answer as ChatCompletion.Failed).reason)
            .contains("image/tiff")
            .contains("image/png")
        assertThat(sent).describedAs("nothing should have been sent").isEmpty()
    }

    /**
     * And the shape everything else speaks is untouched.
     *
     * The two bodies are the product's claim that a provider is interchangeable.
     * Fixing one of them by changing what the other sends would trade this bug
     * for a wider one, so the OpenAI shape is asserted here beside it.
     */
    @Test
    fun `the OpenAI shape still sends a picture as an image_url part`() {
        val modelId = openAiModel()

        chat.complete(
            modelId,
            listOf(ChatTurn(role = "user", content = "What is this?", images = listOf(png))),
        )

        val content = mapper.readTree(sent.single()).path("messages").first().path("content")
        val image = content.last()
        assertThat(image.path("type").asString()).isEqualTo("image_url")
        assertThat(image.path("image_url").path("url").asString()).isEqualTo(png)
    }

    private fun anthropicModel(): Long = modelOn(newProvider("Anthropic", "ANTHROPIC"))

    private fun openAiModel(): Long = modelOn(newProvider("OpenAI", "OPENAI"))

    private fun newProvider(name: String, type: String): Long = graphQlTester.document(
        """mutation { createModelProvider(input: {
             workspaceId: $workspaceId, name: "$name", endpoint: "$endpoint", type: $type, secret: "sk-test"
           }) { id } }""",
    ).execute().path("createModelProvider.id").entity(Long::class.java).get()

    private fun modelOn(providerId: Long): Long = graphQlTester.document(
        """mutation { createModel(input: {
             providerId: $providerId, name: "Sees pictures", modelId: "a-model", kind: CHAT
           }) { id } }""",
    ).execute().path("createModel.id").entity(Long::class.java).get()

    private val endpoint: String
        get() = "http://${provider.address.hostString}:${provider.address.port}"
}
