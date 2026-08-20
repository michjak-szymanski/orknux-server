package io.mszymanski.orknux.server.llm

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentSkillRepository
import io.mszymanski.orknux.server.agent.SkillCatalogRepository
import io.mszymanski.orknux.server.workflow.WorkflowEdgeRepository
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.StepStatus
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
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
 * An agent node writing its turn into a session, without being asked to.
 *
 * The owner's decision, and the one worth a test: recording belongs to the
 * runtime rather than to whoever draws the graph. A transcript a workflow author
 * has to remember to write is a transcript with holes exactly where somebody was
 * busy, and those holes are invisible - a session that recorded half a
 * conversation looks exactly like a session that had half a conversation.
 *
 * The other half is what it costs when nobody asked for it. A node that names no
 * session must touch nothing, because every agent node drawn before this existed
 * is one of those.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class LlmSessionRecordingTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val events: LlmSessionEventRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val catalogs: SkillCatalogRepository,
    @Autowired val skills: AgentSkillRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var workflowId: Long = 0
    private lateinit var server: HttpServer
    private val received = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        events.deleteAll()
        sessions.deleteAll()
        logs.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        agents.deleteAll()
        skills.deleteAll()
        catalogs.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        received.clear()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Incident Response" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
    }

    @AfterEach
    fun stop() = server.stop(0)

    /**
     * The question, the tool, and the answer - none of which the graph asked to
     * have written down.
     *
     * The node names a session and that is the whole of the configuration. What
     * lands in it is the round as it happened, including the lookup the agent
     * did on the way, which is precisely what the run's own log does not keep.
     */
    @Test
    fun `an agent node records its round into the session it names`() {
        val catalogId = catalog("Reviews")
        skill("codeReview", catalogId, "Read the diff twice before commenting.")
        val agentId = agent("Reviewer", model(serveToolThenAnswer()), granted = "Reviews")
        graph(agentId, prefix = "issue", key = "42")

        start()

        val session = sessions.findAll().single()
        assertThat(session.sessionKey).isEqualTo("issue:42")
        assertThat(session.keyPrefix).isEqualTo("issue")
        assertThat(session.workspaceId).isEqualTo(workspaceId)

        val lines = events.findAll().sortedBy { it.id }
        assertThat(lines.map { it.kind }).containsExactly(
            LlmSessionEventKind.USER,
            LlmSessionEventKind.TOOL,
            LlmSessionEventKind.AGENT,
        )
        // The question came from the run, under the node's name - which is the
        // only thing in a workflow that can be said to have asked.
        assertThat(lines[0].actor).isEqualTo("Ask reviewer")
        assertThat(lines[0].content).contains("the database fell over")
        // The tool is named by the tool, with what the model passed it.
        assertThat(lines[1].actor).isEqualTo("skill_load")
        assertThat(lines[1].content).contains("codeReview")
        // The answer is the agent's, under the agent's own name.
        assertThat(lines[2].actor).isEqualTo("Reviewer")
        assertThat(lines[2].content).isEqualTo("Read the diff twice.")

        assertThat(session.id?.let { events.countBySessionId(it) }).isEqualTo(3)
    }

    /**
     * The feature, stated: a second run joins the first one's conversation.
     *
     * Two runs are two executions with two logs and nothing in common. The
     * session is the thing that survives them, and it survives because it is
     * keyed by what the node computed rather than by anything about a run.
     */
    @Test
    fun `a second run writes into the same session rather than a new one`() {
        val agentId = agent("Reviewer", model(serveAnswer()))
        graph(agentId, prefix = "issue", key = "42")

        start()
        start()

        assertThat(sessions.findAll()).hasSize(1)
        // Two rounds of two lines each, in the order the two runs happened.
        assertThat(events.findAll().sortedBy { it.id }.map { it.kind })
            .containsExactly(
                LlmSessionEventKind.USER,
                LlmSessionEventKind.AGENT,
                LlmSessionEventKind.USER,
                LlmSessionEventKind.AGENT,
            )
    }

    /**
     * A node that names no session costs nothing.
     *
     * Every agent node drawn before this existed is this node, so "nothing" has
     * to mean no row and no lookup - not an empty session sitting in the list
     * for somebody to wonder about.
     */
    @Test
    fun `a node that names no session records nothing at all`() {
        val agentId = agent("Reviewer", model(serveAnswer()))
        graph(agentId, prefix = null, key = null)

        start()

        assertThat(steps.findAll().single().status).isEqualTo(StepStatus.COMPLETED)
        assertThat(sessions.findAll()).isEmpty()
        assertThat(events.findAll()).isEmpty()
    }

    /**
     * The key is read off what the run is carrying, which is what makes two
     * workflows agree on one.
     *
     * A key typed into every node would only ever join runs of the same
     * workflow. A key read from the event - a ticket, a thread, a customer - is
     * what lets a second workflow that saw the same thing land in the same
     * conversation.
     */
    @Test
    fun `the key can be read from what the run was handed`() {
        val agentId = agent("Reviewer", model(serveAnswer()))
        graph(agentId, prefix = "issue", key = "ticket", keyIsReference = true)

        start()

        assertThat(sessions.findAll().single().sessionKey).isEqualTo("issue:99")
    }

    /**
     * An agent that could not answer leaves a note rather than silence.
     *
     * A transcript that simply stops leaves whoever reads it looking for words
     * that were never spoken; the note is what says the conversation was
     * interrupted rather than finished.
     */
    @Test
    fun `an agent that could not answer leaves a system note`() {
        val catalogId = catalog("Reviews")
        skill("codeReview", catalogId, "Read the diff twice.")
        val agentId = agent("Looper", model(serveAlwaysCallingTools()), granted = "Reviews")
        graph(agentId, prefix = "issue", key = "42")

        start(expectFailure = true)

        val lines = events.findAll().sortedBy { it.id }
        assertThat(lines.first().kind).isEqualTo(LlmSessionEventKind.USER)
        assertThat(lines.last().kind).isEqualTo(LlmSessionEventKind.SYSTEM)
        assertThat(lines.last().actor).isEqualTo("system")
        assertThat(lines.last().content).contains("could not answer")
        // And the lookups it did on the way are all there, which is what makes
        // the note legible: an agent that went round in circles looks like one.
        assertThat(lines.count { it.kind == LlmSessionEventKind.TOOL }).isGreaterThan(1)
    }

    /** Answers in one round, with no tools involved. */
    private fun serveAnswer(): String = serve {
        """
        {"choices":[{"message":{"role":"assistant","content":"The database was the cause."}}],
         "usage":{"prompt_tokens":11,"completion_tokens":6}}
        """.trimIndent()
    }

    /** Asks for a skill first, then answers with what it read. */
    private fun serveToolThenAnswer(): String = serve { body ->
        if (body.contains("tool_call_id")) {
            """{"choices":[{"message":{"role":"assistant","content":"Read the diff twice."}}],
               "usage":{"prompt_tokens":9,"completion_tokens":4}}"""
        } else {
            """
            {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
              {"id":"call_1","type":"function",
               "function":{"name":"skill_load","arguments":"{\"name\":\"codeReview\"}"}}
            ]}}],"usage":{"prompt_tokens":7,"completion_tokens":2}}
            """.trimIndent()
        }
    }

    /** Never answers, only ever asks for another lookup - which is stopped. */
    private fun serveAlwaysCallingTools(): String = serve {
        """
        {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
          {"id":"call_n","type":"function","function":{"name":"skill_list","arguments":"{}"}}
        ]}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}
        """.trimIndent()
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

    /**
     * One agent node, which is the whole workflow.
     *
     * The session is two node parameters and nothing else: there is no column
     * for it on the node, because a session is named the same way a prompt is -
     * with something written, or with something read off what arrived.
     */
    private fun graph(agentId: Long, prefix: String?, key: String?, keyIsReference: Boolean = false) {
        val mapped = buildList {
            if (prefix != null) add("""{ name: "sessionKeyPrefix", expression: "$prefix", mode: VALUE }""")
            if (key != null) {
                val mode = if (keyIsReference) "REFERENCE" else "VALUE"
                add("""{ name: "sessionKey", expression: "$key", mode: $mode }""")
            }
        }.joinToString(", ")

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [{
                  key: "think", kind: AGENT, name: "Ask reviewer", agentId: $agentId,
                  mappings: [$mapped], x: 0, y: 0
                }],
                edges: []
              }) { nodes { key agentId } }
            }
            """,
        ).execute()
    }

    private fun start(expectFailure: Boolean = false): Long {
        val id = graphQlTester.document(
            """
            mutation(${'$'}input: String) {
              startExecution(workspaceId: $workspaceId, workflowId: $workflowId, input: ${'$'}input) { id status }
            }
            """,
        ).variable("input", """{"summary":"the database fell over","ticket":"99"}""")
            .execute().path("startExecution.id").entity(Long::class.java).get()

        if (!expectFailure) {
            assertThat(steps.findAll().filter { it.executionId == id }.map { it.status })
                .containsOnly(StepStatus.COMPLETED)
        }
        return id
    }

    private fun agent(name: String, modelId: Long, granted: String? = null): Long {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()

        val grant = if (granted == null) "" else """, skillCatalogs: ["$granted"]"""
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "$name", modelId: $modelId$grant }) { id } }""",
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

    private fun catalog(name: String): Long = graphQlTester.document(
        """mutation { createSkillCatalog(workspaceId: $workspaceId, name: "$name") { id } }""",
    ).execute().path("createSkillCatalog.id").entity(Long::class.java).get()

    private fun skill(name: String, catalogId: Long, content: String): Long = graphQlTester.document(
        """mutation { createSkill(input: {
             workspaceId: $workspaceId, name: "$name", catalogId: $catalogId,
             content: ${'"'}${'"'}${'"'}---
name: $name
description: How to review
---

$content
${'"'}${'"'}${'"'}
           }) { id } }""",
    ).execute().path("createSkill.id").entity(Long::class.java).get()
}
