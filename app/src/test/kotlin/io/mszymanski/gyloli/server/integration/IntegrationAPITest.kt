package io.mszymanski.gyloli.server.integration

import io.mszymanski.gyloli.connector.connection.ConnectionRepository
import io.mszymanski.gyloli.connector.connection.ConnectionTargetService
import io.mszymanski.gyloli.connector.connection.McpServerRepository
import io.mszymanski.gyloli.connector.connection.TeamConnectionRepository
import io.mszymanski.gyloli.server.team.Team
import io.mszymanski.gyloli.server.team.TeamAuditRepository
import io.mszymanski.gyloli.server.team.TeamRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Integrations end to end: the app decides who may ask and records what they
 * did, the connection module holds the data. One process, so this exercises
 * both — no stand-in for either half.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class IntegrationAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val connections: ConnectionRepository,
    @Autowired val teamConnections: TeamConnectionRepository,
    @Autowired val mcpServers: McpServerRepository,
    @Autowired val targets: ConnectionTargetService,
    @Autowired val teams: TeamRepository,
    @Autowired val audit: TeamAuditRepository,
) {

    private var teamId: Long = 0

    @BeforeEach
    fun reset() {
        teamConnections.deleteAll()
        mcpServers.deleteAll()
        connections.deleteAll()
        audit.deleteAll()
        teams.deleteAll()
        teamId = requireNotNull(teams.save(Team(name = "backend")).id)
    }

    @Test
    fun `defines a default connection and records it`() {
        graphQlTester.document(
            """
            mutation {
              createConnection(input: { name: "Slack", type: SLACK, url: "https://hooks.slack.com" })
              { id name type url }
            }
            """,
        ).execute()
            .path("createConnection.name").entity(String::class.java).isEqualTo("Slack")
            .path("createConnection.type").entity(String::class.java).isEqualTo("SLACK")

        assertThat(connections.findAll()).singleElement().extracting<String> { it.name }.isEqualTo("Slack")
        assertThat(audit.findAll().map { it.message }).contains("Default connection Slack created")
        // An organization-wide change belongs to no team.
        assertThat(audit.findAll().map { it.teamId }).containsOnly(null)
    }

    @Test
    fun `refuses a duplicate default name`() {
        createDefault("Slack")

        graphQlTester.document(
            """mutation { createConnection(input: { name: "Slack", type: SLACK, url: "https://x" }) { id } }""",
        ).execute().errors().expect { it.message?.contains("already exists") == true }.verify()
    }

    @Test
    fun `a new team is provisioned with every default, and the log says so`() {
        createDefault("Slack")
        createDefault("GitHub", type = "GITHUB")

        val newTeam = graphQlTester.document(
            """mutation { createTeam(input: { name: "platform" }) { id } }""",
        ).execute().path("createTeam.id").entity(Long::class.java).get()

        graphQlTester.document("""query { teamConnections(teamId: $newTeam) { name inherited status } }""")
            .execute()
            .path("teamConnections[*].name").entityList(String::class.java).containsExactly("GitHub", "Slack")
            .path("teamConnections[0].inherited").entity(Boolean::class.java).isEqualTo(true)
            .path("teamConnections[0].status").entity(String::class.java).isEqualTo("NOT_CHECKED")

        assertThat(audit.findAll().map { it.message })
            .contains("2 default connections provisioned for platform")
    }

    @Test
    fun `deleting a team takes its connections with it`() {
        createDefault("Slack")
        val newTeam = graphQlTester.document(
            """mutation { createTeam(input: { name: "platform" }) { id } }""",
        ).execute().path("createTeam.id").entity(Long::class.java).get()
        assertThat(teamConnections.findAll()).isNotEmpty()

        graphQlTester.document("""mutation { deleteTeam(id: $newTeam) }""")
            .execute().path("deleteTeam").entity(Boolean::class.java).isEqualTo(true)

        // No foreign key does this: the module is told.
        assertThat(teamConnections.findAll()).isEmpty()
    }

    @Test
    fun `a backfill hands a new default to the teams that already exist`() {
        graphQlTester.document(
            """
            mutation {
              createConnection(input: {
                name: "Pager", type: WEBHOOK, url: "https://pager.test", addToExistingTeams: true
              }) { id }
            }
            """,
        ).execute().path("createConnection.id").hasValue()

        assertThat(teamConnections.findAll().map { it.teamId }).containsExactly(teamId)
        assertThat(audit.findAll().map { it.message })
            .contains("Default connection Pager added to 1 existing team")
    }

    @Test
    fun `storing credentials connects a connection, and revealing them is recorded`() {
        val id = createTeamConnection("Slack")

        graphQlTester.document(
            """
            mutation {
              updateTeamConnection(id: $id, input: { authType: BEARER_TOKEN, secret: "xoxb-token" })
              { secretSet status }
            }
            """,
        ).execute()
            .path("updateTeamConnection.secretSet").entity(Boolean::class.java).isEqualTo(true)
            .path("updateTeamConnection.status").entity(String::class.java).isEqualTo("NOT_CHECKED")

        graphQlTester.document("""mutation { revealTeamConnectionSecret(id: $id) }""")
            .execute()
            .path("revealTeamConnectionSecret").entity(String::class.java).isEqualTo("xoxb-token")

        val entry = audit.findAll().single { it.message.startsWith("Credentials for") }
        assertThat(entry.message).isEqualTo("Credentials for Slack revealed")
        assertThat(entry.teamId).isEqualTo(teamId)
        assertThat(entry.userId).isEqualTo("alice")
    }

    @Test
    fun `a check reports what the service answered`() {
        val id = createTeamConnection("Slack", url = "https://gyloli-test.invalid/hook")
        graphQlTester.document(
            """mutation { updateTeamConnection(id: $id, input: { secret: "x" }) { secretSet } }""",
        ).execute()

        // .invalid never resolves, so the probe fails without leaving the machine.
        graphQlTester.document("""mutation { testTeamConnection(id: $id) { status lastCheckMessage } }""")
            .execute()
            .path("testTeamConnection.status").entity(String::class.java).isEqualTo("FAILED")
            .path("testTeamConnection.lastCheckMessage").hasValue()

        assertThat(audit.findAll().map { it.message }).contains("Connection Slack checked: failed")
    }

    @Test
    fun `disconnecting removes a connection the team added itself`() {
        val id = createTeamConnection("Slack")

        graphQlTester.document("""mutation { disconnectTeamConnection(id: $id) }""")
            .execute().path("disconnectTeamConnection").entity(Boolean::class.java).isEqualTo(true)

        assertThat(teamConnections.findAll()).isEmpty()
        assertThat(audit.findAll().map { it.message }).contains("Connection Slack disconnected")
    }

    @Test
    fun `an mcp server is added, and its credentials are never listed`() {
        graphQlTester.document(
            """
            mutation {
              createMcpServer(input: {
                teamId: $teamId, name: "brave-search", address: "https://mcp.brave.com/v1", authType: API_KEY,
                secret: "sk-live", headers: [{ name: "X-Custom", value: "v" }, { name: " ", value: "dropped" }]
              }) { name authType secretSet headers { name } }
            }
            """,
        ).execute()
            .path("createMcpServer.authType").entity(String::class.java).isEqualTo("API_KEY")
            .path("createMcpServer.secretSet").entity(Boolean::class.java).isEqualTo(true)
            // A blank header name never reaches the database.
            .path("createMcpServer.headers").entityList(Map::class.java).hasSize(1)

        assertThat(mcpServers.findAll().single().secret).isEqualTo("sk-live")
        assertThat(audit.findAll().map { it.message }).contains("MCP Server brave-search added")
    }

    @Test
    fun `a resolved target carries the credential as a header`() {
        val id = createTeamConnection("Slack")
        graphQlTester.document(
            """
            mutation {
              updateTeamConnection(id: $id, input: { authType: BEARER_TOKEN, secret: "xoxb-token" }) { secretSet }
            }
            """,
        ).execute()

        // What a node runner asks for when it has something to send.
        val target = targets.connectionTarget(id)
        assertThat(target.headers.map { it.name }).contains("Authorization")
        assertThat(target.headers.single { it.name == "Authorization" }.value).isEqualTo("Bearer xoxb-token")
    }

    @Test
    fun `a team that does not exist is refused`() {
        graphQlTester.document("""query { mcpServers(teamId: 999999) { name } }""")
            .execute()
            .errors()
            .expect { it.message?.contains("No team") == true }
            .verify()
    }

    private fun createDefault(name: String, type: String = "SLACK"): Long = graphQlTester.document(
        """mutation { createConnection(input: { name: "$name", type: $type, url: "https://example.test" }) { id } }""",
    ).execute().path("createConnection.id").entity(Long::class.java).get()

    private fun createTeamConnection(name: String, url: String = "https://example.test"): Long =
        graphQlTester.document(
            """
            mutation {
              createTeamConnection(input: { teamId: $teamId, name: "$name", type: SLACK, url: "$url" }) { id }
            }
            """,
        ).execute().path("createTeamConnection.id").entity(Long::class.java).get()
}
