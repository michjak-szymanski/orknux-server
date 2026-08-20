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
 * What happens to the rows when a type nobody implemented stops existing.
 *
 * V160 removes TEAMS. The interface never offered it, but the API took it, so a
 * row could exist - and a CHECK narrowed against a table that still holds the
 * value it is narrowing away does not fail quietly, it fails on startup. V160
 * therefore moves the rows to WEBHOOK first, which is where a TEAMS connection
 * already effectively lived: nothing in the connector ever branched on the type,
 * so it was always just an HTTP endpoint with a URL, a credential and headers.
 *
 * This replays the real Postgres history into a schema of its own - up to the
 * migration before V160, then a TEAMS connection with a credential and a header
 * on it, then V160 - and asserts the connection comes out the other side intact
 * rather than merely renamed.
 */
class TeamsTypeMigrationTest {

    @Test
    fun `a Teams connection comes through V160 as a Webhook connection with everything on it`() {
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
                        VALUES ('Teams default', 'TEAMS', 'https://example.invalid/teams')
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO workspace_connection (workspace_id, name, type, url, url_override, auth_type, secret)
                        VALUES (1, 'Teams', 'TEAMS', 'https://example.invalid/teams',
                                'https://example.invalid/override', 'API_KEY', 'key-was-here')
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO workspace_connection_header (workspace_connection_id, position, name, value)
                        SELECT id, 0, 'X-Was-Here', 'yes' FROM workspace_connection WHERE name = 'Teams'
                        """.trimIndent(),
                    )
                }
            }

            flyway(url, username, password, target = LATEST).migrate()

            connect(url, username, password).use { db ->
                val migrated = read(db)
                assertThat(migrated.type).describedAs("the row moved to the surviving type").isEqualTo("WEBHOOK")

                // The point of the whole test: the conversion changes the name of
                // the type and nothing else. The secret is stored encrypted, so a
                // migration that rewrote it could not be undone afterwards.
                assertThat(migrated.secret).isEqualTo("key-was-here")
                assertThat(migrated.url).isEqualTo("https://example.invalid/teams")
                assertThat(migrated.urlOverride).isEqualTo("https://example.invalid/override")
                assertThat(migrated.authType).isEqualTo("API_KEY")

                // Headers hang off the row by id, so they survive by the row
                // surviving - which is the argument for converting rather than
                // deleting, stated as an assertion.
                assertThat(single(db, "SELECT name FROM workspace_connection_header WHERE value = 'yes'"))
                    .isEqualTo("X-Was-Here")

                // And it is a connection the application still considers usable.
                val connection = WorkspaceConnection(
                    id = 1,
                    workspaceId = 1,
                    name = "Teams",
                    type = ConnectionType.valueOf(migrated.type),
                    url = migrated.url,
                    secret = migrated.secret,
                )
                assertThat(connection.configured).isTrue()

                // The admin default moved with it, so nothing inheriting it was
                // orphaned by the type going away.
                assertThat(single(db, "SELECT type FROM connection WHERE name = 'Teams default'"))
                    .isEqualTo("WEBHOOK")

                // And the value cannot come back in through the front door.
                assertThatThrownBy {
                    insert(
                        db,
                        "INSERT INTO connection (name, type, url) " +
                            "VALUES ('Late', 'TEAMS', 'https://example.invalid/teams')",
                    )
                }.hasMessageContaining("ck_connection_type")

                assertThatThrownBy {
                    insert(
                        db,
                        "INSERT INTO workspace_connection (workspace_id, name, type, url) " +
                            "VALUES (1, 'Late', 'TEAMS', 'https://example.invalid/teams')",
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
            "SELECT type, url, url_override, auth_type, secret FROM workspace_connection WHERE name = 'Teams'",
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
        val urlOverride: String?,
        val authType: String,
        val secret: String?,
    )

    private companion object {
        const val SCHEMA = "teams_type_migration"

        /**
         * Everything below V160. The `?` is Flyway's "or the highest there is
         * under it": nothing is numbered 159 and nothing needs to be, and a
         * migration added between now and then is included without editing this.
         */
        const val BEFORE_THE_REMOVAL = "159?"
        const val LATEST = "latest"
    }
}
