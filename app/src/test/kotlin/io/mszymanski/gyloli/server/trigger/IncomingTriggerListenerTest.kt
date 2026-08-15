package io.mszymanski.gyloli.server.trigger

import io.mszymanski.gyloli.connector.connection.ConnectionRepository
import io.mszymanski.gyloli.connector.connection.IncomingAction
import io.mszymanski.gyloli.connector.connection.IncomingEvent
import io.mszymanski.gyloli.connector.connection.TeamConnectionRepository
import io.mszymanski.gyloli.server.team.Team
import io.mszymanski.gyloli.server.team.TeamAuditRepository
import io.mszymanski.gyloli.server.team.TeamRepository
import io.mszymanski.gyloli.server.workflow.TeamWorkflowRepository
import io.mszymanski.gyloli.server.workflow.WorkflowEdgeRepository
import io.mszymanski.gyloli.server.workflow.WorkflowNodeRepository
import io.mszymanski.gyloli.server.workflow.WorkflowRepository
import io.mszymanski.gyloli.workflow.execution.ExecutionService
import io.mszymanski.gyloli.workflow.execution.ExecutionTrigger
import io.mszymanski.gyloli.workflow.execution.ExecutionLogRepository
import io.mszymanski.gyloli.workflow.execution.ExecutionStepRepository
import io.mszymanski.gyloli.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * What happens when a mention arrives: the connection module publishes it, the
 * definitions waiting on that connection match, and every workflow whose trigger
 * node instances one of them runs. The websocket itself is Slack's to open, so
 * what is tested here starts one step later, with the event already published.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class IncomingTriggerListenerTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val publisher: ApplicationEventPublisher,
    @Autowired val runs: ExecutionService,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val triggers: WorkflowTriggerRepository,
    @Autowired val teamConnections: TeamConnectionRepository,
    @Autowired val connections: ConnectionRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val assignments: TeamWorkflowRepository,
    @Autowired val teams: TeamRepository,
    @Autowired val audit: TeamAuditRepository,
) {

    private var teamId: Long = 0
    private var workflowId: Long = 0
    private var connectionId: Long = 0

    @BeforeEach
    fun reset() {
        triggers.deleteAll()
        teamConnections.deleteAll()
        connections.deleteAll()
        logs.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        assignments.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        workflows.deleteAll()
        audit.deleteAll()
        teams.deleteAll()

        teamId = requireNotNull(teams.save(Team(name = "backend")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { teamId: $teamId, name: "Incident Response" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
        connectionId = graphQlTester.document(
            """
            mutation {
              createTeamConnection(input: {
                teamId: $teamId, name: "Slack", type: SLACK, url: "https://slack.com/api"
              }) { id }
            }
            """,
        ).execute().path("createTeamConnection.id").entity(Long::class.java).get()
    }

    @Test
    fun `a mention starts every workflow that instances the trigger`() {
        val trigger = createTrigger("Slack Mention Handler", "MENTION")
        instance(workflowId, trigger)

        publisher.publishEvent(mention())

        val started = runs.executions(teamId, null, null, null, null, null, null)
        assertThat(started.content).singleElement().satisfies({
            assertThat(it.workflowName).isEqualTo("Incident Response")
            assertThat(it.trigger).isEqualTo(ExecutionTrigger.WEBHOOK)
        })
        assertThat(audit.findAll().map { it.message })
            .contains("Workflow Incident Response run started by trigger Slack Mention Handler")
    }

    @Test
    fun `what the workflow is handed is the message and where it came from`() {
        instance(workflowId, createTrigger("Slack Mention Handler", "MENTION"))

        publisher.publishEvent(mention())

        // The run keeps what it was handed, so a step can read who said what.
        assertThat(executions.findAll().single().input)
            .contains("\"text\":\"<@U123> deploy please\"")
            .contains("\"channel\":\"C42\"")
            .contains("\"threadTs\":\"1699999999.000100\"")
    }

    @Test
    fun `a trigger watching another event on the same connection stays put`() {
        instance(workflowId, createTrigger("Slack Reply Watcher", "REPLY"))

        publisher.publishEvent(mention())

        assertThat(runs.executions(teamId, null, null, null, null, null, null).content).isEmpty()
    }

    @Test
    fun `a disabled trigger does not fire`() {
        val id = createTrigger("Slack Mention Handler", "MENTION")
        instance(workflowId, id)
        graphQlTester.document("""mutation { setTriggerEnabled(id: $id, enabled: false) { enabled } }""").execute()

        publisher.publishEvent(mention())

        assertThat(runs.executions(teamId, null, null, null, null, null, null).content).isEmpty()
    }

    @Test
    fun `a definition no workflow instances starts nothing`() {
        // It is a catalogue entry until a workflow points a trigger node at it.
        createTrigger("Slack Mention Handler", "MENTION")

        publisher.publishEvent(mention())

        assertThat(runs.executions(teamId, null, null, null, null, null, null).content).isEmpty()
    }

    @Test
    fun `two workflows can instance the same definition, and both run`() {
        val trigger = createTrigger("Slack Mention Handler", "MENTION")
        val second = graphQlTester.document(
            """mutation { createWorkflow(input: { teamId: $teamId, name: "Page On-Call" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
        instance(workflowId, trigger)
        instance(second, trigger)

        publisher.publishEvent(mention())

        assertThat(runs.executions(teamId, null, null, null, null, null, null).content.map { it.workflowName })
            .containsExactlyInAnyOrder("Incident Response", "Page On-Call")
    }

    @Test
    fun `the connection module's actions are the trigger actions, name for name`() {
        // They are declared apart, because the module cannot see this one, and
        // the listener maps them by name.
        assertThat(IncomingAction.entries.map { it.name })
            .isEqualTo(TriggerAction.entries.map { it.name })
    }

    private fun mention() = IncomingEvent(
        connectionId = connectionId,
        teamId = teamId,
        action = IncomingAction.MENTION,
        text = "<@U123> deploy please",
        context = mapOf(
            "channel" to "C42",
            "user" to "U7",
            "ts" to "1699999999.000100",
            "threadTs" to "1699999999.000100",
        ),
    )

    private fun createTrigger(name: String, action: String): Long = graphQlTester.document(
        """
        mutation {
          createTrigger(input: {
            teamId: $teamId, name: "$name",
            type: INCOMING_CONNECTION, connectionId: $connectionId, action: $action
          }) { id }
        }
        """,
    ).execute().path("createTrigger.id").entity(Long::class.java).get()

    /** Gives a workflow a trigger node instancing [triggerId] — the wiring. */
    private fun instance(workflow: Long, triggerId: Long) {
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(teamId: $teamId, workflowId: $workflow, input: {
                nodes: [
                  { key: "trigger", kind: TRIGGER, name: "Slack Mention", triggerId: $triggerId, x: 0, y: 0 }
                ],
                edges: []
              }) { nodes { key triggerId } }
            }
            """,
        ).execute().path("saveWorkflowGraph.nodes[0].triggerId").entity(Long::class.java).isEqualTo(triggerId)
    }
}
