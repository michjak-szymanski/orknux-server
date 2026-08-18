package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.GraphVersion
import io.mszymanski.orknux.workflow.execution.WorkflowNotPublishedException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * What publishing is for.
 *
 * It used to be a word on a screen: a trigger fired every workflow with a node
 * instancing it, and the runner read the rows as they stood - so an event
 * arriving while somebody was halfway through drawing ran the half-drawn
 * graph, whatever the badge said. These pin the two halves of the fix: an
 * event runs the copy taken at publication, and a person pressing Run gets
 * what is on their screen.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class PublishedGraphTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val source: AppWorkflowGraphSource,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val publications: WorkflowPublicationRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private var workflowId: Long = 0

    @BeforeEach
    fun reset() {
        publications.deleteAll()
        assignments.deleteAll()
        nodes.deleteAll()
        workflows.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        val workflow = workflows.save(Workflow(name = "Answer the customer"))
        workflowId = requireNotNull(workflow.id)
        assignments.save(WorkspaceWorkflow(workspaceId = workspaceId, workflow = workflow))
    }

    /** One agent node, named, which is all a graph needs to be runnable. */
    private fun draw(nodeName: String) {
        graphQlTester.document(
            """mutation { saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                 nodes: [{ key: "one", kind: AGENT, name: "$nodeName", x: 0, y: 0 }], edges: []
               }) { status } }""",
        ).execute().path("saveWorkflowGraph.status").entity(String::class.java).isEqualTo("DRAFT")
    }

    private fun publish() {
        graphQlTester.document(
            """mutation { publishWorkflow(workspaceId: $workspaceId, workflowId: $workflowId) { status } }""",
        ).execute().path("publishWorkflow.status").entity(String::class.java).isEqualTo("PUBLISHED")
    }

    @Test
    fun `an edit after publishing does not change what an event runs`() {
        draw("Answer politely")
        publish()

        draw("Half-written change")

        // What a trigger, a schedule or the API would run.
        val live = source.graph(workspaceId, workflowId, GraphVersion.PUBLISHED)
        assertThat(live.nodes.single().name).isEqualTo("Answer politely")

        // And publishing again is what moves it on.
        publish()
        assertThat(source.graph(workspaceId, workflowId, GraphVersion.PUBLISHED).nodes.single().name)
            .isEqualTo("Half-written change")
    }

    @Test
    fun `a person pressing Run gets the graph on their screen`() {
        draw("Answer politely")
        publish()
        draw("Half-written change")

        assertThat(source.graph(workspaceId, workflowId, GraphVersion.DRAFT).nodes.single().name)
            .isEqualTo("Half-written change")
    }

    /**
     * Re-running repeats what ran.
     *
     * A rerun is recorded as manual, because a person pressed it - and manual
     * means the draft, so without saying which copy to use, re-running what a
     * webhook did would run a graph that webhook never touched.
     */
    @Test
    fun `a rerun asks for the copy the original ran`() {
        draw("Answer politely")
        publish()
        draw("Half-written change")

        assertThat(source.graph(workspaceId, workflowId, GraphVersion.PUBLISHED).nodes.single().name)
            .isEqualTo("Answer politely")
        assertThat(source.graph(workspaceId, workflowId, GraphVersion.DRAFT).nodes.single().name)
            .isEqualTo("Half-written change")
    }

    @Test
    fun `a workflow nobody has published has nothing to run`() {
        draw("Not ready")

        assertThatThrownBy { source.graph(workspaceId, workflowId, GraphVersion.PUBLISHED) }
            .isInstanceOf(WorkflowNotPublishedException::class.java)
    }

    /**
     * The upgrade case: live when snapshots arrived, so marked published with
     * nothing to point at. Refusing to run those would take a working
     * installation down at the moment it upgraded.
     */
    @Test
    fun `a workflow published before snapshots existed keeps running, and is snapshotted`() {
        draw("From before")
        publish()
        publications.deleteAll()

        assertThat(source.graph(workspaceId, workflowId, GraphVersion.PUBLISHED).nodes.single().name)
            .isEqualTo("From before")
        // Taken once, then read like any other.
        assertThat(publications.findById(workflowId)).isPresent()
    }

    @Test
    fun `publishing records who did it`() {
        draw("Answer politely")
        publish()

        assertThat(publications.findById(workflowId).get().publishedBy).isEqualTo("alice")
    }
}
