package io.mszymanski.gyloli.server.trigger

import io.mszymanski.gyloli.connector.connection.ConnectionRepository
import io.mszymanski.gyloli.connector.connection.TeamConnectionRepository
import io.mszymanski.gyloli.server.team.Team
import io.mszymanski.gyloli.server.team.TeamAuditRepository
import io.mszymanski.gyloli.server.team.TeamRepository
import io.mszymanski.gyloli.server.workflow.TeamWorkflowRepository
import io.mszymanski.gyloli.server.workflow.WorkflowRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * The team's trigger catalogue. The list shows where an event comes from and
 * what it is, so most of what is asserted here is that those read correctly for
 * both kinds of trigger. Nothing here names a workflow — that is the trigger
 * node's job, and `IncomingTriggerListenerTest` covers it.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class TriggerAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val triggers: WorkflowTriggerRepository,
    @Autowired val teamConnections: TeamConnectionRepository,
    @Autowired val connections: ConnectionRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: TeamWorkflowRepository,
    @Autowired val teams: TeamRepository,
    @Autowired val audit: TeamAuditRepository,
) {

    private var teamId: Long = 0
    private var connectionId: Long = 0

    @BeforeEach
    fun reset() {
        triggers.deleteAll()
        teamConnections.deleteAll()
        connections.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        audit.deleteAll()
        teams.deleteAll()

        teamId = requireNotNull(teams.save(Team(name = "backend")).id)
        connectionId = graphQlTester.document(
            """
            mutation {
              createTeamConnection(input: {
                teamId: $teamId, name: "Slack", type: SLACK, url: "https://hooks.slack.com"
              }) { id }
            }
            """,
        ).execute().path("createTeamConnection.id").entity(Long::class.java).get()
    }

    @Test
    fun `an incoming trigger names its connection and its event`() {
        createIncoming("Slack Mention Handler")

        graphQlTester.document(
            """
            query {
              teamTriggers(teamId: $teamId) {
                content { name type source event enabled }
                totalElements
              }
            }
            """,
        ).execute()
            .path("teamTriggers.content[0].type").entity(String::class.java).isEqualTo("INCOMING_CONNECTION")
            .path("teamTriggers.content[0].source").entity(String::class.java).isEqualTo("Slack")
            .path("teamTriggers.content[0].event").entity(String::class.java).isEqualTo("Mention")
            .path("teamTriggers.content[0].enabled").entity(Boolean::class.java).isEqualTo(true)

        assertThat(audit.findAll().map { it.message }).contains("Trigger Slack Mention Handler created")
    }

    @Test
    fun `a scheduled trigger reads as cron, with the expression as its event`() {
        graphQlTester.document(
            """
            mutation {
              createTrigger(input: {
                teamId: $teamId, name: "Nightly Data Sync",
                type: SCHEDULED, cron: "0 2 * * *", timezone: "UTC"
              }) { source event type }
            }
            """,
        ).execute()
            .path("createTrigger.type").entity(String::class.java).isEqualTo("SCHEDULED")
            .path("createTrigger.source").entity(String::class.java).isEqualTo("Cron")
            .path("createTrigger.event").entity(String::class.java).isEqualTo("0 2 * * *")
    }

    @Test
    fun `refuses a cron expression it could not schedule`() {
        graphQlTester.document(
            """
            mutation {
              createTrigger(input: {
                teamId: $teamId, name: "Broken", type: SCHEDULED, cron: "not a cron"
              }) { id }
            }
            """,
        ).execute().errors().expect { it.message?.contains("is not a cron expression") == true }.verify()
    }

    @Test
    fun `refuses an incoming trigger with no connection`() {
        graphQlTester.document(
            """
            mutation {
              createTrigger(input: {
                teamId: $teamId, name: "Nowhere", type: INCOMING_CONNECTION
              }) { id }
            }
            """,
        ).execute().errors().expect { it.message?.contains("needs a connection") == true }.verify()
    }

    @Test
    fun `refuses a duplicate name inside a team`() {
        createIncoming("Slack Mention Handler")

        graphQlTester.document(
            """
            mutation {
              createTrigger(input: {
                teamId: $teamId, name: "Slack Mention Handler",
                type: INCOMING_CONNECTION, connectionId: $connectionId, action: MENTION
              }) { id }
            }
            """,
        ).execute().errors().expect { it.message?.contains("already exists") == true }.verify()
    }

    @Test
    fun `the toggle disables and re-enables it`() {
        val id = createIncoming("Slack Mention Handler")

        graphQlTester.document("""mutation { setTriggerEnabled(id: $id, enabled: false) { enabled } }""")
            .execute().path("setTriggerEnabled.enabled").entity(Boolean::class.java).isEqualTo(false)
        graphQlTester.document("""mutation { setTriggerEnabled(id: $id, enabled: true) { enabled } }""")
            .execute().path("setTriggerEnabled.enabled").entity(Boolean::class.java).isEqualTo(true)

        assertThat(audit.findAll().map { it.message })
            .contains("Trigger Slack Mention Handler disabled", "Trigger Slack Mention Handler enabled")
    }

    @Test
    fun `renaming and deleting are recorded`() {
        val id = createIncoming("Slack Mention Handler")

        graphQlTester.document(
            """mutation { updateTrigger(id: $id, input: { name: "Mentions" }) { name } }""",
        ).execute().path("updateTrigger.name").entity(String::class.java).isEqualTo("Mentions")

        graphQlTester.document("""mutation { deleteTrigger(id: $id) }""")
            .execute().path("deleteTrigger").entity(Boolean::class.java).isEqualTo(true)

        assertThat(triggers.findAll()).isEmpty()
        assertThat(audit.findAll().map { it.message })
            .contains("Trigger Slack Mention Handler renamed to Mentions", "Trigger Mentions deleted")
    }

    @Test
    fun `only the events waiting on a connection are found`() {
        val id = createIncoming("Slack Mention Handler")
        graphQlTester.document(
            """
            mutation {
              createTrigger(input: {
                teamId: $teamId, name: "Slack Reply Watcher",
                type: INCOMING_CONNECTION, connectionId: $connectionId, action: REPLY
              }) { id }
            }
            """,
        ).execute()

        // What an arriving mention asks for.
        val waiting = triggers.findByConnectionIdAndActionAndEnabledTrue(connectionId, TriggerAction.MENTION)
        assertThat(waiting.map { it.id }).containsExactly(id)

        graphQlTester.document("""mutation { setTriggerEnabled(id: $id, enabled: false) { enabled } }""").execute()
        assertThat(triggers.findByConnectionIdAndActionAndEnabledTrue(connectionId, TriggerAction.MENTION)).isEmpty()
    }

    private fun createIncoming(name: String): Long = graphQlTester.document(
        """
        mutation {
          createTrigger(input: {
            teamId: $teamId, name: "$name",
            type: INCOMING_CONNECTION, connectionId: $connectionId, action: MENTION
          }) { id }
        }
        """,
    ).execute().path("createTrigger.id").entity(Long::class.java).get()
}
