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
 * What happens to the rows when a type is renamed rather than removed.
 *
 * V184 renames WEBHOOK to HTTP. One word was naming two opposite directions: a
 * webhook *trigger* is a path this installation exposes for somebody else to
 * call, and a webhook *connection* was a URL this installation sends a request
 * to. The trigger keeps the word; the connection gets the name of what it is.
 *
 * Every row moves, and that is the difference from V160 and V183. Those two
 * removed a type and had to find somewhere for its rows to go. This one changes
 * the spelling of a type that is staying, so there is no conversion to argue
 * about - only the question of whether a row survives being respelled, with its
 * URL, its override, its credential and its headers where they were.
 *
 * This replays the real Postgres history into a schema of its own - up to the
 * migration before V184, then a webhook connection with a credential and a
 * header on it, then V184 - and asserts it is the same row afterwards.
 */
class HttpTypeMigrationTest {

    @Test
    fun `a webhook connection is the same row spelled HTTP afterwards`() {
        val url = System.getProperty("spring.datasource.url").orEmpty()
        // The SQLite run has no numbered history to replay; its half of this
        // change is the baseline, which SqliteSchemaTest applies.
        assumeTrue(url.startsWith("jdbc:postgresql"), "Postgres only: the migration history is Postgres history")

        val username = System.getProperty("spring.datasource.username")
        val password = System.getProperty("spring.datasource.password")

        try {
            flyway(url, username, password, target = BEFORE_THE_RENAME).migrate()

            val id = connect(url, username, password).use { db ->
                db.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO connection (name, type, url)
                        VALUES ('Pager default', 'WEBHOOK', 'https://example.invalid/pager')
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO workspace_connection (workspace_id, name, type, url, url_override, auth_type, secret)
                        VALUES (1, 'Pager', 'WEBHOOK', 'https://example.invalid/pager',
                                'https://example.invalid/override', 'API_KEY', 'key-was-here')
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO workspace_connection_header (workspace_connection_id, position, name, value)
                        SELECT id, 0, 'X-Was-Here', 'yes' FROM workspace_connection WHERE name = 'Pager'
                        """.trimIndent(),
                    )
                }
                single(db, "SELECT id FROM workspace_connection WHERE name = 'Pager'")
            }

            flyway(url, username, password, target = LATEST).migrate()

            connect(url, username, password).use { db ->
                val migrated = read(db)
                assertThat(migrated.type).describedAs("the type was respelled").isEqualTo("HTTP")

                // The same row, not a new one written in its place: the id is
                // what workflow actions and inheriting workspaces point at.
                assertThat(single(db, "SELECT id FROM workspace_connection WHERE name = 'Pager'")).isEqualTo(id)

                // The secret is stored encrypted, so a migration that rewrote it
                // could not be undone afterwards.
                assertThat(migrated.secret).isEqualTo("key-was-here")
                assertThat(migrated.url).isEqualTo("https://example.invalid/pager")
                assertThat(migrated.urlOverride).isEqualTo("https://example.invalid/override")
                assertThat(migrated.authType).isEqualTo("API_KEY")

                assertThat(single(db, "SELECT name FROM workspace_connection_header WHERE value = 'yes'"))
                    .isEqualTo("X-Was-Here")

                val connection = WorkspaceConnection(
                    id = 1,
                    workspaceId = 1,
                    name = "Pager",
                    type = ConnectionType.valueOf(migrated.type),
                    url = migrated.url,
                    secret = migrated.secret,
                )
                assertThat(connection.configured).isTrue()

                // The admin default was respelled with it, so nothing inheriting
                // it disagrees with the workspace's copy about what it is.
                assertThat(single(db, "SELECT type FROM connection WHERE name = 'Pager default'"))
                    .isEqualTo("HTTP")

                // And the old spelling is not a type any more. This is the half
                // SqliteCheckConstraintTest cannot see: it matches constraints by
                // name and compares what Postgres allows against what SQLite
                // allows, so a value withdrawn from one dialect and left in the
                // other passes it.
                assertThatThrownBy {
                    insert(
                        db,
                        "INSERT INTO connection (name, type, url) " +
                            "VALUES ('Late', 'WEBHOOK', 'https://example.invalid/pager')",
                    )
                }.hasMessageContaining("ck_connection_type")

                assertThatThrownBy {
                    insert(
                        db,
                        "INSERT INTO workspace_connection (workspace_id, name, type, url) " +
                            "VALUES (1, 'Late', 'WEBHOOK', 'https://example.invalid/pager')",
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
            "SELECT type, url, url_override, auth_type, secret FROM workspace_connection WHERE name = 'Pager'",
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
        const val SCHEMA = "http_type_migration"

        /**
         * Everything below V184. The `?` is Flyway's "or the highest there is
         * under it", so a migration added between now and then is included
         * without editing this.
         */
        const val BEFORE_THE_RENAME = "183?"
        const val LATEST = "latest"
    }
}
