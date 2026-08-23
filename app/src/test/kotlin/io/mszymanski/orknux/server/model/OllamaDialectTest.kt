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
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.connector.model.ModelUsageRepository
import io.mszymanski.orknux.connector.model.ProviderStatus
import io.mszymanski.orknux.connector.model.ProviderType
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
 * A provider of type OLLAMA is called in Ollama's own dialect.
 *
 * The stub is Ollama: it answers under `/v1` and 404s everywhere else, which is
 * what the real daemon does at the address an operator naturally writes,
 * `http://host:11434`. So a test that passes here is a test that would have
 * failed against the old `$endpoint/models`, and it fails for the same reason a
 * person's installation did.
 *
 * The chat is exercised beside the check on purpose. Teaching only the probe
 * would move the failure rather than fix it: the check would report Connected
 * from `/v1/models` while every message went to `/chat/completions` and 404'd,
 * which is 7876cdd's defect rebuilt.
 */
@SpringBootTest
class OllamaDialectTest(
    @Autowired val service: ModelService,
    @Autowired val chat: ModelChatClient,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val usage: ModelUsageRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer

    /** Every path the stub was asked for, in order. */
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
        server.createContext("/") { exchange ->
            asked += exchange.requestURI.path
            when (exchange.requestURI.path) {
                "/v1/models" -> answer(exchange, 200, """{"object":"list","data":[{"id":"llama3:8b"},{"id":"qwen2"}]}""")
                "/v1/chat/completions" -> answer(
                    exchange,
                    200,
                    """{"choices":[{"message":{"content":"Hello."}}],"usage":{"prompt_tokens":4,"completion_tokens":1}}""",
                )
                // Ollama's own listing, which this must not be reaching for.
                "/api/tags" -> answer(exchange, 200, """{"models":[{"name":"llama3:8b"}]}""")
                else -> answer(exchange, 404, """{"error":"not found"}""")
            }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `an Ollama provider given the address it listens on is checked at its OpenAI surface`() {
        val provider = provider(ProviderType.OLLAMA, root())

        val checked = service.testProvider(provider)

        assertThat(checked.status).isEqualTo(ProviderStatus.CONNECTED)
        assertThat(checked.lastCheckMessage).isEqualTo("Connected; 2 models listed")
        assertThat(asked).containsExactly("/v1/models")
    }

    /**
     * And the chat lands on the same surface the check proved.
     *
     * This is the half that makes the fix worth anything: a green check in front
     * of a chat that 404s is worse than a red one, because it sends whoever is
     * debugging it to look at the endpoint, which is right.
     */
    @Test
    fun `a message to an Ollama model goes to the surface the check reached`() {
        val provider = provider(ProviderType.OLLAMA, root())
        val model = requireNotNull(
            models.save(
                LlmModel(providerId = provider, name = "Llama 3", modelId = "llama3:8b", kind = ModelKind.CHAT),
            ).id,
        )

        val answer = chat.complete(model, listOf(ChatTurn(role = "user", content = "Hi")))

        assertThat(answer).isInstanceOf(ChatCompletion.Answered::class.java)
        assertThat(asked).containsExactly("/v1/chat/completions")
    }

    /**
     * The workaround people have been using is not punished for it. `/v1` is
     * added because the type knows where the surface is, not appended blindly.
     */
    @Test
    fun `an endpoint already written with v1 is not doubled`() {
        val provider = provider(ProviderType.OLLAMA, "${root()}/v1")

        val checked = service.testProvider(provider)

        assertThat(checked.status).isEqualTo(ProviderStatus.CONNECTED)
        assertThat(asked).containsExactly("/v1/models")
    }

    /**
     * And the segment belongs to the type rather than to everybody. A provider
     * of another type at the same address is still asked for `/models`, which is
     * where OpenAI, Anthropic and every self-hosted server that speaks the shape
     * actually put it.
     */
    @Test
    fun `another type at the same address is still asked for models`() {
        val provider = provider(ProviderType.OPENAI, root())

        val checked = service.testProvider(provider)

        assertThat(checked.status).isEqualTo(ProviderStatus.FAILED)
        assertThat(checked.lastCheckMessage).contains("No model list at ${root()}/models")
        assertThat(asked).containsExactly("/models")
    }

    private fun provider(type: ProviderType, endpoint: String): Long = requireNotNull(
        providers.save(
            ModelProvider(
                workspaceId = workspaceId,
                name = "Ollama ${type.name}",
                type = type,
                endpoint = endpoint,
                secret = "sk-test",
            ),
        ).id,
    )

    private fun root(): String = "http://${server.address.hostString}:${server.address.port}"

    private fun answer(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }
}
