package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.connector.connection.McpServer
import io.mszymanski.orknux.connector.connection.McpServerRepository
import io.mszymanski.orknux.connector.security.SecretCipher
import io.mszymanski.orknux.connector.security.SecretMigration
import io.mszymanski.orknux.server.variable.VariableCatalog
import io.mszymanski.orknux.server.variable.VariableCatalogRepository
import io.mszymanski.orknux.server.variable.WorkspaceVariable
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

/**
 * The one thing that runs on every boot and has never had a test.
 *
 * [SecretMigration] rewrites credentials that predate encryption. It is an
 * `ApplicationReadyEvent` listener, so whatever it throws is thrown at the
 * moment the application says it is up — and Spring Boot answers a listener on
 * that event by closing the context and failing the run. There is no screen for
 * that and no log line a person would go looking for: the container simply does
 * not come up.
 *
 * Both tests below put plaintext into a credential column with raw SQL rather
 * than through the entity. That is not a shortcut around the converter, it is
 * the case being tested: a row written before encryption existed is exactly a
 * row the converter never touched, and it is the only kind of row this class is
 * for.
 */
@SpringBootTest
class SecretMigrationTest(
    @Autowired val jdbc: JdbcTemplate,
    @Autowired val cipher: SecretCipher,
    @Autowired val mcpServers: McpServerRepository,
    @Autowired val variables: WorkspaceVariableRepository,
    @Autowired val catalogs: VariableCatalogRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private var catalogId: Long = 0

    @BeforeEach
    fun seed() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "secret-migration")).id)
        catalogId = requireNotNull(
            catalogs.save(VariableCatalog(workspaceId = workspaceId, name = "credentials")).id,
        )
    }

    /**
     * Nothing may be left behind: a plaintext credential in one of these columns
     * is a landmine for the next context this suite builds, which runs this very
     * migration as it starts.
     */
    @AfterEach
    fun clear() {
        variables.findAll().filter { it.workspaceId == workspaceId }.forEach(variables::delete)
        catalogs.findByWorkspaceIdOrderByNameAsc(workspaceId).forEach(catalogs::delete)
        mcpServers.findAll().filter { it.workspaceId == workspaceId }.forEach(mcpServers::delete)
        workspaces.findById(workspaceId).ifPresent(workspaces::delete)
    }

    /**
     * The installation that upgrades without setting the key.
     *
     * `SecretCipher.decrypt` was deliberately written never to throw, and the
     * doctor's "Secret key" card exists to say `Missing` in words — both of
     * which are promises that a server with no key still starts and still tells
     * somebody what is wrong with it. This is the one place that breaks them.
     * `encrypt` reaches a `by lazy` that calls `check`, so the first plaintext
     * row throws `IllegalStateException` out of an `ApplicationReadyEvent`
     * listener, and Spring Boot turns that into a failed start.
     *
     * The person upgrading sees a container that exits. The page built to tell
     * them their key is missing is on the server that will not start.
     *
     * So: it may write nothing, and it must not throw. What it may not do is
     * take the installation with it.
     *
     * Disabled because what it should do *instead* is yours to choose, and every
     * option is something an operator reads:
     *
     *  - Ask `cipher.keyStatus()` first and return without touching anything
     *    when the key is not usable, logging at WARN that credentials are still
     *    in plain text and why. The doctor then says the same thing on a server
     *    that is running, which is what that page is for.
     *  - Or contain per row and per column, so one credential that cannot be
     *    written costs only itself. Wanted anyway, and independently of this.
     *  - Or keep the hard stop but move it somewhere it reads as one — a startup
     *    check that says "this database holds plaintext credentials and no key is
     *    configured" rather than a stack trace out of a lazy delegate.
     *
     * The test itself was run and fails against the code as it stands.
     */
    @Disabled("Decision needed: skip when the key is unusable, or contain per row — and what to log")
    @Test
    fun `an installation with no key still starts, and keeps its credentials`() {
        val id = plaintextMcpSecret("a token written before any of this was encrypted")
        val keyless = SecretMigration(jdbc, SecretCipher(""))

        assertThatCode { keyless.encryptStoredSecrets() }
            .describedAs("a missing key is a thing to report, not a reason the server cannot start")
            .doesNotThrowAnyException()

        // And it left the credential alone, so the boot that does have the key
        // still finds it to seal. Losing it would be worse than not sealing it.
        assertThat(storedMcpSecret(id))
            .describedAs("the credential is still there to be encrypted by a later boot")
            .isEqualTo("a token written before any of this was encrypted")
    }

    /**
     * The columns the migration does not know about.
     *
     * `COLUMNS` lists four. Eight fields carry `@Convert(SecretConverter)`: the
     * four listed, plus `shell.private_key`, `shell.key_passphrase`,
     * `proxy_rule.password` and `workspace_variable.value` — every one of them
     * added after this list was written, and the list's own comment says "a new
     * one belongs here as well as on its entity".
     *
     * What makes it worth a test rather than a note is who is not told. The
     * doctor's "Stored secrets" card counts values that are *in* an envelope, so
     * a column full of plaintext is not unreadable, it is invisible: the card
     * says "All N values readable with the configured key" and the operator
     * reads that as "my credentials are encrypted". An SSH private key sitting
     * in plain text in the database is the thing they were told they did not
     * have.
     *
     * Disabled because adding four columns to `COLUMNS` rewrites real data on a
     * real upgrade, which is not a change to make on an auditor's judgement. Two
     * things worth deciding with it: whether `workspace_variable.value` should be
     * swept whole (the converter already encrypts every variable's value, secret
     * or not, so the column is uniform — but the SELECT would touch every row a
     * workspace has), and whether the doctor should count plaintext in these
     * columns as a finding, since today it cannot see them at all.
     *
     * The test itself was run and fails against the code as it stands.
     */
    @Disabled("Decision needed: which columns to add to COLUMNS, and whether the doctor should report plaintext")
    @Test
    fun `every column that stores a credential is one the migration rewrites`() {
        val id = plaintextVariable("-----BEGIN OPENSSH PRIVATE KEY-----")

        SecretMigration(jdbc, cipher).encryptStoredSecrets()

        assertThat(cipher.isEncrypted(storedVariableValue(id)))
            .describedAs("workspace_variable.value carries @Convert(SecretConverter) and is not in COLUMNS")
            .isTrue()
    }

    /** A credential row as an upgrade finds it: written straight to the column. */
    private fun plaintextMcpSecret(plaintext: String): Long {
        val id = requireNotNull(
            mcpServers.save(
                McpServer(workspaceId = workspaceId, name = "Legacy", address = "https://mcp.example.com"),
            ).id,
        )
        jdbc.update("update mcp_server set secret = ? where id = ?", plaintext, id)
        return id
    }

    private fun plaintextVariable(plaintext: String): Long {
        val id = requireNotNull(
            variables.save(
                WorkspaceVariable(workspaceId = workspaceId, catalogId = catalogId, name = "deployKey"),
            ).id,
        )
        jdbc.update("update workspace_variable set value = ? where id = ?", plaintext, id)
        return id
    }

    /** Read as the column holds it: through the entity it would come back decrypted. */
    private fun storedMcpSecret(id: Long): String? =
        jdbc.queryForObject("select secret from mcp_server where id = ?", String::class.java, id)

    private fun storedVariableValue(id: Long): String? =
        jdbc.queryForObject("select value from workspace_variable where id = ?", String::class.java, id)
}
