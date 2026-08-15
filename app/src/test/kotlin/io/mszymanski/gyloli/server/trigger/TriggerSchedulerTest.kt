package io.mszymanski.gyloli.server.trigger

import io.mszymanski.gyloli.server.team.Team
import io.mszymanski.gyloli.server.team.TeamAuditRepository
import io.mszymanski.gyloli.server.team.TeamRepository
import io.mszymanski.gyloli.server.workflow.TeamWorkflowRepository
import io.mszymanski.gyloli.server.workflow.WorkflowEdgeRepository
import io.mszymanski.gyloli.server.workflow.WorkflowNodeRepository
import io.mszymanski.gyloli.server.workflow.WorkflowRepository
import io.mszymanski.gyloli.workflow.execution.ExecutionLogRepository
import io.mszymanski.gyloli.workflow.execution.ExecutionStepRepository
import io.mszymanski.gyloli.workflow.execution.ExecutionTrigger
import io.mszymanski.gyloli.workflow.execution.WorkflowExecutionRepository
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
    @Autowired val assignments: TeamWorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val teams: TeamRepository,
    @Autowired val audit: TeamAuditRepository,
) {

    private var teamId: Long = 0
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
        teams.deleteAll()

        teamId = requireNotNull(teams.save(Team(name = "backend")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { teamId: $teamId, name: "Nightly Sync" }) { workflowId } }""",
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
            .variable("teamId", teamId)
            .variable("name", "Broken")
            .variable("payload", "[1, 2, 3]")
            .execute()
            .errors()
            .expect { it.message?.contains("has to be a JSON object") == true }
            .verify()
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
        .variable("teamId", teamId)
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
            teamId: $teamId, name: "$name", type: SCHEDULED, cron: "$cron", timezone: "UTC"
          }) { id }
        }
        """,
    ).execute().path("createTrigger.id").entity(Long::class.java).get()

    /** The workflow instances the definition, which is what makes it run. */
    private fun instance(triggerId: Long) {
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(teamId: $teamId, workflowId: $workflowId, input: {
                nodes: [{ key: "start", kind: TRIGGER, name: "Nightly", triggerId: $triggerId, x: 0, y: 0 }],
                edges: []
              }) { nodes { key } }
            }
            """,
        ).execute()
    }

    private companion object {
        /** JSON in a document would need escaping twice, so it travels as a variable. */
        val CREATE_WITH_PAYLOAD = """
            mutation(${'$'}teamId: ID!, ${'$'}name: String!, ${'$'}payload: String!) {
              createTrigger(input: {
                teamId: ${'$'}teamId, name: ${'$'}name, type: SCHEDULED, cron: "* * * * *",
                timezone: "UTC", payload: ${'$'}payload
              }) { id payload }
            }
        """.trimIndent()
    }
}
