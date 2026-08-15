package io.mszymanski.gyloli.server.action

import io.mszymanski.gyloli.server.condition.WorkflowConditionRepository
import io.mszymanski.gyloli.server.team.Team
import io.mszymanski.gyloli.server.team.TeamAuditRepository
import io.mszymanski.gyloli.server.team.TeamRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/** A team's JavaScript: what the list shows, and what the editor saves. */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class FunctionAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val conditions: WorkflowConditionRepository,
    @Autowired val teams: TeamRepository,
    @Autowired val audit: TeamAuditRepository,
) {

    private var teamId: Long = 0

    @BeforeEach
    fun reset() {
        actions.deleteAll()
        // A condition that asks a function holds it, so conditions go first.
        conditions.deleteAll()
        functions.deleteAll()
        audit.deleteAll()
        teams.deleteAll()
        teamId = requireNotNull(teams.save(Team(name = "backend")).id)
    }

    @Test
    fun `a new function starts from a stub that runs, and reads as its signature`() {
        graphQlTester.document(
            """
            mutation {
              createFunction(input: {
                teamId: $teamId, name: "transformPayload", returnType: OBJECT,
                params: [{ name: "input", type: OBJECT }, { name: "format", type: STRING }]
              }) { name signature source returnType lastModifiedBy }
            }
            """,
        ).execute()
            .path("createFunction.signature").entity(String::class.java)
            .isEqualTo("(input: object, format: string)")
            .path("createFunction.lastModifiedBy").entity(String::class.java).isEqualTo("alice")
            .path("createFunction.source").entity(String::class.java)
            .satisfies { assertThat(it).contains("export default async function transformPayload(input, format)") }

        assertThat(audit.findAll().map { it.message }).contains("Function transformPayload created")
    }

    @Test
    fun `the editor saves the code, and what will not parse is refused`() {
        val id = create("validateEmail")

        graphQlTester.document(
            """
            mutation {
              updateFunction(id: $id, input: {
                source: "export default function validateEmail(email) { return email.includes('@'); }",
                description: "Whether an address looks like one."
              }) { source description }
            }
            """,
        ).execute()
            .path("updateFunction.description").entity(String::class.java)
            .isEqualTo("Whether an address looks like one.")

        graphQlTester.document(
            """mutation { updateFunction(id: $id, input: { source: "export default function ( {" }) { id } }""",
        ).execute().errors().expect { it.message?.contains("Expected") == true || it.message?.contains("Error") == true }
            .verify()
    }

    @Test
    fun `validate answers rather than failing, and says where`() {
        graphQlTester.document(
            """
            mutation {
              validateFunctionSource(teamId: $teamId, source: "export default function ( {") {
                valid line
              }
            }
            """,
        ).execute()
            .path("validateFunctionSource.valid").entity(Boolean::class.java).isEqualTo(false)
            .path("validateFunctionSource.line").entity(Int::class.java).isEqualTo(1)

        graphQlTester.document(
            """
            mutation {
              validateFunctionSource(teamId: $teamId, source: "export default function ok() { return 1; }") {
                valid message
              }
            }
            """,
        ).execute().path("validateFunctionSource.valid").entity(Boolean::class.java).isEqualTo(true)
    }

    @Test
    fun `a name a script could not be called by is refused`() {
        graphQlTester.document(
            """mutation { createFunction(input: { teamId: $teamId, name: "not a name" }) { id } }""",
        ).execute().errors().expect { it.message?.contains("is not a name a script") == true }.verify()
    }

    @Test
    fun `a function an action still calls is not deleted`() {
        val id = create("transformPayload")
        graphQlTester.document(
            """
            mutation {
              createAction(input: {
                teamId: $teamId, name: "Transform Data", type: EXECUTE, subtype: FUNCTION, functionId: $id
              }) { id }
            }
            """,
        ).execute()

        graphQlTester.document("""mutation { deleteFunction(id: $id) }""")
            .execute().errors().expect { it.message?.contains("is called by Transform Data") == true }.verify()

        assertThat(functions.findAll()).hasSize(1)
    }

    private fun create(name: String): Long = graphQlTester.document(
        """mutation { createFunction(input: { teamId: $teamId, name: "$name" }) { id } }""",
    ).execute().path("createFunction.id").entity(Long::class.java).get()
}
