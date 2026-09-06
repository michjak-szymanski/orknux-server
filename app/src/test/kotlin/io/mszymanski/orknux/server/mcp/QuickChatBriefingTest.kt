package io.mszymanski.orknux.server.mcp

import com.sun.net.httpserver.HttpServer
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
 * What the panel is told about the one thing the sandbox provides.
 *
 * The briefing said at length what a function may *not* do - no `import`, no
 * Node, no network - and nothing at all about `orknux`, the single global that
 * is there. A model that knows only the prohibitions still has to guess at the
 * rest, and it guessed wrong: asked how to count the messages in a Slack
 * thread it answered `messages.length`, which is the length of the page that
 * was fetched and counts the parent, when the number wanted is `replies` -
 * Slack's own count of the whole thread. The editor beside the panel has
 * declared that shape all along.
 *
 * So the shapes go in the briefing, and this is what a change to them must not
 * lose. It asserts the declarations reach the model rather than that they are
 * spelled a particular way: the wording is free to improve, the union and the
 * two names in it are not.
 *
 * The other copy is `orknux-ui/src/components/monaco.ts`, which feeds the
 * editor. Two copies is one more than there should be; a panel describing a
 * different `orknux` from the one autocompleting under it would be worse than
 * a panel describing none, so if these ever disagree, that file is right.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class QuickChatBriefingTest(
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
    fun `the briefing carries the shape of what orknux answers`() {
        ask()

        val briefing = requireNotNull(received.firstOrNull()) { "the model was never asked anything" }

        // The call itself, so a model does not invent an argument list.
        assertThat(briefing).contains("orknux")
        assertThat(briefing).contains("thread(")

        // Both halves of the union, which is what makes a refusal checkable.
        assertThat(briefing).contains("SlackThread")
        assertThat(briefing).contains("SlackThreadMessage")
        assertThat(briefing).contains("error")

        /*
         * The two that are easy to confuse, and the sentence that tells them
         * apart. This is the whole reason the declarations are here: both names
         * are on the same object and only one of them answers "how many
         * replies".
         */
        assertThat(briefing).contains("messages")
        assertThat(briefing).contains("replies")
        assertThat(briefing).contains("whole thread")
    }

    @Test
    fun `it says the shapes are all there is, so nothing else is invented`() {
        ask()

        val briefing = requireNotNull(received.firstOrNull())
        assertThat(briefing).contains("nothing else on")
    }

    /** One question, answered at once: what is being read is the request. */
    private fun ask() {
        val modelId = model(
            serve {
                """{"choices":[{"message":{"role":"assistant","content":"Read `replies`."}}],
                   "usage":{"prompt_tokens":9,"completion_tokens":4}}"""
            },
        )
        quickChat.answer(
            modelId = modelId,
            workspaceId = workspaceId,
            mayWrite = true,
            page = null,
            said = listOf(ChatTurn("user", "How do I count the messages in a Slack thread?")),
            asker = "alice",
        )
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
