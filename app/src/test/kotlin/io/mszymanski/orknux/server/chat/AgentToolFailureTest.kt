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
 * A tool that failed, in a chat that used to be one transaction with it.
 *
 * `AgentTools.run` says of itself: "Never throws. A tool that failed is a fact
 * the model should be told, not a failed conversation — it can apologise, try
 * another way, or answer without it, and any of those beats the whole exchange
 * dying because a lookup did." `OrknuxTools.run` says the same thing again.
 *
 * Neither could keep that promise, and for the reason the scheduler could not.
 * `ChatService.send` is `@Transactional`; the tools it reaches — `IssueTools`
 * `open`, `comment`, `setStatus`, `update` — were `@Transactional` with the
 * default propagation, so they joined that same transaction rather than opening
 * one. Catching an exception does not undo what it did to the transaction it
 * was thrown in.
 *
 * Against that code this is what happened, in order:
 *
 *  1. `IssueTools.open` insisted on inserting the issue, and Postgres refused:
 *     `value too long for type character varying(200)`.
 *  2. `OrknuxTools.run` caught it and handed the model a polite refusal. The
 *     conversation carried on exactly as the doc comments promise.
 *  3. But the connection was now in an aborted transaction — every later
 *     statement on it answers `current transaction is aborted, commands ignored
 *     until end of transaction block`.
 *  4. So the next line of `ChatService.send` that touches the database, reading
 *     the thread back to write the answer into it, threw
 *     `UncategorizedSQLException` — about `SPRING_AI_CHAT_MEMORY`, a table the
 *     person has never heard of, in a statement that has nothing to do with
 *     what went wrong.
 *  5. No exception resolver maps it, so it fell through every `else -> null`
 *     and reached the browser as a bare `INTERNAL_ERROR` with no message at all.
 *
 * What the person saw: they asked an agent to file an issue, and the entire
 * turn vanished — the agent's answer, and their own message with it — behind an
 * error that says nothing. The same species of sentence as a 401 reported as a
 * missing WWW-Authenticate header, except that here there was no sentence.
 *
 * The failure driven here is one a model can cause by itself and nothing caught
 * by name: a title longer than the 200 characters `issue.title` holds. A model
 * inventing a long title is not an exotic event.
 *
 * Two changes hold it now, and there is a test for each. The tracker tools open
 * their own transaction, so whatever one of them does to a transaction stays in
 * its own — the shape `ScheduledTriggerOccurrence` uses for scheduled triggers.
 * And a title too long for the column is refused by the tool, in a sentence
 * naming the limit, so the ordinary case never reaches the database at all.
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
     * The reported fault, from the person's side.
     *
     * A model wrote a title longer than `issue.title` holds. Against the code
     * as it stood that reached the database, the insert was refused, the
     * connection was left in an aborted transaction, and the next statement in
     * [ChatService.send] - reading the thread back to write the answer into it -
     * threw about `SPRING_AI_CHAT_MEMORY`. Nothing mapped that, so the whole
     * turn came back as a bare `INTERNAL_ERROR` with no message: the person's
     * own message gone, the answer gone, and nothing said about why.
     *
     * Two things now stop it, and this asserts the outer one. The tool checks
     * the length itself and refuses in a sentence naming the limit, so the
     * model is told something it can act on rather than handed a constraint
     * violation to interpret. What the person gets is the conversation: their
     * message, the answer, and no error at all.
     */
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

        /*
         * And the refusal is a sentence about the title, with the limit in it.
         * "value too long for type character varying(200)" is a fact about a
         * column; this is an instruction the model can follow, which is the
         * difference between an apology and a second, shorter attempt.
         */
        assertThat(received[1]).contains("200 characters or fewer")

        // Nothing was filed, which is correct - and the conversation survived it,
        // which is the whole point.
        assertThat(issues.findAll()).isEmpty()

        /*
         * What the person actually has afterwards. The turn that used to vanish
         * is both halves still there: what they said, and what they were told.
         */
        val said = graphQlTester.document("""query { chatMessages(id: $chatId) { role content } }""")
            .execute()
            .path("chatMessages[*].content")
            .entityList(String::class.java)
            .get()
        assertThat(said).contains("File an issue about the flaky build", "I could not file that.")
    }

    /**
     * The inner half: a tool's write is the tool's, and is not undone by what
     * happens to the turn around it.
     *
     * This is what `REQUIRES_NEW` on the tracker tools buys, and it is the same
     * thing said from the other side. The tools used to join
     * [ChatService.send]'s transaction, so a write they made was only as good
     * as the rest of the turn - the agent reported an issue filed, the turn
     * then failed for its own reasons, and the issue was rolled back out from
     * under the sentence that announced it. Here the model files one and the
     * provider then dies; the issue stays filed, because it was.
     *
     * It is also the cost of the shape, stated: a tool call that succeeded is
     * committed even when the exchange around it does not survive.
     */
    @Test
    fun `what a tool wrote survives a turn that dies after it`() {
        val endpoint = serveOpenIssueThenBreak()
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
            // The turn genuinely failed, and said so in words rather than as an
            // empty INTERNAL_ERROR.
            .satisfy { errors ->
                assertThat(errors).hasSize(1)
                assertThat(errors.single().message).isNotBlank()
            }

        assertThat(issues.findAll().map { it.title }).containsExactly("The build is flaky")
    }

    /**
     * Files an issue it is allowed to file, and is then failed by its provider.
     */
    private fun serveOpenIssueThenBreak(): String = serve { body ->
        if (body.contains("tool_call_id")) {
            null
        } else {
            """
            {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
              {"id":"call_1","type":"function",
               "function":{"name":"orknux_open_issue","arguments":"{\"title\":\"The build is flaky\"}"}}
            ]}}],"usage":{"prompt_tokens":7,"completion_tokens":2}}
            """.trimIndent()
        }
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

    /**
     * A stub provider. Answering null is the provider failing, which is how a
     * turn is made to die on a round of the conversation's choosing.
     */
    private fun serve(answer: (String) -> String?): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            received += body
            val said = answer(body)
            val bytes = (said ?: """{"error":"the provider fell over"}""").toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (said == null) 500 else 200, bytes.size.toLong())
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
