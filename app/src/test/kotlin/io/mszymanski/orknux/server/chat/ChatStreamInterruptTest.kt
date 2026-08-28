package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.connector.model.ModelUsageRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.memory.ChatMemoryRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.test.context.support.WithMockUser
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A chat whose reader has gone stops the model, rather than paying it to finish.
 *
 * Issue #299, on the server's half. The browser learnt to abort the request when
 * somebody interrupts; this is the claim that aborting it means anything —
 * because it did not. A servlet container on blocking IO says nothing about a
 * browser that has left: it finds out on the next write, and a chat's answer is
 * one long call into a model with nothing to write for the whole of it. An
 * agent's round reports a lookup and then goes quiet while the answer is
 * composed, so there was no write to fail on and nothing noticed anything.
 * Measured before the fix, with the reader gone from the very first frame, the
 * stub provider below streamed **all four hundred** of them and was never hung
 * up on.
 *
 * **The assertion is made on the provider, not on the endpoint.** What is at
 * issue is whether the model call stopped, and the only witness to that is the
 * thing on the other end of it: the stub streams a long answer one frame at a
 * time and records whether its own write was ever refused. An endpoint that
 * closed its response to the browser and left the provider streaming would pass
 * any assertion made this side of it, which is exactly the bug.
 *
 * Both shapes are driven. Every chat opened now has an agent, but the ones made
 * before issue #295 are still there and still hold a bare model, and the two
 * take different branches through [ChatStreamAPI] - one that streams pieces to
 * the browser as they land and one that does not. The one that does not is
 * where this went unnoticed.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatStreamInterruptTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val streaming: ChatStreamAPI,
    @Autowired val sessions: ChatSessionRepository,
    @Autowired val history: ChatMemoryRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val agentTools: AgentToolRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val usage: ModelUsageRepository,
    @Autowired val llmSessions: LlmSessionRepository,
    @Autowired val llmEvents: LlmSessionEventRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer

    /** How many frames the stub provider got onto the wire before it was cut off. */
    private val written = AtomicInteger()

    /** Whether the stub's own write was refused, which is the provider being hung up on. */
    private val torn = AtomicBoolean()

    /** Counted down when the stub has stopped answering, however it stopped. */
    private val done = CountDownLatch(1)

    @BeforeEach
    fun reset() {
        sessions.findAll().forEach { history.deleteByConversationId(it.conversationId) }
        sessions.deleteAll()
        llmEvents.deleteAll()
        llmSessions.deleteAll()
        agents.deleteAll()
        agentTools.deleteAll()
        usage.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @AfterEach
    fun stop() = server.stop(0)

    /**
     * The ordinary chat: an agent answers, and nothing is written to the browser
     * while it does.
     *
     * This is the shape the issue was reported against and the one that noticed
     * nothing at all. The agent has no tools, so its round is one streaming call
     * whose pieces go to [RoundWatch.answering] - which this endpoint draws
     * nothing for, because the answer is sent whole when the round ends. So
     * between the question and that one frame there is no write, and before the
     * fix there was therefore nothing for a hang-up to be discovered on.
     */
    @Test
    fun `an agent's answer is stopped when the reader goes`() {
        val chatId = chatWithAgent(model(serve()))

        run(chatId)

        assertThat(torn.get()).isTrue()
        assertThat(written.get()).isLessThan(FRAMES)
    }

    /**
     * And a chat from before agents were compulsory, which streams every piece.
     *
     * Held because it is the branch that appeared to work: each chunk is written
     * to the browser as it lands, so a write does fail eventually and the SDK's
     * stream is closed on the way out. That was luck rather than design - it
     * worked only while the model was producing text, and stopped working the
     * moment the answer went quiet - and it is worth keeping honest either way.
     */
    @Test
    fun `a bare model's answer is stopped when the reader goes`() {
        val chatId = chatOnBareModel(model(serve()))

        run(chatId)

        assertThat(torn.get()).isTrue()
        assertThat(written.get()).isLessThan(FRAMES)
    }

    /**
     * And nothing an abandoned turn produced is written down.
     *
     * The chat keeps the question and no answer. What the model had composed
     * when the connection went is part of an answer somebody stopped on purpose,
     * and a conversation reopened tomorrow ending in half a sentence attributed
     * to the model is a worse record than one ending on the question.
     */
    @Test
    fun `an abandoned answer is not kept`() {
        val chatId = chatWithAgent(model(serve()))

        run(chatId)

        graphQlTester.document("{ chatMessages(id: $chatId) { role content } }")
            .execute()
            .path("chatMessages[*].role").entityList(String::class.java)
            .containsExactly("user")
    }

    /** The streaming door, run with a reader that has walked away already. */
    private fun run(chatId: Long) {
        val response = Gone()
        streaming.stream(chatId, ChatStreamRequest("Say something long"), response)
            .writeTo(response.outputStream)
        // The stub answers on a thread of its own, so what it made of the
        // hang-up is only settled once it has stopped.
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue()
    }

    /**
     * A response whose reader has gone.
     *
     * Every write and every flush fails, which is what a servlet container does
     * once the browser has closed the connection under it. The keep-alive
     * [io.mszymanski.orknux.server.stream.ReaderWatch] sends is a write like any
     * other, so this is also how it finds out.
     */
    private class Gone : MockHttpServletResponse() {
        private val stream = object : ServletOutputStream() {
            override fun write(b: Int) = throw IOException("Broken pipe")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("Broken pipe")
            override fun isReady() = true
            override fun setWriteListener(listener: WriteListener?) = Unit
        }

        override fun getOutputStream(): ServletOutputStream = stream

        override fun flushBuffer() = throw IOException("Broken pipe")
    }

    /**
     * A provider that streams a very long answer, one small frame at a time, and
     * remembers where it got to.
     *
     * Long enough that an answer allowed to run to the end is unmistakable, and
     * slow enough that a hang-up has somewhere to land: a stub that writes four
     * hundred frames in a millisecond is over before anything could stop it,
     * which would make this pass for the wrong reason.
     */
    private fun serve(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/chat/completions") { exchange ->
            exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            // Nought means chunked, which is what lets this answer arrive over
            // time rather than be handed over whole.
            exchange.sendResponseHeaders(200, 0)
            try {
                exchange.responseBody.use { out ->
                    repeat(FRAMES) {
                        out.write("""data: {"choices":[{"delta":{"content":"word "}}]}$BLANK""".toByteArray())
                        out.flush()
                        written.incrementAndGet()
                        Thread.sleep(FRAME_MILLIS)
                    }
                    out.write("data: [DONE]$BLANK".toByteArray())
                    out.flush()
                }
            } catch (_: IOException) {
                torn.set(true)
            } finally {
                done.countDown()
            }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    /** A chat of the shape the ones from before issue #295 have: a model, no agent. */
    private fun chatOnBareModel(modelId: Long): Long = requireNotNull(
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

    private fun chatWithAgent(modelId: Long): Long {
        val agentId = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Worker", type: LLM }) { id } }""",
        ).execute().errors().verify().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateAgent(id: $agentId, input: { name: "Worker", modelId: $modelId }) { id } }""",
        ).execute().errors().verify()

        val chatId = graphQlTester.document(
            """mutation { startChat(input: { workspaceId: $workspaceId, title: "Work" }) { id } }""",
        ).execute().errors().verify().path("startChat.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { chooseChatAgent(id: $chatId, agentId: $agentId) { id } }""")
            .execute().errors().verify().path("chooseChatAgent.id").hasValue()
        return chatId
    }

    private fun model(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", type: OPENAI, endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().errors().verify().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT })
               { id } }""",
        ).execute().errors().verify().path("createModel.id").entity(Long::class.java).get()
    }

    private companion object {
        /** What ends one server-sent frame: the blank line the protocol separates them with. */
        const val BLANK = "\n\n"

        /** Long enough that an answer running to the end is unmistakable. */
        const val FRAMES = 400

        /** And slow enough that there is a stream to interrupt rather than a burst. */
        const val FRAME_MILLIS = 5L
    }
}
