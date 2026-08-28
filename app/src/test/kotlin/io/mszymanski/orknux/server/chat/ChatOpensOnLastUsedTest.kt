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
 * A new chat opens on whichever agent this person last chatted with here.
 *
 * It used to open on whichever model sorted first. There were two defaults —
 * one in `ChatAPI` that took the workspace's first enabled model, and one in
 * `ChatService` that already knew about the last chat — and because the first
 * was never null wherever the workspace had any model at all, the second was
 * unreachable. So somebody who talked to an agent every day was handed a bare
 * model every morning, and the picker they then had to open opened on the wrong
 * tab as well (issue #273).
 *
 * ## And now it is never a model
 *
 * The remaining half of that default was the last place in the product that
 * made a chat on a bare model without anybody asking for one: a workspace
 * nobody had chatted in yet fell back to its first chat model, so a fresh
 * workspace's very first conversation was inherently bare whatever the picker
 * offered. Issue #295 took that away. The fallback is now the workspace's first
 * agent that could answer, and a workspace with no such agent is refused by
 * name rather than given something — a condition that did not exist before, and
 * one the screen answers by saying to add an agent.
 *
 * A chat that is already on a bare model is not touched by any of this. It
 * opens, it renders and it answers exactly as it did. What it no longer does is
 * hand its bareness on to the next chat, which is what the reading below skips
 * it for.
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
     * There is nothing to read, so it falls back to the first agent the
     * workspace has that could answer — by name, which is the order the Agents
     * screen lists them in, so the answer is one somebody can point at.
     *
     * It used to be the first chat *model*, and that was the last bare-model
     * chat this product made on its own.
     */
    @Test
    fun `the first chat in a workspace takes the first agent that could answer`() {
        val providerId = provider()
        val modelId = model(providerId, "Alpha")
        val first = agent("Aardvark", modelId)
        agent("Zebra", modelId)

        val chat = startChat("Nothing yet")

        assertThat(agentOf(chat)).isEqualTo(first)
        assertThat(modelOf(chat)).isEqualTo(modelId)
    }

    /**
     * An agent with no model chosen cannot answer, so it is not what a chat
     * opens on however early it sorts.
     *
     * The same rule the picker applies, asked of the fallback. An agent this
     * accepted and `chooseChatAgent` refused would be two screens disagreeing
     * about the same agent.
     */
    @Test
    fun `an agent that could not answer is never what a chat opens on`() {
        val providerId = provider()
        val modelId = model(providerId, "Alpha")
        // First by name, and useless: nothing has been chosen for it to think with.
        val idle = agent("Aardvark", modelId = null)
        val works = agent("Zebra", modelId)

        val chat = startChat("Nothing yet")

        assertThat(agentOf(chat)).isEqualTo(works).isNotEqualTo(idle)
        assertThat(modelOf(chat)).isEqualTo(modelId)
    }

    @Test
    fun `a new chat opens on the agent the last one used`() {
        val providerId = provider()
        val plain = model(providerId, "Alpha")
        val agentModel = model(providerId, "Zebra")
        // Sorts first, so it is what the fallback would give if the reading
        // below did not work - which is what makes the assertion mean anything.
        agent("Another", plain)
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

    /**
     * A chat that is on a bare model does not hand its bareness on.
     *
     * The chats that were opened before issue #295 are still there, and there is
     * no migration turning them into anything else: they open, they render and
     * they answer. What the reading does with one is skip it. Taking it as the
     * answer would make one old chat enough to keep manufacturing new bare ones
     * indefinitely, which is exactly the door that was being closed.
     *
     * Built through the repository because there is no longer an API call that
     * makes one — which is the point being tested.
     */
    @Test
    fun `a chat already on a bare model is skipped rather than repeated`() {
        val providerId = provider()
        val zebra = model(providerId, "Zebra")
        val agentId = agent("Responder", zebra)

        val bare = requireNotNull(
            sessions.save(
                ChatSession(
                    workspaceId = workspaceId,
                    conversationId = "00000000-0000-0000-0000-00000000beef",
                    title = "From before",
                    userId = "alice",
                    modelId = zebra,
                    agentId = null,
                ),
            ).id,
        )
        // It is the newest thing this person has, so it is the first row read.
        assertThat(chat(bare).agentId).isNull()

        val today = startChat("Today")

        assertThat(agentOf(today)).isEqualTo(agentId)
        assertThat(modelOf(today)).isEqualTo(zebra)
    }

    /**
     * An agent that has since been deleted leaves a chat pointing at nothing,
     * and the next chat reads past it.
     *
     * `chat_session.agent_id` is `ON DELETE SET NULL`, so deleting an agent
     * turns every chat that was using it into a chat on a bare model — the
     * agent's own model, which is what the chat was already answering on. Those
     * chats keep working, and this is what the reading now does with one: skips
     * it, the same as any other bare chat, and finds an agent that still exists.
     *
     * What matters is the guarantee: never the dead id, and never nothing.
     */
    @Test
    fun `a deleted agent leaves the next chat on an agent that still exists`() {
        val providerId = provider()
        val alpha = model(providerId, "Alpha")
        val zebra = model(providerId, "Zebra")
        val survivor = agent("Aardvark", alpha)
        val doomed = agent("Doomed", zebra)

        val earlier = startChat("Tuesday")
        graphQlTester.document("""mutation { chooseChatAgent(id: $earlier, agentId: $doomed) { agentId } }""")
            .execute().errors().verify()

        graphQlTester.document("""mutation { deleteAgent(id: $doomed) }""").execute().errors().verify()

        val today = startChat("Wednesday")

        assertThat(agentOf(today)).isEqualTo(survivor)
        assertThat(modelOf(today)).isEqualTo(alpha).isNotEqualTo(zebra)
    }

    /**
     * An agent that is switched off is skipped, and it is the case the reading
     * forward is actually for.
     *
     * A disabled agent keeps its id on the chat, unlike a deleted one, so this
     * is a chat naming something the picker would refuse. The answer is the
     * fallback rather than that agent — never an id `chooseAgent` would not
     * accept.
     */
    @Test
    fun `an agent that is no longer active is skipped`() {
        val providerId = provider()
        val alpha = model(providerId, "Alpha")
        val other = agent("Aardvark", alpha)
        val agentId = agent("Responder", alpha)

        val earlier = startChat("Yesterday")
        graphQlTester.document("""mutation { chooseChatAgent(id: $earlier, agentId: $agentId) { agentId } }""")
            .execute().errors().verify()

        graphQlTester.document("""mutation { setAgentEnabled(id: $agentId, enabled: false) { id } }""")
            .execute().errors().verify()

        val today = startChat("Today")

        assertThat(agentOf(today)).isEqualTo(other).isNotEqualTo(agentId)
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
        val theirAgent = agent("Theirs", theirModel, inWorkspace = elsewhere)

        val opened = graphQlTester.document(
            """mutation { startChat(input: { workspaceId: $elsewhere, title: "Over here" }) { id } }""",
        ).execute().errors().verify().path("startChat.id").entity(Long::class.java).get()

        assertThat(chat(opened).agentId).isEqualTo(theirAgent).isNotEqualTo(agentId)
        assertThat(chat(opened).modelId).isEqualTo(theirModel)
    }

    /** An agent the caller named is still what it gets. */
    @Test
    fun `an agent asked for by name wins over what was last used`() {
        val providerId = provider()
        val alpha = model(providerId, "Alpha")
        val zebra = model(providerId, "Zebra")
        val familiar = agent("Responder", alpha)
        val wanted = agent("Specialist", zebra)

        val earlier = startChat("Yesterday")
        graphQlTester.document("""mutation { chooseChatAgent(id: $earlier, agentId: $familiar) { agentId } }""")
            .execute().errors().verify()

        val chosen = graphQlTester.document(
            """mutation { startChat(input: { workspaceId: $workspaceId, title: "On Zebra", agentId: $wanted })
               { id } }""",
        ).execute().errors().verify().path("startChat.id").entity(Long::class.java).get()

        assertThat(chat(chosen).agentId).isEqualTo(wanted)
        // The agent's model comes with it, the same as it does everywhere else.
        assertThat(chat(chosen).modelId).isEqualTo(zebra)
    }

    /**
     * A workspace with no agent at all is told to add one.
     *
     * The case issue #295 created, and the reason it is refused by name rather
     * than by a sentence: `ChatAgentMissing` is what the chat screen keys on to
     * say "add an agent" and offer the way, and an unusable-agent refusal in its
     * place would send somebody to a picker with nothing in it.
     *
     * A model in the workspace and no agent is the shape that used to work, so
     * it is the shape asserted: what stopped it was the agent, not the model.
     */
    @Test
    fun `a workspace with no agent has no chat to open`() {
        model(provider(), "Alpha")

        graphQlTester.document(
            """mutation { startChat(input: { workspaceId: $workspaceId, title: "Nobody home" }) { id } }""",
        ).execute()
            .errors().satisfy { errors ->
                assertThat(errors.first().extensions["code"]).isEqualTo("ChatAgentMissing")
                assertThat(errors.first().message).contains("no agent to chat with")
            }

        assertThat(sessions.findAll()).isEmpty()
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

    /** The model is set on update: creating an agent does not take one. */
    private fun agent(name: String, modelId: Long?, inWorkspace: Long = workspaceId): Long {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $inWorkspace, name: "$name", type: LLM }) { id } }""",
        ).execute().errors().verify().path("createAgent.id").entity(Long::class.java).get()

        if (modelId != null) {
            graphQlTester.document(
                """mutation { updateAgent(id: $id, input: { name: "$name", modelId: $modelId }) { id } }""",
            ).execute().errors().verify()
        }
        return id
    }
}
