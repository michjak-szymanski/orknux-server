package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
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
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An agent's round, watched as it happens.
 *
 * Before this, a chat handed to an agent was a spinner and then a paragraph.
 * The lookups were being recorded the whole time — the transcript had them, and
 * a task's page drew them live off that same record — but a chat had no way to
 * be told, so a minute of work reached the person as a minute of nothing.
 *
 * The thing being pinned here is the *ordering*, which is the whole value of a
 * live view and the easy thing to get wrong. A call is announced **before its
 * tool runs**, so a tool that hangs shows as a lookup that was asked for and
 * has not come back rather than as no lookup at all — the same rule the
 * recorder follows, and for the same reason. If those two ever swapped over,
 * every screen would look correct on a fast tool and blank on a slow one, which
 * is the failure that only turns up in front of somebody waiting.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class AgentRoundWatchTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val conversation: AgentConversation,
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

    /** Everything the watcher was told, in the order it was told. */
    private val seen = CopyOnWriteArrayList<String>()

    private val watch = object : RoundWatch {
        override fun thinking(text: String) {
            seen += "thinking:$text"
        }

        override fun called(at: Int, tool: String, arguments: String) {
            seen += "called:$at:$tool"
        }

        override fun returned(at: Int, result: String, failed: Boolean) {
            seen += "returned:$at:${if (failed) "failed" else "ok"}"
        }
    }

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
        seen.clear()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private val asked = listOf(ChatTurn(role = "user", content = "How many issues are open?"))

    /**
     * The ordinary round: it looks something up, then answers.
     *
     * Both halves reach the watcher, in that order, and the call comes first —
     * which is what lets the screen draw a lookup that is running.
     */
    @Test
    fun `a lookup is announced before its tool runs and filled in after`() {
        val endpoint = serveListThenAnswer()
        val agent = requireNotNull(agents.findByIdOrNull(agentWithOrknux("Reader", model(endpoint))))

        val answer = conversation.answer(requireNotNull(agent.modelId), agent, asked, watch = watch)

        assertThat(answer).isInstanceOf(ChatCompletion.Answered::class.java)
        assertThat(seen).containsExactly("called:0:orknux_issues", "returned:0:ok")
        // And the round really did run to two provider calls, so the ordering
        // above is an ordering over real work rather than over nothing.
        assertThat(received).hasSize(2)
    }

    /**
     * A tool that could not be run says so, and the round carries on.
     *
     * `AgentTools.run` never throws — a failed tool is a fact the model is
     * told — so the only way anybody watching learns the difference between
     * "ran and found nothing" and "could not be run" is this flag. It matters
     * on the screen: a lookup that failed explains a thin answer, and one that
     * returned nothing does not.
     */
    @Test
    fun `a tool that could not be run is reported as failed`() {
        val endpoint = serveUnknownToolThenAnswer()
        val agent = requireNotNull(agents.findByIdOrNull(agentWithOrknux("Reader", model(endpoint))))

        conversation.answer(requireNotNull(agent.modelId), agent, asked, watch = watch)

        assertThat(seen).containsExactly("called:0:no_such_tool", "returned:0:failed")
    }

    /**
     * Thinking done in the round that decided to look something up.
     *
     * A reasoning model does most of its thinking there rather than in the
     * round that finally answers, and the answer only ever carried the last
     * round's. So it is accumulated across the rounds and handed over as each
     * one produces it — and it never reaches [ChatCompletion.Answered.content],
     * which is what keeps it out of the copy control and the speech model.
     */
    @Test
    fun `thinking from every round reaches the watcher and stays out of the answer`() {
        val endpoint = serveThinkThenListThenAnswer()
        val agent = requireNotNull(agents.findByIdOrNull(agentWithOrknux("Reader", model(endpoint))))

        val answer = conversation.answer(requireNotNull(agent.modelId), agent, asked, watch = watch) as
            ChatCompletion.Answered

        assertThat(seen).containsExactly(
            "thinking:I should count them.",
            "called:0:orknux_issues",
            "returned:0:ok",
            "thinking:None open.",
        )
        assertThat(answer.reasoning).isEqualTo("I should count them.\n\nNone open.")
        assertThat(answer.content).isEqualTo("None are open.")
        assertThat(answer.content).doesNotContain("count them")
    }

    /** A round with nobody watching keeps exactly the same record. */
    @Test
    fun `a round with no watcher still answers`() {
        val endpoint = serveListThenAnswer()
        val agent = requireNotNull(agents.findByIdOrNull(agentWithOrknux("Reader", model(endpoint))))

        val answer = conversation.answer(requireNotNull(agent.modelId), agent, asked)

        assertThat(answer).isInstanceOf(ChatCompletion.Answered::class.java)
        assertThat(seen).isEmpty()
    }

    private fun serveListThenAnswer(): String = serve { body ->
        if (body.contains("tool_call_id")) {
            """{"choices":[{"message":{"role":"assistant","content":"None are open."}}],
               "usage":{"prompt_tokens":9,"completion_tokens":4}}"""
        } else {
            callFor("orknux_issues", "{}")
        }
    }

    private fun serveUnknownToolThenAnswer(): String = serve { body ->
        if (body.contains("tool_call_id")) {
            """{"choices":[{"message":{"role":"assistant","content":"I could not look."}}],
               "usage":{"prompt_tokens":9,"completion_tokens":4}}"""
        } else {
            callFor("no_such_tool", "{}")
        }
    }

    private fun serveThinkThenListThenAnswer(): String = serve { body ->
        if (body.contains("tool_call_id")) {
            """{"choices":[{"message":{"role":"assistant",
                 "reasoning_content":"None open.","content":"None are open."}}],
               "usage":{"prompt_tokens":9,"completion_tokens":4}}"""
        } else {
            """
            {"choices":[{"message":{"role":"assistant","content":null,
              "reasoning_content":"I should count them.",
              "tool_calls":[{"id":"call_1","type":"function",
                "function":{"name":"orknux_issues","arguments":"{}"}}]}}],
             "usage":{"prompt_tokens":7,"completion_tokens":2}}
            """.trimIndent()
        }
    }

    private fun callFor(name: String, arguments: String) = """
        {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
          {"id":"call_1","type":"function",
           "function":{"name":"$name","arguments":"$arguments"}}
        ]}}],"usage":{"prompt_tokens":7,"completion_tokens":2}}
    """.trimIndent()

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
}
