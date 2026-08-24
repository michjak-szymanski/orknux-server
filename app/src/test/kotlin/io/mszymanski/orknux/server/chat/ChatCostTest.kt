package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentSkillRepository
import io.mszymanski.orknux.server.agent.SkillCatalogRepository
import io.mszymanski.orknux.server.user.AppUserRepository
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What an answer cost, on the way to the screen.
 *
 * The counting was never the missing part - `ModelChatClient` has recorded both
 * counts on every call since there was a chat, and `model_usage_day` has been
 * adding them up. What was missing was the last few feet: nothing carried them
 * out of the call, so a chat could say how long an answer took and nothing about
 * what it came to.
 *
 * So these are about carriage rather than arithmetic. A stub provider reports
 * counts the way OpenAI does, and each test follows one of them somewhere:
 * onto the answer, added up across an agent's rounds, costed at the model's
 * prices, and refused where there are none.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatCostTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val conversation: AgentConversation,
    @Autowired val client: ModelChatClient,
    @Autowired val sessions: ChatSessionRepository,
    @Autowired val history: ChatMemoryRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val catalogs: SkillCatalogRepository,
    @Autowired val skills: AgentSkillRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val users: AppUserRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer

    /** Every request body the stub was sent, so the first can be inspected. */
    private val received = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        sessions.findAll().forEach { history.deleteByConversationId(it.conversationId) }
        sessions.deleteAll()
        agents.deleteAll()
        skills.deleteAll()
        catalogs.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        // So the switch below starts from a row nobody has touched. Guarded
        // because another class may have left one with a token hanging off it,
        // and a fixture that cannot tidy up is not a reason to fail this.
        runCatching { users.findByUsername("alice")?.let { users.delete(it) } }
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        received.clear()
    }

    @AfterEach
    fun stop() = server.stop(0)

    /**
     * The plain case: a bare model, one round, priced.
     *
     * 2,000 in at $3 the million is $0.006 and 1,000 out at $15 is $0.015, so
     * the answer cost 2.1 cents. The assertion is on the number rather than on
     * it merely being present: the point of a recorded price is that it is
     * arithmetic somebody can check.
     */
    @Test
    fun `an answer says what it charged for and what that cost`() {
        val modelId = model(serveAnswer(input = 2000, output = 1000), inputPrice = 3.0, outputPrice = 15.0)
        val chatId = chatOn(modelId)

        graphQlTester.document(
            """mutation { sendChatMessage(id: $chatId, text: "Hello") {
                 millis inputTokens outputTokens cost
               } }""",
        ).execute()
            .path("sendChatMessage.inputTokens").entity(Double::class.java).isEqualTo(2000.0)
            .path("sendChatMessage.outputTokens").entity(Double::class.java).isEqualTo(1000.0)
            .path("sendChatMessage.cost").entity(Double::class.java).isEqualTo(0.021)
    }

    /**
     * A model nobody has priced is costed at nothing rather than at zero.
     *
     * The counts still arrive, because they are the provider's answer and not
     * ours to have an opinion about. What is absent is the money, and it is
     * absent rather than nought: `ModelUsage.costEstimate` has given the same
     * answer to the same question since the metrics card was written, and two
     * places disagreeing about what an unpriced model costs would be worse than
     * either answer.
     */
    @Test
    fun `a model with no prices reports its tokens and no cost`() {
        val modelId = model(serveAnswer(input = 2000, output = 1000))
        val chatId = chatOn(modelId)

        graphQlTester.document(
            """mutation { sendChatMessage(id: $chatId, text: "Hello") { inputTokens outputTokens cost } }""",
        ).execute()
            .path("sendChatMessage.inputTokens").entity(Double::class.java).isEqualTo(2000.0)
            .path("sendChatMessage.cost").valueIsNull()
    }

    /**
     * A provider that reports nothing leaves both at nought, and the screen
     * draws nothing rather than a zero - which is the whole of the rule about
     * an answer whose cost was never recorded.
     */
    @Test
    fun `a provider that reports no counts leaves them at nought`() {
        val modelId = model(serveWithoutUsage(), inputPrice = 3.0, outputPrice = 15.0)
        val chatId = chatOn(modelId)

        graphQlTester.document(
            """mutation { sendChatMessage(id: $chatId, text: "Hello") { inputTokens outputTokens } }""",
        ).execute()
            .path("sendChatMessage.inputTokens").entity(Double::class.java).isEqualTo(0.0)
            .path("sendChatMessage.outputTokens").entity(Double::class.java).isEqualTo(0.0)
    }

    /**
     * The number is the turn's, not the last call's.
     *
     * The stub asks for a tool on the first round - 7 in and 2 out - and answers
     * on the second, 9 and 4. What the turn cost is 16 and 6, because both
     * rounds were billed; the last call's 9 and 4 is the number that would
     * quietly understate every agent in the product, and by more the harder the
     * agent worked.
     */
    @Test
    fun `an agent's rounds are added up, the way its time already is`() {
        val modelId = model(serveToolThenAnswer())
        // Granted a catalogue, because an agent offered no tools takes one round
        // and there would be nothing to add up.
        val agentId = agentOn(modelId, catalog("Reviews"))
        val agent = requireNotNull(agents.findByIdOrNull(agentId))

        val answer = conversation.answer(modelId, agent, listOf(ChatTurn("user", "How should I review this?")))

        assertThat(answer).isInstanceOf(ChatCompletion.Answered::class.java)
        val answered = answer as ChatCompletion.Answered
        assertThat(answered.inputTokens).isEqualTo(16)
        assertThat(answered.outputTokens).isEqualTo(6)
    }

    /**
     * The streamed request asks for the counts, which is what makes any of this
     * visible on the path the chat window actually uses.
     *
     * A blocking call comes back with a `usage` object unasked; a stream sends
     * one only for a request carrying `stream_options.include_usage`, and this
     * one did not. So every OpenAI-shape answer given in the chat window was
     * recorded as nought tokens while the same model answered with a count
     * anywhere else - the metrics under-reported for exactly the traffic there
     * is most of, and there was nothing for a screen to show.
     */
    @Test
    fun `a streamed request asks the provider to include the counts`() {
        val modelId = model(serveStreamed())

        val answer = client.stream(modelId, listOf(ChatTurn("user", "Hello"))) { }

        assertThat(answer).isInstanceOf(ChatCompletion.Answered::class.java)
        assertThat(received).hasSize(1)
        assertThat(received[0]).contains("stream_options").contains("include_usage")
        // And the counts were read back out of the frame that carried no text.
        val answered = answer as ChatCompletion.Answered
        assertThat(answered.content).isEqualTo("Hi there")
        assertThat(answered.inputTokens).isEqualTo(31)
        assertThat(answered.outputTokens).isEqualTo(5)
    }

    /**
     * The switch is off until somebody turns it on, and it is theirs.
     *
     * Off by default is what the issue asked for. Per person rather than per
     * workspace because a chat is one person's - `ChatOwnership` refuses
     * anybody else's - so a workspace switch would settle what somebody else's
     * private screen shows; on the server rather than in the browser because it
     * follows them to the next machine, which is the line the interface draws
     * between its own storage and this.
     */
    @Test
    fun `the cost line is off until somebody turns it on, and is theirs alone`() {
        // Nothing is asked of a model here; the stub exists so the teardown has
        // a server to stop.
        serveAnswer(input = 1, output = 1)
        val id = graphQlTester.document(
            """mutation { createUser(input: { username: "alice", displayName: "Alice" })
               { id chatCostShown } }""",
        ).execute().path("createUser.chatCostShown").entity(Boolean::class.java).isEqualTo(false)
            .path("createUser.id").entity(Long::class.java).get()

        graphQlTester.document("""mutation { setChatCostShown(shown: true) { chatCostShown } }""")
            .execute().path("setChatCostShown.chatCostShown").entity(Boolean::class.java).isEqualTo(true)
        assertThat(requireNotNull(users.findByIdOrNull(id)).chatCostShown).isTrue()

        graphQlTester.document("""mutation { setChatCostShown(shown: false) { chatCostShown } }""")
            .execute().path("setChatCostShown.chatCostShown").entity(Boolean::class.java).isEqualTo(false)
    }

    private fun serveAnswer(input: Long, output: Long): String = serve {
        """{"choices":[{"message":{"role":"assistant","content":"Hello back."}}],
           "usage":{"prompt_tokens":$input,"completion_tokens":$output}}"""
    }

    private fun serveWithoutUsage(): String = serve {
        """{"choices":[{"message":{"role":"assistant","content":"Hello back."}}]}"""
    }

    private fun serveToolThenAnswer(): String = serve { body ->
        if (body.contains("tool_call_id")) {
            """{"choices":[{"message":{"role":"assistant","content":"Read the diff twice."}}],
               "usage":{"prompt_tokens":9,"completion_tokens":4}}"""
        } else {
            """
            {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
              {"id":"call_1","type":"function",
               "function":{"name":"skill_list","arguments":"{}"}}
            ]}}],"usage":{"prompt_tokens":7,"completion_tokens":2}}
            """.trimIndent()
        }
    }

    /** The counts arrive in a frame of their own, carrying no text - as they do. */
    private fun serveStreamed(): String = serve {
        listOf(
            """data: {"choices":[{"delta":{"content":"Hi "}}]}""",
            """data: {"choices":[{"delta":{"content":"there"}}]}""",
            """data: {"choices":[],"usage":{"prompt_tokens":31,"completion_tokens":5}}""",
            "data: [DONE]",
        ).joinToString("\n\n", postfix = "\n\n")
    }

    private fun serve(answer: (String) -> String): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            received += body
            val bytes = answer(body).toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    private fun model(endpoint: String, inputPrice: Double? = null, outputPrice: Double? = null): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

        val prices = listOfNotNull(
            inputPrice?.let { "inputCostPerMillion: $it" },
            outputPrice?.let { "outputCostPerMillion: $it" },
        ).joinToString("") { ", $it" }

        return graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT$prices
               }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    private fun chatOn(modelId: Long): Long = graphQlTester.document(
        """mutation { startChat(input: { workspaceId: $workspaceId, title: "Costs", modelId: $modelId })
           { id } }""",
    ).execute().path("startChat.id").entity(Long::class.java).get()

    private fun agentOn(modelId: Long, granted: String): Long {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Reviewer", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "Reviewer", modelId: $modelId,
                 skillCatalogs: ["$granted"] }) { id } }""",
        ).execute()
        return id
    }

    private fun catalog(name: String): String {
        graphQlTester.document(
            """mutation { createSkillCatalog(workspaceId: $workspaceId, name: "$name") { id } }""",
        ).execute().path("createSkillCatalog.id").entity(Long::class.java).get()
        return name
    }
}
