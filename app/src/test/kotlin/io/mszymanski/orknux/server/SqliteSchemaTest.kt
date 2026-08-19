package io.mszymanski.orknux.server

import org.assertj.core.api.Assertions.assertThat
import com.zaxxer.hikari.HikariDataSource
import io.mszymanski.orknux.server.database.SqliteConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

/**
 * The whole application, started on SQLite.
 *
 * It is here because the second database is the one nobody is looking at. The
 * rest of the suite runs on Postgres, so a migration written for Postgres alone,
 * or a mapping SQLite's dialect spells differently, would pass everything and
 * break the installation that keeps its state in a file. This starts the real
 * context against a real SQLite file with Flyway and `ddl-auto: validate` on:
 * the baseline has to apply, and Hibernate has to agree that what it produced is
 * what the entities describe. Those two together are most of what "supported"
 * means.
 */
@SpringBootTest(properties = ["spring.datasource.url=jdbc:sqlite:target/schema-test.db"])
class SqliteSchemaTest {

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `the baseline applies and Hibernate validates it`() {
        // Reaching this line at all is the assertion: the context would not have
        // started if Flyway had refused the baseline or validation had failed.
        val tables = jdbc.queryForObject(
            "SELECT count(*) FROM sqlite_master WHERE type = 'table'", Int::class.java
        )!!
        assertThat(tables).isGreaterThan(60)
    }

    @Test
    fun `an identity column is filled in rather than left null`() {
        // BIGSERIAL is accepted by SQLite and then inserts nothing, which is the
        // failure mode worth a test of its own: it is silent.
        jdbc.update("INSERT INTO workspace (name, description) VALUES ('sqlite-id-probe', 'x')")
        val id = jdbc.queryForObject(
            "SELECT id FROM workspace WHERE name = 'sqlite-id-probe'", Long::class.java
        )
        assertThat(id).isNotNull().isGreaterThan(0)
        jdbc.update("DELETE FROM workspace WHERE name = 'sqlite-id-probe'")
    }

    @Test
    fun `the pragmas that make SQLite behave are all set`() {
        assertThat(jdbc.queryForObject("PRAGMA journal_mode", String::class.java)).isEqualTo("wal")
        assertThat(jdbc.queryForObject("PRAGMA busy_timeout", Int::class.java)).isEqualTo(30_000)
    }

    @Test
    fun `foreign keys are enforced`() {
        // SQLite has them off per connection unless something turns them on, and
        // off means a workspace can be deleted while its agents stay behind.
        assertThat(jdbc.queryForObject("PRAGMA foreign_keys", Int::class.java)).isEqualTo(1)
    }

    @Test
    fun `a file in a directory that is not there is refused by name`() {
        // The mistake an operator makes first: a volume that was not mounted.
        // What the machinery underneath says is that it could not get a
        // connection, and it never mentions the path.
        val pool = HikariDataSource().apply { jdbcUrl = "jdbc:sqlite:target/no-such-place/orknux.db" }
        val refused = assertThrows<IllegalStateException> {
            SqliteConfig().sqlitePragmas().postProcessAfterInitialization(pool, "dataSource")
        }
        assertThat(refused).hasMessageContaining("no-such-place")
    }
}
