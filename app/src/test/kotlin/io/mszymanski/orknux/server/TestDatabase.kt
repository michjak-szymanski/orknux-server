package io.mszymanski.orknux.server

import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path

/**
 * The database the suite runs against: its own, thrown away with the run.
 *
 * The fixtures start each test from a known state by deleting everything, and
 * `deleteAll()` cannot tell whose rows are whose. Pointed at the development
 * database it takes the workspaces, providers, models and chat history somebody
 * was in the middle of looking at. A container can only reach itself.
 *
 * A JUnit launcher listener, found through `META-INF/services`, because it has
 * to run before anything else: earlier than the first Spring context, and
 * earlier than any test class. The obvious alternatives do not hold. Annotating
 * each test class means a test added later can forget to, and lands on the
 * development database quietly. Boot's `context.initializer.classes` is worse
 * than either — Spring Boot 4 dropped `DelegatingApplicationContextInitializer`,
 * so the property is read by nothing at all and the isolation it promises is
 * imaginary.
 *
 * One container for the whole run: the suite builds several contexts — each
 * distinct set of test properties gets its own — and starting Postgres for each
 * would cost more than the tests do. Nothing stops it; Ryuk removes it when the
 * JVM exits.
 */
class TestDatabase : LauncherSessionListener {

    override fun launcherSessionOpened(session: LauncherSession) {
        if (started) return
        started = true

        // SqliteSchemaTest keeps its database in a file of its own, and Flyway
        // writes the baseline's checksum into it. A file left behind by the last
        // run therefore refuses the next one the moment the baseline is edited,
        // which is precisely the moment somebody is editing it - and it refuses
        // it with a checksum mismatch rather than with anything about the change
        // being made. Removed here, before any context exists, so that a run
        // always starts from a database the current baseline built.
        deleteDatabase("schema-test.db")

        // The other database, when the run asks for it: `-Dorknux.test.database=sqlite`.
        //
        // It is a switch rather than a second suite because the point is to run
        // the same tests. A suite that only ever exercises Postgres will not
        // notice the day a query, a mapping or a migration stops working on
        // SQLite, and what an operator running the file needs is not a separate
        // set of promises but the same ones.
        //
        // A file rather than :memory:, since the suite builds several contexts
        // and each would otherwise get a database of its own with nothing in it.
        if (System.getProperty("orknux.test.database") == "sqlite") {
            deleteDatabase("suite.db")
            System.setProperty("spring.datasource.url", "jdbc:sqlite:${Path.of("target", "suite.db")}")
            System.setProperty("spring.datasource.username", "")
            System.setProperty("spring.datasource.password", "")
            return
        }

        // Matches the version compose runs, so the suite is not passing against
        // a Postgres nothing is deployed on.
        val postgres = PostgreSQLContainer("postgres:18")
            .withDatabaseName("orknux")
            .withUsername("orknux")
            .withPassword("orknux")
        postgres.start()

        // System properties outrank application.yml, so every context built
        // afterwards finds the container rather than the configured database.
        System.setProperty("spring.datasource.url", postgres.jdbcUrl)
        System.setProperty("spring.datasource.username", postgres.username)
        System.setProperty("spring.datasource.password", postgres.password)
    }

    /**
     * A SQLite database under WAL is three files rather than one, and leaving
     * either of the other two behind is leaving the database behind.
     */
    private fun deleteDatabase(name: String) {
        for (suffix in listOf("", "-wal", "-shm")) {
            Files.deleteIfExists(Path.of("target", name + suffix))
        }
    }

    private companion object {
        @Volatile
        var started = false
    }
}
