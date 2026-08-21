package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionService
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStepView
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.GraphVersion
import io.mszymanski.orknux.workflow.execution.StartExecutionInput
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Where publishing stops.
 *
 * Publishing takes a copy of the graph, and [PublishedGraphTest] pins what that
 * copy is worth: the nodes, their bindings and the edges, frozen, so redrawing
 * the canvas afterwards cannot change what a trigger runs. What the copy holds
 * of the things those nodes *call* is an id and nothing more - `actionId`,
 * `agentId`, `conditionId` - and every node runner resolves its id against the
 * live row at the moment the step runs.
 *
 * So the line publishing draws goes around the shape of a workflow and not
 * around its behaviour. Editing a function a published workflow calls changes
 * what that workflow does, immediately, with nobody republishing anything and
 * no screen anywhere saying so. That is arguably what a workspace wants - a fix
 * to a shared function reaching every caller at once is most of the reason
 * functions are shared - but it is not what the word "published" implies, and
 * the comment on `ExecutionStep.actionId` currently claims the stronger thing:
 * "copied when the run started: editing the action afterwards does not change
 * what this run did". Only the id is copied.
 *
 * These are here so the true sentence is written down as behaviour rather than
 * believed. If component versioning ever puts a definition inside the snapshot,
 * these are what will fail and say what moved.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class PublishedDefinitionsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val runs: ExecutionService,
    @Autowired val publications: WorkflowPublicationRepository,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private var workflowId: Long = 0

    @BeforeEach
    fun reset() {
        logs.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        publications.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        actions.deleteAll()
        functions.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Answer" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
    }

    /**
     * The one a workspace would be surprised by.
     *
     * The workflow is published, nobody touches the canvas, and the run a
     * trigger starts answers differently - because the function it calls was
     * edited in between.
     */
    @Test
    fun `a published workflow calls the function as it is now, not as it was when published`() {
        val functionId = function("""export default function answer() { return { said: "first" }; }""")
        graph(functionAction(functionId))
        publish()

        assertThat(published().output).isEqualTo("""{"said":"first"}""")

        // The graph is untouched. Only the function the node calls is edited.
        rewrite(functionId, """export default function answer() { return { said: "second" }; }""")

        assertThat(published().output).isEqualTo("""{"said":"second"}""")
    }

    /**
     * And why: what publishing wrote down never had the function in it.
     *
     * The snapshot is the graph as the execution module reads one - keys, kinds,
     * bindings, edges, and the ids a node points at. A definition's text is not
     * in there to be frozen, so there is nothing publishing could have run in
     * place of the row that is there now.
     */
    @Test
    fun `the snapshot holds the reference and not the definition`() {
        val functionId = function("""export default function answer() { return { said: "first" }; }""")
        val actionId = functionAction(functionId)
        graph(actionId)
        publish()

        val snapshot = publications.findById(workflowId).orElseThrow().graph

        // The node's pointer, kept. Written by Postgres' jsonb, so with a space
        // after the colon; matched on the pair rather than on the text.
        assertThat(snapshot.replace(" ", "")).contains("\"actionId\":$actionId")
        // What it points at, not kept: neither the function's body nor its name.
        assertThat(snapshot).doesNotContain("said")
        assertThat(snapshot).doesNotContain("answer")
    }

    /**
     * Deleting is the same rule read the other way, and the two halves of the
     * reference are not guarded alike.
     *
     * A function an action calls cannot be deleted at all - `deleteFunction`
     * refuses and names the caller. The action itself has no such guard, so it
     * can be deleted while a published workflow's snapshot still names it, and
     * the snapshot goes on naming it because a snapshot is a copy and nothing
     * cascades into it. The run is left holding an id that resolves to nothing.
     *
     * It says so, which is the right end of a bad situation: a step reporting
     * that its action is gone beats one that quietly does nothing. But it is
     * still a published workflow that stopped working without anybody touching
     * the workflow.
     */
    @Test
    fun `an action deleted after publishing leaves the published run with nothing to call`() {
        val functionId = function("""export default function answer() { return { said: "first" }; }""")
        val actionId = functionAction(functionId)
        graph(actionId)
        publish()

        // The function is held in place by the action that calls it.
        graphQlTester.document("""mutation { deleteFunction(id: $functionId) }""")
            .execute()
            .errors()
            .satisfy { errors -> assertThat(errors.single().message).contains("answer is called by Act") }

        // The action is held in place by nothing.
        graphQlTester.document("""mutation { deleteAction(id: $actionId) }""")
            .execute().path("deleteAction").entity(Boolean::class.java).isEqualTo(true)

        assertThat(published().output).contains("has been deleted")
    }

    /** A function the workspace owns, returning an object and taking nothing. */
    private fun function(source: String): Long = graphQlTester.document(
        """
        mutation {
          createFunction(input: {
            workspaceId: $workspaceId, name: "answer", returnType: MAP,
            source: ${'"'}${'"'}${'"'}$source${'"'}${'"'}${'"'},
            typescript: ${'"'}${'"'}${'"'}$source${'"'}${'"'}${'"'},
            params: []
          }) { id }
        }
        """,
    ).execute().path("createFunction.id").entity(Long::class.java).get()

    /** The same function, saying something else. Nothing else about it moves. */
    private fun rewrite(functionId: Long, source: String) {
        graphQlTester.document(
            """
            mutation {
              updateFunction(id: $functionId, input: {
                source: ${'"'}${'"'}${'"'}$source${'"'}${'"'}${'"'},
                typescript: ${'"'}${'"'}${'"'}$source${'"'}${'"'}${'"'}
              }) { id }
            }
            """,
        ).execute().path("updateFunction.id").entity(Long::class.java).isEqualTo(functionId)
    }

    private fun functionAction(functionId: Long): Long = graphQlTester.document(
        """
        mutation {
          createAction(input: {
            workspaceId: $workspaceId, name: "Act", type: EXECUTE, subtype: FUNCTION, functionId: $functionId
          }) { id }
        }
        """,
    ).execute().path("createAction.id").entity(Long::class.java).get()

    /** One action node, which is the whole workflow. */
    private fun graph(actionId: Long) {
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [{ key: "act", kind: ACTION, name: "Act", actionId: $actionId, x: 0, y: 0 }], edges: []
              }) { nodes { key actionId } }
            }
            """,
        ).execute().path("saveWorkflowGraph.nodes[0].actionId").entity(Long::class.java).isEqualTo(actionId)
    }

    private fun publish() {
        graphQlTester.document(
            """mutation { publishWorkflow(workspaceId: $workspaceId, workflowId: $workflowId) { status } }""",
        ).execute().path("publishWorkflow.status").entity(String::class.java).isEqualTo("PUBLISHED")
    }

    /**
     * A run of the published copy, the way a trigger starts one.
     *
     * Not `startExecution` through the API: that is a person pressing Run, which
     * means the draft, and the draft is not what any of this is about.
     */
    private fun published(): ExecutionStepView {
        val started = runs.startExecution(
            StartExecutionInput(
                workspaceId = workspaceId,
                workflowId = workflowId,
                trigger = ExecutionTrigger.WEBHOOK,
                version = GraphVersion.PUBLISHED,
            ),
        )
        return requireNotNull(runs.execution(started.id)).steps.single()
    }
}
