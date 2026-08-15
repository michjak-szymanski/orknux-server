package io.mszymanski.gyloli.server.condition

import io.mszymanski.gyloli.server.action.WorkflowActionRepository
import io.mszymanski.gyloli.server.action.WorkflowFunctionRepository
import io.mszymanski.gyloli.server.team.Team
import io.mszymanski.gyloli.server.team.TeamAuditRepository
import io.mszymanski.gyloli.server.team.TeamRepository
import io.mszymanski.gyloli.server.workflow.WorkflowNodeRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * The condition catalogue, and deciding one.
 *
 * A condition is data — a property, a check, what to check against — so what is
 * asserted here is that the data is refused when it could not be answered, and
 * that answering it gives the answer the definition describes.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ConditionAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val evaluator: ConditionEvaluator,
    @Autowired val conditions: WorkflowConditionRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val teams: TeamRepository,
    @Autowired val audit: TeamAuditRepository,
) {

    private var teamId: Long = 0

    @BeforeEach
    fun reset() {
        nodes.deleteAll()
        actions.deleteAll()
        conditions.deleteAll()
        functions.deleteAll()
        audit.deleteAll()
        teams.deleteAll()
        teamId = requireNotNull(teams.save(Team(name = "backend")).id)
    }

    @Test
    fun `a condition says in words what it asks`() {
        val id = slackAuthorInList("Is Teammate Message", negate = false, values = listOf("alice@example.com"))

        graphQlTester.document("""query { condition(id: $id) { typeLabel description negate } }""").execute()
            .path("condition.typeLabel").entity(String::class.java).isEqualTo("Slack")
            .path("condition.description").entity(String::class.java)
            .satisfies { assertThat(it).contains("message author").contains("one of 1 listed values") }

        assertThat(audit.findAll().map { it.message }).contains("Condition Is Teammate Message created")
    }

    @Test
    fun `it decides what a run is carrying, and the negated one answers the opposite`() {
        val plain = conditions.findById(
            slackAuthorInList("Author allowed", negate = false, values = listOf("alice@example.com")),
        ).orElseThrow()
        val negated = conditions.findById(
            slackAuthorInList("Author not allowed", negate = true, values = listOf("alice@example.com")),
        ).orElseThrow()

        val fromAlice = """{"user":"alice@example.com","text":"hello"}"""
        val fromBob = """{"user":"bob@example.com","text":"hello"}"""

        assertThat(evaluator.holds(plain, fromAlice)).isTrue()
        assertThat(evaluator.holds(plain, fromBob)).isFalse()
        assertThat(evaluator.holds(negated, fromAlice)).isFalse()
        assertThat(evaluator.holds(negated, fromBob)).isTrue()
    }

    @Test
    fun `a composite combines the ones it names`() {
        val first = slackAuthorInList("From alice", negate = false, values = listOf("alice@example.com"))
        val second = graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                teamId: $teamId, name: "In incidents", type: SLACK, property: MESSAGE_CHANNEL,
                check: EQUALS, values: ["#incidents"]
              }) { id }
            }
            """,
        ).execute().path("createCondition.id").entity(Long::class.java).get()

        val anyOf = conditions.findById(
            graphQlTester.document(
                """
                mutation {
                  createCondition(input: {
                    teamId: $teamId, name: "Either", type: ANY_OF, members: [$first, $second]
                  }) { id description }
                }
                """,
            ).execute().path("createCondition.id").entity(Long::class.java).get(),
        ).orElseThrow()

        assertThat(evaluator.holds(anyOf, """{"user":"alice@example.com","channel":"#other"}""")).isTrue()
        assertThat(evaluator.holds(anyOf, """{"user":"bob@example.com","channel":"#incidents"}""")).isTrue()
        assertThat(evaluator.holds(anyOf, """{"user":"bob@example.com","channel":"#other"}""")).isFalse()
    }

    @Test
    fun `a function condition answers with the function, and has to return a boolean`() {
        val boolean = function("isUrgent", "BOOLEAN", """
            export default function isUrgent(input) {
              return input !== null && input.priority === "Critical";
            }
        """.trimIndent())
        val id = graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                teamId: $teamId, name: "Urgent", type: FUNCTION, functionId: $boolean
              }) { id functionName description }
            }
            """,
        ).execute().path("createCondition.id").entity(Long::class.java).get()

        val condition = conditions.findById(id).orElseThrow()
        assertThat(evaluator.holds(condition, """{"priority":"Critical"}""")).isTrue()
        assertThat(evaluator.holds(condition, """{"priority":"Low"}""")).isFalse()

        // One that answers with anything else is not a condition.
        val objectReturning = function("summarize", "OBJECT", "export default function summarize() { return {}; }")
        graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                teamId: $teamId, name: "Not a question", type: FUNCTION, functionId: $objectReturning
              }) { id }
            }
            """,
        ).execute().errors().expect { it.message?.contains("returns object") == true }.verify()
    }

    @Test
    fun `a check that does not belong to the property is refused`() {
        graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                teamId: $teamId, name: "Nonsense", type: SLACK, property: MESSAGE_AUTHOR,
                check: BETWEEN, values: ["09:00", "17:00"]
              }) { id }
            }
            """,
        ).execute().errors().expect { it.message?.contains("cannot be tested with between") == true }.verify()
    }

    @Test
    fun `a composite cannot contain itself`() {
        val first = slackAuthorInList("From alice", negate = false, values = listOf("alice@example.com"))
        val second = slackAuthorInList("From bob", negate = false, values = listOf("bob@example.com"))
        val composite = graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                teamId: $teamId, name: "Either", type: ANY_OF, members: [$first, $second]
              }) { id }
            }
            """,
        ).execute().path("createCondition.id").entity(Long::class.java).get()

        graphQlTester.document(
            """
            mutation {
              updateCondition(id: $composite, input: { members: [$first, $composite] }) { id }
            }
            """,
        ).execute().errors().expect { it.message?.contains("would contain itself") == true }.verify()
    }

    @Test
    fun `a time condition reads the clock rather than the run`() {
        val id = graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                teamId: $teamId, name: "All day", type: TIME, property: CURRENT_TIME,
                check: BETWEEN, values: ["00:00", "23:59"]
              }) { id }
            }
            """,
        ).execute().path("createCondition.id").entity(Long::class.java).get()

        assertThat(evaluator.holds(conditions.findById(id).orElseThrow(), null)).isTrue()
    }

    private fun slackAuthorInList(name: String, negate: Boolean, values: List<String>): Long =
        graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                teamId: $teamId, name: "$name", type: SLACK, property: MESSAGE_AUTHOR, check: IN_LIST,
                negate: $negate, values: [${values.joinToString(", ") { "\"$it\"" }}]
              }) { id }
            }
            """,
        ).execute().path("createCondition.id").entity(Long::class.java).get()

    private fun function(name: String, returnType: String, source: String): Long = graphQlTester.document(
        """
        mutation(${'$'}source: String!) {
          createFunction(input: {
            teamId: $teamId, name: "$name", returnType: $returnType, source: ${'$'}source,
            params: [{ name: "input", type: OBJECT }]
          }) { id }
        }
        """,
    ).variable("source", source).execute().path("createFunction.id").entity(Long::class.java).get()
}
