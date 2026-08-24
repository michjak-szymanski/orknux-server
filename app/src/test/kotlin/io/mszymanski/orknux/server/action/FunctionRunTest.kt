package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.server.variable.VariableCatalog
import io.mszymanski.orknux.server.variable.VariableCatalogRepository
import io.mszymanski.orknux.server.variable.VariableKind
import io.mszymanski.orknux.server.variable.VariableType
import io.mszymanski.orknux.server.variable.WorkspaceVariable
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Running a function from the editor: issue #266.
 *
 * What is worth pinning here is not that a happy path returns a number. It is
 * that this is the *same* call a workflow makes — the same sandbox, the same
 * imports, the same grants — and that the interesting answers survive the trip:
 * a script that threw, one that will not stop, and one asked for a value that is
 * not JSON. A test run that could only report success would be a test run nobody
 * should trust.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class FunctionRunTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val catalogs: VariableCatalogRepository,
    @Autowired val variables: WorkspaceVariableRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        actions.deleteAll()
        functions.deleteAll()
        variables.deleteAll()
        catalogs.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @Test
    fun `it runs, with the arguments given, and says how it went`() {
        val id = function(
            "addUp",
            "export default async function addUp(a, b) { return { total: a + b }; }",
            """[{ name: "a", type: NUMBER }, { name: "b", type: NUMBER }]""",
        )

        graphQlTester.document(
            """
            mutation {
              runFunction(input: {
                workspaceId: $workspaceId, functionId: $id,
                arguments: [{ name: "a", json: "2" }, { name: "b", json: "40" }]
              }) { ok returned error settled grants }
            }
            """,
        ).execute()
            .path("runFunction.ok").entity(Boolean::class.java).isEqualTo(true)
            .path("runFunction.returned").entity(String::class.java).isEqualTo("""{"total":42}""")
            .path("runFunction.error").valueIsNull()
            .path("runFunction.grants").entityList(String::class.java).hasSize(0)
    }

    @Test
    fun `a parameter nobody filled in arrives as null, exactly as an unmapped node passes it`() {
        val id = function(
            "saidWhat",
            "export default async function saidWhat(word) { return { said: word === null ? 'nothing' : word }; }",
            """[{ name: "word", type: STRING }]""",
        )

        graphQlTester.document(
            """mutation { runFunction(input: { workspaceId: $workspaceId, functionId: $id }) { ok returned } }""",
        ).execute()
            .path("runFunction.ok").entity(Boolean::class.java).isEqualTo(true)
            .path("runFunction.returned").entity(String::class.java).isEqualTo("""{"said":"nothing"}""")
    }

    /**
     * The one #142 is about, asked of the test run rather than of a node.
     *
     * A grant is not the caller's to pass — the input has nowhere to put one — so
     * if the run did not resolve it the function would read `undefined` and answer
     * wrongly rather than fail. That is why the value is asserted and not only the
     * name beside it.
     */
    @Test
    fun `the workspace's variables are handed over, and named without their values`() {
        val variableId = variable("apiKey", "sk-live-0000")
        val id = function(
            "whoAmI",
            "export default async function whoAmI(caller, apiKey) { return { caller, key: apiKey }; }",
            """[{ name: "caller", type: STRING }]""",
            externals = variableId,
        )

        graphQlTester.document(
            """
            mutation {
              runFunction(input: {
                workspaceId: $workspaceId, functionId: $id,
                arguments: [{ name: "caller", json: "\"alice\"" }]
              }) { ok returned grants }
            }
            """,
        ).execute()
            .path("runFunction.ok").entity(Boolean::class.java).isEqualTo(true)
            .path("runFunction.returned").entity(String::class.java)
            .isEqualTo("""{"caller":"alice","key":"sk-live-0000"}""")
            // The name of the grant, and only the name: the panel says what was
            // handed over without becoming a second way to read a secret.
            .path("runFunction.grants").entityList(String::class.java).containsExactly("apiKey")
    }

    /**
     * An imported function keeps its own grants, which is the half of #142 that a
     * test run could most easily have got wrong: the grant belongs to the callee
     * and is appended by the module registry, not by whoever called it.
     */
    @Test
    fun `an imported function is reached, with its own grants`() {
        val variableId = variable("suffix", "!")
        val shout = function(
            "shout",
            "export default async function shout(word, suffix) { return word.toUpperCase() + suffix; }",
            """[{ name: "word", type: STRING }]""",
            externals = variableId,
        )
        val id = function(
            "announce",
            "export default async function announce(word) { return { said: await imports.shout(word) }; }",
            """[{ name: "word", type: STRING }]""",
            imports = """imports: [{ functionId: $shout, name: "shout" }]""",
        )

        graphQlTester.document(
            """
            mutation {
              runFunction(input: {
                workspaceId: $workspaceId, functionId: $id,
                arguments: [{ name: "word", json: "\"hello\"" }]
              }) { ok returned }
            }
            """,
        ).execute()
            .path("runFunction.ok").entity(Boolean::class.java).isEqualTo(true)
            .path("runFunction.returned").entity(String::class.java).isEqualTo("""{"said":"HELLO!"}""")
    }

    @Test
    fun `a script that throws comes back as what it said, under the function's name`() {
        val id = function(
            "alwaysThrows",
            "export default async function alwaysThrows(x) { throw new Error('no ' + x); }",
            """[{ name: "x", type: STRING }]""",
        )

        graphQlTester.document(
            """
            mutation {
              runFunction(input: {
                workspaceId: $workspaceId, functionId: $id,
                arguments: [{ name: "x", json: "\"thanks\"" }]
              }) { ok returned error settled }
            }
            """,
        ).execute()
            .path("runFunction.ok").entity(Boolean::class.java).isEqualTo(false)
            .path("runFunction.returned").valueIsNull()
            .path("runFunction.error").entity(String::class.java)
            .satisfies { assertThat(it).isEqualTo("alwaysThrows no thanks") }
            // A script can reach nothing that might change, so it will throw again.
            .path("runFunction.settled").entity(Boolean::class.java).isEqualTo(true)
    }

    /**
     * A function that never finishes.
     *
     * Which budget stops it — the statement limit or the clock — is the sandbox's
     * business and is not asserted; what a test run has to do is come back at all,
     * say so, and say that asking again might answer differently, so that nobody
     * is sent to read a function that is fine on a machine that was busy.
     */
    @Test
    fun `a script that will not stop is stopped, and says so`() {
        val id = function(
            "spins",
            "export default async function spins() { let n = 0; for (;;) { n += 1; } }",
            "[]",
        )

        graphQlTester.document(
            """mutation { runFunction(input: { workspaceId: $workspaceId, functionId: $id }) { ok error settled } }""",
        ).execute()
            .path("runFunction.ok").entity(Boolean::class.java).isEqualTo(false)
            .path("runFunction.error").entity(String::class.java)
            .satisfies { assertThat(it).startsWith("spins ").contains("stopped") }
            .path("runFunction.settled").entity(Boolean::class.java).isEqualTo(false)
    }

    @Test
    fun `a function that returns nothing says nothing rather than failing`() {
        val id = function("doesWork", "export default async function doesWork() { }", "[]")

        graphQlTester.document(
            """mutation { runFunction(input: { workspaceId: $workspaceId, functionId: $id }) { ok returned } }""",
        ).execute()
            .path("runFunction.ok").entity(Boolean::class.java).isEqualTo(true)
            .path("runFunction.returned").valueIsNull()
    }

    /**
     * The arguments are joined into one array, so a value carrying a second one at
     * the top level would arrive as two and push the workspace's variables along -
     * and the function would read whatever came after the comma as its own grant.
     *
     * Both halves are here. The smuggled second value is refused, and an argument
     * whose commas are its own - an object - still goes through, because a check
     * that refused those would refuse most of what anybody wants to test with.
     */
    @Test
    fun `an argument cannot smuggle a second one in and move a grant`() {
        val variableId = variable("apiKey", "sk-live-0000")
        val id = function(
            "guarded",
            "export default async function guarded(a, apiKey) { return { got: a, key: apiKey }; }",
            """[{ name: "a", type: MAP }]""",
            externals = variableId,
        )

        graphQlTester.document(
            """
            mutation {
              runFunction(input: {
                workspaceId: $workspaceId, functionId: $id,
                arguments: [{ name: "a", json: "2, 40" }]
              }) { ok }
            }
            """,
        ).execute().errors()
            .expect { it.message?.contains("\"a\" was not given a value") == true }
            .verify()

        graphQlTester.document(
            """
            mutation {
              runFunction(input: {
                workspaceId: $workspaceId, functionId: $id,
                arguments: [{ name: "a", json: "{\"x\": 1, \"y\": 2}" }]
              }) { ok returned }
            }
            """,
        ).execute()
            .path("runFunction.ok").entity(Boolean::class.java).isEqualTo(true)
            // One argument, and the grant still in the position it declares.
            .path("runFunction.returned").entity(String::class.java)
            .isEqualTo("""{"got":{"x":1,"y":2},"key":"sk-live-0000"}""")
    }

    @Test
    fun `a value that is not JSON is refused by the parameter's name`() {
        val id = function(
            "echo",
            "export default async function echo(word) { return { word }; }",
            """[{ name: "word", type: STRING }]""",
        )

        graphQlTester.document(
            """
            mutation {
              runFunction(input: {
                workspaceId: $workspaceId, functionId: $id,
                arguments: [{ name: "word", json: "not json at all" }]
              }) { ok }
            }
            """,
        ).execute().errors()
            .expect { it.message?.contains("\"word\" was not given a value") == true }
            .verify()
    }

    /** It ran; the audit is the only place that can say so, and it says so either way. */
    @Test
    fun `every run is recorded, including one that failed`() {
        val answers = function("answers", "export default async function answers() { return 1; }", "[]")
        val throws = function("throws", "export default async function throws() { throw new Error('no'); }", "[]")

        graphQlTester.document(
            """mutation { runFunction(input: { workspaceId: $workspaceId, functionId: $answers }) { ok } }""",
        ).execute().path("runFunction.ok").entity(Boolean::class.java).isEqualTo(true)
        graphQlTester.document(
            """mutation { runFunction(input: { workspaceId: $workspaceId, functionId: $throws }) { ok } }""",
        ).execute().path("runFunction.ok").entity(Boolean::class.java).isEqualTo(false)

        assertThat(audit.findAll().map { it.message })
            .contains("Function answers run from the editor", "Function throws run from the editor")
    }

    /**
     * A function is run from the workspace that owns it, and the answer for
     * anywhere else is the one an id belonging to somebody else always gets.
     */
    @Test
    fun `a function is not run from another workspace`() {
        val id = function("mine", "export default async function mine() { return 1; }", "[]")
        val elsewhere = requireNotNull(workspaces.save(Workspace(name = "frontend")).id)

        graphQlTester.document(
            """mutation { runFunction(input: { workspaceId: $elsewhere, functionId: $id }) { ok } }""",
        ).execute().errors().expect { it.message?.contains("No function with id") == true }.verify()
    }

    /** One of the workspace's variables, for a function to be granted. */
    private fun variable(name: String, held: String): Long {
        val catalogId = requireNotNull(catalogs.save(VariableCatalog(workspaceId = workspaceId, name = name)).id)
        return requireNotNull(
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
    }

    /** A function with code of its own, saved the way the editor saves one. */
    private fun function(
        name: String,
        source: String,
        params: String,
        externals: Long? = null,
        imports: String = "",
    ): Long {
        val granted = if (externals == null) "" else ", externalVariableIds: [$externals]"
        val imported = if (imports.isEmpty()) "" else ", $imports"
        return graphQlTester.document(
            """
            mutation {
              createFunction(input: {
                workspaceId: $workspaceId, name: "$name", returnType: MAP,
                source: ${'"'}${'"'}${'"'}$source${'"'}${'"'}${'"'}, typescript: ${'"'}${'"'}${'"'}$source${'"'}${'"'}${'"'},
                params: $params$granted$imported
              }) { id }
            }
            """,
        ).execute().path("createFunction.id").entity(Long::class.java).get()
    }
}
