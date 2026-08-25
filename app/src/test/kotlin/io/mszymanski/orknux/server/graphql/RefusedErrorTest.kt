package io.mszymanski.orknux.server.graphql

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceNameTakenException
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * A refusal carries enough for a client to say it in its own language.
 *
 * The interface is translated in the browser, from `extensions.code` and
 * `extensions.arguments` rather than from the sentence - `RefusedError.kt`
 * argues why. What that arrangement needs from this side is three things, and
 * this is what holds them:
 *
 *   the English is unchanged, so every existing client and every test that pins
 *   an error text still reads what it always did;
 *
 *   the code and the arguments are actually sent, by every resolver rather than
 *   by the one somebody remembered;
 *
 *   and a code means one thing. The code is derived from the exception's class
 *   name, which is free and unambiguous right up until two classes are given
 *   the same name - which has already happened seven times in this repository,
 *   `app` and a module each declaring their own. Two of those pairs do not say
 *   the same thing, so a browser translating on the code alone would show the
 *   wrong sentence. The list below is what is known; a new one is a failure
 *   here rather than a mistranslation nobody notices.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class RefusedErrorTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val repository: WorkspaceRepository,
    @Autowired val auditRepository: WorkspaceAuditRepository,
) {

    @BeforeEach
    fun clearWorkspaces() {
        auditRepository.deleteAll()
        repository.deleteAll()
    }

    @Test
    fun `a refusal carries its code and its arguments beside the English`() {
        repository.save(Workspace(name = "platform"))

        graphQlTester.document("""mutation { createWorkspace(input: { name: "platform" }) { id } }""")
            .execute()
            .errors()
            .satisfy { errors ->
                val refusal = errors.single()
                // Unchanged: this is what a client with no catalogue still shows.
                assertThat(refusal.message).isEqualTo("""A workspace named "platform" already exists""")
                assertThat(refusal.extensions["code"]).isEqualTo("WorkspaceNameTaken")
                assertThat(refusal.extensions["arguments"]).isEqualTo(mapOf("name" to "platform"))
            }
    }

    /**
     * A refusal with nothing in its sentence sends no arguments at all.
     *
     * An empty map would be a thing for a client to check for; absence is the
     * same answer with nothing to get wrong.
     */
    @Test
    fun `a refusal with no values sends a code and nothing else`() {
        graphQlTester.document("""mutation { createWorkspace(input: { name: "  " }) { id } }""")
            .execute()
            .errors()
            .satisfy { errors ->
                val refusal = errors.single()
                assertThat(refusal.message).isEqualTo("A workspace name is required")
                assertThat(refusal.extensions["code"]).isEqualTo("WorkspaceNameInvalid")
                assertThat(refusal.extensions).doesNotContainKey("arguments")
            }
    }

    @Test
    fun `the code is the class name with Exception dropped`() {
        assertThat(codeOf(WorkspaceNameTakenException("platform"))).isEqualTo("WorkspaceNameTaken")
        assertThat(codeOf(IllegalStateException("anything"))).isEqualTo("IllegalState")
    }

    /**
     * Every resolver answers through [refused], so none of them can quietly go
     * back to a bare `GraphQLError.newError()` and stop sending the code.
     *
     * Read out of the source rather than driven through twelve mutations: what
     * is being guarded is that the twelve files agree, and a test that drove
     * one refusal per resolver would be twelve fixtures guarding one line each.
     */
    @Test
    fun `every exception resolver answers through the one builder`() {
        val resolvers = Files.walk(Path.of("src/main/kotlin")).use { paths ->
            paths.filter { it.fileName.toString().endsWith("ExceptionResolver.kt") }.toList()
        }
        assertThat(resolvers).hasSizeGreaterThanOrEqualTo(12)

        val direct = resolvers.filter { it.readText().contains("GraphQLError.newError()") }
        assertThat(direct.map { it.fileName.toString() })
            .describedAs("a resolver building its own error sends no code, so its refusals cannot be translated")
            .isEmpty()
    }

    /**
     * The exception names that two classes answer to, which is the whole of what
     * makes a derived code unsafe.
     *
     * Left as a recorded list rather than fixed, because fixing it means
     * renaming a public exception in a module and that is a change of its own.
     * What matters is that the list cannot grow without somebody deciding it
     * should, and that the interface's refusal catalogue holds none of them.
     */
    @Test
    fun `no new exception name is declared twice`() {
        val declaration = Regex("""\bclass ([A-Z]\w*Exception)\b""")
        val declared = mutableMapOf<String, MutableList<String>>()
        for (root in listOf("src/main/kotlin", "../modules/connection/src/main/kotlin", "../modules/execution/src/main/kotlin")) {
            val path = Path.of(root)
            if (!Files.exists(path)) continue
            Files.walk(path).use { paths ->
                paths.filter { it.extension == "kt" }.forEach { file ->
                    for (found in declaration.findAll(file.readText())) {
                        declared.getOrPut(found.groupValues[1]) { mutableListOf() }.add(file.toString())
                    }
                }
            }
        }

        val twice = declared.filterValues { it.size > 1 }.keys.map { it.removeSuffix("Exception") }
        assertThat(twice).containsExactlyInAnyOrder(
            "ConnectionNotFound",
            "ExecutionNotFound",
            "McpServerNotFound",
            "ModelNotFound",
            "ModelProviderNotFound",
            "WorkflowGraphEmpty",
            "WorkflowNotFound",
        )
    }
}
