package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.GraphVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * A workflow's versions are its publications.
 *
 * The owner's rule: a component with a draft is versioned by publishing, and a
 * draft is not a version — the same way the commits going onto main are not
 * releases. So `workflow_publication` stopped being one row per workflow
 * overwritten on every publish, and started being the history.
 *
 * Two things these pin, because both are easy to get wrong and neither would
 * fail loudly. What runs is always the newest publication, so restoring
 * publishes again rather than reviving a row. And a restore does not touch the
 * draft, which is not versioned and would have nothing to be recovered from.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WorkflowPublicationHistoryTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val source: AppWorkflowGraphSource,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val publications: WorkflowPublicationRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private var workflowId: Long = 0

    @BeforeEach
    fun reset() {
        publications.deleteAll()
        assignments.deleteAll()
        edges.deleteAll()
        nodes.deleteAll()
        workflows.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        val workflow = workflows.save(Workflow(name = "Answer the customer"))
        workflowId = requireNotNull(workflow.id)
        assignments.save(WorkspaceWorkflow(workspaceId = workspaceId, workflow = workflow))
    }

    /** It used to overwrite. What a workflow ran last month was unanswerable. */
    @Test
    fun `publishing twice keeps both, and the newest is what runs`() {
        draw("First answer")
        publish()
        draw("Second answer")
        publish()

        assertThat(publications.findAll().filter { it.workflowId == workflowId }).hasSize(2)
        assertThat(source.graph(workspaceId, workflowId, GraphVersion.PUBLISHED).nodes.single().name)
            .isEqualTo("Second answer")

        graphQlTester.document(
            """query { workflowPublications(workspaceId: $workspaceId, workflowId: $workflowId)
               { id publishedBy current restoredFrom } }""",
        ).execute()
            .path("workflowPublications").entityList(Any::class.java).hasSize(2)
            .path("workflowPublications[0].current").entity(Boolean::class.java).isEqualTo(true)
            .path("workflowPublications[1].current").entity(Boolean::class.java).isEqualTo(false)
            .path("workflowPublications[0].publishedBy").entity(String::class.java).isEqualTo("alice")
    }

    /**
     * Restoring is a new publication, not a resurrection.
     *
     * The history only grows, so there is a record that somebody rolled back —
     * and what runs is still simply "the newest one", which is the rule the
     * runner already uses and the only one that cannot go stale.
     */
    @Test
    fun `restoring an older publication publishes it again`() {
        draw("First answer")
        publish()
        val first = publicationIds().last()
        draw("Second answer")
        publish()

        graphQlTester.document(
            """mutation { restoreWorkflowPublication(workspaceId: $workspaceId, publicationId: $first)
               { status } }""",
        ).execute().path("restoreWorkflowPublication.status").entity(String::class.java)
            .satisfies { assertThat(it).isNotBlank() }

        assertThat(publications.findAll().filter { it.workflowId == workflowId }).hasSize(3)
        assertThat(source.graph(workspaceId, workflowId, GraphVersion.PUBLISHED).nodes.single().name)
            .isEqualTo("First answer")

        graphQlTester.document(
            """query { workflowPublications(workspaceId: $workspaceId, workflowId: $workflowId)
               { current restoredFrom } }""",
        ).execute()
            .path("workflowPublications[0].current").entity(Boolean::class.java).isEqualTo(true)
            .path("workflowPublications[0].restoredFrom").entity(Long::class.java).isEqualTo(first)
    }

    /**
     * The draft is what somebody is in the middle of, and it is not versioned —
     * so a restore that overwrote it would destroy unpublished work with
     * nothing anywhere to get it back from.
     */
    @Test
    fun `restoring leaves the draft alone and says the two differ`() {
        draw("First answer")
        publish()
        val first = publicationIds().last()
        draw("Still being drawn")

        graphQlTester.document(
            """mutation { restoreWorkflowPublication(workspaceId: $workspaceId, publicationId: $first)
               { status nodes { name } } }""",
        ).execute()
            // The draft on screen is untouched...
            .path("restoreWorkflowPublication.nodes[0].name").entity(String::class.java)
            .isEqualTo("Still being drawn")
            // ...and the badge says so, because what runs is not what is drawn.
            .path("restoreWorkflowPublication.status").entity(String::class.java).isEqualTo("DRAFT")

        assertThat(source.graph(workspaceId, workflowId, GraphVersion.PUBLISHED).nodes.single().name)
            .isEqualTo("First answer")
    }

    /** Restoring what the draft already says is a workflow that is published. */
    @Test
    fun `restoring the graph the draft already holds says published`() {
        draw("First answer")
        publish()
        val first = publicationIds().last()

        graphQlTester.document(
            """mutation { restoreWorkflowPublication(workspaceId: $workspaceId, publicationId: $first)
               { status } }""",
        ).execute().path("restoreWorkflowPublication.status").entity(String::class.java).isEqualTo("PUBLISHED")
    }

    /** A publication of another workspace's workflow is not there to be read. */
    @Test
    fun `a publication is only reachable through the workspace that has the workflow`() {
        draw("First answer")
        publish()
        val mine = publicationIds().first()
        val elsewhere = requireNotNull(workspaces.save(Workspace(name = "sales")).id)

        graphQlTester.document(
            """query { workflowPublicationGraph(workspaceId: $elsewhere, publicationId: $mine) }""",
        ).execute().errors().satisfy { errors -> assertThat(errors).isNotEmpty() }
    }

    // ---------------------------------------------------------------- helpers

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

    /** Newest first, which is the order the history is read in. */
    private fun publicationIds(): List<Long> = graphQlTester.document(
        """query { workflowPublications(workspaceId: $workspaceId, workflowId: $workflowId) { id } }""",
    ).execute().path("workflowPublications[*].id").entityList(Long::class.java).get()
}
