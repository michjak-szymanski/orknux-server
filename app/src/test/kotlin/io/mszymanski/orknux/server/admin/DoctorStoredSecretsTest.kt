package io.mszymanski.orknux.server.admin

import io.mszymanski.orknux.connector.security.SecretCipher
import io.mszymanski.orknux.server.variable.VariableCatalog
import io.mszymanski.orknux.server.variable.VariableCatalogRepository
import io.mszymanski.orknux.server.variable.VariableKind
import io.mszymanski.orknux.server.variable.VariableType
import io.mszymanski.orknux.server.variable.WorkspaceVariable
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.WithMockUser
import java.util.Base64

/**
 * The doctor, asked about a credential written with a key this installation lost.
 *
 * This is the case the check exists for and the one it could not see. It used to
 * decide a value was unreadable by catching around `SecretCipher.decrypt` — and
 * `decrypt` swallows its own failure on purpose, so nothing was ever caught and
 * the card reported every value readable whatever the key was. Every test anybody
 * would naturally write passed, because they would all encrypt with the key the
 * server is holding.
 *
 * So the value here is encrypted with a different one, by a second cipher built
 * for the purpose. It goes into the column verbatim — `encrypt` leaves a value
 * that is already in an envelope alone, which is what lets a credential nobody
 * can read survive being written back — and what the doctor then finds is a real
 * value from a real column, not a string shaped like one.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DoctorStoredSecretsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val variables: WorkspaceVariableRepository,
    @Autowired val catalogs: VariableCatalogRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val jdbc: JdbcTemplate,
) {

    private var workspaceId: Long = 0
    private var catalogId: Long = 0

    /**
     * Its own workspace, removed again afterwards.
     *
     * The check reads every text column in the database, so anything this test
     * leaves behind is something the next one's doctor has to explain.
     */
    @BeforeEach
    fun seed() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "issue-133")).id)
        catalogId = requireNotNull(
            catalogs.save(VariableCatalog(workspaceId = workspaceId, name = "credentials")).id,
        )
    }

    @AfterEach
    fun clear() {
        variables.findByWorkspaceId(workspaceId).forEach(variables::delete)
        catalogs.findByWorkspaceIdOrderByNameAsc(workspaceId).forEach(catalogs::delete)
        workspaces.findById(workspaceId).ifPresent(workspaces::delete)
    }

    @Test
    fun `a value written with another key is reported unreadable, by name and by count`() {
        store(FOREIGN.encrypt("the private key of a shell nobody can open")!!)

        val stored = check("Stored secrets")

        assertThat(stored.verdict).isEqualTo("FAIL")
        // The count is the size of the problem: one credential to enter again is
        // not the same day as every credential this installation holds.
        assertThat(stored.detail).contains("1 of")
        assertThat(stored.detail).contains("cannot be read")
        assertThat(stored.detail).contains("this is not the key they were written with")
        // And where it lives, so the next step is not a database query.
        assertThat(stored.detail).contains("workspace_variable.value")
    }

    /**
     * Two of them, in one column, counted rather than listed twice.
     *
     * The installation this was found on holds a dozen seeded shells whose keys
     * were all written with the same lost key. Naming the column once per value
     * turns one problem into a dozen entries of the same text.
     */
    @Test
    fun `several in one column are named once, with how many`() {
        store(FOREIGN.encrypt("one")!!, name = "lostOne")
        store(FOREIGN.encrypt("two")!!, name = "lostTwo")

        val stored = check("Stored secrets")

        assertThat(stored.verdict).isEqualTo("FAIL")
        assertThat(stored.detail).contains("2 of")
        assertThat(stored.detail).contains("workspace_variable.value (2)")
    }

    /**
     * The other half, and the half that used to be the only one anybody wrote.
     *
     * It has to keep passing: a check that reports FAIL for everything is no more
     * use than one that reports OK for everything.
     */
    @Test
    fun `a value written with the configured key is reported readable`() {
        store("a token this server can open")

        val stored = check("Stored secrets")

        assertThat(stored.verdict).isEqualTo("OK")
        assertThat(stored.detail).contains("readable with the configured key")
    }

    /**
     * The fault the card could not see at all.
     *
     * A value that was never encrypted has no envelope, and the envelope is how
     * [DoctorAPI.encryptedValues] recognises a secret in a column nobody named. So
     * four columns missing from the boot sweep did not read as unreadable, they
     * read as absent — and the card went on saying "All N values readable with the
     * configured key", which an operator reads as "my credentials are encrypted",
     * over an SSH private key stored as the key it is.
     *
     * Plaintext cannot be found by its shape, so this half of the check is told
     * where to look by `SecretColumns` — the `@Convert(SecretConverter)` fields
     * themselves, which is the same thing that decides a value is encrypted at all.
     *
     * Written with raw SQL because that is the case: a row from before the
     * converter existed is exactly a row the converter never touched.
     */
    @Test
    fun `a credential that was never encrypted is reported as stored in the clear`() {
        val id = requireNotNull(
            variables.save(
                WorkspaceVariable(
                    workspaceId = workspaceId,
                    catalogId = catalogId,
                    name = "deployKey",
                    kind = VariableKind.SECRET,
                ),
            ).id,
        )
        jdbc.update("update workspace_variable set value = ? where id = ?", "-----BEGIN OPENSSH PRIVATE KEY-----", id)

        val stored = check("Stored secrets")

        assertThat(stored.verdict)
            .describedAs("a credential anyone with the database can read is a finding, not a footnote")
            .isEqualTo("FAIL")
        assertThat(stored.detail).contains("stored in the clear, not encrypted at all")
        // Named, so the next step is not a database query.
        assertThat(stored.detail).contains("workspace_variable.value")
    }

    /**
     * And the other half stays sayable: nothing in the clear is said in as many
     * words, because "all readable" was the sentence that hid this for a year.
     */
    @Test
    fun `nothing in the clear is said out loud, not left to be inferred`() {
        store("a token this server can open")

        val stored = check("Stored secrets")

        assertThat(stored.verdict).isEqualTo("OK")
        assertThat(stored.detail).contains("none stored in the clear")
    }

    /** Saved through the converter, which leaves an envelope it did not write alone. */
    private fun store(value: String, name: String = "lostToken") {
        variables.save(
            WorkspaceVariable(
                workspaceId = workspaceId,
                catalogId = catalogId,
                name = name,
                type = VariableType.STRING,
                kind = VariableKind.SECRET,
                value = value,
            ),
        )
    }

    private fun check(name: String): Verdict {
        val answer = graphQlTester.document("{ doctor { name verdict detail } }").execute()
        val verdict = answer.path("doctor[?(@.name == '$name')].verdict")
            .entityList(String::class.java).get()
        val detail = answer.path("doctor[?(@.name == '$name')].detail")
            .entityList(String::class.java).get()
        return Verdict(verdict.single(), detail.single())
    }

    private data class Verdict(val verdict: String, val detail: String)

    private companion object {
        /**
         * A key this installation does not have, and never had.
         *
         * Built here rather than configured, because the point is a second key
         * existing at the same time as the real one: the server keeps the key the
         * suite gave it and still cannot read what this wrote.
         */
        val FOREIGN = SecretCipher(
            Base64.getEncoder().encodeToString("orknux-lost-key-32-bytes-exactly".toByteArray()),
        )
    }
}
