package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.server.action.FunctionParam
import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.action.WorkflowFunction
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * What a model can find out about a workspace's code.
 *
 * Asked of the tools directly rather than through a model: what a model does
 * with an answer is its business, and what is worth checking is that the answer
 * carries the source, that it is the TypeScript somebody wrote rather than the
 * JavaScript it compiled to, and that the workspace is a boundary — a scope on
 * one workspace must not read another's code, which is the whole of the
 * protection here.
 */
@SpringBootTest
class OrknuxFunctionToolsTest(
    @Autowired val tools: OrknuxTools,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var elsewhereId: Long = 0

    @BeforeEach
    fun reset() {
        functions.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        elsewhereId = requireNotNull(workspaces.save(Workspace(name = "billing")).id)
    }

    private fun scope() = OrknuxScope(workspaceId = workspaceId)

    private fun store(workspace: Long, name: String) = functions.save(
        WorkflowFunction(
            workspaceId = workspace,
            name = name,
            description = "Whether the sender is one of us.",
            source = "export default async function $name(email) { return email.endsWith('@acme.test'); }",
            typescript = "export default async function $name(email: string) { return email.endsWith('@acme.test'); }",
            returnType = ValueType.BOOLEAN,
            params = mutableListOf(FunctionParam(name = "email", type = ValueType.STRING)),
        ),
    )

    @Test
    fun `the list says what each function takes and gives back`() {
        store(workspaceId, "isTeammate")
        store(elsewhereId, "isBillable")

        val answer = tools.run(scope(), "orknux_functions", "{}")

        assertThat(answer).contains("isTeammate").contains("(email: string)").contains("BOOLEAN")
        // Another workspace's code is not this workspace's business.
        assertThat(answer).doesNotContain("isBillable")
    }

    @Test
    fun `one function comes back with the source somebody wrote`() {
        store(workspaceId, "isTeammate")

        val answer = tools.run(scope(), "orknux_function", """{"function":"isTeammate"}""")

        assertThat(answer).contains("typescript")
        // The annotated half: what the editor holds, and what a suggestion has
        // to be written against.
        assertThat(answer).contains("email: string")
    }

    @Test
    fun `a function in another workspace is not there to be read`() {
        val other = store(elsewhereId, "isBillable")

        val byName = tools.run(scope(), "orknux_function", """{"function":"isBillable"}""")
        val byId = tools.run(scope(), "orknux_function", """{"function":"${other.id}"}""")

        assertThat(byName).contains("There is no function called isBillable here")
        assertThat(byId).contains("There is no function called ${other.id} here")
    }
}
