package io.mszymanski.orknux.server.trigger

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkflowEdgeRepository
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workflow.WorkflowPublicationRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.time.OffsetDateTime

/**
 * The clock behind a scheduled trigger.
 *
 * db-scheduler decides when the tick runs; what the tick does with a definition
 * is what is asserted here, by calling it with the time to pretend it is.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class TriggerSchedulerTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val scheduler: TriggerScheduler,
    @Autowired val triggers: WorkflowTriggerRepository,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val publications: WorkflowPublicationRepository,
) {

    private var workspaceId: Long = 0
    private var workflowId: Long = 0

    @BeforeEach
    fun reset() {
        triggers.deleteAll()
        logs.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Nightly Sync" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
    }

    @Test
    fun `a due trigger starts the workflows that instance it`() {
        val id = scheduled("Nightly Data Sync", "* * * * *")
        instance(id)
        // It has never fired, so the tick starts from a minute ago and this is due.
        scheduler.tick(OffsetDateTime.now())

        val started = executions.findAll()
        assertThat(started).singleElement().satisfies({
            assertThat(it.workflowName).isEqualTo("Nightly Sync")
            assertThat(it.trigger).isEqualTo(ExecutionTrigger.SCHEDULE)
        })
        assertThat(triggers.findAll().single().lastFiredAt).isNotNull()
        assertThat(audit.findAll().map { it.message })
            .contains("Workflow Nightly Sync run started by trigger Nightly Data Sync")
    }

    @Test
    fun `a trigger that has just fired is not fired again on the next tick`() {
        instance(scheduled("Nightly Data Sync", "0 2 * * *"))

        scheduler.tick(OffsetDateTime.now())
        val after = executions.count()
        scheduler.tick(OffsetDateTime.now())

        assertThat(executions.count()).isEqualTo(after)
    }

    @Test
    fun `the run is handed the trigger's payload, with the firing on top`() {
        val id = createWithPayload(
            "Nightly Data Sync",
            """{ "format": "compact", "limits": { "rows": 100 } }""",
        )
        instance(id)

        scheduler.tick(OffsetDateTime.now())

        // The clock carries no data, so what the workflow works on is the
        // payload — with what this firing says written over it.
        val input = requireNotNull(executions.findAll().single().input)
        assertThat(input)
            .contains(""""format":"compact"""")
            .contains(""""rows":100""")
            .contains(""""cron":"* * * * *"""")
    }

    @Test
    fun `a payload that is not a JSON object is refused`() {
        graphQlTester.document(CREATE_WITH_PAYLOAD)
            .variable("workspaceId", workspaceId)
            .variable("name", "Broken")
            .variable("payload", "[1, 2, 3]")
            .execute()
            .errors()
            .expect { it.message?.contains("has to be a JSON object") == true }
            .verify()
    }

    /**
     * The regression that stopped every schedule in the installation.
     *
     * Firing at a draft threw out of a transactional method, which marked the
     * round's shared transaction rollback-only; the commit then failed, and with
     * it went every other trigger's run and every other trigger's `lastFiredAt`.
     * A minute later the same triggers were due against the same draft, so
     * nothing scheduled ever fired again.
     *
     * Which is why this asserts on the *other* trigger. A test that only checked
     * that the draft was skipped passed on the broken code.
     */
    @Test
    fun `a trigger pointing at a draft does not stop the other triggers firing`() {
        val draft = draftWorkflow("Half Drawn")
        instance(scheduled("Unpublished Trigger", "* * * * *"), workflow = draft, publish = false)
        instance(scheduled("Nightly Data Sync", "* * * * *"))

        scheduler.tick(OffsetDateTime.now())

        // The published one ran, alone and unharmed.
        assertThat(executions.findAll()).singleElement().satisfies({
            assertThat(it.workflowName).isEqualTo("Nightly Sync")
            assertThat(it.trigger).isEqualTo(ExecutionTrigger.SCHEDULE)
        })
        assertThat(triggers.findByWorkspaceIdAndName(workspaceId, "Nightly Data Sync")?.lastFiredAt)
            .describedAs("the healthy trigger's stamp survives the round")
            .isNotNull()

        // And the draft's own occurrence was taken rather than left due, so it
        // does not come back a minute later to try the same thing again.
        assertThat(triggers.findByWorkspaceIdAndName(workspaceId, "Unpublished Trigger")?.lastFiredAt).isNotNull()
    }

    /**
     * The shape of the bug, rather than the one instance of it that was reported.
     *
     * A draft workflow is what stopped every scheduled trigger in the
     * installation, and the test above pins that exact case. But the reason it
     * could happen was structural: the whole round ran in one transaction, so
     * *anything* failing in it took every other trigger down with it. Fixing the
     * draft alone would leave the next thing that throws free to do the same.
     *
     * So this one breaks a firing for a reason nothing in the product catches by
     * name - a published snapshot that is not readable, which is what a
     * half-written row or a bad restore looks like - and asks the only question
     * that matters: did the triggers on either side of it still go?
     */
    @Test
    fun `a trigger that fails for any reason at all does not stop the others`() {
        // A workflow each: instancing writes the graph, so two triggers on one
        // workflow would leave only the second of them attached to it.
        val before = draftWorkflow("Fires Before")
        instance(scheduled("Before Trigger", "* * * * *"), workflow = before)
        val broken = draftWorkflow("Corrupt Snapshot")
        instance(scheduled("Breaks", "* * * * *"), workflow = broken)
        val after = draftWorkflow("Fires After")
        instance(scheduled("After Trigger", "* * * * *"), workflow = after)

        // Its published graph is well-formed JSON and not a graph: the column
        // is a json type, so nonsense is refused before this code ever sees it,
        // and what a bad restore actually leaves behind is a readable document
        // of the wrong shape. Nothing declares an exception for it, which is
        // the point.
        val snapshot = publications.findAll().first { it.workflowId == broken }
        snapshot.graph = """{"nodes": "this should have been a list"}"""
        publications.save(snapshot)

        scheduler.tick(OffsetDateTime.now())

        assertThat(executions.findAll().map { it.workflowName })
            .describedAs("a failure in the middle of the round stops neither side of it")
            .containsExactlyInAnyOrder("Fires Before", "Fires After")
        for (name in listOf("Before Trigger", "After Trigger")) {
            assertThat(triggers.findByWorkspaceIdAndName(workspaceId, name)?.lastFiredAt)
                .describedAs("$name kept its stamp")
                .isNotNull()
        }
    }

    @Test
    fun `a disabled trigger stays put, and so does one nothing instances`() {
        val disabled = scheduled("Disabled", "* * * * *")
        instance(disabled)
        graphQlTester.document("""mutation { setTriggerEnabled(id: $disabled, enabled: false) { enabled } }""")
            .execute()
        scheduled("Unused", "* * * * *")

        scheduler.tick(OffsetDateTime.now())

        assertThat(executions.findAll()).isEmpty()
    }

    private fun createWithPayload(name: String, payload: String): Long = graphQlTester.document(CREATE_WITH_PAYLOAD)
        .variable("workspaceId", workspaceId)
        .variable("name", name)
        .variable("payload", payload)
        .execute()
        .path("createTrigger.id")
        .entity(Long::class.java)
        .get()

    private fun scheduled(name: String, cron: String): Long = graphQlTester.document(
        """
        mutation {
          createTrigger(input: {
            workspaceId: $workspaceId, name: "$name", type: SCHEDULED, cron: "$cron", timezone: "UTC"
          }) { id }
        }
        """,
    ).execute().path("createTrigger.id").entity(Long::class.java).get()

    /** A second workflow, so a draft and a published one can be in the same round. */
    private fun draftWorkflow(name: String): Long = graphQlTester.document(
        """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "$name" }) { workflowId } }""",
    ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()

    /** The workflow instances the definition, which is what makes it run. */
    private fun instance(triggerId: Long, workflow: Long = workflowId, publish: Boolean = true) {
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflow, input: {
                nodes: [{ key: "start", kind: TRIGGER, name: "Nightly", triggerId: $triggerId, x: 0, y: 0 }],
                edges: []
              }) { nodes { key } }
            }
            """,
        ).execute()

        // Published, because a trigger runs the published copy: a graph that
        // was only ever saved is one somebody is still drawing.
        if (publish) {
            graphQlTester.document(
                """mutation { publishWorkflow(workspaceId: $workspaceId, workflowId: $workflow) { status } }""",
            ).execute()
        }
    }

    private companion object {
        /** JSON in a document would need escaping twice, so it travels as a variable. */
        val CREATE_WITH_PAYLOAD = """
            mutation(${'$'}workspaceId: ID!, ${'$'}name: String!, ${'$'}payload: String!) {
              createTrigger(input: {
                workspaceId: ${'$'}workspaceId, name: ${'$'}name, type: SCHEDULED, cron: "* * * * *",
                timezone: "UTC", payload: ${'$'}payload
              }) { id payload }
            }
        """.trimIndent()
    }
}
