package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A speech model that is not read the blank lines.
 *
 * A blank line is a thing the eye reads and the ear cannot, and readers differ
 * on what to do with one: some pause for far too long, some treat it as the end
 * of the utterance and clip what follows. Which of those happens is the
 * reader's, so this is a switch on the model rather than a rule.
 *
 * What is measured is the text that actually leaves for the provider, because
 * that is the whole of the feature — nothing else about a reading changes, and
 * in particular where an answer is cut is decided in the browser long before
 * any of this.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SpeechEmptyLinesTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val speech: SpeechAPI,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private var modelId: Long = 0
    private lateinit var server: HttpServer
    private val asked = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun start() {
        models.deleteAll()
        providers.deleteAll()
        workspaces.deleteAll()
        asked.clear()

        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/audio/speech") { exchange ->
            asked += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val bytes = "ID3-not-really-an-mp3".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "audio/mpeg")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        modelId = speechModel("http://${server.address.hostString}:${server.address.port}")

        // Whichever model the workspace reads with, which is what SpeechAPI looks up.
        val workspace = workspaces.findById(workspaceId).orElseThrow()
        workspace.speechModelId = modelId
        workspaces.save(workspace)
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `a model that was not asked is read exactly what it always was`() {
        speech.speak(workspaceId, SpeechRequest(ANSWER))

        // Not "unchanged apart from the blank lines": unchanged. An
        // installation that has not been asked hears what it heard before.
        assertThat(sent()).isEqualTo(ANSWER)
    }

    @Test
    fun `a model told to skip them is handed the lines with the blank ones gone`() {
        skipEmptyLines()

        speech.speak(workspaceId, SpeechRequest(ANSWER))

        assertThat(sent()).isEqualTo("Two things went wrong.\nThe first was the sync.\nThe second was the retry.")
    }

    /**
     * The line breaks that are not blank stay. A reader draws breath at one, and
     * running a list together into a single sentence is a different complaint
     * from the one this fixes.
     */
    @Test
    fun `the line breaks between the lines are kept`() {
        skipEmptyLines()

        speech.speak(workspaceId, SpeechRequest("One.\nTwo.\n\nThree."))

        assertThat(sent()).isEqualTo("One.\nTwo.\nThree.")
    }

    /** A line of spaces looks empty on the screen, so it is empty here too. */
    @Test
    fun `a line of nothing but spaces counts as empty`() {
        skipEmptyLines()

        speech.speak(workspaceId, SpeechRequest("One.\n   \t \nTwo."))

        assertThat(sent()).isEqualTo("One.\nTwo.")
    }

    /** The setting reaches the model through the form that edits it. */
    private fun skipEmptyLines() {
        graphQlTester.document(
            """mutation { updateModel(id: $modelId, input: {
                 name: "Reader", modelId: "tts-1", kind: SPEECH, skipEmptyLines: true
               }) { skipEmptyLines } }""",
        ).execute().path("updateModel.skipEmptyLines").entity(Boolean::class.java).isEqualTo(true)
    }

    private fun sent(): String {
        val body = asked.single()
        // The provider is sent JSON; `input` is the text, escaped.
        val input = Regex("\"input\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(body)?.groupValues?.get(1)
        return requireNotNull(input) { "No input field in $body" }
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun speechModel(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Reader", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "Reader", modelId: "tts-1", kind: SPEECH
               }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    private companion object {
        /** A short answer with the shape a model actually writes: paragraphs. */
        const val ANSWER = "Two things went wrong.\n\nThe first was the sync.\n\nThe second was the retry."
    }
}
