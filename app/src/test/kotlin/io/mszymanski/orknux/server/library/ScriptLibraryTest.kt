package io.mszymanski.orknux.server.library

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.chat.AgentTools
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
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithMockUser

/**
 * Libraries: JavaScript an installation loads once and its scripts import.
 *
 * The upload is driven through the controller rather than the repository, because
 * what it does before storing anything is the interesting half: a library is
 * evaluated in the sandbox it will run in, and what its export turned out to hold
 * is read off the value. A test that wrote the member list itself would still pass
 * on the day the reading stopped working.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ScriptLibraryTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val upload: ScriptLibraryUploadAPI,
    @Autowired val libraries: ScriptLibraryRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val tools: AgentToolRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val agentTools: AgentTools,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        agents.deleteAll()
        tools.deleteAll()
        functions.deleteAll()
        libraries.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @Test
    fun `a loaded library is read for what it exports, and listed with it`() {
        load("slugs", "export default { tag: 'slugs', of: (t) => t.toLowerCase().split(' ').join('-') };")

        graphQlTester.document(
            """query { scriptLibraries { key callable members { name callable } usedBy { name } uploadedBy } }""",
        ).execute()
            .path("scriptLibraries[0].key").entity(String::class.java).isEqualTo("slugs")
            .path("scriptLibraries[0].callable").entity(Boolean::class.java).isEqualTo(false)
            .path("scriptLibraries[0].uploadedBy").entity(String::class.java).isEqualTo("alice")
            .path("scriptLibraries[0].members[*].name").entityList(String::class.java).containsExactly("of", "tag")
            .path("scriptLibraries[0].members[0].callable").entity(Boolean::class.java).isEqualTo(true)
            .path("scriptLibraries[0].members[1].callable").entity(Boolean::class.java).isEqualTo(false)
            .path("scriptLibraries[0].usedBy").entityList(Object::class.java).hasSize(0)
    }

    /** A bundle that exports one function rather than an object is the other spelling. */
    @Test
    fun `a library whose export is itself a function says so`() {
        load("shout", "export default function shout(t) { return t.toUpperCase(); }")

        graphQlTester.document("""query { scriptLibraries { callable members { name } } }""").execute()
            .path("scriptLibraries[0].callable").entity(Boolean::class.java).isEqualTo(true)
    }

    /** Refused where somebody is looking, rather than found when a workflow needed it. */
    @Test
    fun `a file with nothing to import is refused`() {
        val failure = runCatching {
            upload.upload(file("empty.js", "const x = 1;"), null)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LibraryUnreadableException::class.java)
        assertThat(failure?.message).contains("no default export")
        assertThat(libraries.findAll()).isEmpty()
    }

    /**
     * A function imports a library and a tool imports the same one, and both run.
     *
     * The end-to-end one, taken through an agent's call so that nothing about the
     * path is stood in for: the tool imports a function that imports the library,
     * which is also the case that proves a module reached through another still
     * gets what it calls.
     */
    @Test
    fun `a tool imports a function that uses a library, and the whole chain runs`() {
        val library = load("slugs", "export default { of: (t) => t.toLowerCase().split(' ').join('-') };")

        val slugify = graphQlTester.document(
            """
            mutation {
              createFunction(input: {
                workspaceId: $workspaceId, name: "slugify",
                source: "export default function slugify(title) { return imports.slugs.of(title); }",
                typescript: "export default function slugify(title) { return imports.slugs.of(title); }",
                params: [{ name: "title", type: STRING }], returnType: STRING,
                libraries: [{ libraryId: $library, name: "slugs" }]
              }) { id libraries { name library { key members { name } } } }
            }
            """,
        ).execute()
            .path("createFunction.libraries[0].library.key").entity(String::class.java).isEqualTo("slugs")
            .path("createFunction.id").entity(Long::class.java).get()

        graphQlTester.document(
            """
            mutation {
              createTool(input: {
                workspaceId: $workspaceId, name: "makeSlug", description: "Slugs a title",
                source: "export default function makeSlug(title) { return { slug: imports.slug(title) }; }",
                typescript: "export default function makeSlug(title) { return { slug: imports.slug(title) }; }",
                params: [{ name: "title", type: STRING }],
                imports: [{ functionId: $slugify, name: "slug" }]
              }) { id }
            }
            """,
        ).execute().path("createTool.id").entity(Long::class.java).get()

        val agentId = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Writer", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateAgent(id: $agentId, input: { name: "Writer", tools: ["makeSlug"] }) { tools } }""",
        ).execute()

        val answer = agentTools.run(
            requireNotNull(agents.findById(agentId).orElse(null)),
            ToolCall(id = "call_1", name = "makeSlug", arguments = """{"title":"Hello There World"}"""),
        )
        assertThat(answer).contains("hello-there-world")
    }

    /**
     * What depends on a library is the question the installation has to answer, and
     * the answer is what stops it being removed.
     */
    @Test
    fun `a library something imports cannot be removed, and the refusal names it`() {
        val library = load("slugs", "export default { of: (t) => t };")
        graphQlTester.document(
            """
            mutation {
              createFunction(input: {
                workspaceId: $workspaceId, name: "slugify",
                source: "export default function slugify(t) { return imports.slugs.of(t); }",
                typescript: "export default function slugify(t) { return imports.slugs.of(t); }",
                params: [{ name: "t", type: STRING }], returnType: STRING,
                libraries: [{ libraryId: $library, name: "slugs" }]
              }) { id }
            }
            """,
        ).execute()

        graphQlTester.document("""query { scriptLibraries { usedBy { kind name workspaceName } } }""").execute()
            .path("scriptLibraries[0].usedBy[0].kind").entity(String::class.java).isEqualTo("FUNCTION")
            .path("scriptLibraries[0].usedBy[0].name").entity(String::class.java).isEqualTo("slugify")
            .path("scriptLibraries[0].usedBy[0].workspaceName").entity(String::class.java).isEqualTo("backend")

        graphQlTester.document("""mutation { deleteScriptLibrary(id: $library) }""").execute()
            .errors().expect { it.message?.contains("imported by slugify in backend") == true }.verify()
    }

    /**
     * Who else uses a library is an administrator's question.
     *
     * The picker needs the library and not the list of everything that imports it —
     * the same line the function picker already draws around plugins.
     */
    @Test
    fun `the workspace query offers the library without saying who else uses it`() {
        val library = load("slugs", "export default { of: (t) => t };")
        graphQlTester.document(
            """
            mutation {
              createFunction(input: {
                workspaceId: $workspaceId, name: "slugify",
                source: "export default function slugify(t) { return imports.slugs.of(t); }",
                typescript: "export default function slugify(t) { return imports.slugs.of(t); }",
                params: [{ name: "t", type: STRING }], returnType: STRING,
                libraries: [{ libraryId: $library, name: "slugs" }]
              }) { id }
            }
            """,
        ).execute()

        graphQlTester.document("""query { workspaceLibraries(workspaceId: $workspaceId) { key usedBy { name } } }""")
            .execute()
            .path("workspaceLibraries[0].key").entity(String::class.java).isEqualTo("slugs")
            .path("workspaceLibraries[0].usedBy").entityList(Object::class.java).hasSize(0)
    }

    /**
     * Updating a library keeps its row, so nothing that imports it is repointed.
     *
     * The reason an import is stored as an id rather than as a key: replacing the
     * library is the ordinary way to update one, and every function that uses it
     * has to go on using it without being edited.
     */
    @Test
    fun `loading the same key again replaces it in place`() {
        val first = load("slugs", "export default { of: (t) => t };")
        val again = load("slugs", "export default { of: (t) => t, version: 2 };")

        assertThat(again).isEqualTo(first)
        assertThat(libraries.findAll()).hasSize(1)
        graphQlTester.document("""query { scriptLibraries { members { name } } }""").execute()
            .path("scriptLibraries[0].members[*].name").entityList(String::class.java)
            .containsExactly("of", "version")
    }

    /** Uploads one and answers its id. */
    private fun load(key: String, source: String): Long {
        val answered = upload.upload(file("$key.js", source), null)
        @Suppress("UNCHECKED_CAST")
        return (answered.body as Map<String, Any>)["id"] as Long
    }

    private fun file(name: String, source: String) =
        MockMultipartFile("file", name, "text/plain", source.toByteArray())
}
