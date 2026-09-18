package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.plugin.Plugin
import io.mszymanski.orknux.server.plugin.PluginDeclarations
import io.mszymanski.orknux.server.plugin.PluginFunctionRegistry
import io.mszymanski.orknux.server.plugin.PluginRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.script.PluginInspection
import io.mszymanski.orknux.workflow.script.PluginRunner
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
 * A plugin's tools, granted to an agent.
 *
 * A plugin declares two surfaces: `functions()` for workflows and `tools()`
 * for agents. What the grant list resolves against is the tools - most of them
 * fronts for the plugin's own functions, declared as `OrknuxFunctionTool`, so
 * a call goes down the one path a function is called on and the plugin's
 * settings and capabilities apply exactly as they do in a run. A tool with a
 * `run` of its own dispatches against `tools()` directly, and a function no
 * tool fronts is not offered to a model at all.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class PluginToolGrantTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val conversation: AgentConversation,
    @Autowired val agents: AgentRepository,
    @Autowired val sessions: ChatSessionRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val plugins: PluginRepository,
    @Autowired val declarations: PluginDeclarations,
    @Autowired val registry: PluginFunctionRegistry,
    @Autowired val runner: PluginRunner,
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
        sessions.deleteAll()
        agents.deleteAll()
        functions.deleteAll()
        plugins.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        received.clear()

        val read = runner.inspect(SOURCE) as PluginInspection.Read
        val plugin = plugins.save(
            Plugin(
                key = read.id,
                name = "greeter",
                filename = "greeter.js",
                source = SOURCE,
                sizeBytes = SOURCE.length.toLong(),
                apiVersion = read.apiVersion,
                sha256 = "0".repeat(64),
                declaredFunctions = declarations.validated(read.functions),
                declaredTools = declarations.validatedTools(read.tools),
                declaredParameters = declarations.validatedParameters(read.parameters),
            ),
        )
        registry.reconcile(plugin)
    }

    @AfterEach
    fun stop() {
        if (::server.isInitialized) server.stop(0)
    }

    /**
     * The proxy is resolved when the plugin is read, not when a model calls.
     *
     * A tool fronting a function the plugin does not declare is a broken
     * promise, and the first agent to call it is the worst place to find out.
     */
    @Test
    fun `a tool proxying a function the plugin does not declare is refused at load`() {
        val broken = SOURCE.replace("function: 'greet'", "function: 'nothingHere'")

        val read = runner.inspect(broken)

        assertThat(read).isInstanceOf(PluginInspection.Unreadable::class.java)
        assertThat((read as PluginInspection.Unreadable).reason).contains("nothingHere")
    }

    @Test
    fun `a granted proxy tool is offered, called down the function path, and threads back`() {
        val endpoint = serveToolThenAnswer()
        val agentId = agentGrantedTool("Concierge", model(endpoint), "greeter_greet")

        val agent = requireNotNull(agents.findByIdOrNull(agentId))
        val answer = conversation.answer(
            requireNotNull(agent.modelId),
            agent,
            listOf(ChatTurn("user", "Greet Dana for me.")),
        )

        assertThat(answer).isInstanceOf(ChatCompletion.Answered::class.java)
        assertThat((answer as ChatCompletion.Answered).content).isEqualTo("Done.")

        assertThat(received).hasSize(2)
        // Offered under the plugin's own words, which were written for exactly
        // this reader.
        assertThat(received[0]).contains("greeter_greet").contains("Says hello, warmly")
        // A function no tool fronts is not on an agent's menu, whatever the
        // grant list says elsewhere.
        assertThat(received[0]).doesNotContain("internalOnly")
        // The call ran down the function path and its answer threaded back.
        assertThat(received[1]).contains("tool_call_id")
        assertThat(received[1]).contains("hello, Dana")
    }

    /** A tool with a `run` of its own dispatches against tools(), not functions(). */
    @Test
    fun `a granted standalone tool runs its own code`() {
        val endpoint = serveToolThenAnswer(calling = "greeter_shout")
        val agentId = agentGrantedTool("Herald", model(endpoint), "greeter_shout")

        val agent = requireNotNull(agents.findByIdOrNull(agentId))
        val answer = conversation.answer(
            requireNotNull(agent.modelId),
            agent,
            listOf(ChatTurn("user", "Shout for Dana.")),
        )

        assertThat(answer).isInstanceOf(ChatCompletion.Answered::class.java)
        assertThat(received[0]).contains("greeter_shout").contains("Says it loudly")
        assertThat(received[1]).contains("HELLO, DANA!")
    }

    /**
     * A function the plugin did not put a tool in front of is not an agent's:
     * granting its name grants nothing, so the model is offered nothing - and
     * with nothing offered there is no tool loop to enter at all.
     */
    @Test
    fun `a plugin function no tool fronts is not offered`() {
        val endpoint = serveToolThenAnswer(calling = "greeter_internalOnly")
        val agentId = agentGrantedTool("Snoop", model(endpoint), "greeter_internalOnly")

        val agent = requireNotNull(agents.findByIdOrNull(agentId))
        conversation.answer(
            requireNotNull(agent.modelId),
            agent,
            listOf(ChatTurn("user", "Call the hidden one.")),
        )

        assertThat(received.first()).doesNotContain("greeter_internalOnly")
    }

    private fun serveToolThenAnswer(calling: String = "greeter_greet"): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            received += body
            val saying = if (body.contains("tool_call_id")) {
                """{"choices":[{"message":{"role":"assistant","content":"Done."}}],
                   "usage":{"prompt_tokens":9,"completion_tokens":2}}"""
            } else {
                """
                {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                  {"id":"call_1","type":"function",
                   "function":{"name":"$calling","arguments":"{\"name\":\"Dana\"}"}}
                ]}}],"usage":{"prompt_tokens":7,"completion_tokens":2}}
                """.trimIndent()
            }
            val bytes = saying.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    private fun agentGrantedTool(name: String, modelId: Long, tool: String): Long {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "$name", modelId: $modelId, tools: ["$tool"] }) { id } }""",
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

    private companion object {
        val SOURCE = """
            export default class Greeter extends OrknuxPlugin {
              id() { return 'greeter'; }
              apiVersion() { return 1; }

              functions() {
                return [
                  new OrknuxFunction({
                    name: 'greet',
                    description: 'Says hello, warmly, to whoever is named.',
                    params: [{ name: 'name', type: 'string' }],
                    returnType: 'string',
                    run: (name) => 'hello, ' + name,
                  }),
                  new OrknuxFunction({
                    name: 'internalOnly',
                    description: 'For workflows; no tool fronts this.',
                    returnType: 'string',
                    run: () => 'hidden',
                  }),
                ];
              }

              tools() {
                return [
                  new OrknuxFunctionTool({ function: 'greet' }),
                  new OrknuxTool({
                    name: 'shout',
                    description: 'Says it loudly, to whoever is named.',
                    params: [{ name: 'name', type: 'string' }],
                    returnType: 'string',
                    run: (name) => 'HELLO, ' + name.toUpperCase() + '!',
                  }),
                ];
              }
            }
        """.trimIndent()
    }
}
