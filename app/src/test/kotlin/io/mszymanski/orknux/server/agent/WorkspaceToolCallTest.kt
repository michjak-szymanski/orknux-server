package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.chat.AgentTools
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
 * An agent calling the workspace's own JavaScript.
 *
 * The grant is the thing under test. A skill an agent was not given is a page it
 * does not read; a tool it was not given is code it cannot run, which is a
 * stronger claim and worth holding with a test rather than a comment.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WorkspaceToolCallTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val tools: AgentTools,
    @Autowired val agents: AgentRepository,
    @Autowired val toolRepository: AgentToolRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        agents.deleteAll()
        toolRepository.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @Test
    fun `a granted tool is offered, runs in the sandbox, and answers with what it returned`() {
        tool("shout", "Upper-cases what it is given", "export default function shout(input) { return { said: input.text.toUpperCase() }; }")
        val agent = agent("Reviewer", granted = "shout")

        // Offered under its own name, with what it is for.
        val offered = tools.specsFor(agent).single { it.name == "shout" }
        assertThat(offered.description).isEqualTo("Upper-cases what it is given")

        val answer = tools.run(
            agent,
            ToolCall(id = "call_1", name = "shout", arguments = """{"input":{"text":"hello"}}"""),
        )
        assertThat(answer).contains("HELLO")
    }

    /**
     * A tool it was not granted is not offered and does not run, even if the
     * model names it exactly.
     */
    @Test
    fun `an ungranted tool is neither offered nor callable`() {
        tool("shout", "Upper-cases", "export default function shout(input) { return { said: 'ran' }; }")
        val agent = agent("Reviewer", granted = null)

        assertThat(tools.specsFor(agent).map { it.name }).doesNotContain("shout")

        val answer = tools.run(agent, ToolCall(id = "call_1", name = "shout", arguments = "{}"))
        assertThat(answer).contains("There is no tool called shout")
        assertThat(answer).doesNotContain("ran")
    }

    /** Switched off is out of reach here as everywhere, grant or no grant. */
    @Test
    fun `a disabled tool is not offered even when granted`() {
        val id = tool("shout", "Upper-cases", "export default function shout(input) { return {}; }")
        graphQlTester.document("""mutation { setToolEnabled(id: $id, enabled: false) { id } }""").execute()
        val agent = agent("Reviewer", granted = "shout")

        assertThat(tools.specsFor(agent).map { it.name }).doesNotContain("shout")
    }

    /**
     * A script that throws is reported to the model, not raised at the caller.
     *
     * It can apologise, try another way, or answer without it — all of which
     * beat the conversation dying because a tool threw.
     */
    @Test
    fun `a tool that throws comes back as an error the model can read`() {
        tool("boom", "Always fails", "export default function boom(input) { throw new Error('no good'); }")
        val agent = agent("Reviewer", granted = "boom")

        val answer = tools.run(agent, ToolCall(id = "call_1", name = "boom", arguments = "{}"))
        assertThat(answer).contains("error")
        assertThat(answer).contains("no good")
    }

    /**
     * The signature a tool declares is the one the model is shown and the one the
     * sandbox fills.
     *
     * Both halves in one test on purpose: a parameter list that is offered and
     * not passed, or passed and not offered, is worse than none at all.
     */
    @Test
    fun `a declared parameter list is offered by name and passed in order`() {
        tool(
            "greet",
            "Greets somebody",
            "export default function greet(who, times) { return { said: who.repeat(times) }; }",
            params = """[{ name: "who", type: STRING }, { name: "times", type: NUMBER }]""",
        )
        val agent = agent("Reviewer", granted = "greet")

        val offered = tools.specsFor(agent).single { it.name == "greet" }
        assertThat(offered.parameters.map { it.name }).containsExactly("who", "times")
        assertThat(offered.parameters).allMatch { it.required }

        val answer = tools.run(
            agent,
            ToolCall(id = "call_1", name = "greet", arguments = """{"who":"ho","times":3}"""),
        )
        assertThat(answer).contains("hohoho")
    }

    /**
     * A parameter the model left out arrives as nothing, in its own place.
     *
     * The failure this rules out is the one positional calling invites: dropping
     * a missing argument would slide every argument after it one to the left, and
     * the tool would run on values that belong to other parameters.
     */
    @Test
    fun `a parameter the model omitted arrives as null without moving the others`() {
        tool(
            "pick",
            "Says what it got",
            "export default function pick(first, second) { return { first: first, second: second }; }",
            params = """[{ name: "first", type: STRING }, { name: "second", type: STRING }]""",
        )
        val agent = agent("Reviewer", granted = "pick")

        val answer = tools.run(
            agent,
            ToolCall(id = "call_1", name = "pick", arguments = """{"second":"there"}"""),
        )
        assertThat(answer).contains("\"first\":null")
        assertThat(answer).contains("there")
    }

    /**
     * A tool created before parameters existed goes on being called exactly as it
     * was: one argument, named `input`, and the object under that key.
     */
    @Test
    fun `a tool that says nothing about what it takes still takes input`() {
        tool("shout", "Upper-cases", "export default function shout(input) { return { said: input.text }; }")
        val agent = agent("Reviewer", granted = "shout")

        assertThat(tools.specsFor(agent).single { it.name == "shout" }.parameters.map { it.name })
            .containsExactly("input")

        val answer = tools.run(
            agent,
            ToolCall(id = "call_1", name = "shout", arguments = """{"input":{"text":"hello"}}"""),
        )
        assertThat(answer).contains("hello")
    }

    /** Granting one is worth an entry: it changes what the agent can do. */
    @Test
    fun `granting a tool is recorded`() {
        tool("shout", "Upper-cases", "export default function shout(input) { return {}; }")
        agent("Reviewer", granted = "shout")

        assertThat(audit.findAll().map { it.message }).anyMatch { it.contains("Agent Reviewer given tool shout") }
    }

    private fun agent(name: String, granted: String?): Agent {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()

        val grant = if (granted == null) "" else """, tools: ["$granted"]"""
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "$name"$grant }) { tools } }""",
        ).execute()
        return requireNotNull(agents.findByIdOrNull(id))
    }

    private fun tool(name: String, description: String, source: String, params: String? = null): Long {
        // Left out means the tool says nothing about what it takes, which is the
        // one parameter every tool had before any of them could say.
        val declared = if (params == null) "" else ", params: $params"
        return graphQlTester.document(
            """mutation { createTool(input: {
                 workspaceId: $workspaceId, name: "$name", description: "$description",
                 source: ${'"'}${'"'}${'"'}$source${'"'}${'"'}${'"'}, typescript: ${'"'}${'"'}${'"'}$source${'"'}${'"'}${'"'}$declared
               }) { id } }""",
        ).execute().path("createTool.id").entity(Long::class.java).get()
    }
}
