package io.mszymanski.orknux.server.task

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.issue.IssueNewsKind
import io.mszymanski.orknux.server.issue.IssueNewsRepository
import io.mszymanski.orknux.server.llm.LlmSessionEvent
import io.mszymanski.orknux.server.llm.LlmSessionEventKind
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A task's loop, driven a turn at a time.
 *
 * The loop is asked directly rather than through an engine, which is what makes
 * these deterministic: an engine runs a task on a thread of its own, and a test
 * that waits for one is a test that is either slow or flaky. What an engine adds
 * is when the next turn happens, and that is not what is worth pinning here.
 *
 * The model is a stub that answers the way a real one would - a tool call, then
 * whatever the tool's result leads to - because everything interesting about a
 * task is what the loop does with a tool call it was not expecting.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class TaskLoopTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val loop: TaskLoop,
    @Autowired val service: TaskService,
    @Autowired val tasks: TaskRepository,
    @Autowired val requests: TaskRequestRepository,
    @Autowired val grants: TaskGrantRepository,
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
    private lateinit var server: HttpServer

    /** Every request body the stub was sent, so what was offered can be read off it. */
    private val received = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        grants.deleteAll()
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
        received.clear()
    }

    @AfterEach
    fun stop() = server.stop(0)

    /**
     * The ordinary ending: the agent works, says it is done, and what it said is
     * the outcome.
     */
    @Test
    fun `an agent that calls task_done finishes the task and leaves its summary`() {
        val taskId = taskFor(serve { finishing("The report is in /tmp/report.md") })

        assertThat(loop.advance(taskId)).isEqualTo(TaskTurn.Over)

        val task = requireNotNull(tasks.findByIdOrNull(taskId))
        assertThat(task.status).isEqualTo(TaskStatus.DONE)
        assertThat(task.outcome).isEqualTo("The report is in /tmp/report.md")
        assertThat(task.endedBecause).isEqualTo("finished")
        assertThat(task.turnsSpent).isEqualTo(1)

        // The whole account of it is one LLM session and not a second log: the
        // prompt, the call, and the note that it finished.
        val log = events.findAll().filter { it.sessionId == task.sessionId }
        assertThat(log.map { it.kind }).contains(LlmSessionEventKind.USER, LlmSessionEventKind.TOOL)
        assertThat(log.first { it.kind == LlmSessionEventKind.USER }.content).isEqualTo(PROMPT)
        assertThat(log.map { it.content.orEmpty() + it.result.orEmpty() })
            .anyMatch { it.contains("The report is in /tmp/report.md") }
    }

    /**
     * Progress is not an ending.
     *
     * A model that writes what it has done and stops has not finished, and the
     * loop says so rather than taking prose for a conclusion.
     */
    @Test
    fun `text without task_done is progress and the task carries on`() {
        val taskId = taskFor(serve { saying("Started reading.") })

        assertThat(loop.advance(taskId)).isEqualTo(TaskTurn.Working)

        val task = requireNotNull(tasks.findByIdOrNull(taskId))
        assertThat(task.status).isEqualTo(TaskStatus.RUNNING)
        val said = events.findAll().filter { it.sessionId == task.sessionId }.map { it.content }
        assertThat(said).contains("Started reading.")
        // And the nudge is written down too, so the transcript alternates and
        // reads as a conversation rather than as a model talking to itself.
        assertThat(said).anyMatch { it?.contains("Call task_done") == true }
    }

    /**
     * The whole of the permission mechanism, end to end.
     *
     * It stops, it says what it wants and why, the person who asked for the task
     * is told, and approving hands it exactly that - for this task and not for
     * the agent.
     */
    @Test
    fun `a task stops for permission, rings a bell, and carries on with exactly what was granted`() {
        val taskId = taskFor(serveAsking())

        assertThat(loop.advance(taskId)).isInstanceOf(TaskTurn.Parked::class.java)

        val parked = requireNotNull(tasks.findByIdOrNull(taskId))
        assertThat(parked.status).isEqualTo(TaskStatus.WAITING)
        assertThat(parked.waitingUntil).isNotNull()

        val asked = requireNotNull(requests.findFirstByTaskIdAndDecisionIsNullOrderByAskedAtAscIdAsc(taskId))
        assertThat(asked.kind).isEqualTo(TaskRequestKind.PERMISSION)
        assertThat(asked.capability).isEqualTo(TaskCapability.SHELLS)
        assertThat(asked.asks).contains("run the build")

        // Whoever asked for the task hears about it. Nothing happens at all
        // until somebody looks, so this is the mechanism and not a courtesy.
        val told = news.findAll()
        assertThat(told).hasSize(1)
        assertThat(told[0].kind).isEqualTo(IssueNewsKind.TASK_WAITING)
        assertThat(told[0].taskId).isEqualTo(taskId)
        assertThat(told[0].audienceName).isEqualTo("alice")
        assertThat(told[0].issueId).isNull()

        // Asking again while it is parked does nothing but say so.
        assertThat(loop.advance(taskId)).isInstanceOf(TaskTurn.Parked::class.java)

        service.approve(requireNotNull(asked.id), "alice")

        val granted = grants.findByTaskIdOrderByGrantedAtAscIdAsc(taskId)
        assertThat(granted).hasSize(1)
        assertThat(granted[0].capability).isEqualTo(TaskCapability.SHELLS)
        assertThat(granted[0].grantedBy).isEqualTo("alice")
        // On the task, and not on the agent: an afternoon's approval must not
        // arm the agent in every chat for ever.
        assertThat(agents.findAll().single().shellAccess).isFalse()

        assertThat(requireNotNull(tasks.findByIdOrNull(taskId)).status).isEqualTo(TaskStatus.RUNNING)

        // And the next turn is actually offered the thing that was granted.
        received.clear()
        loop.advance(taskId)
        assertThat(received).isNotEmpty()
        assertThat(received.last()).contains("shell_open_session")
    }

    /** A question parks the same way, and the answer comes back as a turn. */
    @Test
    fun `a task stops to ask a question and resumes with the answer`() {
        val taskId = taskFor(serve { body ->
            if (body.contains("Email it to ops@example.com")) {
                finishing("Sent it to ops.")
            } else {
                calling("task_ask", """{\"question\":\"Where should I put the report?\"}""")
            }
        })

        assertThat(loop.advance(taskId)).isInstanceOf(TaskTurn.Parked::class.java)
        val asked = requireNotNull(requests.findFirstByTaskIdAndDecisionIsNullOrderByAskedAtAscIdAsc(taskId))
        assertThat(asked.kind).isEqualTo(TaskRequestKind.QUESTION)

        service.answer(requireNotNull(asked.id), "Email it to ops@example.com", "alice")

        assertThat(loop.advance(taskId)).isEqualTo(TaskTurn.Over)
        val task = requireNotNull(tasks.findByIdOrNull(taskId))
        assertThat(task.status).isEqualTo(TaskStatus.DONE)
        assertThat(task.outcome).isEqualTo("Sent it to ops.")
    }

    /**
     * The ceiling, which is the whole reason there is one.
     *
     * An agent that never says it is done is stopped and the reason is legible.
     * Left alone it is a bill nobody agreed to.
     */
    @Test
    fun `a task that never finishes runs out of turns and says so`() {
        val taskId = taskFor(
            serve { saying("Still going.") },
            turns = 2,
        )

        assertThat(loop.advance(taskId)).isEqualTo(TaskTurn.Working)
        assertThat(loop.advance(taskId)).isEqualTo(TaskTurn.Working)
        assertThat(loop.advance(taskId)).isEqualTo(TaskTurn.Over)

        val task = requireNotNull(tasks.findByIdOrNull(taskId))
        assertThat(task.status).isEqualTo(TaskStatus.FAILED)
        assertThat(task.endedBecause).isEqualTo("out of turns after 2")
    }

    /**
     * What the model thought is written into the task's log, and settles.
     *
     * The complaint this answers was that a task's page does not move while the
     * model is working, and the reason it did not is here rather than in the
     * page: nothing was written between one turn and the next. So what is
     * asserted is the record - a line of its own kind, carrying the whole of the
     * reasoning, with a duration on it once the model stopped.
     *
     * The duration is the marker as well as the measurement: null means still
     * thinking, and a line left null is what a page draws as a model at work. A
     * turn that has ended and left one null would be a page claiming for ever
     * that a finished task is thinking.
     */
    @Test
    fun `what the model thought is recorded as its own line and settles when it stops`() {
        val taskId = taskFor(serve { thinkingThen("Started reading.") })

        assertThat(loop.advance(taskId)).isEqualTo(TaskTurn.Working)

        val task = requireNotNull(tasks.findByIdOrNull(taskId))
        val thinking = events.findAll()
            .filter { it.sessionId == task.sessionId && it.kind == LlmSessionEventKind.THINKING }

        // One line for the round rather than one per frame: a model emits
        // hundreds of those and a transcript spread over them is unreadable.
        assertThat(thinking).hasSize(1)
        assertThat(thinking.single().content)
            .isEqualTo("Let me look at what failed. Last week had three runs.")
        assertThat(thinking.single().millis).isNotNull()
        assertThat(thinking.single().unfinished).isFalse()

        // And it is not something the agent said. What goes back in front of a
        // model is USER and AGENT, and reasoning replayed as an answer is the
        // lie the chat's own thinking was kept out of its thread to avoid.
        assertThat(recorder.remembered(requireNotNull(task.sessionId)).map { it.content })
            .noneMatch { it.contains("Last week had three runs") }
    }

    /**
     * And it settles when the model starts answering, not when the turn ends.
     *
     * Issue #290, and the whole of it is the word *when*. The test above asks
     * the question after the round is over, and every build this has ever had
     * passes it: [TaskThinking.settle] runs in the loop's `finally` and writes
     * the duration and whatever reasoning had not been flushed. What nobody
     * asked was what the line looks like *while the model is writing*, and for
     * a prompt whose answer is long - "write 1000 words, split it into
     * chapters" is the one that was reported - that is nearly the whole turn.
     *
     * It looked like this: the reasoning cut off wherever the last flush fell,
     * which is a sentence stopping in the middle, and no duration on the line -
     * so the page said *Thinking*, counted up on its own clock for two minutes,
     * and moved only when somebody reloaded it after the turn had ended. It was
     * read as the live view having stopped delivering; it was the record having
     * nothing more to deliver.
     *
     * So the stub holds the answer open. The assertion is made at a moment when
     * the round is provably still in flight - the model has written a piece of
     * its answer and nothing has released it - which is the only moment that
     * can tell a line closed by [TaskThinking.answering] from one closed by the
     * `finally` afterwards.
     */
    @Test
    fun `the thinking settles when the model starts answering rather than when the turn ends`() {
        val answering = CountDownLatch(1)
        val release = CountDownLatch(1)
        val taskId = taskFor(serveHolding(answering, release))

        val turn = Thread({ loop.advance(taskId) }, "held-turn")
        turn.start()
        try {
            assertThat(answering.await(HELD_SECONDS, TimeUnit.SECONDS))
                .describedAs("the stub got as far as writing its answer")
                .isTrue()

            val sessionId = requireNotNull(requireNotNull(tasks.findByIdOrNull(taskId)).sessionId)
            val settled = thinkingSettledWithin(sessionId, WHILE_WRITING_MILLIS)

            assertThat(settled)
                .describedAs("the reasoning is closed while the model is still writing its answer")
                .isNotNull()
            // The whole of it, including the frames that arrived inside the last
            // flush window - which is the half of the line a reader could see
            // was missing, because it stops mid-sentence.
            assertThat(settled?.content)
                .isEqualTo("Let me look at what failed. Last week had three runs.")
            assertThat(settled?.unfinished).isFalse()
        } finally {
            release.countDown()
            turn.join(HELD_SECONDS * 1_000)
        }
    }

    /** A finished task is not started again by a second delivery of the same turn. */
    @Test
    fun `advancing a task that is over does nothing`() {
        val taskId = taskFor(serve { finishing("Done.") })
        loop.advance(taskId)
        received.clear()

        assertThat(loop.advance(taskId)).isEqualTo(TaskTurn.Over)
        assertThat(received).isEmpty()
    }

    /**
     * A task with an agent, a stub model and its prompt already in the log.
     *
     * Built here rather than through [TaskService.start] on purpose: starting one
     * hands it to an engine, which would run it on a thread of its own while the
     * test was still setting up.
     */
    private fun taskFor(endpoint: String, turns: Int = 10): Long {
        val modelId = model(endpoint)
        val agentId = agent("Worker", modelId)
        val task = tasks.save(
            Task(
                workspaceId = workspaceId,
                title = "Build the report",
                prompt = PROMPT,
                agentId = agentId,
                modelId = modelId,
                createdBy = "alice",
                turnsAllowed = turns,
                secondsAllowed = 600,
            ),
        )
        val id = requireNotNull(task.id)
        task.sessionId = recorder.open(workspaceId, "task", id.toString())
        recorder.userSaid(requireNotNull(task.sessionId), "alice", PROMPT)
        tasks.save(task)
        return id
    }

    /** Asks for the shells first, and finishes once it has them. */
    private fun serveAsking(): String = serve { body ->
        when {
            body.contains("has given you shells") -> finishing("Built it.")
            else -> calling(
                "task_request_permission",
                """{\"capability\":\"shells\",\"why\":\"I need to run the build\"}""",
            )
        }
    }

    private fun finishing(summary: String) =
        calling("task_done", """{\"summary\":\"$summary\"}""")

    /**
     * A round the stub streams, because a task's rounds are streamed.
     *
     * They were not when this was written: the loop passed no `RoundWatch`, so
     * [io.mszymanski.orknux.server.chat.AgentConversation] took the blocking
     * path and the stub could answer with one JSON object. It passes one now,
     * which is what makes a turn visible while it is happening, and the price
     * is that the stub has to speak the shape a provider speaks when it is
     * asked to stream. Spelled once here rather than in every answer.
     */
    private fun streamed(vararg frames: String) =
        frames.joinToString("\n\n", postfix = "\n\ndata: [DONE]\n\n") { "data: $it" }

    private fun calling(tool: String, arguments: String) = streamed(
        """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function",""" +
            """"function":{"name":"$tool","arguments":"$arguments"}}]}}]}""",
        """{"choices":[{"delta":{}}],"usage":{"prompt_tokens":7,"completion_tokens":2}}""",
    )

    /** A round that says something and asks for nothing. */
    private fun saying(said: String) = streamed(
        """{"choices":[{"delta":{"content":"$said"}}]}""",
    )

    /**
     * A round that thinks out loud before it says anything.
     *
     * Two frames of reasoning rather than one, because what is pinned by the
     * test using it is that a *growing* block reaches the session. One frame
     * would pass just as well on a build that wrote the thinking down once the
     * round was over, which is the shape this feature replaced.
     */
    private fun thinkingThen(said: String) = streamed(
        """{"choices":[{"delta":{"reasoning_content":"Let me look at what failed. "}}]}""",
        """{"choices":[{"delta":{"reasoning_content":"Last week had three runs."}}]}""",
        """{"choices":[{"delta":{"content":"$said"}}]}""",
    )

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
     * A round that thinks, writes the first of its answer, and then stops there.
     *
     * Chunked rather than sent whole, because what is being watched is a round
     * *in flight*: a response with a length on it is one the client has already
     * finished reading by the time anything can be asserted about it. The two
     * reasoning frames go out back to back on purpose - that is what leaves the
     * second of them inside [TaskThinking.FLUSH_EVERY_MILLIS] and unwritten,
     * which is the state the reported line was found in.
     *
     * @param answering counted down once a piece of the answer is on the wire.
     * @param release awaited before the round is allowed to finish, so the
     *   caller decides how long the model is still writing for.
     */
    private fun serveHolding(answering: CountDownLatch, release: CountDownLatch): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            received += body
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            // Nought is chunked, which is what lets this write a piece and wait.
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                fun write(frame: String) {
                    out.write("data: $frame\n\n".toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                }
                write("""{"choices":[{"delta":{"reasoning_content":"Let me look at what failed. "}}]}""")
                write("""{"choices":[{"delta":{"reasoning_content":"Last week had three runs."}}]}""")
                write("""{"choices":[{"delta":{"content":"Three runs failed, "}}]}""")
                answering.countDown()
                release.await(HELD_SECONDS, TimeUnit.SECONDS)
                write("""{"choices":[{"delta":{"content":"all of them on the same step."}}]}""")
                write("""{"choices":[{"delta":{}}],"usage":{"prompt_tokens":7,"completion_tokens":2}}""")
                out.write("data: [DONE]\n\n".toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    /**
     * The session's one block of reasoning, once it has been closed, or null.
     *
     * Polled rather than read once: the assertion is being made from another
     * thread while the round runs, so "has it been closed yet" is a question
     * about a moment that arrives shortly after the answer starts. Bounded, and
     * the bound is what makes a failure a failure - the stub holds the round
     * open for far longer, so a line that is not closed inside this was not
     * closed by the answer beginning at all.
     */
    private fun thinkingSettledWithin(sessionId: Long, millis: Long): LlmSessionEvent? {
        val until = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < until) {
            val line = events.findAll()
                .singleOrNull { it.sessionId == sessionId && it.kind == LlmSessionEventKind.THINKING }
            if (line?.millis != null) return line
            Thread.sleep(POLL_MILLIS)
        }
        return null
    }

    private fun agent(name: String, modelId: Long): Long {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "$name", modelId: $modelId }) { id } }""",
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
        const val PROMPT = "Write a report of last week's failed runs."

        /**
         * How long the reasoning is given to close, once the answer has begun.
         *
         * Generous against the machine and tiny against the thing it is telling
         * apart: the stub holds the round open for thirty seconds, so a line
         * that is still open after this was not closed by the answer starting
         * and will not be closed until the turn ends.
         */
        const val WHILE_WRITING_MILLIS = 5_000L

        const val POLL_MILLIS = 100L

        /** How long the stub goes on writing if nothing releases it. */
        const val HELD_SECONDS = 30L
    }
}
