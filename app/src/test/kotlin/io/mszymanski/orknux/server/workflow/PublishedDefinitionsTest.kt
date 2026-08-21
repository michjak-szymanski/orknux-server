package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.trigger.WorkflowTriggerRepository
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
    @Autowired val agents: AgentRepository,
    @Autowired val triggers: WorkflowTriggerRepository,
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
        agents.deleteAll()
        triggers.deleteAll()
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

        // Found by the workflow it belongs to rather than by key: what a
        // publication is keyed by is the publishing side's business, and this
        // test is about what is inside the copy.
        val snapshot = publications.findAll().single { it.workflowId == workflowId }.graph

        // The node's pointer, kept. Written by Postgres' jsonb, so with a space
        // after the colon; matched on the pair rather than on the text.
        assertThat(snapshot.replace(" ", "")).contains("\"actionId\":$actionId")
        // What it points at, not kept: neither the function's body nor its name.
        assertThat(snapshot).doesNotContain("said")
        assertThat(snapshot).doesNotContain("answer")
    }

    /**
     * Deleting is the same rule read the other way, and now both halves of the
     * reference are guarded alike.
     *
     * A function an action calls cannot be deleted at all - `deleteFunction`
     * refuses and names the caller. The action itself used to be held in place
     * by nothing, so it could be deleted while a published workflow's copy still
     * named it, and that copy went on naming it because it is a copy and nothing
     * cascades into it. The run said "the action Act ran has been deleted",
     * which is the right end of a bad situation and still a published workflow
     * that stopped working with nobody touching the workflow.
     *
     * Both ends refuse now, and the refusal names what is in the way.
     */
    @Test
    fun `an action a published workflow runs cannot be deleted either`() {
        val functionId = function("""export default function answer() { return { said: "first" }; }""")
        val actionId = functionAction(functionId)
        graph(actionId)
        publish()

        // The function is held in place by the action that calls it.
        graphQlTester.document("""mutation { deleteFunction(id: $functionId) }""")
            .execute()
            .errors()
            .satisfy { errors -> assertThat(errors.single().message).contains("answer is called by Act") }

        // And the action by the workflow that runs it, which is named.
        graphQlTester.document("""mutation { deleteAction(id: $actionId) }""")
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors.single().message).contains("Act is used by the published workflow Answer")
            }

        // Nothing was deleted, so the published run still answers.
        assertThat(published().output).isEqualTo("""{"said":"first"}""")
    }

    /**
     * The half a draft-only guard would have missed.
     *
     * The node is taken off the canvas, which puts the workflow back into draft
     * and leaves no drawn node naming the action at all. The published copy is
     * untouched by that - it is what a trigger still runs - and it still names
     * the action. A guard that asked the graph tables would find nothing here
     * and let the delete through.
     */
    @Test
    fun `an action only the published copy still names cannot be deleted`() {
        val functionId = function("""export default function answer() { return { said: "first" }; }""")
        val actionId = functionAction(functionId)
        graph(actionId)
        publish()

        // The canvas is redrawn without it. Nothing republishes.
        val second = graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Wait", type: WAIT, subtype: TIME, durationSeconds: 1
              }) { id }
            }
            """,
        ).execute().path("createAction.id").entity(Long::class.java).get()
        graph(second)

        assertThat(nodes.findByActionId(actionId)).isEmpty()

        graphQlTester.document("""mutation { deleteAction(id: $actionId) }""")
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors.single().message).contains("Act is used by the published workflow Answer")
            }
    }

    /**
     * And the guard is a guard, not a ban.
     *
     * An action no workflow names deletes exactly as it always did. Said out
     * loud because a refusal that fires on everything is the way this change
     * would go wrong: the delete button in the list is how a workspace tidies
     * up, and most of what it removes was never wired to anything.
     */
    @Test
    fun `an action no workflow names still deletes`() {
        val functionId = function("""export default function answer() { return { said: "first" }; }""")
        graph(functionAction(functionId))
        publish()

        val spare = graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Spare", type: WAIT, subtype: TIME, durationSeconds: 1
              }) { id }
            }
            """,
        ).execute().path("createAction.id").entity(Long::class.java).get()

        graphQlTester.document("""mutation { deleteAction(id: $spare) }""")
            .execute().path("deleteAction").entity(Boolean::class.java).isEqualTo(true)
    }

    /**
     * The way out, which a refusal is only fair if it exists.
     *
     * Publishing again over the copy that named it is what releases the action:
     * the newest published copy is what runs, and it does not name it any more.
     */
    @Test
    fun `publishing again releases an action the older published copy named`() {
        val functionId = function("""export default function answer() { return { said: "first" }; }""")
        val actionId = functionAction(functionId)
        graph(actionId)
        publish()

        val second = graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Wait", type: WAIT, subtype: TIME, durationSeconds: 1
              }) { id }
            }
            """,
        ).execute().path("createAction.id").entity(Long::class.java).get()
        graph(second)
        publish()

        graphQlTester.document("""mutation { deleteAction(id: $actionId) }""")
            .execute().path("deleteAction").entity(Boolean::class.java).isEqualTo(true)
    }

    /**
     * The same guard, one node kind over.
     *
     * An agent id is the second of the three a published copy carries, and
     * `deleteAgent` said in a comment that it was refused while a node instanced
     * it while doing nothing of the kind - [AgentInUseException] was declared and
     * never thrown. The sentence and the behaviour now agree.
     */
    @Test
    fun `an agent a published workflow instances cannot be deleted`() {
        val agentId = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Answerer", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [{ key: "ask", kind: AGENT, name: "Ask", agentId: $agentId, x: 0, y: 0 }], edges: []
              }) { nodes { key } }
            }
            """,
        ).execute().path("saveWorkflowGraph.nodes[0].key").entity(String::class.java).isEqualTo("ask")
        publish()

        graphQlTester.document("""mutation { deleteAgent(id: $agentId) }""")
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors.single().message).contains("Answerer is used by the published workflow Answer")
            }
    }

    /**
     * And the one the published copy cannot answer for.
     *
     * A trigger id is not written into the copy at all, so the question of
     * whether a workflow starts from a trigger is a question about the drawn
     * graph. Deleting it would not break a run - it would stop the workflow
     * being started, silently, which is why the refusal is here too.
     */
    @Test
    fun `a trigger a workflow starts from cannot be deleted`() {
        val triggerId = graphQlTester.document(
            """
            mutation {
              createTrigger(input: {
                workspaceId: $workspaceId, name: "Nightly", type: SCHEDULED, cron: "0 0 3 * * *"
              }) { id }
            }
            """,
        ).execute().path("createTrigger.id").entity(Long::class.java).get()

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [{ key: "start", kind: TRIGGER, name: "Start", triggerId: $triggerId, x: 0, y: 0 }], edges: []
              }) { nodes { key } }
            }
            """,
        ).execute().path("saveWorkflowGraph.nodes[0].key").entity(String::class.java).isEqualTo("start")

        graphQlTester.document("""mutation { deleteTrigger(id: $triggerId) }""")
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors.single().message).contains("Nightly is used by the workflow Answer")
            }
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
