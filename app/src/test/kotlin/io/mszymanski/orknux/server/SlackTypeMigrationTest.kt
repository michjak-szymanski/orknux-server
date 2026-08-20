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
 * What happens to the rows when an enum value stops existing.
 *
 * V150 collapses SLACK_SOCKET_MODE into SLACK, and the interesting half of that
 * is not the code: it is the connection somebody set up months ago, with two
 * tokens in it, that has to keep listening afterwards. A migration that narrows
 * a CHECK and forgets the rows fails loudly; one that moves the rows and drops a
 * credential on the way fails silently, months later, when the websocket does
 * not come back. This is what watches the second kind.
 *
 * It replays the real Postgres history into a schema of its own inside the
 * suite's container - up to the migration before V150, then a Socket Mode row,
 * then V150 - so what it exercises is the migration as it will run on the
 * installation rather than a re-statement of it.
 */
class SlackTypeMigrationTest {

    @Test
    fun `a Socket Mode connection comes through V150 as a Slack connection that still listens`() {
        val url = System.getProperty("spring.datasource.url").orEmpty()
        // The SQLite run has no numbered history to replay; its half of this
        // change is the baseline, which SqliteSchemaTest applies.
        assumeTrue(url.startsWith("jdbc:postgresql"), "Postgres only: the migration history is Postgres'")

        val username = System.getProperty("spring.datasource.username")
        val password = System.getProperty("spring.datasource.password")

        try {
            flyway(url, username, password, target = BEFORE_THE_COLLAPSE).migrate()

            connect(url, username, password).use { db ->
                db.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO connection (name, type, url)
                        VALUES ('Slack default', 'SLACK_SOCKET_MODE', 'https://slack.com/api')
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO workspace_connection (workspace_id, name, type, url, auth_type, secret, app_token)
                        VALUES (1, 'Slack', 'SLACK_SOCKET_MODE', 'https://slack.com/api', 'BEARER_TOKEN',
                                'xoxb-was-here', 'xapp-1-was-here')
                        """.trimIndent(),
                    )
                }
            }

            flyway(url, username, password, target = LATEST).migrate()

            connect(url, username, password).use { db ->
                val migrated = read(db)
                assertThat(migrated.type).describedAs("the row moved to the surviving type").isEqualTo("SLACK")

                // The point of the whole test: both credentials are still there,
                // character for character. They are stored encrypted, so a
                // migration that rewrote either could not be undone afterwards.
                assertThat(migrated.secret).isEqualTo("xoxb-was-here")
                assertThat(migrated.appToken).isEqualTo("xapp-1-was-here")

                // And it is a Slack connection as the collapse defines one.
                assertThat(migrated.url).isEqualTo("https://slack.com/api")
                assertThat(migrated.authType).isEqualTo("BEARER_TOKEN")

                // Which is what the settings screen asks before it stops calling
                // a connection unconfigured, and what SlackListener asks before
                // it opens a socket for one.
                val connection = WorkspaceConnection(
                    id = 1,
                    workspaceId = 1,
                    name = "Slack",
                    type = ConnectionType.valueOf(migrated.type),
                    url = migrated.url,
                    secret = migrated.secret,
                    appToken = migrated.appToken,
                )
                assertThat(connection.configured).isTrue()
                assertThat(connection.appToken.isNullOrBlank()).describedAs("it would still listen").isFalse()

                // The admin default moved with it.
                assertThat(single(db, "SELECT type FROM connection WHERE name = 'Slack default'")).isEqualTo("SLACK")

                // And the value cannot come back in through the front door.
                assertThatThrownBy {
                    insert(
                        db,
                        "INSERT INTO connection (name, type, url) " +
                            "VALUES ('Late', 'SLACK_SOCKET_MODE', 'https://slack.com/api')",
                    )
                }.hasMessageContaining("ck_connection_type")

                assertThatThrownBy {
                    insert(
                        db,
                        "INSERT INTO workspace_connection (workspace_id, name, type, url) " +
                            "VALUES (1, 'Late', 'SLACK_SOCKET_MODE', 'https://slack.com/api')",
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

    private fun read(db: Connection): Row = db.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT type, url, auth_type, secret, app_token FROM workspace_connection WHERE name = 'Slack'",
        ).use {
            assertThat(it.next()).describedAs("the connection survived the migration at all").isTrue()
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
        val authType: String,
        val secret: String?,
        val appToken: String?,
    )

    private companion object {
        const val SCHEMA = "slack_type_migration"

        /**
         * Everything below V150. The `?` is Flyway's "or the highest there is
         * under it": nothing is numbered 149 and nothing needs to be, and a
         * migration added between now and then is included without editing this.
         */
        const val BEFORE_THE_COLLAPSE = "149?"
        const val LATEST = "latest"
    }
}
