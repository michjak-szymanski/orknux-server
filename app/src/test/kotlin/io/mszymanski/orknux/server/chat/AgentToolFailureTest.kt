package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.issue.IssueRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
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
 * A tool that failed, in a chat that is one transaction.
 *
 * `AgentTools.run` says of itself: "Never throws. A tool that failed is a fact
 * the model should be told, not a failed conversation — it can apologise, try
 * another way, or answer without it, and any of those beats the whole exchange
 * dying because a lookup did." `OrknuxTools.run` says the same thing again.
 *
 * Neither can keep that promise, and for the reason the scheduler could not.
 * `ChatService.send` is `@Transactional`; the tools it reaches — `IssueTools`
 * `open`, `comment`, `setStatus`, `update` — are `@Transactional` with the
 * default propagation, so they join that same transaction rather than opening
 * one. Catching an exception does not undo what it did to the transaction it
 * was thrown in.
 *
 * Run against the code as it stands, this is what happens, in order:
 *
 *  1. `IssueTools.open` insists on inserting the issue, and Postgres refuses:
 *     `value too long for type character varying(200)`.
 *  2. `OrknuxTools.run` catches it and hands the model a polite refusal. The
 *     conversation carries on exactly as the doc comments promise.
 *  3. But the connection is now in an aborted transaction — every later
 *     statement on it answers `current transaction is aborted, commands ignored
 *     until end of transaction block`.
 *  4. So the next line of `ChatService.send` that touches the database, reading
 *     the thread back to write the answer into it, throws
 *     `UncategorizedSQLException` — about `SPRING_AI_CHAT_MEMORY`, a table the
 *     person has never heard of, in a statement that has nothing to do with
 *     what went wrong.
 *  5. No exception resolver maps it, so it falls through every `else -> null`
 *     and reaches the browser as a bare `INTERNAL_ERROR` with no message at all.
 *
 * What the person sees: they asked an agent to file an issue, and the entire
 * turn vanished — the agent's answer, and their own message with it — behind an
 * error that says nothing. The same species of sentence as a 401 reported as a
 * missing WWW-Authenticate header, except that here there is no sentence.
 *
 * The failure driven here is one a model can cause by itself and nothing catches
 * by name: a title longer than the 200 characters `issue.title` holds. A model
 * inventing a long title is not an exotic event.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class AgentToolFailureTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val agents: AgentRepository,
    @Autowired val sessions: ChatSessionRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val issues: IssueRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer

    private val received = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        sessions.deleteAll()
        agents.deleteAll()
        issues.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        received.clear()
    }

    @AfterEach
    fun stop() = server.stop(0)

    /**
     * Disabled because the fix is a decision, not a repair.
     *
     * The test is real and fails against the code as it stands — it was run.
     * What to do about it is the part worth choosing deliberately, because every
     * option changes something a person sees:
     *
     *  - Give the tracker tools `Propagation.REQUIRES_NEW`, so a tool's failure
     *    stays inside the tool. That is the shape [ScheduledTriggerOccurrence]
     *    already uses and it keeps the promise the doc comments make. It also
     *    means a tool call that half-succeeded is committed while the chat turn
     *    around it may still fail, which is a real change in what "the agent
     *    filed an issue" means.
     *  - Or take `@Transactional` off `ChatService.send` and put boundaries
     *    around the writes inside it, so the turn is no longer one unit.
     *  - Or leave the transaction alone and stop the catches from lying: let a
     *    tool failure end the exchange with a sentence about the tool.
     *
     * The first is what the trigger fix chose for the same shape. It is still
     * yours to pick.
     */
    @Disabled("Decision needed: REQUIRES_NEW on the tracker tools, or narrower boundaries in ChatService.send")
    @Test
    fun `a tool whose write fails does not take the whole exchange with it`() {
        val endpoint = serveOpenIssueThenApologise()
        val agentId = agentWithOrknux("Filer", model(endpoint))
        val chatId = startChat()
        graphQlTester.document("""mutation { chooseChatAgent(id: $chatId, agentId: $agentId) { agentId } }""")
            .execute()

        graphQlTester.document(
            """
            mutation {
              sendChatMessage(id: $chatId, text: "File an issue about the flaky build") {
                answer { content }
              }
            }
            """,
        ).execute()
            .errors()
            .verify()
            .path("sendChatMessage.answer.content")
            .entity(String::class.java)
            .isEqualTo("I could not file that.")

        // Two rounds, so the refusal really did go back to the model and it
        // really did answer around it. Without this the assertion above could
        // pass on a turn that never called a tool at all.
        assertThat(received).hasSize(2)
        assertThat(received[1]).contains("tool_call_id")

        // Nothing was filed, which is correct — and the conversation survived it,
        // which is the whole point.
        assertThat(issues.findAll()).isEmpty()
    }

    /**
     * Asks to open an issue with a title the column cannot hold, then apologises
     * once it has been told it did not work.
     */
    private fun serveOpenIssueThenApologise(): String = serve { body ->
        if (body.contains("tool_call_id")) {
            """{"choices":[{"message":{"role":"assistant","content":"I could not file that."}}],
               "usage":{"prompt_tokens":9,"completion_tokens":4}}"""
        } else {
            val title = "the build is flaky ".repeat(20)
            """
            {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
              {"id":"call_1","type":"function",
               "function":{"name":"orknux_open_issue","arguments":"{\"title\":\"$title\"}"}}
            ]}}],"usage":{"prompt_tokens":7,"completion_tokens":2}}
            """.trimIndent()
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

    private fun agentWithOrknux(name: String, modelId: Long): Long {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: {
                 name: "$name", modelId: $modelId, orknuxAccess: true
               }) { id } }""",
        ).execute()
        return id
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

    private fun startChat(): Long = graphQlTester.document(
        """mutation { startChat(input: { workspaceId: $workspaceId, title: "Build" }) { id } }""",
    ).execute().path("startChat.id").entity(Long::class.java).get()
}
