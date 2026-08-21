package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.server.variable.VariableCatalog
import io.mszymanski.orknux.server.variable.VariableCatalogRepository
import io.mszymanski.orknux.server.variable.VariableKind
import io.mszymanski.orknux.server.variable.VariableType
import io.mszymanski.orknux.server.variable.WorkspaceVariable
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.util.concurrent.atomic.AtomicLong

/**
 * An HTTP request action's headers: rows rather than a JSON blob, and a row that
 * names a variable rather than holding what it holds.
 *
 * Two halves are asserted here and the second is the reason for the first. The
 * rows are ergonomics - typing JSON by hand is a smart quote away from an action
 * that fails when it runs. The reference is a security property: a bearer token
 * pasted into a header is a credential in a column that is not a credential
 * column, unencrypted and legible to anybody who can open the action, and the
 * only way to stop that is to make naming a variable possible.
 *
 * So: what a reference stores, when it is read, and everywhere it must not
 * appear.
 *
 * A workspace of its own per test, and nothing wiped - deleting a workspace
 * cascades to its functions, which nulls `workflow_action.function_id`, which
 * trips `ck_workflow_action_shape` on SQLite for a row that was valid a moment
 * before.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ActionHeadersTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val headers: ActionHeaders,
    @Autowired val variables: WorkspaceVariableRepository,
    @Autowired val catalogs: VariableCatalogRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private var elsewhereId: Long = 0
    private var catalogId: Long = 0

    @BeforeEach
    fun reset() {
        val mine = counter.incrementAndGet()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "headers-$mine")).id)
        elsewhereId = requireNotNull(workspaces.save(Workspace(name = "headers-elsewhere-$mine")).id)
        catalogId = requireNotNull(catalogs.save(VariableCatalog(workspaceId = workspaceId, name = "tokens")).id)
    }

    // ------------------------------------------------- what people already have

    /**
     * The shape every action saved before rows existed holds.
     *
     * It is read, it is shown as rows, and - until somebody saves the form - it
     * is left in the column byte for byte. An upgrade that rewrote every action's
     * headers would be an upgrade that could get one of them wrong.
     */
    @Test
    fun `an action saved as a JSON object reads back as rows and is left as it was`() {
        val written = """{"Accept": "application/json", "X-Trace": "on"}"""
        val id = create("""headers: ${written.quoted()}""")

        graphQlTester.document("""query { action(id: $id) { headersReadable headers headerRows { name value variableName } } }""")
            .execute()
            .path("action.headersReadable").entity(Boolean::class.java).isEqualTo(true)
            .path("action.headers").entity(String::class.java).isEqualTo(written)
            .path("action.headerRows[*].name").entityList(String::class.java).containsExactly("Accept", "X-Trace")
            .path("action.headerRows[*].value").entityList(String::class.java).containsExactly("application/json", "on")

        // And it still sends exactly what it sent before any of this existed.
        assertThat(headers.sentBy(saved(id)))
            .containsExactly(entry("Accept", "application/json"), entry("X-Trace", "on"))
    }

    /**
     * Unreadable JSON keeps doing what it has always done, and says so.
     *
     * The runner already sent no headers for a blob nobody could parse. Making
     * that a failure now would break a call that has been working - the endpoint
     * evidently did not need the header - so the change is that the form is told,
     * and offers the text back to be mended.
     */
    @Test
    fun `headers nobody can parse send nothing, are kept, and are marked unreadable`() {
        val id = create("""headers: "{ \"Authorization\": \"Bearer a-token\" "  """)

        graphQlTester.document("""query { action(id: $id) { headersReadable headers headerRows { name } } }""")
            .execute()
            .path("action.headersReadable").entity(Boolean::class.java).isEqualTo(false)
            .path("action.headerRows").entityList(Any::class.java).hasSize(0)
            .path("action.headers").entity(String::class.java)
            // Trimmed on the way in, the way every other setting is, and
            // otherwise exactly the text that was typed.
            .isEqualTo("""{ "Authorization": "Bearer a-token"""")

        assertThat(headers.sentBy(saved(id))).isEmpty()
    }

    // --------------------------------------------------------------- the rows

    @Test
    fun `rows are saved as rows, and a row with no name is not a row`() {
        val id = create(
            """headerRows: [
                { name: "Accept", value: "application/json" },
                { name: "  ", value: "nothing" },
                { name: " X-Trace ", value: "on" }
            ]""",
        )

        graphQlTester.document("""query { action(id: $id) { headerRows { name value } } }""").execute()
            .path("action.headerRows[*].name").entityList(String::class.java).containsExactly("Accept", "X-Trace")

        assertThat(saved(id).headers)
            .isEqualTo("""[{"name":"Accept","value":"application/json"},{"name":"X-Trace","value":"on"}]""")
    }

    // ---------------------------------------------------------- the reference

    /**
     * The whole point: what is stored is which variable, not what it holds.
     *
     * If the reference resolved when the action was saved, the token would be
     * sitting in the action's column in the clear and the reference would have
     * bought nothing at all.
     */
    @Test
    fun `a header that reads a variable stores the variable, never its value`() {
        val variableId = variable("ACME_TOKEN", "Bearer sk-live-9If2")
        val id = create("""headerRows: [{ name: "Authorization", variableId: "$variableId" }]""")

        assertThat(saved(id).headers)
            .isEqualTo("""[{"name":"Authorization","variableId":"$variableId"}]""")
            .doesNotContain("sk-live-9If2")
    }

    /** The name of the variable travels to the form. What it holds does not. */
    @Test
    fun `the form is told which variable a header reads and never what it holds`() {
        val variableId = variable("ACME_TOKEN", "Bearer sk-live-9If2")
        val id = create("""headerRows: [{ name: "Authorization", variableId: "$variableId" }]""")

        graphQlTester
            .document("""query { action(id: $id) { headers headerRows { name value variableId variableName } } }""")
            .execute()
            .path("action.headerRows[0].variableName").entity(String::class.java).isEqualTo("ACME_TOKEN")
            .path("action.headerRows[0].variableId").entity(String::class.java).isEqualTo(variableId.toString())
            // The value, in the field the literal side uses. There is nowhere
            // else on the type it could arrive: `headers` is the same JSON the
            // column holds, and that holds the number.
            .path("action.headerRows[0].value").valueIsNull()
            .path("action.headers").entity(String::class.java)
            .isEqualTo("""[{"name":"Authorization","variableId":"$variableId"}]""")
    }

    /** Read when it runs, so rotating the token means changing one variable. */
    @Test
    fun `a reference is resolved when the action runs, not when it is saved`() {
        val variableId = variable("ACME_TOKEN", "Bearer first")
        val id = create("""headerRows: [{ name: "Authorization", variableId: "$variableId" }]""")

        val held = requireNotNull(variables.findById(variableId).orElse(null))
        held.value = "Bearer second"
        variables.save(held)

        assertThat(headers.sentBy(saved(id))).containsExactly(entry("Authorization", "Bearer second"))
    }

    @Test
    fun `a literal and a reference sit in the same set of headers`() {
        val variableId = variable("ACME_TOKEN", "Bearer sk-live-9If2")
        val id = create(
            """headerRows: [
                { name: "Accept", value: "application/json" },
                { name: "Authorization", variableId: "$variableId" }
            ]""",
        )

        assertThat(headers.sentBy(saved(id))).containsExactly(
            entry("Accept", "application/json"),
            entry("Authorization", "Bearer sk-live-9If2"),
        )
    }

    // -------------------------------------------------------- what is refused

    @Test
    fun `a header given both a value and a variable is refused`() {
        val variableId = variable("ACME_TOKEN", "Bearer sk-live-9If2")

        graphQlTester.document(mutation("""headerRows: [{ name: "A", value: "b", variableId: "$variableId" }]"""))
            .execute()
            .errors().satisfy { errors ->
                assertThat(errors.single().message).contains("both a value and a variable")
            }
    }

    @Test
    fun `a header given neither a value nor a variable is refused`() {
        graphQlTester.document(mutation("""headerRows: [{ name: "A" }]""")).execute()
            .errors().satisfy { errors ->
                assertThat(errors.single().message).contains("neither a value nor a variable")
            }
    }

    /**
     * Another workspace's variable is not this action's to read.
     *
     * Without this a header would be a way to read any variable in the
     * installation by number, which is a worse hole than the one references were
     * added to close.
     */
    @Test
    fun `a header cannot read another workspace's variable`() {
        val elsewhere = requireNotNull(
            catalogs.save(VariableCatalog(workspaceId = elsewhereId, name = "theirs")).id,
        )
        val theirs = requireNotNull(
            variables.save(
                WorkspaceVariable(
                    workspaceId = elsewhereId,
                    catalogId = elsewhere,
                    name = "THEIR_TOKEN",
                    type = VariableType.STRING,
                    kind = VariableKind.SECRET,
                    value = "Bearer not-yours",
                ),
            ).id,
        )

        graphQlTester.document(mutation("""headerRows: [{ name: "Authorization", variableId: "$theirs" }]"""))
            .execute()
            .errors().satisfy { errors ->
                assertThat(errors.single().message).contains("belongs to another workspace")
            }
    }

    /**
     * A variable a header reads cannot be deleted out from under it.
     *
     * The same rule a function's external parameter has had, read one link over.
     * There is no foreign key here to enforce it - the reference lives inside
     * JSON - so the guard is written or the reference dangles.
     */
    @Test
    fun `a variable a header reads cannot be deleted`() {
        val variableId = variable("ACME_TOKEN", "Bearer sk-live-9If2")
        create("""headerRows: [{ name: "Authorization", variableId: "$variableId" }]""", name = "Call ACME")

        graphQlTester.document("""mutation { deleteVariable(id: $variableId) }""").execute()
            .errors().satisfy { errors ->
                assertThat(errors.single().message).contains("Call ACME")
            }
    }

    /**
     * And if one gets away anyway - an imported action naming a number this
     * installation gave to somebody else - the step stops rather than sending
     * the request undressed. The header is named; nothing else is.
     */
    @Test
    fun `a reference that comes to nothing stops the step and names only the header`() {
        val variableId = variable("ACME_TOKEN", "Bearer sk-live-9If2")
        val id = create("""headerRows: [{ name: "Authorization", variableId: "$variableId" }]""")

        val held = requireNotNull(variables.findById(variableId).orElse(null))
        held.value = ""
        variables.save(held)

        assertThatThrownBy { headers.sentBy(saved(id)) }
            .isInstanceOf(ActionHeaderUnresolvedException::class.java)
            .hasMessageContaining("Authorization")
            .hasMessageContaining("ACME_TOKEN")
            .hasMessageNotContaining("sk-live-9If2")
    }

    // ------------------------------------------------------------------ props

    private fun create(setting: String, name: String = "Call the API"): Long = graphQlTester
        .document(mutation(setting, name))
        .execute()
        .path("createAction.id").entity(String::class.java).get().toLong()

    private fun mutation(setting: String, name: String = "Call the API") = """
        mutation {
          createAction(input: {
            workspaceId: $workspaceId, name: "$name", type: EXECUTE, subtype: HTTP_REQUEST,
            url: "https://api.example.com/orders", method: "GET", $setting
          }) { id }
        }
    """

    private fun saved(id: Long): WorkflowAction = requireNotNull(actions.findById(id).orElse(null))

    private fun variable(name: String, held: String): Long = requireNotNull(
        variables.save(
            WorkspaceVariable(
                workspaceId = workspaceId,
                catalogId = catalogId,
                name = name,
                type = VariableType.STRING,
                kind = VariableKind.SECRET,
                value = held,
            ),
        ).id,
    )

    private fun entry(name: String, value: String) = org.assertj.core.api.Assertions.entry(name, value)

    private fun String.quoted() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        /** One workspace per test, named so two runs of the class cannot collide. */
        val counter = AtomicLong(System.nanoTime())
    }
}
