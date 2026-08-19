package io.mszymanski.orknux.server

import org.assertj.core.api.Assertions.assertThat
import com.zaxxer.hikari.HikariDataSource
import io.mszymanski.orknux.server.database.SqliteConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
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

/**
 * The one thing `ddl-auto: validate` does not look at.
 *
 * Hibernate compares tables and columns and says nothing at all about a CHECK
 * constraint, so a migration that widens one on Postgres - a new audit category,
 * a new status, a new kind of trigger - leaves SqliteSchemaTest green and breaks
 * the SQLite installation on the first row that uses the new value. It is the
 * quiet half of the two-schemas bargain, and this is what watches it.
 *
 * Textual on purpose. It reads the Postgres history and the SQLite baseline as
 * files rather than asking a database, so it needs neither container nor
 * connection and runs in whichever suite is going. What it asserts is that every
 * literal a named CHECK allows on Postgres is also allowed under SQLite. How the
 * constraint is spelled is not its business, and a value SQLite accepts and
 * Postgres does not is not the mistake anybody makes.
 *
 * It compares the constraints the two schemas share by name and nothing else,
 * because the Postgres history also contains constraints on tables it went on to
 * drop and a name that no longer exists is not a drift. A CHECK added to a table
 * that already existed and folded into only one of the two would therefore slip
 * past this; a CHECK arriving with a new table would not, since the table itself
 * is what validate is looking for.
 */
class SqliteCheckConstraintTest {

    @Test
    fun `every value a CHECK allows on Postgres is allowed on SQLite`() {
        val postgres = checksIn(postgresSchema())
        val sqlite = checksIn(sqliteBaseline())

        assertThat(postgres).isNotEmpty()

        val missing = postgres.mapNotNull { (name, allowed) ->
            val here = sqlite[name] ?: return@mapNotNull null
            val absent = allowed - here
            if (absent.isEmpty()) null else "$name allows $absent on Postgres and not on SQLite"
        }
        assertThat(missing).describedAs(
            "A CHECK constraint was changed in a Postgres migration and not folded into " +
                "db/migration/sqlite/V1__baseline.sql",
        ).isEmpty()
    }

    /**
     * The Postgres history flattened into the schema it ends at: a constraint
     * dropped and added again by a later migration is the later one, which is
     * how every widening in this history has been written.
     */
    private fun postgresSchema(): String {
        val resolver = PathMatchingResourcePatternResolver()
        val migrations = resolver.getResources("classpath:db/migration/postgresql/V*.sql")
            .sortedBy { it.filename!!.substringAfter('V').substringBefore("__").toInt() }
        return migrations.joinToString("\n") { it.inputStream.reader().readText() }
    }

    private fun sqliteBaseline(): String =
        ClassPathResource("db/migration/sqlite/V1__baseline.sql").inputStream.reader().readText()

    /**
     * Every named CHECK in the text, and the quoted literals it names. Later
     * definitions of one name replace earlier ones, which is what makes reading
     * the whole history in order equivalent to reading the schema it produced.
     */
    private fun checksIn(sql: String): Map<String, Set<String>> {
        val checks = linkedMapOf<String, Set<String>>()
        val declaration = Regex("""CONSTRAINT\s+(\w+)\s+CHECK\s*\(""", RegexOption.IGNORE_CASE)
        for (match in declaration.findAll(sql)) {
            val body = balanced(sql, match.range.last) ?: continue
            checks[match.groupValues[1].lowercase()] = Regex("'([^']*)'").findAll(body)
                .map { it.groupValues[1] }
                .toSet()
        }
        return checks
    }

    /** The text between the parenthesis at [open] and the one that closes it. */
    private fun balanced(sql: String, open: Int): String? {
        var depth = 0
        for (i in open until sql.length) {
            when (sql[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return sql.substring(open + 1, i)
            }
        }
        return null
    }
}
