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
 * What happens to the rows when the type that promised any format stops existing.
 *
 * V224 removes CUSTOM. Unlike the types V160 and V183 removed it was not merely
 * unimplemented, and unlike GOOGLE_AI it was not wrong on the wire: a CUSTOM
 * provider worked, because it was called as an OpenAI one in every respect - the
 * Bearer header, `{endpoint}/models`, `{endpoint}/chat/completions`, an OpenAI
 * body. What was wrong was the name, which answered "who is at the other end"
 * where every other value in the enum answers "what does this endpoint speak",
 * and so promised a wire format nobody had written a line of code for.
 *
 * The rows therefore move to OPENAI, which is what they already were, and this
 * test is mostly the statement that moving them changes nothing else. The
 * interface offered CUSTOM prominently - it is what the manual told people to
 * pick for Gemini and for a local server - so rows are near certain to exist,
 * and a CHECK narrowed against a table still holding the value it is narrowing
 * away fails on startup rather than quietly.
 *
 * This replays the real Postgres history into a schema of its own - up to the
 * migration before V224, then a Custom provider with a key, an endpoint of its
 * own and a model configured against it, then V224 - and asserts the provider
 * comes out intact rather than merely renamed, and that its models came with it.
 */
class CustomTypeMigrationTest {

    @Test
    fun `a Custom provider comes through V224 as an OpenAI provider with its models intact`() {
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
                        VALUES (1, 'Gemini', 'CUSTOM', 'https://generativelanguage.googleapis.com/v1beta/openai',
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
                assertThat(migrated.type).describedAs("the row moved to the surviving type").isEqualTo("OPENAI")

                // The point of the whole test: the conversion changes the name of
                // the type and nothing else. The endpoint above is not OpenAI's
                // and was never meant to be - it is Google's OpenAI-compatible
                // surface - and the migration leaves it exactly where somebody
                // typed it, because the type says what is spoken there and the
                // endpoint says where. The secret is stored encrypted, so a
                // migration that rewrote it could not be undone afterwards.
                assertThat(migrated.secret).isEqualTo("key-was-here")
                assertThat(migrated.endpoint).isEqualTo("https://generativelanguage.googleapis.com/v1beta/openai")
                assertThat(migrated.authMethod).isEqualTo("API_KEY")

                // Models hang off the provider by id and are deleted with it by
                // the foreign key, so they survive by the row surviving - which
                // is the argument for converting rather than deleting, stated as
                // an assertion.
                assertThat(single(db, "SELECT model_id FROM llm_model WHERE name = 'Flash'"))
                    .isEqualTo("gemini-2.0-flash")

                // And it is a provider the application still considers usable,
                // reached at the address it was already reached at: nothing here
                // was Ollama, so no `/v1` is added and the endpoint is the base.
                val provider = ModelProvider(
                    id = 1,
                    workspaceId = 1,
                    name = "Gemini",
                    type = ProviderType.valueOf(migrated.type),
                    endpoint = migrated.endpoint,
                    secret = migrated.secret,
                )
                assertThat(provider.configured()).isTrue()
                assertThat(provider.openAiBase()).isEqualTo("https://generativelanguage.googleapis.com/v1beta/openai")

                // And the value cannot come back in through the front door.
                assertThatThrownBy {
                    insert(
                        db,
                        "INSERT INTO model_provider (workspace_id, name, type, endpoint) " +
                            "VALUES (1, 'Late', 'CUSTOM', 'https://example.invalid')",
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
        const val SCHEMA = "custom_type_migration"

        /**
         * Everything below V224. The `?` is Flyway's "or the highest there is
         * under it", so a migration added between now and then is included
         * without editing this.
         */
        const val BEFORE_THE_REMOVAL = "223?"
        const val LATEST = "latest"
    }
}
