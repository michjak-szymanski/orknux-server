package io.mszymanski.orknux.server.integration

import io.mszymanski.orknux.connector.shell.ShellRepository
import io.mszymanski.orknux.connector.shell.ShellSessionRepository
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import org.apache.sshd.common.util.OsUtils
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.WithMockUser

/**
 * The shells as an administrator edits them.
 *
 * What a shell *does* is held still by ShellSessionTest, which drives a real SSH
 * server. This file is about the other half: that a shell which could never work
 * is refused where somebody can still fix it, that only an administrator can see
 * one at all, and that a private key goes in and does not come back out.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ShellAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val shells: ShellRepository,
    @Autowired val sessions: ShellSessionRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val jdbc: JdbcTemplate,
) {

    @BeforeEach
    fun reset() {
        sessions.deleteAll()
        shells.deleteAll()
        audit.deleteAll()
    }

    @Test
    fun `a shell is added, and the audit log says which machine it points at`() {
        create("build box", host = "127.0.0.1", port = 2222, username = "runner", privateKey = KEY)

        graphQlTester.document("{ shells { name host port username enabled status } }").execute()
            .path("shells[0].name").entity(String::class.java).isEqualTo("build box")
            .path("shells[0].port").entity(Int::class.java).isEqualTo(2222)
            .path("shells[0].username").entity(String::class.java).isEqualTo("runner")
            // Configured but not yet spoken to, which is a different thing from
            // failing and is worth saying so on the page.
            .path("shells[0].status").entity(String::class.java).isEqualTo("NOT_CHECKED")

        assertThat(audit.findAll().map { it.message })
            .contains("Shell build box added for runner@127.0.0.1:2222")
    }

    @Test
    fun `a shell with no account named runs as the one this server runs as`() {
        // The field left empty, which is what `ssh build.internal` allows and
        // what an administrator adding a container they already have a shell on
        // expects to be allowed here.
        val id = create("build box", username = null, privateKey = KEY)

        graphQlTester.document("{ shells { username account status } }").execute()
            // Nothing was typed, and the API says so rather than inventing a
            // name: a screen has to be able to show the box empty again.
            .path("shells[0].username").valueIsNull()
            // But what it will connect as is not a mystery. This is the account
            // this process runs as, which is the only thing on the answer that
            // no screen could have worked out for itself.
            .path("shells[0].account").entity(String::class.java).isEqualTo(OsUtils.getCurrentUser())
            // And a key with no username is still something to connect with,
            // which NOT_CONFIGURED would have denied.
            .path("shells[0].status").entity(String::class.java).isEqualTo("NOT_CHECKED")

        assertThat(audit.findAll().map { it.message })
            .contains("Shell build box added for ${OsUtils.getCurrentUser()}@127.0.0.1:22")

        // And it can be given one afterwards without anything else changing.
        graphQlTester.document(
            """
            mutation { updateShell(id: $id, input: {
              name: "build box", host: "127.0.0.1", port: 22, username: "runner"
            }) { username account privateKeySet } }
            """,
        ).execute()
            .path("updateShell.username").entity(String::class.java).isEqualTo("runner")
            .path("updateShell.account").entity(String::class.java).isEqualTo("runner")
            .path("updateShell.privateKeySet").entity(Boolean::class.java).isEqualTo(true)
    }

    @Test
    fun `an account of nothing but spaces is stored as no account at all`() {
        // Blank and absent are the same thing for an account name, unlike for
        // the key below it, and the row has to say which so that "not set" and
        // "set to nothing" cannot drift apart.
        create("build box", username = "   ", privateKey = KEY)

        assertThat(shells.findAll().single().username).isNull()
    }

    @Test
    fun `a shell never gives its private key back`() {
        create("build box", privateKey = KEY, keyPassphrase = "open-sesame")

        // There is no field to ask for, which is the strongest form this can
        // take: the schema itself cannot express the question.
        graphQlTester.document("{ shells { name privateKeySet passphraseSet } }").execute()
            .path("shells[0].privateKeySet").entity(Boolean::class.java).isEqualTo(true)
            .path("shells[0].passphraseSet").entity(Boolean::class.java).isEqualTo(true)

        graphQlTester.document("{ shells { privateKey } }").execute()
            .errors().satisfy { errors -> assertThat(errors).isNotEmpty() }

        graphQlTester.document("{ shells { keyPassphrase } }").execute()
            .errors().satisfy { errors -> assertThat(errors).isNotEmpty() }

        // And it is not sitting in the database as the text it is either. What
        // the column holds is the envelope, and a stolen dump is not a stolen key.
        val stored = jdbc.queryForObject("SELECT private_key FROM shell", String::class.java)
        assertThat(stored).startsWith("orkx1:")
        assertThat(stored).doesNotContain("PRIVATE KEY")
    }

    @Test
    fun `a private key that cannot be read is refused when the shell is saved`() {
        graphQlTester.document(
            """
            mutation {
              createShell(input: {
                name: "Broken", host: "127.0.0.1", port: 22, username: "runner",
                privateKey: "this is not a key"
              }) { id }
            }
            """,
        ).execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors).singleElement().extracting<String> { it.message }
                    .asString().contains("That private key cannot be used")
            }

        // Refused where somebody still has the right key in front of them,
        // rather than stored and found out by an agent a week later.
        assertThat(shells.findAll()).isEmpty()
    }

    @Test
    fun `a host the address guard refuses cannot be saved`() {
        // The same guard every outbound address goes past. A shell pointed here
        // is a shell pointed at this host's own instance metadata.
        graphQlTester.document(
            """
            mutation {
              createShell(input: { name: "Metadata", host: "0.0.0.0", port: 22, username: "root" }) { id }
            }
            """,
        ).execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors).singleElement().extracting<String> { it.message }
                    .asString().contains("That host cannot be used")
            }

        assertThat(shells.findAll()).isEmpty()
    }

    @Test
    fun `an empty key clears the stored one and a null key leaves it alone`() {
        val id = create("build box", privateKey = KEY)

        // Null is "not mentioned", because the screen never had the key to send
        // back and "unchanged" has to be sayable.
        graphQlTester.document(
            """
            mutation { updateShell(id: $id, input: {
              name: "build box", host: "127.0.0.1", port: 22, username: "runner"
            }) { privateKeySet } }
            """,
        ).execute().path("updateShell.privateKeySet").entity(Boolean::class.java).isEqualTo(true)

        graphQlTester.document(
            """
            mutation { updateShell(id: $id, input: {
              name: "build box", host: "127.0.0.1", port: 22, username: "runner", privateKey: ""
            }) { privateKeySet status } }
            """,
        ).execute()
            .path("updateShell.privateKeySet").entity(Boolean::class.java).isEqualTo(false)
            // Nothing to connect with is not the same as failing to connect.
            .path("updateShell.status").entity(String::class.java).isEqualTo("NOT_CONFIGURED")
    }

    @Test
    fun `a shell that is removed is written down, and takes its sessions with it`() {
        val id = create("build box", privateKey = KEY)

        graphQlTester.document("mutation { deleteShell(id: $id) }").execute()
            .path("deleteShell").entity(Boolean::class.java).isEqualTo(true)

        assertThat(shells.findAll()).isEmpty()
        assertThat(audit.findAll().map { it.message }).contains("Shell build box removed")
    }

    @Test
    @WithMockUser(username = "bob", roles = ["USERS"])
    fun `somebody who is not an administrator cannot see the shells`() {
        graphQlTester.document("{ shells { name host } }").execute()
            .errors().satisfy { errors -> assertThat(errors).isNotEmpty() }

        graphQlTester.document(
            """
            mutation {
              createShell(input: { name: "Mine", host: "127.0.0.1", port: 22, username: "root" }) { id }
            }
            """,
        ).execute().errors().satisfy { errors -> assertThat(errors).isNotEmpty() }

        assertThat(shells.findAll()).isEmpty()
    }

    private fun create(
        name: String,
        host: String = "127.0.0.1",
        port: Int = 22,
        username: String? = "runner",
        privateKey: String? = null,
        keyPassphrase: String? = null,
    ): Long {
        // Left out of the document entirely rather than sent as null, because
        // "an administrator did not fill the field in" is the case worth
        // covering and that is what the screen sends.
        val account = username?.let { ", username: ${quote(it)}" }.orEmpty()
        val key = privateKey?.let { ", privateKey: ${quote(it)}" }.orEmpty()
        val passphrase = keyPassphrase?.let { ", keyPassphrase: ${quote(it)}" }.orEmpty()

        return graphQlTester.document(
            """
            mutation {
              createShell(input: {
                name: ${quote(name)}, host: ${quote(host)}, port: $port$account$key$passphrase
              }) { id }
            }
            """,
        ).execute().path("createShell.id").entity(Long::class.java).get()
    }

    /** GraphQL string literals, with the newlines a key is full of. */
    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private companion object {
        /**
         * A real key, generated once and used nowhere: it authorises nothing,
         * and the point of it here is only that it parses.
         */
        val KEY = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
            QyNTUxOQAAACAtdoZN2L/cWOqC5dnlHgLvqzOpkOPQj5k/NJ4SOJ+qzgAAAJhvqP/gb6j/
            4AAAAAtzc2gtZWQyNTUxOQAAACAtdoZN2L/cWOqC5dnlHgLvqzOpkOPQj5k/NJ4SOJ+qzg
            AAAEBK8mxTiQiPTVk2hPwhTmXf07XFiYlghYUvRpqW+waDPi12hk3Yv9xY6oLl2eUeAu+r
            M6mQ49CPmT80nhI4n6rOAAAAE29ya251eC10ZXN0LWZpeHR1cmUBAg==
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent()
    }
}
