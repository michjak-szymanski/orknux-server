package io.mszymanski.orknux.server.integration

import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.connector.proxy.ProxyRule
import io.mszymanski.orknux.connector.proxy.ProxyRuleRepository
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.WithMockUser

/**
 * The proxy rules as an administrator edits them.
 *
 * What a rule *does* is held still by ProxyRoutingTest, which drives real
 * clients through a real proxy. This file is about the other half: that a rule
 * which could never work is refused where somebody can still fix it, that the
 * order is stated and visible, and that a proxy password goes in and does not
 * come back out.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ProxyRuleAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val rules: ProxyRuleRepository,
    @Autowired val router: ProxyRouter,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val jdbc: JdbcTemplate,
) {

    @BeforeEach
    fun reset() {
        rules.deleteAll()
        audit.deleteAll()
        router.reload()
    }

    @Test
    fun `a rule is added, and lands behind the rules already there`() {
        create("Entra", """login\.microsoftonline\.com""")
        create("Everything else", """.*""")

        graphQlTester.document("{ proxyRules { name position enabled } }").execute()
            .path("proxyRules[0].name").entity(String::class.java).isEqualTo("Entra")
            .path("proxyRules[0].position").entity(Int::class.java).isEqualTo(0)
            .path("proxyRules[1].name").entity(String::class.java).isEqualTo("Everything else")
            // Behind, not in front: a new rule that jumped the queue would change
            // what every rule already there does the moment it was added.
            .path("proxyRules[1].position").entity(Int::class.java).isEqualTo(1)

        assertThat(audit.findAll().map { it.message })
            .contains("Proxy rule Entra created for 127.0.0.1:3128")
    }

    @Test
    fun `a pattern that will not compile is refused when the rule is saved`() {
        graphQlTester.document(
            """
            mutation {
              createProxyRule(input: {
                name: "Broken", pattern: "(unclosed", proxyHost: "127.0.0.1", proxyPort: 3128
              }) { id }
            }
            """,
        ).execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors).singleElement().extracting<String> { it.message }
                    .asString().contains("not a usable regular expression")
            }

        // Refused where somebody can still fix it, rather than stored and found
        // out on the next outbound call.
        assertThat(rules.findAll()).isEmpty()
    }

    @Test
    fun `a proxy address the guard refuses cannot be saved`() {
        // The same guard every outbound address goes past. A rule pointing here
        // would turn every URL it matched into a request this host answers.
        graphQlTester.document(
            """
            mutation {
              createProxyRule(input: {
                name: "Metadata", pattern: ".*", proxyHost: "0.0.0.0", proxyPort: 80
              }) { id }
            }
            """,
        ).execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors).singleElement().extracting<String> { it.message }
                    .asString().contains("That proxy cannot be used")
            }

        assertThat(rules.findAll()).isEmpty()
    }

    @Test
    fun `a rule never gives its password back`() {
        create("Entra", """login\.microsoftonline\.com""", username = "sentry", password = "open-sesame")

        // There is no field to ask for, which is the strongest form this can
        // take: the schema itself cannot express the question.
        graphQlTester.document("{ proxyRules { name username passwordSet } }").execute()
            .path("proxyRules[0].username").entity(String::class.java).isEqualTo("sentry")
            .path("proxyRules[0].passwordSet").entity(Boolean::class.java).isEqualTo(true)

        graphQlTester.document("{ proxyRules { password } }").execute()
            .errors()
            .satisfy { errors -> assertThat(errors).isNotEmpty() }
    }

    @Test
    fun `a proxy password is encrypted in the database`() {
        create("Entra", """login\.microsoftonline\.com""", username = "sentry", password = "open-sesame")

        val stored = jdbc.queryForObject("SELECT password FROM proxy_rule", String::class.java)

        // Read through the column rather than through the entity, because the
        // entity's converter would decrypt it and prove nothing.
        assertThat(stored).startsWith("orkx1:")
        assertThat(stored).doesNotContain("open-sesame")
    }

    @Test
    fun `an empty password clears the stored one and a null leaves it alone`() {
        val id = create("Entra", """login\.microsoft""", username = "sentry", password = "open-sesame")

        update(id, "Entra", """login\.microsoft""", username = "sentry", password = null)
        assertThat(rules.findAll().single().password).isEqualTo("open-sesame")

        update(id, "Entra", """login\.microsoft""", username = "sentry", password = "")
        assertThat(rules.findAll().single().password).isNull()
    }

    @Test
    fun `the route test names the rule that answers and the rules it beat`() {
        create("Everything", """.*""")
        create("Entra", """login\.microsoft""")

        graphQlTester.document(
            """
            { proxyRoute(url: "http://127.0.0.1/login.microsoft/token") {
                matched { name } beaten { name } refusedBecause proxyProblem
            } }
            """,
        ).execute()
            .path("proxyRoute.matched.name").entity(String::class.java).isEqualTo("Everything")
            // The whole reason this exists: the second rule matches and will
            // never fire, and the page can say so instead of leaving somebody to
            // find out from an endpoint that does not answer.
            .path("proxyRoute.beaten[0].name").entity(String::class.java).isEqualTo("Entra")
            .path("proxyRoute.refusedBecause").valueIsNull()
            .path("proxyRoute.proxyProblem").valueIsNull()
    }

    @Test
    fun `moving a rule changes which one answers`() {
        create("Everything", """.*""")
        val entra = create("Entra", """login\.microsoft""")

        graphQlTester.document("mutation { moveProxyRule(id: $entra, up: true) { name position } }").execute()
            .path("moveProxyRule[0].name").entity(String::class.java).isEqualTo("Entra")
            .path("moveProxyRule[1].name").entity(String::class.java).isEqualTo("Everything")

        assertThat(router.resolve("http://127.0.0.1/login.microsoft/token")?.ruleName).isEqualTo("Entra")
    }

    @Test
    fun `a rule turned off stops answering but stays in the list`() {
        val id = create("Entra", """login\.microsoft""")

        graphQlTester.document("mutation { setProxyRuleEnabled(id: $id, enabled: false) { enabled } }").execute()
            .path("setProxyRuleEnabled.enabled").entity(Boolean::class.java).isEqualTo(false)

        assertThat(router.resolve("http://127.0.0.1/login.microsoft/token")).isNull()
        assertThat(rules.findAll()).hasSize(1)
        assertThat(audit.findAll().map { it.message }).contains("Proxy rule Entra turned off")
    }

    @Test
    fun `a stored rule whose pattern will not compile is ignored rather than breaking every call`() {
        // Saved past the API on purpose: validation stops this arriving through
        // the screen, but a restore or a hand-edited row is not the screen.
        rules.save(
            ProxyRule(name = "Broken", pattern = "(unclosed", proxyHost = "127.0.0.1", proxyPort = 3128, position = 0),
        )
        rules.save(
            ProxyRule(name = "Entra", pattern = """login\.microsoft""", proxyHost = "127.0.0.1", proxyPort = 3128, position = 1),
        )
        router.reload()

        // The bad rule costs itself and nothing else. Every other outbound call
        // this installation makes is unaffected, including this one.
        assertThat(router.resolve("http://127.0.0.1/login.microsoft/token")?.ruleName).isEqualTo("Entra")
        assertThat(router.resolve("http://127.0.0.1/elsewhere")).isNull()
    }

    private fun create(
        name: String,
        pattern: String,
        username: String? = null,
        password: String? = null,
    ): Long {
        val credentials = buildString {
            if (username != null) append(""", username: "$username"""")
            if (password != null) append(""", password: "$password"""")
        }
        return graphQlTester.document(
            """
            mutation {
              createProxyRule(input: {
                name: "$name", pattern: "${pattern.replace("\\", "\\\\")}",
                proxyHost: "127.0.0.1", proxyPort: 3128$credentials
              }) { id }
            }
            """,
        ).execute().path("createProxyRule.id").entity(Long::class.java).get()
    }

    private fun update(id: Long, name: String, pattern: String, username: String?, password: String?) {
        val credentials = buildString {
            if (username != null) append(""", username: "$username"""")
            if (password != null) append(""", password: "$password"""")
        }
        graphQlTester.document(
            """
            mutation {
              updateProxyRule(id: $id, input: {
                name: "$name", pattern: "${pattern.replace("\\", "\\\\")}",
                proxyHost: "127.0.0.1", proxyPort: 3128$credentials
              }) { id }
            }
            """,
        ).execute().path("updateProxyRule.id").hasValue()
    }
}
