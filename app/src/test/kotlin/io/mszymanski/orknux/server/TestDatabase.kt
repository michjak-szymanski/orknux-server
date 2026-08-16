package io.mszymanski.orknux.server

import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener
import org.testcontainers.containers.PostgreSQLContainer

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

    private companion object {
        @Volatile
        var started = false
    }
}
