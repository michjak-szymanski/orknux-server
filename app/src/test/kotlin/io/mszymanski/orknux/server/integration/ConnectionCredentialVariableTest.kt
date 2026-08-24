package io.mszymanski.orknux.server.integration

import io.mszymanski.orknux.connector.connection.ConnectionCredentials
import io.mszymanski.orknux.connector.connection.ConnectionTargetService
import io.mszymanski.orknux.connector.connection.McpServerRepository
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.server.variable.VariableCatalog
import io.mszymanski.orknux.server.variable.VariableCatalogRepository
import io.mszymanski.orknux.server.variable.VariableKind
import io.mszymanski.orknux.server.variable.VariableType
import io.mszymanski.orknux.server.variable.WorkspaceVariable
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
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
 * Every secret field chooses for itself: its own value, or a workspace secret.
 *
 * #232 gave the choice to a model provider, which has one secret column - so a
 * choice made once for the card and a choice made once for the field were the
 * same thing, and it was impossible to tell which had been built. A Slack
 * connection is where they come apart: two credentials on one card, and the
 * first test here is the one that could not pass under the old arrangement -
 * the bot token read from a variable while the app-level token stays the
 * connection's own.
 *
 * The credential is read through the paths the application reads it by, not off
 * the row, so "it works" here means the variable's value would actually reach
 * the wire.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ConnectionCredentialVariableTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaceConnections: WorkspaceConnectionRepository,
    @Autowired val mcpServers: McpServerRepository,
    @Autowired val credentials: ConnectionCredentials,
    @Autowired val targets: ConnectionTargetService,
    @Autowired val variables: WorkspaceVariableRepository,
    @Autowired val catalogs: VariableCatalogRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var catalogId: Long = 0

    @BeforeEach
    fun reset() {
        workspaceConnections.deleteAll()
        mcpServers.deleteAll()
        variables.deleteAll()
        catalogs.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        catalogId = catalog("Keys")
    }

    /** The whole of #244, in one connection. */
    @Test
    fun `a Slack connection reads one token from a variable and keeps the other itself`() {
        val botToken = secret("SLACK_BOT_TOKEN", "xoxb-from-the-vault")

        val id = graphQlTester.document(
            """mutation { createWorkspaceConnection(input: {
                 workspaceId: $workspaceId, name: "Support Slack", type: SLACK,
                 secretVariableId: $botToken, appToken: "xapp-typed-in"
               }) { id } }""",
        ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()

        val row = requireNotNull(workspaceConnections.findById(id).orElse(null))
        // The bot token is the variable's and the connection holds no copy;
        // the app token is the connection's own and reads no variable.
        assertThat(row.secret).isNull()
        assertThat(row.secretVariableId).isEqualTo(botToken)
        assertThat(row.appToken).isEqualTo("xapp-typed-in")
        assertThat(row.appTokenVariableId).isNull()

        assertThat(credentials.secretOf(row).credential).isEqualTo("xoxb-from-the-vault")
        assertThat(credentials.appTokenOf(row).credential).isEqualTo("xapp-typed-in")
    }

    /** And the other way round, because a field is not a card. */
    @Test
    fun `the app token reads a variable while the bot token stays the connection's own`() {
        val appToken = secret("SLACK_APP_TOKEN", "xapp-from-the-vault")

        val id = graphQlTester.document(
            """mutation { createWorkspaceConnection(input: {
                 workspaceId: $workspaceId, name: "Support Slack", type: SLACK,
                 secret: "xoxb-typed-in", appTokenVariableId: $appToken
               }) { id } }""",
        ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()

        val row = requireNotNull(workspaceConnections.findById(id).orElse(null))
        assertThat(credentials.secretOf(row).credential).isEqualTo("xoxb-typed-in")
        assertThat(credentials.appTokenOf(row).credential).isEqualTo("xapp-from-the-vault")
        assertThat(row.appToken).isNull()
    }

    /** The credential resolved for an outbound call is the variable's value. */
    @Test
    fun `a referenced credential reaches the request that is about to be made`() {
        val key = secret("PAGER_KEY", "tok-from-the-vault")
        val id = graphQlTester.document(
            """mutation { createWorkspaceConnection(input: {
                 workspaceId: $workspaceId, name: "Pager", type: HTTP,
                 url: "https://pager.invalid", authType: BEARER_TOKEN, secretVariableId: $key
               }) { id } }""",
        ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()

        val target = targets.connectionTarget(id)

        assertThat(target.headers.map { "${it.name}: ${it.value}" })
            .contains("Authorization: Bearer tok-from-the-vault")
    }

    @Test
    fun `an MCP server reads its credential from a variable`() {
        val key = secret("BRAVE_KEY", "brave-from-the-vault")
        val id = graphQlTester.document(
            """mutation { createMcpServer(input: {
                 workspaceId: $workspaceId, name: "brave-search", address: "https://brave.invalid",
                 authType: BEARER_TOKEN, secretVariableId: $key
               }) { id } }""",
        ).execute().path("createMcpServer.id").entity(Long::class.java).get()

        assertThat(targets.mcpServerTarget(id).headers.map { it.value })
            .contains("Bearer brave-from-the-vault")
        assertThat(requireNotNull(mcpServers.findById(id).orElse(null)).secret).isNull()
    }

    /**
     * The refusal names what reads it, and names it as the kind of thing it is:
     * "Slack" on its own leaves whoever hit this to go and find out what Slack
     * is in this installation.
     */
    @Test
    fun `a variable a connection reads cannot be deleted`() {
        val botToken = secret("SLACK_BOT_TOKEN", "xoxb-from-the-vault")
        referencing("Support Slack", "secretVariableId: $botToken")

        graphQlTester.document("""mutation { deleteVariable(id: $botToken) }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors.single().message)
                    .contains("\"SLACK_BOT_TOKEN\" is the credential of the connection Support Slack")
            }

        assertThat(variables.findById(botToken)).isPresent()
    }

    /** The app-level token holds it in place just as the bot token does. */
    @Test
    fun `a variable only the app token reads cannot be deleted either`() {
        val appToken = secret("SLACK_APP_TOKEN", "xapp-from-the-vault")
        referencing("Support Slack", """secret: "xoxb-typed-in", appTokenVariableId: $appToken""")

        graphQlTester.document("""mutation { deleteVariable(id: $appToken) }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors.single().message).contains("the connection Support Slack")
            }
    }

    @Test
    fun `a variable an MCP server reads cannot be deleted`() {
        val key = secret("BRAVE_KEY", "brave-from-the-vault")
        graphQlTester.document(
            """mutation { createMcpServer(input: {
                 workspaceId: $workspaceId, name: "brave-search", address: "https://brave.invalid",
                 secretVariableId: $key
               }) { id } }""",
        ).execute().path("createMcpServer.id").hasValue()

        graphQlTester.document("""mutation { deleteVariable(id: $key) }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors.single().message).contains("the MCP server brave-search")
            }
    }

    /**
     * A VALUE is returned with the listing, so turning a bound variable into one
     * would put a bot token on every member's screen.
     */
    @Test
    fun `a variable a connection reads cannot stop being a secret`() {
        val botToken = secret("SLACK_BOT_TOKEN", "xoxb-from-the-vault")
        referencing("Support Slack", "secretVariableId: $botToken")

        graphQlTester.document("""mutation { updateVariable(id: $botToken, input: { kind: VALUE }) { kind } }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors.single().message).contains("has to stay a secret")
            }

        assertThat(requireNotNull(variables.findById(botToken).orElse(null)).kind).isEqualTo(VariableKind.SECRET)
    }

    @Test
    fun `a value cannot be bound as a credential in the first place`() {
        val plain = requireNotNull(
            variables.save(
                WorkspaceVariable(
                    workspaceId = workspaceId,
                    catalogId = catalogId,
                    name = "CHANNEL",
                    type = VariableType.STRING,
                    kind = VariableKind.VALUE,
                    value = "#general",
                ),
            ).id,
        )

        graphQlTester.document(
            """mutation { createWorkspaceConnection(input: {
                 workspaceId: $workspaceId, name: "Support Slack", type: SLACK, secretVariableId: $plain
               }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).contains("is a workspace value rather than a secret")
        }
    }

    @Test
    fun `a token and a variable on the same field are refused rather than resolved`() {
        val botToken = secret("SLACK_BOT_TOKEN", "xoxb-from-the-vault")

        graphQlTester.document(
            """mutation { createWorkspaceConnection(input: {
                 workspaceId: $workspaceId, name: "Support Slack", type: SLACK,
                 secret: "xoxb-typed-in", secretVariableId: $botToken
               }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).contains("not both")
        }
    }

    /** Another workspace's secret is answered as one that does not exist. */
    @Test
    fun `a variable from another workspace cannot be bound`() {
        val elsewhereId = requireNotNull(workspaces.save(Workspace(name = "elsewhere")).id)
        val elsewhereCatalog = requireNotNull(
            catalogs.save(VariableCatalog(workspaceId = elsewhereId, name = "Theirs")).id,
        )
        val theirs = requireNotNull(
            variables.save(
                WorkspaceVariable(
                    workspaceId = elsewhereId,
                    catalogId = elsewhereCatalog,
                    name = "THEIR_TOKEN",
                    type = VariableType.STRING,
                    kind = VariableKind.SECRET,
                    value = "xoxb-not-yours",
                ),
            ).id,
        )

        graphQlTester.document(
            """mutation { createWorkspaceConnection(input: {
                 workspaceId: $workspaceId, name: "Support Slack", type: SLACK, secretVariableId: $theirs
               }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).contains("No workspace variable with id $theirs")
        }
    }

    /**
     * The choice is made by what arrives, and making one unmakes the other - on
     * that field alone. The app token is left untouched throughout, which is the
     * property a card-level switch could not have.
     */
    @Test
    fun `each kind of credential displaces the other, on its own field`() {
        val botToken = secret("SLACK_BOT_TOKEN", "xoxb-from-the-vault")
        val id = referencing("Support Slack", """appToken: "xapp-typed-in", secretVariableId: $botToken""")

        update(id, """secret: "xoxb-typed-in"""")
        val own = requireNotNull(workspaceConnections.findById(id).orElse(null))
        assertThat(own.secret).isEqualTo("xoxb-typed-in")
        assertThat(own.secretVariableId).isNull()
        assertThat(own.appToken).isEqualTo("xapp-typed-in")

        update(id, "secretVariableId: $botToken")
        val referenced = requireNotNull(workspaceConnections.findById(id).orElse(null))
        assertThat(referenced.secret).isNull()
        assertThat(referenced.secretVariableId).isEqualTo(botToken)
        assertThat(referenced.appToken).isEqualTo("xapp-typed-in")

        // And an emptied box clears that field whichever kind it was.
        update(id, """secret: """"")
        val cleared = requireNotNull(workspaceConnections.findById(id).orElse(null))
        assertThat(cleared.secret).isNull()
        assertThat(cleared.secretVariableId).isNull()
        assertThat(cleared.appToken).isEqualTo("xapp-typed-in")
    }

    /**
     * Revealing is recorded against the secret it reads, so the connection is
     * not a second door onto the same value under the wrong name.
     */
    @Test
    fun `a referenced credential is not revealed through the connection`() {
        val botToken = secret("SLACK_BOT_TOKEN", "xoxb-from-the-vault")
        val id = referencing("Support Slack", """appToken: "xapp-typed-in", secretVariableId: $botToken""")

        graphQlTester.document("""mutation { revealWorkspaceConnectionSecret(id: $id) }""")
            .execute().path("revealWorkspaceConnectionSecret").valueIsNull()
        // The field that does keep its own copy still hands it back.
        graphQlTester.document("""mutation { revealWorkspaceConnectionAppToken(id: $id) }""")
            .execute().path("revealWorkspaceConnectionAppToken").entity(String::class.java)
            .isEqualTo("xapp-typed-in")
    }

    /** The value never comes back on the connection, whichever field is asked for. */
    @Test
    fun `nothing on the connection hands the credential out`() {
        val botToken = secret("SLACK_BOT_TOKEN", "xoxb-from-the-vault")
        referencing("Support Slack", "secretVariableId: $botToken")

        val answer = graphQlTester.document(
            """{ workspaceConnections(workspaceId: $workspaceId) {
                 name secretSet secretVariableId secretVariableName secretVariableCatalog secretVariableMissing
                 appTokenSet appTokenVariableId appTokenVariableName appTokenVariableMissing
               } }""",
        ).execute().path("workspaceConnections").entity(Any::class.java).get().toString()

        assertThat(answer).doesNotContain("xoxb-from-the-vault")
        assertThat(answer).contains("SLACK_BOT_TOKEN").contains("Keys")
    }

    /**
     * Nothing should be able to strand a reference, so this strands one the only
     * way left - by writing the row the guard protects - and asserts the card
     * says so rather than reading as a connection nobody configured.
     */
    @Test
    fun `a reference that has come apart is reported as one`() {
        val botToken = secret("SLACK_BOT_TOKEN", "xoxb-from-the-vault")
        val id = referencing("Support Slack", "secretVariableId: $botToken")
        variables.deleteById(botToken)

        graphQlTester.document(
            """{ workspaceConnection(id: $id) { status secretSet secretVariableMissing secretVariableName } }""",
        ).execute()
            .path("workspaceConnection.secretVariableMissing").entity(Boolean::class.java).isEqualTo(true)
            .path("workspaceConnection.secretVariableName").valueIsNull()
            // Configured, because somebody did configure it. What is wrong is
            // the variable, and saying "Not configured" would send them to the
            // one part of the setup that is right.
            .path("workspaceConnection.status").entity(String::class.java).isEqualTo("NOT_CHECKED")
    }

    private fun referencing(name: String, credential: String): Long = graphQlTester.document(
        """mutation { createWorkspaceConnection(input: {
             workspaceId: $workspaceId, name: "$name", type: SLACK, $credential
           }) { id } }""",
    ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()

    private fun update(id: Long, credential: String) {
        graphQlTester.document(
            """mutation { updateWorkspaceConnection(id: $id, input: { $credential }) { id } }""",
        ).execute().path("updateWorkspaceConnection.id").hasValue()
    }

    private fun catalog(name: String): Long =
        requireNotNull(catalogs.save(VariableCatalog(workspaceId = workspaceId, name = name)).id)

    private fun secret(name: String, held: String): Long = requireNotNull(
        variables.save(
            WorkspaceVariable(
                workspaceId = workspaceId,
                catalogId = catalogId,
                name = name,
                type = VariableType.STRING,
                kind = VariableKind.SECRET,
                value = held,
            ),
        ).id,
    )
}
