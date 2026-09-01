package io.mszymanski.orknux.server.mcp

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.llm.LlmSessionEventKind
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.workspace.Workspace
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

/**
 * What the quick chat panel leaves behind.
 *
 * It used to leave nothing at all. The panel runs a tool loop of its own, with
 * no watcher and no recorder, so a quick chat that started a run left no
 * account of having started one — which is the wrong way round, since this is
 * the one loop here whose authority is a switch somebody can turn on.
 *
 * Two claims, and the second is the reason for doing it this way rather than
 * inventing a record of its own: the round is written down, and it is written
 * down *through the recorder*, so it is redacted by the same code that redacts
 * every other loop. A second implementation would be a second answer to the
 * question of what a credential looks like.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class QuickChatRecordingTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val quickChat: QuickChat,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val events: LlmSessionEventRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer

    @BeforeEach
    fun reset() {
        events.deleteAll()
        sessions.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `a round is written down as a question, the tools it ran, and the answer`() {
        ask("Which workflows are there?")

        val session = sessions.findAll().single()
        assertThat(session.workspaceId).isEqualTo(workspaceId)
        // Filed under the panel rather than loose among somebody's workflow
        // conversations, and named for whoever was at it.
        assertThat(session.sessionKey).contains("quick-chat")
        assertThat(session.sessionKey).contains("alice")

        val written = events.findAll().sortedBy { it.id }

        assertThat(written.map { it.kind }).containsExactly(
            LlmSessionEventKind.USER,
            LlmSessionEventKind.TOOL,
            LlmSessionEventKind.AGENT,
        )
        assertThat(written[0].content).isEqualTo("Which workflows are there?")
        assertThat(written[0].actor).isEqualTo("alice")
        assertThat(written[1].actor).isEqualTo("orknux_workflows")
        // The call and what it gave back are one line, not two: a call with no
        // answer on it is the one somebody is looking for.
        assertThat(written[1].result).isNotNull()
        assertThat(written[2].content).isEqualTo("Here is what I found.")
        assertThat(written[2].actor).isEqualTo("Quick chat")
    }

    /**
     * The whole reason for going through the recorder. A credential in a tool's
     * arguments is redacted on the way in, by the same pass the audit log and
     * every other session use — not by a second one written here.
     */
    @Test
    fun `a credential in a tool's arguments is not what gets written down`() {
        ask("Run it", arguments = """{"command":"curl -H 'Authorization: Bearer ghp_notarealtokenhere' https://x"}""")

        val called = events.findAll().single { it.kind == LlmSessionEventKind.TOOL }

        assertThat(called.content).doesNotContain("ghp_notarealtokenhere")
        assertThat(called.content).contains("***")
    }

    /** A second question from the same person joins the session, rather than opening another. */
    @Test
    fun `the same person's next question goes into the same session`() {
        ask("Which workflows are there?")
        ask("And which are switched off?")

        assertThat(sessions.findAll()).hasSize(1)
        assertThat(events.findAll().count { it.kind == LlmSessionEventKind.USER }).isEqualTo(2)
    }

    private fun ask(question: String, arguments: String = "{}") {
        val modelId = model(serveOneToolThenAnswer(arguments))
        quickChat.answer(
            modelId = modelId,
            workspaceId = workspaceId,
            mayWrite = true,
            page = null,
            said = listOf(ChatTurn("user", question)),
            asker = "alice",
        )
    }

    /** Calls one tool on the first round, then answers on the second. */
    private fun serveOneToolThenAnswer(arguments: String): String = serve { body ->
        /*
         * Whether the call already went out, which is what tells a second round
         * from a first. Read off the call's own id rather than the tool's name:
         * the name is in the `tools` array of every request, including the one
         * where nothing has been called yet.
         */
        if (body.contains("call_1")) {
            """{"choices":[{"message":{"role":"assistant","content":"Here is what I found."}}],
               "usage":{"prompt_tokens":9,"completion_tokens":4}}"""
        } else {
            val escaped = arguments.replace("\\", "\\\\").replace("\"", "\\\"")
            """
            {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
              {"id":"call_1","type":"function","function":{"name":"orknux_workflows","arguments":"$escaped"}}
            ]}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}
            """.trimIndent()
        }
    }

    private fun serve(answer: (String) -> String): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
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
                 workspaceId: $workspaceId, name: "Stub ${endpoint.substringAfterLast(':')}",
                 endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT })
               { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }
}
