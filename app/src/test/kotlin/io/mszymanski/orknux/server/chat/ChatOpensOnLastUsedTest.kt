package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * A new chat opens on whatever this person last chatted with here.
 *
 * It used to open on whichever model sorted first. There were two defaults —
 * one in `ChatAPI` that took the workspace's first enabled model, and one in
 * `ChatService` that already knew about the last chat — and because the first
 * was never null wherever the workspace had any model at all, the second was
 * unreachable. So somebody who talked to an agent every day was handed a bare
 * model every morning, and the picker they then had to open opened on the wrong
 * tab as well (issue #273).
 *
 * ## Nothing is stored for this
 *
 * There is no new column and nothing in local storage. `chat_session` already
 * carries a user, a workspace, an agent, a model and when the chat was last
 * spoken in, so what was last used is a question the data answers. That is why
 * these tests set it up by *having chats* rather than by writing a preference:
 * there is no preference to write, and a test that had to write one would be a
 * test of a copy that could disagree with the chats it was a copy of.
 *
 * ## Scope
 *
 * Per person and per workspace, both of which fall out of the query. The
 * assertions below hold each of those separately, because the failure they
 * describe is quiet: a chat pointed at another workspace's agent looks
 * perfectly normal until it is sent to.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatOpensOnLastUsedTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val agents: AgentRepository,
    @Autowired val sessions: ChatSessionRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        sessions.deleteAll()
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    /**
     * The first chat in a workspace nobody has chatted in.
     *
     * There is nothing to read, so it falls back to the first chat model the
     * workspace offers — which is what every chat used to get and is still the
     * right answer here. Issue #249 put agents first in the *picker*, where
     * somebody is choosing; a chat nobody has chosen for opens on the one thing
     * certain to answer.
     */
    @Test
    fun `the first chat in a workspace takes the first chat model`() {
        val providerId = provider()
        val first = model(providerId, "Alpha")
        model(providerId, "Beta")

        val chat = startChat("Nothing yet")

        assertThat(agentOf(chat)).isNull()
        assertThat(modelOf(chat)).isEqualTo(first)
    }

    /**
     * A model that cannot hold a conversation is not the answer.
     *
     * The default in the controller did not look at the kind, so a workspace
     * whose alphabetically first model transcribes audio opened every chat on a
     * model the picker itself refuses to offer. Nothing failed until somebody
     * typed.
     */
    @Test
    fun `an audio model is never what a chat opens on`() {
        val providerId = provider()
        // First by the order models come back in, and useless for a chat.
        val ears = model(providerId, "Aardvark ears", kind = "TRANSCRIPTION")
        val talks = model(providerId, "Zebra")

        val chat = startChat("Nothing yet")

        assertThat(modelOf(chat)).isEqualTo(talks).isNotEqualTo(ears)
    }

    @Test
    fun `a new chat opens on the agent the last one used`() {
        val providerId = provider()
        val plain = model(providerId, "Alpha")
        val agentModel = model(providerId, "Zebra")
        val agentId = agent("Responder", agentModel)

        val earlier = startChat("Yesterday")
        graphQlTester.document("""mutation { chooseChatAgent(id: $earlier, agentId: $agentId) { agentId } }""")
            .execute().errors().verify()

        val today = startChat("Today")

        assertThat(agentOf(today)).isEqualTo(agentId)
        // And on the agent's model, because that is what will actually answer -
        // a chat naming an agent and answering on some other model is not
        // answering as what the screen says it is.
        assertThat(modelOf(today)).isEqualTo(agentModel).isNotEqualTo(plain)
    }

    @Test
    fun `a new chat opens on the bare model the last one was moved to`() {
        val providerId = provider()
        val alpha = model(providerId, "Alpha")
        val zebra = model(providerId, "Zebra")
        val agentId = agent("Responder", alpha)

        val earlier = startChat("Yesterday")
        graphQlTester.document("""mutation { chooseChatAgent(id: $earlier, agentId: $agentId) { agentId } }""")
            .execute().errors().verify()
        // Moved off the agent and onto a bare model, deliberately. That is the
        // last thing this person talked to and it is what should come back.
        graphQlTester.document("""mutation { chooseChatModel(id: $earlier, modelId: $zebra) { modelId } }""")
            .execute().errors().verify()

        val today = startChat("Today")

        assertThat(agentOf(today)).isNull()
        assertThat(modelOf(today)).isEqualTo(zebra)
    }

    /**
     * An agent that has since been deleted leaves a chat on that agent's model,
     * and never pointing at nothing.
     *
     * `chat_session.agent_id` is `ON DELETE SET NULL`, so deleting an agent
     * turns every chat that was using it into a chat on a bare model - the
     * agent's own model, which is what the chat was already answering on. There
     * is nothing left in the row to distinguish that from a chat somebody moved
     * onto a bare model deliberately, and this deliberately does not try: what
     * the person sees when they open that chat *is* a bare model, and a new
     * chat opening on something the old one no longer shows would be two
     * screens disagreeing about the same fact.
     *
     * What matters is the guarantee: never the dead id, and never nothing.
     */
    @Test
    fun `a deleted agent leaves the next chat on its model and not on a dead id`() {
        val providerId = provider()
        val alpha = model(providerId, "Alpha")
        val zebra = model(providerId, "Zebra")
        val doomed = agent("Doomed", zebra)

        val earlier = startChat("Tuesday")
        graphQlTester.document("""mutation { chooseChatAgent(id: $earlier, agentId: $doomed) { agentId } }""")
            .execute().errors().verify()

        graphQlTester.document("""mutation { deleteAgent(id: $doomed) }""").execute().errors().verify()

        val today = startChat("Wednesday")

        assertThat(agentOf(today)).isNull()
        // The agent's model, which is what that chat is still answering on -
        // not the workspace's first, which is what the old default gave.
        assertThat(modelOf(today)).isEqualTo(zebra).isNotEqualTo(alpha)
    }

    /**
     * An agent that is switched off is skipped, and it is the case the reading
     * forward is actually for.
     *
     * A disabled agent keeps its id on the chat, unlike a deleted one, so this
     * is a chat naming something the picker would refuse. The answer is the
     * fallback rather than that agent - never an id `chooseAgent` would not
     * accept.
     */
    @Test
    fun `an agent that is no longer active is skipped`() {
        val providerId = provider()
        val alpha = model(providerId, "Alpha")
        val agentId = agent("Responder", alpha)

        val earlier = startChat("Yesterday")
        graphQlTester.document("""mutation { chooseChatAgent(id: $earlier, agentId: $agentId) { agentId } }""")
            .execute().errors().verify()

        graphQlTester.document("""mutation { setAgentEnabled(id: $agentId, enabled: false) { id } }""")
            .execute().errors().verify()

        val today = startChat("Today")

        assertThat(agentOf(today)).isNull()
        assertThat(modelOf(today)).isEqualTo(alpha)
    }

    /**
     * A workspace's agents do not exist in the next one, so what was last used
     * there is not an answer here.
     */
    @Test
    fun `what was used in one workspace is not carried into another`() {
        val providerId = provider()
        val alpha = model(providerId, "Alpha")
        val agentId = agent("Responder", alpha)

        val earlier = startChat("Yesterday")
        graphQlTester.document("""mutation { chooseChatAgent(id: $earlier, agentId: $agentId) { agentId } }""")
            .execute().errors().verify()

        val elsewhere = requireNotNull(workspaces.save(Workspace(name = "frontend")).id)
        val theirProvider = provider(elsewhere)
        val theirModel = model(theirProvider, "Theirs")

        val opened = graphQlTester.document(
            """mutation { startChat(input: { workspaceId: $elsewhere, title: "Over here" }) { id } }""",
        ).execute().errors().verify().path("startChat.id").entity(Long::class.java).get()

        assertThat(chat(opened).agentId).isNull()
        assertThat(chat(opened).modelId).isEqualTo(theirModel)
    }

    /** A model the caller named is still what it gets, and clears no agent onto it. */
    @Test
    fun `a model asked for by name wins over what was last used`() {
        val providerId = provider()
        val alpha = model(providerId, "Alpha")
        val zebra = model(providerId, "Zebra")
        val agentId = agent("Responder", alpha)

        val earlier = startChat("Yesterday")
        graphQlTester.document("""mutation { chooseChatAgent(id: $earlier, agentId: $agentId) { agentId } }""")
            .execute().errors().verify()

        val chosen = graphQlTester.document(
            """mutation { startChat(input: { workspaceId: $workspaceId, title: "On Zebra", modelId: $zebra })
               { id } }""",
        ).execute().errors().verify().path("startChat.id").entity(Long::class.java).get()

        assertThat(chat(chosen).agentId).isNull()
        assertThat(chat(chosen).modelId).isEqualTo(zebra)
    }

    private fun startChat(title: String): Long = graphQlTester.document(
        """mutation { startChat(input: { workspaceId: $workspaceId, title: "$title" }) { id } }""",
    ).execute().errors().verify().path("startChat.id").entity(Long::class.java).get()

    /**
     * What a chat is pointed at, read off the row rather than through GraphQL.
     *
     * Both of these are nullable and both are `ID` in the schema, and neither
     * fact travels well through `GraphQlTester`: a null answers `Cannot map
     * null into type long`, and asking for the field of a single object with a
     * null in it fails inside the decoder. The question here is about what was
     * saved, so it is asked of what was saved.
     */
    private fun agentOf(chatId: Long): Long? = chat(chatId).agentId

    private fun modelOf(chatId: Long): Long? = chat(chatId).modelId

    private fun chat(chatId: Long): ChatSession =
        requireNotNull(sessions.findById(chatId).orElse(null)) { "no chat $chatId" }

    private fun provider(inWorkspace: Long = workspaceId): Long = graphQlTester.document(
        """mutation { createModelProvider(input: {
             workspaceId: $inWorkspace, name: "Stub $inWorkspace", endpoint: "http://models.invalid",
             secret: "sk-test"
           }) { id } }""",
    ).execute().errors().verify().path("createModelProvider.id").entity(Long::class.java).get()

    private fun model(providerId: Long, name: String, kind: String = "CHAT"): Long = graphQlTester.document(
        """mutation { createModel(input: {
             providerId: $providerId, name: "$name", modelId: "${name.lowercase()}", kind: $kind
           }) { id } }""",
    ).execute().errors().verify().path("createModel.id").entity(Long::class.java).get()

    private fun agent(name: String, modelId: Long): Long {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
        ).execute().errors().verify().path("createAgent.id").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "$name", modelId: $modelId }) { id } }""",
        ).execute().errors().verify()
        return id
    }
}
