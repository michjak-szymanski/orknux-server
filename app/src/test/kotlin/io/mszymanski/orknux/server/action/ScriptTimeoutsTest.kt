package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Two waits, and two numbers.
 *
 * A function runs where nobody is waiting in particular - a workflow's step, a
 * condition being decided, a webhook answering - while a tool runs with a model
 * stopped mid-turn and, in a chat, a person watching it happen. One setting
 * bounded both for a long time, under a heading that said Agents, which made it
 * a number that suited neither and described neither.
 *
 * What is pinned here is that the two are actually separate: a workspace that
 * sets one does not move the other, and each caller asks for the one its run
 * is. The callers themselves are named in `ScriptTimeouts`; this holds the
 * resolver to its side of that bargain.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ScriptTimeoutsTest(
    @Autowired val timeouts: ScriptTimeouts,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
) {

    @Test
    fun `a workspace bounds its functions and its tools separately`() {
        val workspace = workspaces.save(Workspace(name = "split", functionTimeoutSeconds = 30, toolTimeoutSeconds = 5))
        val id = requireNotNull(workspace.id)

        assertThat(timeouts.forFunction(ownSeconds = null, workspaceId = id)).isEqualTo(30_000)
        assertThat(timeouts.forTool(ownSeconds = null, workspaceId = id)).isEqualTo(5_000)
    }

    /**
     * What a function or a tool says about itself wins, which is what makes
     * the workspace's numbers defaults rather than ceilings.
     */
    @Test
    fun `a run that carries its own number keeps it`() {
        val id = requireNotNull(
            workspaces.save(Workspace(name = "own", functionTimeoutSeconds = 30, toolTimeoutSeconds = 5)).id,
        )

        assertThat(timeouts.forFunction(ownSeconds = 2, workspaceId = id)).isEqualTo(2_000)
        assertThat(timeouts.forTool(ownSeconds = 90, workspaceId = id)).isEqualTo(90_000)
    }

    /**
     * Nothing decided is null rather than a number, which the sandbox reads as
     * the installation's bound - one place holds that, and it is not this.
     */
    @Test
    fun `a workspace that has decided nothing says nothing`() {
        val id = requireNotNull(workspaces.save(Workspace(name = "quiet")).id)

        assertThat(timeouts.forFunction(ownSeconds = null, workspaceId = id)).isNull()
        assertThat(timeouts.forTool(ownSeconds = null, workspaceId = id)).isNull()
        assertThat(timeouts.forFunction(ownSeconds = null, workspaceId = null)).isNull()
    }

    /** And setting one through the API leaves the other exactly as it was. */
    @Test
    fun `setting the tool timeout does not move the function timeout`() {
        val id = requireNotNull(workspaces.save(Workspace(name = "apart", functionTimeoutSeconds = 45)).id)

        graphQlTester.document(
            """mutation { setWorkspaceToolTimeout(workspaceId: $id, seconds: 7) {
                 functionTimeoutSeconds toolTimeoutSeconds
               } }""",
        ).execute()
            .path("setWorkspaceToolTimeout.toolTimeoutSeconds").entity(Int::class.java).isEqualTo(7)

        assertThat(timeouts.forFunction(ownSeconds = null, workspaceId = id)).isEqualTo(45_000)
        assertThat(timeouts.forTool(ownSeconds = null, workspaceId = id)).isEqualTo(7_000)

        // And emptying it is a real answer: back to the installation's bound,
        // rather than back to whatever the other box says.
        graphQlTester.document(
            """mutation { setWorkspaceToolTimeout(workspaceId: $id, seconds: null) { toolTimeoutSeconds } }""",
        ).execute()

        assertThat(timeouts.forTool(ownSeconds = null, workspaceId = id)).isNull()
        assertThat(timeouts.forFunction(ownSeconds = null, workspaceId = id)).isEqualTo(45_000)
    }
}
