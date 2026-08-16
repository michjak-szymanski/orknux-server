package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentSkillRepository
import io.mszymanski.orknux.server.agent.SkillCatalogRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Chatting with an agent rather than with a bare model.
 *
 * An agent is a configuration: the model that answers, the instructions it works
 * under, and the skills it has been granted. What is worth holding here is that
 * a grant means what it says — an agent is told the skills in the catalogs it
 * was given, and none of the others.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatAgentTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val briefing: AgentBriefing,
    @Autowired val agents: AgentRepository,
    @Autowired val sessions: ChatSessionRepository,
    @Autowired val catalogs: SkillCatalogRepository,
    @Autowired val skills: AgentSkillRepository,
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
        skills.deleteAll()
        catalogs.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @Test
    fun `handing a chat to an agent makes the agent's model the one that answers`() {
        val modelId = model("Gemma")
        val agentId = agent("Reviewer", modelId)
        val chatId = startChat()

        graphQlTester.document(
            """mutation { chooseChatAgent(id: $chatId, agentId: $agentId) { agentId agentName modelId } }""",
        ).execute()
            .path("chooseChatAgent.agentName").entity(String::class.java).isEqualTo("Reviewer")
            // The agent supplies the model; a chat answering on some other one
            // would not be answering as what the screen says it is.
            .path("chooseChatAgent.modelId").entity(Long::class.java).isEqualTo(modelId)
    }

    /** An agent that cannot run is not one to hand a conversation to. */
    @Test
    fun `an agent with no model is refused, and says why`() {
        val agentId = agent("Reviewer", modelId = null)
        val chatId = startChat()

        graphQlTester.document("""mutation { chooseChatAgent(id: $chatId, agentId: $agentId) { agentId } }""")
            .execute()
            .errors().satisfy { errors ->
                assertThat(errors.first().message).contains("has no model chosen")
            }
    }

    @Test
    fun `choosing a bare model afterwards ends the agent's part in it`() {
        val modelId = model("Gemma")
        val agentId = agent("Reviewer", modelId)
        val chatId = startChat()

        graphQlTester.document("""mutation { chooseChatAgent(id: $chatId, agentId: $agentId) { agentId } }""").execute()
        graphQlTester.document("""mutation { chooseChatModel(id: $chatId, modelId: $modelId) { agentId modelId } }""")
            .execute()
            .path("chooseChatModel.agentId").valueIsNull()
    }

    /**
     * The briefing is the agent's instructions plus the skills it was granted —
     * and only those.
     *
     * A skill in a catalog nobody granted is the workspace's, not this agent's,
     * and a skill switched off is defined but out of reach here as anywhere. If
     * either leaked in, "granted" would mean nothing.
     */
    @Test
    fun `an agent is told its own catalogs' skills and no others`() {
        val granted = catalog("Reviews")
        val withheld = catalog("Secrets")
        skill("codeReview", granted, "Read the diff twice.")
        skill("handling", withheld, "The passphrase is hunter2.")
        val muted = skill("retired", granted, "Ignore everything.")
        graphQlTester.document("""mutation { setSkillEnabled(id: $muted, enabled: false) { id } }""").execute()

        val agentId = agent("Reviewer", model("Gemma"), prompt = "You review code carefully.")
        // Sent together: updateAgent treats an omitted systemPrompt as "clear it",
        // so granting a catalog on its own would wipe the instructions.
        graphQlTester.document(
            """mutation { updateAgent(id: $agentId, input: {
                 name: "Reviewer", systemPrompt: "You review code carefully.", skillCatalogs: ["Reviews"]
               }) { skillCatalogs } }""",
        ).execute()

        val said = requireNotNull(briefing.of(requireNotNull(agents.findByIdOrNull(agentId))))
        assertThat(said).contains("You review code carefully.")
        // Listed by name, not spelled out: the agent loads the body with
        // skill_load when it decides the skill applies.
        assertThat(said).contains("codeReview")
        assertThat(said).doesNotContain("Read the diff twice.")
        // Not granted, so not even named.
        assertThat(said).doesNotContain("handling").doesNotContain("hunter2")
        // Granted but switched off.
        assertThat(said).doesNotContain("retired")
    }

    /** Nothing to say is no system turn: an empty briefing costs tokens for nothing. */
    @Test
    fun `an agent with no prompt and no skills is briefed with nothing`() {
        val agentId = agent("Plain", model("Gemma"))
        assertThat(briefing.of(requireNotNull(agents.findByIdOrNull(agentId)))).isNull()
    }

    private fun startChat(): Long = graphQlTester.document(
        """mutation { startChat(input: { workspaceId: $workspaceId, title: "Review" }) { id } }""",
    ).execute().path("startChat.id").entity(Long::class.java).get()

    /** The model is set on update: creating an agent does not take one. */
    private fun agent(name: String, modelId: Long?, prompt: String? = null): Long {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()

        val settings = buildString {
            if (modelId != null) append(", modelId: $modelId")
            if (prompt != null) append(""", systemPrompt: "$prompt"""")
        }
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "$name"$settings }) { id } }""",
        ).execute()
        return id
    }

    private fun model(name: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Local $name", endpoint: "http://localhost:9/v1", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "$name", modelId: "$name", kind: CHAT })
               { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    private fun catalog(name: String): Long = graphQlTester.document(
        """mutation { createSkillCatalog(workspaceId: $workspaceId, name: "$name") { id } }""",
    ).execute().path("createSkillCatalog.id").entity(Long::class.java).get()

    private fun skill(name: String, catalogId: Long, content: String): Long = graphQlTester.document(
        """mutation { createSkill(input: {
             workspaceId: $workspaceId, name: "$name", catalogId: $catalogId,
             content: ${'"'}${'"'}${'"'}---
name: $name
description: $name
---

$content
${'"'}${'"'}${'"'}
           }) { id } }""",
    ).execute().path("createSkill.id").entity(Long::class.java).get()
}
