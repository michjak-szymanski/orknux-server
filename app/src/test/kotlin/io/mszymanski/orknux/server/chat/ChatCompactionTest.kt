package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.memory.ChatMemoryRepository
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A chat that has grown too long is summarised rather than left to fail.
 *
 * Issue #286. A conversation that outgrows its model's window fails on the next
 * turn, and it fails with a number — *maximum context length is 128000 tokens* —
 * which is true and useless: the person reading it wanted to keep talking, and
 * the only thing they can do is start again and lose everything.
 *
 * Five things this pins:
 *
 *   off by default    a workspace that has not asked for it is untouched,
 *                     whatever the conversation has grown to
 *   under the line    and one that has asked is untouched until it is over
 *   over the line     the older turns become one summary and the recent ones
 *                     stay word for word, because the recent ones are what the
 *                     next answer is about
 *   the summariser    the model the workspace named is the one asked, not the
 *                     one the chat is held with
 *   a failure         a summariser that will not answer leaves the thread alone.
 *                     Losing the older half because a model was unreachable is
 *                     worse than a conversation that is still too long
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatCompactionTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val compaction: ChatCompaction,
    @Autowired val history: ChatMemoryRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var modelId: Long = 0
    private lateinit var server: HttpServer
    private val asked = CopyOnWriteArrayList<String>()

    /** What the stubbed summariser answers, so a failure can be arranged. */
    private var answering = true

    private val conversation = "compaction-test"

    @BeforeEach
    fun start() {
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        asked.clear()
        answering = true

        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            asked += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val body = if (answering) {
                """{"choices":[{"message":{"role":"assistant","content":"They agreed on the sync fix."}}]}"""
            } else {
                """{"error":{"message":"no"}}"""
            }
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (answering) 200 else 500, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        modelId = chatModel("http://${server.address.hostString}:${server.address.port}")

        // Long enough that the estimate is well over any threshold below.
        history.saveAll(conversation, (1..20).map { at -> UserMessage("Turn $at. ${"word ".repeat(60)}") })
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `a workspace that has not asked for compaction is left alone`() {
        val workspace = workspaces.findById(workspaceId).orElseThrow()

        assertThat(compaction.compactIfNeeded(workspace, conversation, modelId)).isFalse()
        assertThat(history.findByConversationId(conversation)).hasSize(20)
        assertThat(asked).isEmpty()
    }

    @Test
    fun `one under the threshold is left alone too`() {
        compactionSetTo(afterTokens = 1_000_000, summaryTokens = 200)

        val workspace = workspaces.findById(workspaceId).orElseThrow()
        assertThat(compaction.compactIfNeeded(workspace, conversation, modelId)).isFalse()
        assertThat(history.findByConversationId(conversation)).hasSize(20)
    }

    /**
     * The shape of the thing: one summary, then the recent turns unchanged.
     *
     * The recent end is asserted verbatim because that is the half a compaction
     * must not touch — it is what the next answer is about, and summarising the
     * question being asked is how a chat starts answering something adjacent.
     */
    @Test
    fun `over the threshold the older turns become one summary`() {
        compactionSetTo(afterTokens = 100, summaryTokens = 50)

        val workspace = workspaces.findById(workspaceId).orElseThrow()
        assertThat(compaction.compactIfNeeded(workspace, conversation, modelId)).isTrue()

        val thread = history.findByConversationId(conversation)
        assertThat(thread).hasSize(7)
        assertThat(thread.first()).isInstanceOf(AssistantMessage::class.java)
        assertThat(thread.first().text).isEqualTo("They agreed on the sync fix.")
        assertThat(thread.last().text).startsWith("Turn 20.")
        assertThat(thread[1].text).startsWith("Turn 15.")
    }

    /** What it was told to write, and how short. */
    @Test
    fun `the summariser is asked for what the workspace allowed`() {
        compactionSetTo(afterTokens = 100, summaryTokens = 50)

        compaction.compactIfNeeded(workspaces.findById(workspaceId).orElseThrow(), conversation, modelId)

        assertThat(asked).singleElement().satisfies({ sent ->
            assertThat(sent).contains("under 50 tokens")
            // The older turns, and not the ones being kept.
            assertThat(sent).contains("Turn 1.")
            assertThat(sent).doesNotContain("Turn 20.")
        })
    }

    /**
     * The half that would be worst to get wrong.
     *
     * Throwing the older turns away because the summariser was unreachable is a
     * conversation destroyed by a network error.
     */
    @Test
    fun `a summariser that will not answer leaves the conversation alone`() {
        compactionSetTo(afterTokens = 100, summaryTokens = 50)
        answering = false

        val workspace = workspaces.findById(workspaceId).orElseThrow()
        assertThat(compaction.compactIfNeeded(workspace, conversation, modelId)).isFalse()
        assertThat(history.findByConversationId(conversation)).hasSize(20)
    }

    /** A summary allowed to be as long as the conversation would compact nothing. */
    @Test
    fun `a summary budget at or above the threshold is refused`() {
        graphQlTester.document(
            """mutation { setWorkspaceCompaction(workspaceId: $workspaceId, afterTokens: 100, summaryTokens: 100)
                 { compactAfterTokens } }""",
        ).execute().errors().expect { it.message?.contains("smaller budget") == true }.verify()
    }

    private fun compactionSetTo(afterTokens: Int, summaryTokens: Int) {
        graphQlTester.document(
            """
            mutation {
              setWorkspaceCompaction(
                workspaceId: $workspaceId, afterTokens: $afterTokens,
                summaryTokens: $summaryTokens, modelId: $modelId
              ) { compactAfterTokens compactionSummaryTokens compactionModelId }
            }
            """,
        ).execute().path("setWorkspaceCompaction.compactAfterTokens").entity(Int::class.java).isEqualTo(afterTokens)
    }

    private fun chatModel(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Local", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "Summariser", modelId: "small", kind: CHAT
               }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }
}
