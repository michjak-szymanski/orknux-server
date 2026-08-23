package io.mszymanski.orknux.server.monitoring

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser

/**
 * One service, so what the screen reports is what it needs: the database and the
 * directory are both up in the suite, and Temporal is switched off, which is
 * exactly what a dependency being unreachable has to look like.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class MonitoringAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    /*
     * The version the build put into the configuration, rather than a literal.
     * Written out, this assertion failed on the release that gave the project
     * its first version number — which is a test breaking on the one change it
     * should have been indifferent to. What matters is that the screen reports
     * the build's version, not which version that happens to be.
     */
    @Value("\${orknux.version}") val version: String,
    /*
     * Which engine this leg of the suite is running on, so the database card can
     * be asserted to name it. Read from the URL rather than assumed, because the
     * same tests run against Postgres and against SQLite and the right answer is
     * different in each - which is the whole of what was wrong with the card.
     */
    @Value("\${spring.datasource.url}") val jdbcUrl: String,
) {

    @Test
    fun `reports the service, its version and what it depends on`() {
        val engine = if (jdbcUrl.startsWith("jdbc:sqlite:")) "SQLite" else "Postgres"

        graphQlTester.document(
            """
            query {
              components {
                name description status version detail
                dependencies { name description reachable detail }
              }
            }
            """,
        ).execute()
            // The other services were folded in; there is one component now.
            .path("components").entityList(Map::class.java).hasSize(1)
            .path("components[0].name").entity(String::class.java).isEqualTo("orknux-server")
            .path("components[0].status").entity(String::class.java).isEqualTo("HEALTHY")
            .path("components[0].version").entity(String::class.java).isEqualTo(version)
            .path("components[0].detail").entity(String::class.java).isEqualTo("Answering")
            .path("components[0].dependencies[*].name").entityList(String::class.java)
            .containsExactly("Database", "Directory")
            .path("components[0].dependencies[*].reachable").entityList(Boolean::class.java)
            .containsExactly(true, true)
            // The card names the engine underneath rather than one of them always.
            .path("components[0].dependencies[0].description").entity(String::class.java)
            .isEqualTo("$engine, for everything the platform stores")
    }
}

/**
 * The database card, on the database `orknux-one` actually ships with.
 *
 * The card was a fixed string reading "Postgres", so the all-in-one image - the
 * one most people run, and the one whose operator is least likely to already
 * know - was told the wrong engine on the screen it opens when something is
 * wrong. The engines differ in ways that reach that person: one writer at a
 * time, no `information_schema`, no varchar length enforced. Somebody looking
 * into a lock timeout on a page that says Postgres is looking for the wrong
 * thing.
 *
 * Pinned to SQLite rather than left to the run, so this fails on the Postgres
 * leg too. The properties are the ones `SqliteSchemaTest` and `DoctorOnSqliteTest`
 * use, so the context is one that already exists rather than a third.
 */
@SpringBootTest(properties = ["spring.datasource.url=jdbc:sqlite:target/schema-test.db"])
class MonitoringOnSqliteTest(
    @Autowired val monitoring: MonitoringAPI,
) {

    @BeforeEach
    fun signInAsAdministrator() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("monitor", "n/a", listOf(SimpleGrantedAuthority("ROLE_ADMINS")))
    }

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    @Test
    fun `the database card names SQLite`() {
        val database = monitoring.components().single().dependencies.single { it.name == "Database" }

        // The sentence keeps its shape; only the engine name moved.
        assertThat(database.description).isEqualTo("SQLite, for everything the platform stores")
        // And it is still a card that says whether the thing is up.
        assertThat(database.reachable).isTrue()
    }
}

/** The screen is behind the Admin section, so the check belongs here too. */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "bob", roles = ["USERS"])
class MonitoringAccessTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
) {

    @Test
    fun `a non-administrator may not read it`() {
        graphQlTester.document("""query { components { name } }""")
            .execute()
            .errors()
            .expect { it.message?.contains("administrator") == true }
            .verify()
    }
}
