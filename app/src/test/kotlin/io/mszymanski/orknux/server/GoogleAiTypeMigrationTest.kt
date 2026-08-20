package io.mszymanski.orknux.server

import io.mszymanski.orknux.connector.model.ModelProvider
import io.mszymanski.orknux.connector.model.ProviderType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * What happens to the rows when a type that could not work stops existing.
 *
 * V170 removes GOOGLE_AI. Unlike TEAMS the interface did offer it, so a row is
 * far likelier to exist than in V160's case - and a CHECK narrowed against a
 * table still holding the value it is narrowing away fails on startup rather
 * than quietly. V170 therefore moves the rows to CUSTOM first, which is the only
 * type under which that endpoint and that key can actually reach Google: CUSTOM
 * sends `Authorization: Bearer`, which is what Google's OpenAI-compatible
 * surface documents, where GOOGLE_AI sent `x-goog-api-key` at OpenAI-shaped
 * paths that do not exist on the API that header belongs to.
 *
 * This replays the real Postgres history into a schema of its own - up to the
 * migration before V170, then a Google provider with a key and a model
 * configured against it, then V170 - and asserts the provider comes out intact
 * rather than merely renamed, and that its models came with it.
 */
class GoogleAiTypeMigrationTest {

    @Test
    fun `a Google AI provider comes through V170 as a Custom provider with its models intact`() {
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
                        INSERT INTO model_provider (workspace_id, name, type, endpoint, auth_method, secret, status)
                        VALUES (1, 'Gemini', 'GOOGLE_AI', 'https://generativelanguage.googleapis.com/v1beta',
                                'API_KEY', 'key-was-here', 'CONNECTED')
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO llm_model (provider_id, name, model_id, kind)
                        SELECT id, 'Flash', 'gemini-2.0-flash', 'CHAT' FROM model_provider WHERE name = 'Gemini'
                        """.trimIndent(),
                    )
                }
            }

            flyway(url, username, password, target = LATEST).migrate()

            connect(url, username, password).use { db ->
                val migrated = read(db)
                assertThat(migrated.type).describedAs("the row moved to the surviving type").isEqualTo("CUSTOM")

                // The point of the whole test: the conversion changes the name of
                // the type and nothing else. The secret is stored encrypted, so a
                // migration that rewrote it could not be undone afterwards.
                assertThat(migrated.secret).isEqualTo("key-was-here")
                assertThat(migrated.endpoint).isEqualTo("https://generativelanguage.googleapis.com/v1beta")
                assertThat(migrated.authMethod).isEqualTo("API_KEY")

                // Models hang off the provider by id and are deleted with it by
                // the foreign key, so they survive by the row surviving - which
                // is the argument for converting rather than deleting, stated as
                // an assertion.
                assertThat(single(db, "SELECT model_id FROM llm_model WHERE name = 'Flash'"))
                    .isEqualTo("gemini-2.0-flash")

                // And it is a provider the application still considers usable.
                val provider = ModelProvider(
                    id = 1,
                    workspaceId = 1,
                    name = "Gemini",
                    type = ProviderType.valueOf(migrated.type),
                    endpoint = migrated.endpoint,
                    secret = migrated.secret,
                )
                assertThat(provider.configured()).isTrue()

                // And the value cannot come back in through the front door.
                assertThatThrownBy {
                    insert(
                        db,
                        "INSERT INTO model_provider (workspace_id, name, type, endpoint) " +
                            "VALUES (1, 'Late', 'GOOGLE_AI', 'https://generativelanguage.googleapis.com')",
                    )
                }.hasMessageContaining("ck_model_provider_type")
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
            "SELECT type, endpoint, auth_method, secret FROM model_provider WHERE name = 'Gemini'",
        ).use {
            assertThat(it.next()).describedAs("the provider survived the migration at all").isTrue()
            Row(it.getString(1), it.getString(2), it.getString(3), it.getString(4))
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
        val endpoint: String,
        val authMethod: String,
        val secret: String?,
    )

    private companion object {
        const val SCHEMA = "google_ai_type_migration"

        /**
         * Everything below V170. The `?` is Flyway's "or the highest there is
         * under it": nothing is numbered 169 and nothing needs to be, and a
         * migration added between now and then is included without editing this.
         */
        const val BEFORE_THE_REMOVAL = "169?"
        const val LATEST = "latest"
    }
}
