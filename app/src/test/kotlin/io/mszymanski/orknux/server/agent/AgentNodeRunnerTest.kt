package io.mszymanski.orknux.server.agent

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.workflow.WorkflowEdgeRepository
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
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
 * An agent node in a workflow, run.
 *
 * The point of this one is that a workflow and a chat get the same agent: the
 * node asks through the same loop, with the same briefing and the same tools, so
 * an agent behaves the same whether somebody is talking to it or a run is. Two
 * behaviours under one name is a difference nobody sees until it matters.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class AgentNodeRunnerTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
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

    @Test
    fun `an agent node answers, and what it said is what the next node is handed`() {
        val agentId = agent("Reviewer", model(serveAnswer()), prompt = "You summarise incidents.")
        graph(agentId)

        start()

        val step = steps.findAll().single { it.agentId == agentId }
        assertThat(step.status).isEqualTo(StepStatus.COMPLETED)
        assertThat(step.output).isEqualTo("The database was the cause.")
        assertThat(executions.findAll().single().status).isEqualTo(ExecutionStatus.COMPLETED)

        // The run's input became the question, and the agent's instructions came
        // with it — the same briefing a chat with this agent would open on.
        assertThat(received.single()).contains("You summarise incidents.")
        assertThat(received.single()).contains("the database fell over")
    }

    /**
     * A node pointing at no agent is unfinished, not broken.
     *
     * A graph is drawn before it is finished, so the run says what it found and
     * carries on rather than failing the workflow over a node nobody has
     * configured yet.
     */
    @Test
    fun `a node naming no agent is skipped, and says so`() {
        // Still needs a server: the fixture stops one after every test.
        serveAnswer()
        graph(agentId = null)

        start()

        val step = steps.findAll().single()
        assertThat(step.status).isEqualTo(StepStatus.SKIPPED)
        assertThat(step.output).contains("names no agent")
        assertThat(executions.findAll().single().status).isEqualTo(ExecutionStatus.COMPLETED)
    }

    @Test
    fun `an agent with no model fails the step, and says what is missing`() {
        serveAnswer()
        val agentId = agent("Reviewer", modelId = null)
        graph(agentId)

        start(expectFailure = true)

        val step = steps.findAll().single()
        assertThat(step.status).isEqualTo(StepStatus.FAILED)
        assertThat(step.error).contains("has no model chosen")
    }

    private fun serveAnswer(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            received += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val body = """
                {"choices":[{"message":{"role":"assistant","content":"The database was the cause."}}],
                 "usage":{"prompt_tokens":11,"completion_tokens":6}}
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    /** One agent node, which is the whole workflow. */
    private fun graph(agentId: Long?) {
        val names = if (agentId == null) "" else ", agentId: $agentId"
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [{ key: "think", kind: AGENT, name: "Reviewer"$names, x: 0, y: 0 }],
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
        ).variable("input", """{"summary":"the database fell over"}""")
            .execute().path("startExecution.id").entity(Long::class.java).get()

        val run = executions.findAll().single { it.id == id }
        if (!expectFailure) assertThat(run.status).isIn(ExecutionStatus.COMPLETED, ExecutionStatus.RUNNING)
        return id
    }

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
