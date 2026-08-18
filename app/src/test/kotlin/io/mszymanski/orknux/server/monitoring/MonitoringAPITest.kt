package io.mszymanski.orknux.server.monitoring

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
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
) {

    @Test
    fun `reports the service, its version and what it depends on`() {
        graphQlTester.document(
            """
            query {
              components {
                name description status version detail
                dependencies { name reachable detail }
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
