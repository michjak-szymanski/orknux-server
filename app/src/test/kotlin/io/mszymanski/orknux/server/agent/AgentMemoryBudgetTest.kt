package io.mszymanski.orknux.server.agent

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
 * How much of a session an agent may carry, as the API offers it.
 *
 * The setting is stored in two places on purpose - the window on the model, the
 * share on the agent - so most of what is worth pinning down here is that the
 * two halves are actually joined: a share means nothing without a window, and
 * one that cannot work is refused where it is typed rather than found out at
 * the provider on somebody's turn.
 *
 * And that the surface speaks tokens. It is counted in characters everywhere
 * inside, because that is the unit every model agrees on; whoever sets this is
 * looking at a context window measured in tokens, and a surface that reported
 * the other unit would be read wrong by a factor of four every single time.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class AgentMemoryBudgetTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        agents.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    /**
     * An agent nobody has touched carries what it always carried.
     *
     * The five numbers this replaced, reported as tokens: ten thousand
     * altogether, six for the conversation and four for what tools returned,
     * and forty turns. Nothing derived, because nothing was set.
     */
    @Test
    fun `an agent with no share set gets the built-in default`() {
        val id = agent("Reviewer")

        graphQlTester.document(
            """query { agent(id: $id) { memoryShare memoryBudget {
                 derived totalTokens conversationTokens toolResultTokens longestResultTokens turns refusal
               } } }""",
        ).execute()
            .path("agent.memoryShare").valueIsNull()
            .path("agent.memoryBudget.derived").entity(Boolean::class.java).isEqualTo(false)
            .path("agent.memoryBudget.totalTokens").entity(Int::class.java).isEqualTo(10_000)
            .path("agent.memoryBudget.conversationTokens").entity(Int::class.java).isEqualTo(6_000)
            .path("agent.memoryBudget.toolResultTokens").entity(Int::class.java).isEqualTo(4_000)
            .path("agent.memoryBudget.longestResultTokens").entity(Int::class.java).isEqualTo(2_000)
            .path("agent.memoryBudget.turns").entity(Int::class.java).isEqualTo(40)
            .path("agent.memoryBudget.refusal").valueIsNull()
    }

    /**
     * One number set, and the rest of the shape follows from it.
     *
     * Ten percent of a 200,000-token window is 20,000 tokens, which is twice
     * the default - and the turn count, the two allowances and the longest
     * single result all move with it rather than being typed one by one.
     */
    @Test
    fun `a share of the model's window is what the numbers are worked out from`() {
        val model = model("Sonnet", contextWindow = 200_000)
        val id = agent("Reviewer", model)

        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "Reviewer", modelId: $model, memoryShare: 10 }) {
                 memoryShare memoryBudget { derived share contextWindow totalTokens conversationTokens
                   toolResultTokens longestResultTokens turns }
               } }""",
        ).execute()
            .path("updateAgent.memoryShare").entity(Int::class.java).isEqualTo(10)
            .path("updateAgent.memoryBudget.derived").entity(Boolean::class.java).isEqualTo(true)
            .path("updateAgent.memoryBudget.share").entity(Int::class.java).isEqualTo(10)
            .path("updateAgent.memoryBudget.contextWindow").entity(Int::class.java).isEqualTo(200_000)
            .path("updateAgent.memoryBudget.totalTokens").entity(Int::class.java).isEqualTo(20_000)
            .path("updateAgent.memoryBudget.conversationTokens").entity(Int::class.java).isEqualTo(12_000)
            .path("updateAgent.memoryBudget.toolResultTokens").entity(Int::class.java).isEqualTo(8_000)
            .path("updateAgent.memoryBudget.longestResultTokens").entity(Int::class.java).isEqualTo(4_000)
            .path("updateAgent.memoryBudget.turns").entity(Int::class.java).isEqualTo(80)

        assertThat(agents.findAll().single().memoryShare).isEqualTo(10)
    }

    /**
     * The same share against a small window is a different number of tokens.
     *
     * Which is the argument for storing a share rather than a count: one
     * installation runs models whose windows differ by an order of magnitude,
     * and a count that is generous on one of them is a request the other
     * refuses.
     */
    @Test
    fun `the same share means different budgets on different models`() {
        val big = agent("Big", model("Sonnet", contextWindow = 200_000))
        val small = agent("Small", model("Local", contextWindow = 32_000))

        assertThat(totalOf(big, share = 10)).isEqualTo(20_000)
        assertThat(totalOf(small, share = 10)).isEqualTo(3_200)
    }

    /**
     * A share too large for the window is refused where it is set.
     *
     * Not clamped and not discovered later. Raising this is paid for on every
     * turn and eventually fails the request outright, so the refusal names the
     * model, its window and what it reserves for its answer - because "too
     * large" tells nobody what would fit.
     */
    @Test
    fun `a share the model cannot give is refused, and says what it could give`() {
        val model = model("Local", contextWindow = 8_000, maxOutput = 4_000)
        val id = agent("Reviewer", model)

        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "Reviewer", modelId: $model, memoryShare: 45 })
               { id } }""",
        ).execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors).singleElement()
                    .extracting { it.message }
                    .asString()
                    .contains("at most 40%")
                    .contains("8,000")
                    .contains("4,000")
            }

        assertThat(agents.findAll().single().memoryShare).isNull()
    }

    /** And a share of a window nobody wrote down cannot be worked out at all. */
    @Test
    fun `a share is refused while the model has no context window recorded`() {
        val model = model("Mystery", contextWindow = null)
        val id = agent("Reviewer", model)

        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "Reviewer", modelId: $model, memoryShare: 10 })
               { id } }""",
        ).execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors).singleElement()
                    .extracting { it.message }
                    .asString()
                    .contains("has no context window recorded")
            }
    }

    /**
     * The preview answers the same question without saving, and without failing.
     *
     * It is what a slider asks while it is being dragged, so a share that could
     * not be saved has to come back as a sentence rather than as an error - the
     * form needs to say why beside the control rather than after Save.
     */
    @Test
    fun `the preview reports a refusal rather than raising one`() {
        val model = model("Local", contextWindow = 8_000, maxOutput = 4_000)

        graphQlTester.document(
            """query { memoryBudget(workspaceId: $workspaceId, modelId: $model, share: 45)
               { derived totalTokens refusal } }""",
        ).execute()
            .path("memoryBudget.derived").entity(Boolean::class.java).isEqualTo(false)
            // Still usable numbers: what an agent with no share set would get.
            .path("memoryBudget.totalTokens").entity(Int::class.java).isEqualTo(10_000)
            .path("memoryBudget.refusal").entity(String::class.java).satisfies {
                assertThat(it).contains("at most")
            }
    }

    /** And a share it can give comes back with no refusal and the numbers filled in. */
    @Test
    fun `the preview works out a share that would be saved`() {
        val model = model("Sonnet", contextWindow = 200_000)

        graphQlTester.document(
            """query { memoryBudget(workspaceId: $workspaceId, modelId: $model, share: 5)
               { derived totalTokens turns refusal } }""",
        ).execute()
            .path("memoryBudget.derived").entity(Boolean::class.java).isEqualTo(true)
            .path("memoryBudget.totalTokens").entity(Int::class.java).isEqualTo(10_000)
            .path("memoryBudget.turns").entity(Int::class.java).isEqualTo(40)
            .path("memoryBudget.refusal").valueIsNull()
    }

    /**
     * Setting it is worth a line in the log of its own.
     *
     * It changes what every turn this agent takes costs, and a bill that grew
     * is a question somebody asks the audit log rather than the agent.
     */
    @Test
    fun `changing the share is recorded, and clearing it says so`() {
        val model = model("Sonnet", contextWindow = 200_000)
        val id = agent("Reviewer", model)

        save(id, model, share = 10)
        save(id, model, share = null)

        assertThat(audit.findAll().map { it.message })
            .contains("Agent Reviewer memory set to 10% of its model's context window")
            .contains("Agent Reviewer memory reset to the default")
    }

    private fun totalOf(agent: String, share: Int): Int {
        val model = requireNotNull(agents.findById(agent.toLong()).get().modelId)
        return graphQlTester.document(
            """query { memoryBudget(workspaceId: $workspaceId, modelId: $model, share: $share) { totalTokens } }""",
        ).execute().path("memoryBudget.totalTokens").entity(Int::class.java).get()
    }

    private fun save(id: String, model: Long, share: Int?) {
        val shareInput = if (share == null) "" else ", memoryShare: $share"
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "Reviewer", modelId: $model$shareInput })
               { id } }""",
        ).execute().path("updateAgent.id").hasValue()
    }

    private fun agent(name: String, model: Long? = null): String {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(String::class.java).get()

        if (model != null) {
            graphQlTester.document(
                """mutation { updateAgent(id: $id, input: { name: "$name", modelId: $model }) { id } }""",
            ).execute().path("updateAgent.id").hasValue()
        }
        return id
    }

    private fun model(name: String, contextWindow: Int?, maxOutput: Int? = null): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "$name provider", endpoint: "https://stub.invalid", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

        val settings = buildString {
            if (contextWindow != null) append(", contextWindow: $contextWindow")
            if (maxOutput != null) append(", maxOutput: $maxOutput")
        }
        return graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "$name", modelId: "stub", kind: CHAT$settings
               }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }
}
