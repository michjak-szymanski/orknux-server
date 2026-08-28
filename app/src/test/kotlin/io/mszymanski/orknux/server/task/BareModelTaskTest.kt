package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.connector.model.LlmModel
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProvider
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.issue.IssueNewsRepository
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.LlmSessionRepository
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
 * The tasks that were started on a bare model, after the door to starting one
 * closed.
 *
 * The task half of what `BareModelChatTest` holds for chats, and it is the half
 * with the larger blast radius: a task's page draws a title, an agent, a model,
 * a transcript, a turn count, whatever it stopped to ask and whatever it
 * finished with, and every one of those is read off a row whose `agent_id` is
 * null. A list that skipped those rows, or a page that would not open one, would
 * lose the record of work somebody actually had done — and a task's record is
 * most of what a task is for.
 *
 * `agent_id` stays nullable for exactly this reason. Issue #295 made the column
 * required at the *door* — `StartTaskInput.agentId` is `ID!` and `NewTask.agentId`
 * is a `Long` — and deliberately did not make it required in the database, which
 * would have meant deciding on somebody's behalf which agent had done work that
 * no agent did.
 *
 * The rows are written through the repository because there is no longer a call
 * that makes one. That is the assertion the rest of this rests on.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class BareModelTaskTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val tasks: TaskRepository,
    @Autowired val requests: TaskRequestRepository,
    @Autowired val grants: TaskGrantRepository,
    @Autowired val messages: TaskMessageRepository,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val events: LlmSessionEventRepository,
    @Autowired val news: IssueNewsRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var modelId: Long = 0

    @BeforeEach
    fun reset() {
        grants.deleteAll()
        messages.deleteAll()
        requests.deleteAll()
        tasks.deleteAll()
        news.deleteAll()
        events.deleteAll()
        sessions.deleteAll()
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        modelId = model()
    }

    /**
     * It opens, and it says what did the work.
     *
     * `agentId` and `agentName` come back null, which is what tells the screen to
     * draw the model instead — the same pair of fields the list has always read.
     * A page that could not describe it is a page that would draw a finished
     * task as an error.
     */
    @Test
    fun `a task started on a bare model still opens`() {
        val taskId = bareTask("Tidy the failed runs")

        graphQlTester.document(
            """query { task(id: $taskId) { id title prompt status agentId agentName modelId createdBy } }""",
        ).execute().errors().verify()
            .path("task.title").entity(String::class.java).isEqualTo("Tidy the failed runs")
            .path("task.status").entity(TaskStatus::class.java).isEqualTo(TaskStatus.DONE)
            .path("task.modelId").entity(Long::class.java).isEqualTo(modelId)
            .path("task.agentId").valueIsNull()
            .path("task.agentName").valueIsNull()
    }

    /** And it is still in the list, which is where anybody would look for it. */
    @Test
    fun `a task started on a bare model is still listed`() {
        val taskId = bareTask("Tidy the failed runs")

        graphQlTester.document(
            """query { workspaceTasks(workspaceId: $workspaceId) { totalElements content { id agentId } } }""",
        ).execute().errors().verify()
            .path("workspaceTasks.totalElements").entity(Int::class.java).isEqualTo(1)
            .path("workspaceTasks.content[0].id").entity(Long::class.java).isEqualTo(taskId)
    }

    /**
     * And the transcript is still readable, which is the part that is actually
     * worth keeping.
     *
     * The log is a session's, found off `task.sessionId`, and nothing about
     * reading one has anything to do with agents. Held here anyway, because "the
     * task still renders" would be a hollow promise if the page it renders were
     * empty.
     */
    @Test
    fun `what a bare-model task did is still readable`() {
        val taskId = bareTask("Tidy the failed runs")
        val sessionId = requireNotNull(tasks.findById(taskId).orElseThrow().sessionId)

        graphQlTester.document("""query { llmSessionEvents(sessionId: $sessionId) { content { content } } }""")
            .execute().errors().verify()
            .path("llmSessionEvents.content[*].content").entityList(String::class.java)
            .contains("Tidy the failed runs")
    }

    /**
     * A finished task with no agent, of the shape the ones from before this
     * change have: a model, no agent, and a log with the prompt in it.
     */
    private fun bareTask(title: String): Long {
        val task = tasks.save(
            Task(
                workspaceId = workspaceId,
                title = title,
                prompt = title,
                modelId = modelId,
                agentId = null,
                status = TaskStatus.DONE,
                createdBy = "alice",
                turnsAllowed = 10,
                secondsAllowed = 600,
            ),
        )
        val id = requireNotNull(task.id)
        task.sessionId = recorder.open(workspaceId, "task", id.toString())
        recorder.userSaid(requireNotNull(task.sessionId), "alice", title)
        tasks.save(task)
        assertThat(requireNotNull(tasks.findById(id).orElse(null)).agentId).isNull()
        return id
    }

    private fun model(): Long {
        val provider = providers.save(
            ModelProvider(workspaceId = workspaceId, name = "Nowhere", endpoint = "http://nowhere.invalid"),
        )
        return requireNotNull(
            models.save(
                LlmModel(providerId = requireNotNull(provider.id), name = "Stub", modelId = "stub"),
            ).id,
        )
    }
}
