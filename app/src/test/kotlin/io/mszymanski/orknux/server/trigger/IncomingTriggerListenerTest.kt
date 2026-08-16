package io.mszymanski.orknux.server.trigger

import io.mszymanski.orknux.connector.connection.ConnectionRepository
import io.mszymanski.orknux.connector.connection.IncomingAction
import io.mszymanski.orknux.connector.connection.IncomingEvent
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkflowEdgeRepository
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.workflow.execution.ExecutionService
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
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
    @Autowired val workspaceConnections: WorkspaceConnectionRepository,
    @Autowired val connections: ConnectionRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var workflowId: Long = 0
    private var connectionId: Long = 0

    @BeforeEach
    fun reset() {
        triggers.deleteAll()
        workspaceConnections.deleteAll()
        connections.deleteAll()
        logs.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        assignments.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        workflows.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Incident Response" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
        connectionId = graphQlTester.document(
            """
            mutation {
              createWorkspaceConnection(input: {
                workspaceId: $workspaceId, name: "Slack", type: SLACK, url: "https://slack.com/api"
              }) { id }
            }
            """,
        ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()
    }

    @Test
    fun `a mention starts every workflow that instances the trigger`() {
        val trigger = createTrigger("Slack Mention Handler", "MENTION")
        instance(workflowId, trigger)

        publisher.publishEvent(mention())

        val started = runs.executions(workspaceId, null, null, null, null, null, null)
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

    /**
     * The listener matches on the action, and a definition watching a different
     * one is left alone.
     *
     * Saved directly rather than through the API, which now refuses an event
     * nothing publishes. The row is still reachable — a definition saved before
     * that guard existed, or one whose publisher was removed — and what the
     * listener does with it is the thing under test.
     */
    @Test
    fun `a trigger watching another event on the same connection stays put`() {
        val watcher = requireNotNull(
            triggers.save(
                WorkflowTrigger(
                    workspaceId = workspaceId,
                    name = "Slack Reply Watcher",
                    type = TriggerType.INCOMING_CONNECTION,
                    connectionId = connectionId,
                    action = TriggerAction.REPLY,
                ),
            ).id,
        )
        instance(workflowId, watcher)

        publisher.publishEvent(mention())

        assertThat(runs.executions(workspaceId, null, null, null, null, null, null).content).isEmpty()
    }

    @Test
    fun `a disabled trigger does not fire`() {
        val id = createTrigger("Slack Mention Handler", "MENTION")
        instance(workflowId, id)
        graphQlTester.document("""mutation { setTriggerEnabled(id: $id, enabled: false) { enabled } }""").execute()

        publisher.publishEvent(mention())

        assertThat(runs.executions(workspaceId, null, null, null, null, null, null).content).isEmpty()
    }

    @Test
    fun `a definition no workflow instances starts nothing`() {
        // It is a catalogue entry until a workflow points a trigger node at it.
        createTrigger("Slack Mention Handler", "MENTION")

        publisher.publishEvent(mention())

        assertThat(runs.executions(workspaceId, null, null, null, null, null, null).content).isEmpty()
    }

    @Test
    fun `two workflows can instance the same definition, and both run`() {
        val trigger = createTrigger("Slack Mention Handler", "MENTION")
        val second = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Page On-Call" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
        instance(workflowId, trigger)
        instance(second, trigger)

        publisher.publishEvent(mention())

        assertThat(runs.executions(workspaceId, null, null, null, null, null, null).content.map { it.workflowName })
            .containsExactlyInAnyOrder("Incident Response", "Page On-Call")
    }

    @Test
    fun `the connection module's actions are the trigger actions, name for name`() {
        // They are declared apart, because the module cannot see this one, and
        // the listener maps them by name.
        assertThat(IncomingAction.entries.map { it.name })
            .isEqualTo(TriggerAction.entries.map { it.name })
    }

    /**
     * A firing that starts nothing still leaves a record.
     *
     * This is the whole point of the log: "the trigger does not work" and "the
     * trigger was never asked" look identical everywhere else, because the
     * executions list only holds runs that began. Every outcome is written,
     * especially the ones that are not runs.
     */
    @Test
    fun `what a trigger did is recorded, run or no run`() {
        val trigger = createTrigger("Slack Mention Handler", "MENTION")

        // Nothing instances it yet, so this firing starts nothing at all.
        publisher.publishEvent(mention())

        graphQlTester.document("""query { triggerFirings(triggerId: $trigger) { content { outcome detail runsStarted } } }""")
            .execute()
            .path("triggerFirings.content[0].outcome").entity(String::class.java).isEqualTo("NO_INSTANCE")
            .path("triggerFirings.content[0].runsStarted").entity(Int::class.java).isEqualTo(0)

        // Wired up, the next one starts a run and says so.
        instance(workflowId, trigger)
        publisher.publishEvent(mention())

        graphQlTester.document(
            """query { workspaceTriggers(workspaceId: $workspaceId) { content { name lastFiring { outcome runsStarted } } } }""",
        ).execute()
            .path("workspaceTriggers.content[0].lastFiring.outcome").entity(String::class.java).isEqualTo("STARTED")
            .path("workspaceTriggers.content[0].lastFiring.runsStarted").entity(Int::class.java).isEqualTo(1)
    }

    /**
     * A condition on the trigger decides before a run exists.
     *
     * The alternative is a condition node inside the workflow, which only
     * decides after the run has started, been audited and appeared in the
     * executions list — by which point an unwanted mention is indistinguishable
     * from real work. Nothing is started here at all.
     */
    @Test
    fun `a trigger asking a condition only fires when it holds`() {
        val conditionId = graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                workspaceId: $workspaceId, name: "From alice", type: SLACK, property: MESSAGE_AUTHOR,
                check: IN_LIST, values: ["U7"]
              }) { id }
            }
            """,
        ).execute().path("createCondition.id").entity(Long::class.java).get()

        val trigger = createTrigger("Slack Mention Handler", "MENTION")
        graphQlTester.document(
            """
            mutation {
              updateTrigger(id: $trigger, input: {
                name: "Slack Mention Handler", connectionId: $connectionId, action: MENTION,
                conditionId: $conditionId
              }) { conditionId conditionName }
            }
            """,
        ).execute()
            .path("updateTrigger.conditionName").entity(String::class.java).isEqualTo("From alice")
        instance(workflowId, trigger)

        // U7 is who the mention came from, so this one is wanted.
        publisher.publishEvent(mention())
        assertThat(runs.executions(workspaceId, null, null, null, null, null, null).content).hasSize(1)

        // The same trigger, a mention from somebody else: no run, and nothing
        // in the executions list to explain away.
        publisher.publishEvent(mention().copy(context = mention().context + ("user" to "U99")))
        assertThat(runs.executions(workspaceId, null, null, null, null, null, null).content).hasSize(1)
    }

    /** A condition still being asked is not one to delete out from under. */
    @Test
    fun `a condition a trigger asks cannot be deleted`() {
        val conditionId = graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                workspaceId: $workspaceId, name: "From alice", type: SLACK, property: MESSAGE_AUTHOR,
                check: IN_LIST, values: ["U7"]
              }) { id }
            }
            """,
        ).execute().path("createCondition.id").entity(Long::class.java).get()

        val trigger = createTrigger("Slack Mention Handler", "MENTION")
        graphQlTester.document(
            """
            mutation {
              updateTrigger(id: $trigger, input: {
                name: "Slack Mention Handler", connectionId: $connectionId, action: MENTION,
                conditionId: $conditionId
              }) { id }
            }
            """,
        ).execute()

        graphQlTester.document("""mutation { deleteCondition(id: $conditionId) }""")
            .execute()
            .errors().satisfy { errors ->
                assertThat(errors.first().message).contains("Slack Mention Handler")
            }
    }

    private fun mention() = IncomingEvent(
        connectionId = connectionId,
        workspaceId = workspaceId,
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
            workspaceId: $workspaceId, name: "$name",
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
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflow, input: {
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
