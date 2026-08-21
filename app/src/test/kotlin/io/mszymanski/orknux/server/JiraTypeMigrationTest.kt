package io.mszymanski.orknux.server

import io.mszymanski.orknux.connector.connection.ConnectionType
import io.mszymanski.orknux.connector.connection.WorkspaceConnection
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * What happens to the rows when two more types nobody implemented stop existing.
 *
 * V183 removes JIRA and GITHUB. Neither was ever implemented - the connector
 * branches on SMTP, which is a mail server, and on SLACK, which has its own
 * probe, and everything else falls through the `else` as a plain HTTP endpoint -
 * but unlike TEAMS, which V160 removed for the same reason, these two were
 * offered: the Add Connection form listed them and the settings page wrote a
 * credential hint for each. So there are rows out there, entered on purpose.
 *
 * V183 therefore moves them to WEBHOOK, where they already effectively lived,
 * and V184 renames WEBHOOK to HTTP - so a row that goes in as JIRA comes out as
 * HTTP with everything still hanging off it.
 *
 * This replays the real Postgres history into a schema of its own - up to the
 * migration before V183, then a Jira connection and a GitHub connection with
 * credentials and a header, then the rest of the history - and asserts both come
 * out the other side intact rather than merely renamed.
 */
class JiraTypeMigrationTest {

    @Test
    fun `a Jira connection and a GitHub connection come through the removal with everything on them`() {
        val url = System.getProperty("spring.datasource.url").orEmpty()
        // The SQLite run has no numbered history to replay; its half of this
        // change is the baseline, which SqliteSchemaTest applies.
        assumeTrue(url.startsWith("jdbc:postgresql"), "Postgres only: the migration history is Postgres history")

        val username = System.getProperty("spring.datasource.username")
        val password = System.getProperty("spring.datasource.password")

        try {
            flyway(url, username, password, target = BEFORE_THE_REMOVAL).migrate()

            connect(url, username, password).use { db ->
                db.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO connection (name, type, url)
                        VALUES ('Jira default', 'JIRA', 'https://example.invalid/jira')
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO workspace_connection (workspace_id, name, type, url, url_override, auth_type, secret)
                        VALUES (1, 'Jira', 'JIRA', 'https://example.invalid/jira',
                                'https://example.invalid/override', 'BASIC', 'token-was-here')
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO workspace_connection (workspace_id, name, type, url, auth_type, secret)
                        VALUES (1, 'GitHub', 'GITHUB', 'https://example.invalid/github',
                                'BEARER_TOKEN', 'pat-was-here')
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO workspace_connection_header (workspace_connection_id, position, name, value)
                        SELECT id, 0, 'X-Was-Here', 'yes' FROM workspace_connection WHERE name = 'Jira'
                        """.trimIndent(),
                    )
                }
            }

            flyway(url, username, password, target = LATEST).migrate()

            connect(url, username, password).use { db ->
                val jira = read(db, "Jira")
                assertThat(jira.type).describedAs("the row moved to the surviving type").isEqualTo("HTTP")

                // The point of the whole test: the conversion changes the name of
                // the type and nothing else. The secret is stored encrypted, so a
                // migration that rewrote it could not be undone afterwards.
                assertThat(jira.secret).isEqualTo("token-was-here")
                assertThat(jira.url).isEqualTo("https://example.invalid/jira")
                assertThat(jira.urlOverride).isEqualTo("https://example.invalid/override")
                assertThat(jira.authType).isEqualTo("BASIC")

                val github = read(db, "GitHub")
                assertThat(github.type).isEqualTo("HTTP")
                assertThat(github.secret).isEqualTo("pat-was-here")
                assertThat(github.url).isEqualTo("https://example.invalid/github")
                assertThat(github.authType).isEqualTo("BEARER_TOKEN")

                // Headers hang off the row by id, so they survive by the row
                // surviving - which is the argument for converting rather than
                // deleting, stated as an assertion.
                assertThat(single(db, "SELECT name FROM workspace_connection_header WHERE value = 'yes'"))
                    .isEqualTo("X-Was-Here")

                // And it is a connection the application still considers usable.
                val connection = WorkspaceConnection(
                    id = 1,
                    workspaceId = 1,
                    name = "Jira",
                    type = ConnectionType.valueOf(jira.type),
                    url = jira.url,
                    secret = jira.secret,
                )
                assertThat(connection.configured).isTrue()

                // The admin default moved with it, so nothing inheriting it was
                // orphaned by the type going away.
                assertThat(single(db, "SELECT type FROM connection WHERE name = 'Jira default'"))
                    .isEqualTo("HTTP")

                // And the values cannot come back in through the front door.
                assertThatThrownBy {
                    insert(
                        db,
                        "INSERT INTO connection (name, type, url) " +
                            "VALUES ('Late', 'JIRA', 'https://example.invalid/jira')",
                    )
                }.hasMessageContaining("ck_connection_type")

                assertThatThrownBy {
                    insert(
                        db,
                        "INSERT INTO workspace_connection (workspace_id, name, type, url) " +
                            "VALUES (1, 'Late', 'GITHUB', 'https://example.invalid/github')",
                    )
                }.hasMessageContaining("ck_workspace_connection_type")
            }
        } finally {
            connect(url, username, password, search = false).use { db ->
                db.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $SCHEMA CASCADE") }
            }
        }
    }

    private fun flyway(url: String, username: String?, password: String?, target: String) = Flyway.configure()
        .dataSource(url, username, password)
        .locations("classpath:db/migration/postgresql")
        // A schema of its own, so replaying the history costs the suite a few
        // seconds rather than a second container, and leaves the schema every
        // other test shares exactly as it found it.
        .schemas(SCHEMA)
        .defaultSchema(SCHEMA)
        .createSchemas(true)
        .target(target)
        .load()

    private fun connect(url: String, username: String?, password: String?, search: Boolean = true): Connection =
        DriverManager.getConnection(url, username, password).apply {
            if (search) createStatement().use { it.execute("SET search_path TO $SCHEMA") }
        }

    private fun read(db: Connection, name: String): Row = db.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT type, url, url_override, auth_type, secret FROM workspace_connection WHERE name = '$name'",
        ).use {
            assertThat(it.next()).describedAs("$name survived the migration at all").isTrue()
            Row(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5))
        }
    }

    private fun single(db: Connection, sql: String): String? = db.createStatement().use { statement ->
        statement.executeQuery(sql).use {
            assertThat(it.next()).describedAs(sql).isTrue()
            it.getString(1)
        }
    }

    private fun insert(db: Connection, sql: String) = db.createStatement().use { it.execute(sql) }

    private data class Row(
        val type: String,
        val url: String,
        val urlOverride: String?,
        val authType: String,
        val secret: String?,
    )

    private companion object {
        const val SCHEMA = "jira_type_migration"

        /**
         * Everything below V183. The `?` is Flyway's "or the highest there is
         * under it", so a migration added between now and then is included
         * without editing this.
         */
        const val BEFORE_THE_REMOVAL = "182?"
        const val LATEST = "latest"
    }
}
