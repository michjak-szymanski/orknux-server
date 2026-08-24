package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.chat.AgentTools
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.plugin.PluginRepository
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
 * One script importing another: what the editor may save, and what then runs.
 *
 * The reference is an id and the name is the importer's, and most of what is here
 * exists to hold that apart. A rename that broke every caller, or a delete that
 * left an import pointing at nothing, would both be found at the moment a workflow
 * ran — which is the worst moment and the worst wording to find them in.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class FunctionImportTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val conditions: WorkflowConditionRepository,
    @Autowired val tools: AgentToolRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val agentTools: AgentTools,
    @Autowired val plugins: PluginRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        actions.deleteAll()
        conditions.deleteAll()
        agents.deleteAll()
        tools.deleteAll()
        functions.deleteAll()
        plugins.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @Test
    fun `a function imports another and calls it under the name it chose`() {
        val shared = function("toUpper", "export default function toUpper(word) { return word.toUpperCase(); }")
        val caller = function(
            "shout",
            "export default function shout(word) { return { said: imports.upper(word) }; }",
            imports = """[{ functionId: $shared, name: "upper" }]""",
        )

        graphQlTester.document("""query { function(id: $caller) { imports { functionId name function { name signature } } } }""")
            .execute()
            .path("function.imports[0].name").entity(String::class.java).isEqualTo("upper")
            .path("function.imports[0].functionId").entity(Long::class.java).isEqualTo(shared)
            .path("function.imports[0].function.name").entity(String::class.java).isEqualTo("toUpper")
    }

    /**
     * The whole reason the two halves are stored apart.
     *
     * Renaming the imported function moves nothing in the importer: the row points
     * at an id, and the code says a name of its own. A reference held by name would
     * have stranded this the moment the rename was saved.
     */
    @Test
    fun `renaming the imported function leaves the importer untouched`() {
        val shared = function("toUpper", "export default function toUpper(word) { return word.toUpperCase(); }")
        val caller = function(
            "shout",
            "export default function shout(word) { return { said: imports.upper(word) }; }",
            imports = """[{ functionId: $shared, name: "upper" }]""",
        )

        graphQlTester.document("""mutation { updateFunction(id: $shared, input: { name: "uppercase" }) { name } }""")
            .execute().path("updateFunction.name").entity(String::class.java).isEqualTo("uppercase")

        graphQlTester.document("""query { function(id: $caller) { source imports { name function { name } } } }""")
            .execute()
            // The code still says `upper`, and the import still resolves.
            .path("function.imports[0].name").entity(String::class.java).isEqualTo("upper")
            .path("function.imports[0].function.name").entity(String::class.java).isEqualTo("uppercase")
            .path("function.source").entity(String::class.java)
            .satisfies { assertThat(it).contains("imports.upper(word)") }
    }

    /** A function something imports is not one to delete, and the refusal says who. */
    @Test
    fun `a function that is imported cannot be deleted`() {
        val shared = function("toUpper", "export default function toUpper(word) { return word; }")
        function(
            "shout",
            "export default function shout(word) { return imports.upper(word); }",
            imports = """[{ functionId: $shared, name: "upper" }]""",
        )

        graphQlTester.document("""mutation { deleteFunction(id: $shared) }""").execute()
            .errors().expect { it.message?.contains("is imported by shout") == true }.verify()

        assertThat(functions.findAll().map { it.name }).contains("toUpper")
    }

    /** Two functions that import each other would be a run nobody could assemble. */
    @Test
    fun `an import that closes a loop is refused, and the message names the loop`() {
        val first = function("first", "export default function first(w) { return w; }")
        val second = function(
            "second",
            "export default function second(w) { return imports.a(w); }",
            imports = """[{ functionId: $first, name: "a" }]""",
        )

        graphQlTester.document(
            """mutation { updateFunction(id: $first, input: { imports: [{ functionId: $second, name: "b" }] }) { id } }""",
        ).execute().errors().expect {
            it.message?.contains("loop") == true && it.message?.contains("second imports first") == true
        }.verify()
    }

    /** Including the shortest loop there is. */
    @Test
    fun `a function cannot import itself`() {
        val only = function("only", "export default function only(w) { return w; }")

        graphQlTester.document(
            """mutation { updateFunction(id: $only, input: { imports: [{ functionId: $only, name: "me" }] }) { id } }""",
        ).execute().errors().expect { it.message?.contains("loop") == true }.verify()
    }

    @Test
    fun `two imports may not answer to one name`() {
        val one = function("one", "export default function one(w) { return w; }")
        val two = function("two", "export default function two(w) { return w; }")

        graphQlTester.document(
            """
            mutation {
              updateFunction(id: ${function("caller", "export default function caller(w) { return w; }")}, input: {
                imports: [{ functionId: $one, name: "same" }, { functionId: $two, name: "same" }]
              }) { id }
            }
            """,
        ).execute().errors().expect { it.message?.contains("already imports something called") == true }.verify()
    }

    /** A function in another workspace is not one this one may reach by id. */
    @Test
    fun `a function from another workspace cannot be imported`() {
        val elsewhere = requireNotNull(workspaces.save(Workspace(name = "frontend")).id)
        val theirs = requireNotNull(
            functions.save(
                WorkflowFunction(
                    workspaceId = elsewhere,
                    name = "theirs",
                    source = "export default function theirs(w) { return w; }",
                    typescript = "export default function theirs(w) { return w; }",
                ),
            ).id,
        )
        val mine = function("mine", "export default function mine(w) { return w; }")

        graphQlTester.document(
            """mutation { updateFunction(id: $mine, input: { imports: [{ functionId: $theirs, name: "t" }] }) { id } }""",
        ).execute().errors().expect { it.message?.contains("There is no function $theirs to import") == true }.verify()
    }

    /**
     * A tool imports a function and runs it, all the way through the agent.
     *
     * The end-to-end one. Everything above checks what may be saved; this checks
     * that what was saved is what the sandbox is handed, through the same path an
     * agent actually takes.
     */
    @Test
    fun `a tool imports a function and the agent's call runs it`() {
        val shared = function(
            "slugify",
            "export default function slugify(text) { return text.toLowerCase().split(' ').join('-'); }",
        )
        graphQlTester.document(
            """
            mutation {
              createTool(input: {
                workspaceId: $workspaceId, name: "makeSlug", description: "Slugs a title",
                source: "export default function makeSlug(title) { return { slug: imports.slug(title) }; }",
                typescript: "export default function makeSlug(title) { return { slug: imports.slug(title) }; }",
                params: [{ name: "title", type: STRING }],
                imports: [{ functionId: $shared, name: "slug" }]
              }) { id imports { name function { name } } }
            }
            """,
        ).execute()
            .path("createTool.imports[0].function.name").entity(String::class.java).isEqualTo("slugify")

        val agentId = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Writer", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateAgent(id: $agentId, input: { name: "Writer", tools: ["makeSlug"] }) { tools } }""",
        ).execute()
        val agent = requireNotNull(agents.findById(agentId).orElse(null))

        val answer = agentTools.run(
            agent,
            ToolCall(id = "call_1", name = "makeSlug", arguments = """{"title":"Hello There World"}"""),
        )
        assertThat(answer).contains("hello-there-world")
    }

    /** And a function a tool imports is a function that cannot be deleted either. */
    @Test
    fun `a function a tool imports cannot be deleted`() {
        val shared = function("slugify", "export default function slugify(text) { return text; }")
        graphQlTester.document(
            """
            mutation {
              createTool(input: {
                workspaceId: $workspaceId, name: "makeSlug",
                source: "export default function makeSlug(t) { return imports.slug(t); }",
                typescript: "export default function makeSlug(t) { return imports.slug(t); }",
                params: [{ name: "t", type: STRING }],
                imports: [{ functionId: $shared, name: "slug" }]
              }) { id }
            }
            """,
        ).execute().path("createTool.id").entity(Long::class.java).get()

        graphQlTester.document("""mutation { deleteFunction(id: $shared) }""").execute()
            .errors().expect { it.message?.contains("is imported by the tool makeSlug") == true }.verify()
    }

    /**
     * An import is not an argument.
     *
     * The signature check counts what the code declares against the parameters and
     * the externals, and an import is neither — it arrives through `imports`. A
     * function that gained one and was then refused for taking too few arguments
     * would be a rule contradicting itself.
     */
    @Test
    fun `adding an import does not change what the code has to accept`() {
        val shared = function("helper", "export default function helper(w) { return w; }")
        val caller = function("caller", "export default function caller(word) { return word; }")

        graphQlTester.document(
            """
            mutation {
              updateFunction(id: $caller, input: { imports: [{ functionId: $shared, name: "h" }] }) {
                signature imports { name }
              }
            }
            """,
        ).execute()
            .path("updateFunction.signature").entity(String::class.java).isEqualTo("(word: string)")
            .path("updateFunction.imports[0].name").entity(String::class.java).isEqualTo("h")
    }

    /** An empty list takes them all off; null would have left them alone. */
    @Test
    fun `an empty list clears the imports`() {
        val shared = function("helper", "export default function helper(w) { return w; }")
        val caller = function(
            "caller",
            "export default function caller(w) { return w; }",
            imports = """[{ functionId: $shared, name: "h" }]""",
        )

        graphQlTester.document(
            """mutation { updateFunction(id: $caller, input: { imports: [] }) { imports { name } } }""",
        ).execute().path("updateFunction.imports").entityList(Object::class.java).hasSize(0)

        graphQlTester.document("""mutation { deleteFunction(id: $shared) }""").execute()
            .path("deleteFunction").entity(Boolean::class.java).isEqualTo(true)
    }

    private fun function(name: String, source: String, imports: String = "[]"): Long = graphQlTester.document(
        """
        mutation {
          createFunction(input: {
            workspaceId: $workspaceId, name: "$name", source: "${source.replace("\"", "\\\"")}",
            typescript: "${source.replace("\"", "\\\"")}",
            params: [{ name: "word", type: STRING }], imports: $imports
          }) { id }
        }
        """,
    ).execute().path("createFunction.id").entity(Long::class.java).get()
}
