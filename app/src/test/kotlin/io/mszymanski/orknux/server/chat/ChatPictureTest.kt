package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.connector.model.ModelUsageRepository
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.ChatAttachmentRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.memory.ChatMemoryRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A model that draws, end to end: which providers are asked, where the picture
 * goes, what it cost, and what is said when nothing comes back.
 *
 * Nothing here reaches a real provider. The stub is an `HttpServer` on the
 * loopback address — the same arrangement `ModelEndpointGuardTest` uses — and it
 * records the paths it was asked for, so "the request was never made" is an
 * assertion rather than an absence of evidence.
 *
 * Its own attachment directory, under `target`, because these tests write real
 * bytes to a real disk: that is the point of them, and a development
 * installation's attachment folder is not the place to do it.
 */
@SpringBootTest(properties = ["orknux.attachments.location=target/test-pictures"])
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatPictureTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val sessions: ChatSessionRepository,
    @Autowired val history: ChatMemoryRepository,
    @Autowired val attachments: ChatAttachmentRepository,
    @Autowired val store: AttachmentStore,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val usage: ModelUsageRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer

    /** Which paths the stub was asked for. Empty means nothing was sent. */
    private val asked = CopyOnWriteArrayList<String>()

    /** What was in the body of each request, so the shape can be inspected. */
    private val bodies = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        sessions.findAll().forEach { history.deleteByConversationId(it.conversationId) }
        attachments.deleteAll()
        sessions.deleteAll()
        usage.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        asked.clear()
        bodies.clear()

        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    /**
     * The whole of it: asked for, drawn, filed, and in the chat afterwards.
     *
     * Four assertions and each one is a separate promise. The request went to
     * `/images/generations` and not to `/chat/completions`, which is the whole
     * of what makes this a different API rather than a prompt. The bytes are on
     * the disk under the workspace, which is what makes the picture outlive the
     * response. The chat holds the exchange, which is what makes it survive
     * being reopened. And the line it holds is a markdown image pointing at the
     * attachment, because that is what the screen renders it out of.
     */
    @Test
    fun `a description is drawn, filed under the workspace, and left in the chat`() {
        drawing(PIXEL)
        val chatId = chatWithImageModel(imagePrice = 0.04)

        val drawn = graphQlTester.document(
            """mutation { drawChatPicture(chatId: $chatId, prompt: "A red square")
               { attachmentId prompt said millis cost } }""",
        ).execute()
        val attachmentId = drawn.path("drawChatPicture.attachmentId").entity(Long::class.java).get()
        drawn.path("drawChatPicture.prompt").entity(String::class.java).isEqualTo("A red square")
            .path("drawChatPicture.said").entity(String::class.java)
            .isEqualTo("![A red square](/api/attachments/$attachmentId)")

        assertThat(asked).containsExactly("/images/generations")

        val filed = requireNotNull(attachments.findByIdOrNull(attachmentId))
        assertThat(filed.workspaceId).isEqualTo(workspaceId)
        assertThat(filed.chatSessionId).isEqualTo(chatId)
        assertThat(filed.contentType).isEqualTo("image/png")
        // Named from the description, so a file saved to a desktop says what it is.
        assertThat(filed.filename).isEqualTo("a-red-square.png")
        // And the bytes really are where the row says they are.
        assertThat(store.open(filed.location).use { it.readBytes() })
            .isEqualTo(Base64.getDecoder().decode(PIXEL))

        // The chat is the record: reopening it renders the picture out of this.
        graphQlTester.document("{ chatMessages(id: $chatId) { role content } }").execute()
            .path("chatMessages[0].role").entity(String::class.java).isEqualTo("user")
            .path("chatMessages[0].content").entity(String::class.java).isEqualTo("A red square")
            .path("chatMessages[1].role").entity(String::class.java).isEqualTo("assistant")
            .path("chatMessages[1].content").entity(String::class.java)
            .isEqualTo("![A red square](/api/attachments/$attachmentId)")
    }

    /**
     * One picture, and the request says so.
     *
     * `n: 1` is not a matter of taste. It is what makes one recorded request one
     * picture, which is what the per-image price is multiplied by — so a request
     * that quietly asked for four would make the cost line wrong by a factor of
     * four without anything failing.
     *
     * Nothing else is sent. No `size`, no `quality`, no `response_format`: those
     * are the provider's own vocabulary and a value from one is a 400 from
     * another, and OpenAI's own newest image model rejects `response_format`
     * outright while always answering in it.
     */
    @Test
    fun `it asks for one picture and dictates nothing else`() {
        drawing(PIXEL)
        val chatId = chatWithImageModel()

        graphQlTester.document(
            """mutation { drawChatPicture(chatId: $chatId, prompt: "A red square") { attachmentId } }""",
        ).execute().path("drawChatPicture.attachmentId").hasValue()

        assertThat(bodies).hasSize(1)
        assertThat(bodies[0]).contains("\"n\":1").contains("A red square").contains("stub-image")
        assertThat(bodies[0]).doesNotContain("response_format").doesNotContain("size")
    }

    /**
     * What it cost, at the price the model records — and nothing where it
     * records none.
     *
     * The second half is the one worth having. An image model has no token
     * prices and reports no tokens, so costed the ordinary way every picture
     * comes out at `$0.00` — a claim about money, and the wrong one. Null is the
     * answer, and the screen draws nothing for it.
     */
    @Test
    fun `a picture is costed per picture, and is uncosted where no price is recorded`() {
        drawing(PIXEL)
        val priced = chatWithImageModel(imagePrice = 0.04)

        graphQlTester.document(
            """mutation { drawChatPicture(chatId: $priced, prompt: "A red square") { cost } }""",
        ).execute().path("drawChatPicture.cost").entity(Double::class.java).isEqualTo(0.04)

        val unpriced = chatWithImageModel(name = "Second")
        graphQlTester.document(
            """mutation { drawChatPicture(chatId: $unpriced, prompt: "A blue square") { cost } }""",
        ).execute().path("drawChatPicture.cost").valueIsNull()
    }

    /**
     * And the metrics card costs its window the same way.
     *
     * Two pictures at four cents is eight cents, worked out from the requests
     * rather than from the tokens — of which there are none. Before this the
     * same window read `$0.00` for a model that had been drawing all month,
     * which is the number this whole arrangement exists not to print.
     */
    @Test
    fun `the usage window costs an image model by its requests`() {
        drawing(PIXEL)
        val chatId = chatWithImageModel(imagePrice = 0.04)
        val modelId = requireNotNull(requireNotNull(workspaces.findByIdOrNull(workspaceId)).imageModelId)

        repeat(2) {
            graphQlTester.document(
                """mutation { drawChatPicture(chatId: $chatId, prompt: "A red square") { attachmentId } }""",
            ).execute().path("drawChatPicture.attachmentId").hasValue()
        }

        graphQlTester.document("{ modelUsage(id: $modelId) { requests totalTokens costEstimate } }").execute()
            .path("modelUsage.requests").entity(Int::class.java).isEqualTo(2)
            // No tokens at all, which is exactly why the money cannot come from them.
            .path("modelUsage.totalTokens").entity(Double::class.java).isEqualTo(0.0)
            .path("modelUsage.costEstimate").entity(Double::class.java).isEqualTo(0.08)
    }

    /**
     * Ollama is refused in a sentence, and nothing is sent.
     *
     * A local Ollama is a first-class target for this product and it is worth
     * being exact: it runs chat models and embedding models, and models that see
     * are among them, so a picture sent *to* a model works against one. What it
     * has no endpoint for is drawing — neither its own `/api` surface nor the
     * OpenAI-compatible one under `/v1` offers image generation. Calling it
     * anyway would produce a 404 that reads as a mistyped endpoint, and somebody
     * would go and check a URL that was right.
     */
    @Test
    fun `Ollama is refused with a sentence rather than called`() {
        val chatId = chatWithImageModel(type = "OLLAMA")

        graphQlTester.document(
            """mutation { drawChatPicture(chatId: $chatId, prompt: "A red square") { attachmentId } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors).hasSize(1)
            assertThat(errors[0].message)
                .contains("has no image generation")
                .contains("Custom provider")
        }

        assertThat(asked).isEmpty()
        assertThat(attachments.count()).isZero()
    }

    /** Anthropic generates text. Claude reads a picture and does not draw one. */
    @Test
    fun `Anthropic is refused with a sentence rather than called`() {
        val chatId = chatWithImageModel(type = "ANTHROPIC")

        graphQlTester.document(
            """mutation { drawChatPicture(chatId: $chatId, prompt: "A red square") { attachmentId } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors[0].message).contains("generates text, not pictures")
        }

        assertThat(asked).isEmpty()
    }

    /**
     * A refused description comes back in the provider's own words.
     *
     * "The provider answered 400" would hide the one thing worth knowing about
     * it, and the picture is the whole of the request — so a failure that says
     * nothing leaves an empty bubble where the answer was meant to be.
     */
    @Test
    fun `a description the provider will not draw is refused in its own words`() {
        refusing("Your request was rejected by our safety system.")
        val chatId = chatWithImageModel()

        graphQlTester.document(
            """mutation { drawChatPicture(chatId: $chatId, prompt: "Something unwise") { attachmentId } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors[0].message).isEqualTo("Your request was rejected by our safety system.")
        }

        // Nothing was filed and nothing was written into the chat: a refusal is
        // not half a turn, and half a turn is what a description in the history
        // with no picture after it would be.
        assertThat(attachments.count()).isZero()
        val conversation = requireNotNull(sessions.findByIdOrNull(chatId)).conversationId
        assertThat(history.findByConversationId(conversation)).isEmpty()
    }

    /** A workspace that has chosen nothing to draw with says so, in a sentence. */
    @Test
    fun `a workspace with no image model refuses before anything is called`() {
        drawing(PIXEL)
        val chatId = chatWithImageModel(chosen = false)

        graphQlTester.document(
            """mutation { drawChatPicture(chatId: $chatId, prompt: "A red square") { attachmentId } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors[0].message).contains("This workspace has no image model")
        }

        assertThat(asked).isEmpty()
    }

    /**
     * Only an image model will do, refused where the picker is.
     *
     * The same rule the speech and transcription pickers follow, and this is the
     * half that keeps the failure early: a chat model chosen here would be sent
     * a description at an endpoint it does not serve.
     */
    @Test
    fun `the picker will not take a model of another kind`() {
        drawing(PIXEL)
        val providerId = provider("Stub", "OPENAI")
        val chatModel = graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "Talker", modelId: "stub-chat", kind: CHAT
               }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation { setWorkspaceImageModel(workspaceId: $workspaceId, modelId: $chatModel) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors[0].message).contains("is not an image model")
            // Said with a code beside it, so the interface can say it in Polish.
            assertThat(errors[0].extensions["code"]).isEqualTo("ModelNotImage")
        }
    }

    /**
     * A picture parked behind a link is collected now rather than pointed at.
     *
     * OpenAI's older image models answer with a URL by default and those links
     * expire within the hour, so a chat that stored the link would hold a
     * picture that worked until lunchtime. The bytes are fetched once and filed
     * like any other.
     */
    @Test
    fun `a picture answered as a link is collected and filed`() {
        val bytes = Base64.getDecoder().decode(PIXEL)
        server.createContext("/parked.png") { exchange ->
            asked += exchange.requestURI.path
            answer(exchange, bytes, "image/png", 200)
        }
        server.createContext("/images/generations") { exchange ->
            asked += exchange.requestURI.path
            bodies += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val url = "http://${server.address.hostString}:${server.address.port}/parked.png"
            answer(exchange, """{"data":[{"url":"$url"}]}""".toByteArray(StandardCharsets.UTF_8), "application/json", 200)
        }
        val chatId = chatWithImageModel()

        val attachmentId = graphQlTester.document(
            """mutation { drawChatPicture(chatId: $chatId, prompt: "A red square") { attachmentId } }""",
        ).execute().path("drawChatPicture.attachmentId").entity(Long::class.java).get()

        assertThat(asked).containsExactly("/images/generations", "/parked.png")
        val filed = requireNotNull(attachments.findByIdOrNull(attachmentId))
        assertThat(store.open(filed.location).use { it.readBytes() }).isEqualTo(bytes)
    }

    /** A stub that draws, answering the way OpenAI's newest image model does. */
    private fun drawing(base64: String) {
        server.createContext("/images/generations") { exchange ->
            asked += exchange.requestURI.path
            bodies += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            answer(
                exchange,
                """{"data":[{"b64_json":"$base64"}]}""".toByteArray(StandardCharsets.UTF_8),
                "application/json",
                200,
            )
        }
    }

    /** A stub that will not draw, answering the way these providers refuse. */
    private fun refusing(says: String) {
        server.createContext("/images/generations") { exchange ->
            asked += exchange.requestURI.path
            exchange.requestBody.use { it.readBytes() }
            answer(
                exchange,
                """{"error":{"message":"$says","type":"image_generation_user_error"}}"""
                    .toByteArray(StandardCharsets.UTF_8),
                "application/json",
                400,
            )
        }
    }

    private fun answer(exchange: HttpExchange, body: ByteArray, contentType: String, status: Int) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
        exchange.close()
    }

    private fun provider(name: String, type: String): Long = graphQlTester.document(
        """mutation { createModelProvider(input: {
             workspaceId: $workspaceId, name: "$name", type: $type,
             endpoint: "http://${server.address.hostString}:${server.address.port}", secret: "sk-test"
           }) { id } }""",
    ).execute().path("createModelProvider.id").entity(Long::class.java).get()

    /**
     * A chat in a workspace that has an image model, chosen unless said
     * otherwise.
     *
     * The chat's own model is left to the default, which is whatever the
     * workspace has: drawing does not consult it, and a chat that had to have a
     * conversational model before it could draw would be a rule nothing needs.
     */
    private fun chatWithImageModel(
        imagePrice: Double? = null,
        type: String = "OPENAI",
        name: String = "Stub",
        chosen: Boolean = true,
    ): Long {
        val providerId = provider(name, type)
        val price = imagePrice?.let { ", imageCostPerImage: $it" } ?: ""
        val modelId = graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "$name drawer", modelId: "stub-image", kind: IMAGE$price
               }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()

        if (chosen) {
            graphQlTester.document(
                """mutation { setWorkspaceImageModel(workspaceId: $workspaceId, modelId: $modelId) { id } }""",
            ).execute().path("setWorkspaceImageModel.id").hasValue()
        }

        return graphQlTester.document(
            """mutation { startChat(input: { workspaceId: $workspaceId, title: "Pictures" }) { id } }""",
        ).execute().path("startChat.id").entity(Long::class.java).get()
    }

    private companion object {
        /** A one-pixel PNG, base64, which is a real picture and eight bytes of one. */
        const val PIXEL =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }
}
