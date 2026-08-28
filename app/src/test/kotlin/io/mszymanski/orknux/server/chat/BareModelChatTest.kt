package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
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
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * The chats that were started on a bare model, after the door to making one
 * closed.
 *
 * Issue #295 removed the choice between an agent and a bare model, on the
 * grounds that it was never a choice: a bare model is an agent with the tools,
 * the skills, the grants, the memory catalogue and the system prompt taken off,
 * and it was offered beside agents as though it were a peer. What went is the
 * door. What did not go is anything already through it.
 *
 * That distinction is the whole of this class, and it is worth a test of its
 * own because it is the half a removal gets wrong. A migration that turned the
 * old chats into something else, a send path that started refusing them, a
 * screen that drew them as broken — each would be a defensible reading of "we
 * removed bare models" and each would take a conversation away from somebody who
 * was in the middle of it. Nothing is taken away. The row keeps its null
 * `agent_id`, the chat opens, the thread renders, and the model answers exactly
 * as it did.
 *
 * The chats are built through the repository because there is no longer an API
 * call that makes one. That is not a workaround; it is the assertion the rest of
 * the class rests on.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class BareModelChatTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val sessions: ChatSessionRepository,
    @Autowired val history: ChatMemoryRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer

    @BeforeEach
    fun reset() {
        sessions.findAll().forEach { history.deleteByConversationId(it.conversationId) }
        sessions.deleteAll()
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @AfterEach
    fun stop() {
        if (::server.isInitialized) server.stop(0)
    }

    /**
     * The one that matters: it still answers.
     *
     * Through the same mutation the screen sends, against a stub provider, so
     * what is proved is the whole path rather than that a row can be read.
     * `ChatService.ask` branches on the agent being null and completes against
     * the model with no tools offered — that branch is untouched by any of this
     * and this is what holds it there.
     */
    @Test
    fun `a chat started on a bare model still answers`() {
        val chatId = bareChat(model(serveSaying("Still here.")))

        graphQlTester.document(
            """mutation { sendChatMessage(id: $chatId, text: "Are you there?") { answer { role content } } }""",
        ).execute().errors().verify()
            .path("sendChatMessage.answer.content").entity(String::class.java).isEqualTo("Still here.")

        // And what was said is in the thread, which is what survives a reload.
        graphQlTester.document("""{ chatMessages(id: $chatId) { role content } }""")
            .execute()
            .path("chatMessages[*].content").entityList(String::class.java)
            .containsExactly("Are you there?", "Still here.")
    }

    /**
     * It renders, with the model's name where an agent's would be.
     *
     * `agentId` and `agentName` come back null, which is what tells the screen
     * to fall back to the model — the same three fields it has always read. A
     * chat this could not describe is a chat the sidebar would draw as an error.
     */
    @Test
    fun `a chat started on a bare model still opens and says what answers it`() {
        val modelId = model(serveSaying("Nothing to say."))
        val chatId = bareChat(modelId)

        graphQlTester.document("""{ chatSession(id: $chatId) { id title modelName agentId agentName } }""")
            .execute().errors().verify()
            .path("chatSession.title").entity(String::class.java).isEqualTo("From before")
            .path("chatSession.modelName").entity(String::class.java).isEqualTo("Stub")
            .path("chatSession.agentId").valueIsNull()
            .path("chatSession.agentName").valueIsNull()

        graphQlTester.document("""{ chatSessions(workspaceId: $workspaceId) { id } }""")
            .execute().errors().verify()
            .path("chatSessions[*].id").entityList(Long::class.java).containsExactly(chatId)
    }

    /**
     * And it can be adopted, which is the way out rather than a second door in.
     *
     * `chooseChatAgent` is the only mutation left that moves a chat between the
     * two, and it only moves it one way. Somebody who wants their old
     * conversation to have tools hands it to an agent and carries on in the same
     * thread; there is nothing that hands it back.
     */
    @Test
    fun `a chat on a bare model can be handed to an agent, and not the other way`() {
        val modelId = model(serveSaying("Nothing to say."))
        val chatId = bareChat(modelId)
        val agentId = agent("Reviewer", modelId)

        graphQlTester.document("""mutation { chooseChatAgent(id: $chatId, agentId: $agentId) { agentId } }""")
            .execute().errors().verify()
            .path("chooseChatAgent.agentId").entity(Long::class.java).isEqualTo(agentId)

        graphQlTester.document("""mutation { chooseChatAgent(id: $chatId, agentId: null) { agentId } }""")
            .execute()
            .errors().satisfy { errors -> assertThat(errors.first().message).contains("agentId") }
    }

    /**
     * A chat on a bare model, of the shape the ones from before this change
     * have: a model, no agent, and this person's name on it.
     */
    private fun bareChat(modelId: Long): Long = requireNotNull(
        sessions.save(
            ChatSession(
                workspaceId = workspaceId,
                conversationId = "3f2b9c4e-8a71-4d55-9c02-6d1e7f0a5b83",
                title = "From before",
                userId = "alice",
                modelId = modelId,
                agentId = null,
            ),
        ).id,
    )

    /** The model is set on update: creating an agent does not take one. */
    private fun agent(name: String, modelId: Long): Long {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
        ).execute().errors().verify().path("createAgent.id").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "$name", modelId: $modelId }) { id } }""",
        ).execute().errors().verify()
        return id
    }

    private fun model(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().errors().verify().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT
               }) { id } }""",
        ).execute().errors().verify().path("createModel.id").entity(Long::class.java).get()
    }

    /** A provider that answers once, in the words it is given, the way OpenAI's does. */
    private fun serveSaying(said: String): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val bytes = """{"choices":[{"message":{"role":"assistant","content":"$said"}}]}"""
                .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }
}
