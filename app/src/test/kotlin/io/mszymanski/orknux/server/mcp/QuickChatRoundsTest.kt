package io.mszymanski.orknux.server.mcp

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A model that keeps looking things up, and the panel that has to answer anyway.
 *
 * "Can you finish this function?" came back as "That took more looking up than
 * this panel is for" - a refusal about the panel rather than an answer, after
 * the model had already read everything it needed. Reading a function costs two
 * calls on its own, and a model that looks things up one at a time rather than
 * in parallel can spend every round doing it.
 *
 * The fix is not a bigger number. The last round is asked with no tools at all,
 * so the only thing left to do is say what it found; the stub here would
 * otherwise call tools forever, which is what makes that the thing being tested.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class QuickChatRoundsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val quickChat: QuickChat,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer
    private val received = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        received.clear()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `a model that never stops looking things up is made to answer`() {
        val modelId = model(serveToolsWhileOffered())

        val answer = quickChat.answer(
            modelId = modelId,
            workspaceId = workspaceId,
            mayWrite = true,
            page = null,
            said = listOf(ChatTurn("user", "Can you finish this function?")),
        )

        // An answer, not a refusal about the panel.
        assertThat(answer.completion).isInstanceOf(ChatCompletion.Answered::class.java)
        assertThat((answer.completion as ChatCompletion.Answered).content).isEqualTo("Here is what I found.")

        // Every round but the last offered tools; the last offered none, which
        // is the whole of why there is an answer at all.
        assertThat(received.size).isGreaterThan(1)
        assertThat(received.dropLast(1)).allSatisfy { assertThat(it).contains("\"tools\":") }
        assertThat(received.last()).doesNotContain("\"tools\":")
    }

    /** Calls a tool for as long as it is offered one, and answers when it is not. */
    private fun serveToolsWhileOffered(): String = serve { body ->
        // Whether tools were offered on *this* request, not whether the
        // history mentions one: every round after the first carries the
        // calls already made, names and all.
        if (body.contains("\"tools\":")) {
            """
            {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
              {"id":"call_n","type":"function","function":{"name":"orknux_workflows","arguments":"{}"}}
            ]}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}
            """.trimIndent()
        } else {
            """{"choices":[{"message":{"role":"assistant","content":"Here is what I found."}}],
               "usage":{"prompt_tokens":9,"completion_tokens":4}}"""
        }
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

    private fun model(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT })
               { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }
}
