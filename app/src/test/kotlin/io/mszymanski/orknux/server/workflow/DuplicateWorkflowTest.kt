package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.trigger.TriggerFiringRepository
import io.mszymanski.orknux.server.trigger.WorkflowTriggerRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
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
 * The same workflow again, under another name.
 *
 * Issue #310. A workflow is the one thing here somebody edits while it is in
 * use, so trying a change meant redrawing it node by node or editing the one
 * that works.
 *
 * What is worth pinning is not that a copy exists but that it is the *same
 * graph*: the settings on a node, the mappings under it, where it sits and
 * which way each edge leaves by are all things a copy could plausibly lose, and
 * a duplicate that lost one would look like it worked and be a workflow that
 * did something else.
 *
 * And two things about what it is not. The copy is a draft, whatever the
 * original was - which is what makes copying a workflow with triggers on it
 * safe, since an event runs the published copy. And the trigger is pointed at
 * rather than copied, because two workflows may name one trigger and a copied
 * one would be a second webhook path nobody asked for.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DuplicateWorkflowTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val publications: WorkflowPublicationRepository,
    @Autowired val triggers: WorkflowTriggerRepository,
    @Autowired val firings: TriggerFiringRepository,
    @Autowired val objects: WorkflowObjectRepository,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun seed() {
        publications.deleteAll()
        logs.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        firings.deleteAll()
        triggers.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        objects.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "Support")).id)
    }

    /** The whole graph, node for node and edge for edge. */
    @Test
    fun `a duplicate carries the graph it was made from`() {
        val original = drawn("Triage")

        val copy = duplicate(assignmentOf(original))

        val theirs = nodes.findByWorkflowId(original).sortedBy { it.nodeKey }
        val ours = nodes.findByWorkflowId(copy).sortedBy { it.nodeKey }
        assertThat(ours).hasSameSizeAs(theirs)
        assertThat(ours.map { it.nodeKey }).isEqualTo(theirs.map { it.nodeKey })

        val branch = ours.single { it.nodeKey == "branch" }
        assertThat(branch.name).isEqualTo("Urgent?")
        assertThat(branch.yesLabel).isEqualTo("Escalate")
        assertThat(branch.noLabel).isEqualTo("File it")
        assertThat(branch.positionX).isEqualTo(240.0)
        assertThat(branch.positionY).isEqualTo(80.0)

        assertThat(edges.findByWorkflowId(copy).map { Triple(it.sourceKey, it.targetKey, it.branch) })
            .containsExactlyInAnyOrderElementsOf(
                edges.findByWorkflowId(original).map { Triple(it.sourceKey, it.targetKey, it.branch) },
            )
    }

    /**
     * The rows are separate rows.
     *
     * Copying the objects rather than re-pointing at them is the mistake that
     * would pass every assertion above and turn editing the copy into editing
     * the original.
     */
    @Test
    fun `editing the copy does not touch the original`() {
        val original = drawn("Triage")
        val copy = duplicate(assignmentOf(original))

        assertThat(nodes.findByWorkflowId(copy).map { it.id })
            .doesNotContainAnyElementsOf(nodes.findByWorkflowId(original).map { it.id })
        assertThat(edges.findByWorkflowId(copy).map { it.id })
            .doesNotContainAnyElementsOf(edges.findByWorkflowId(original).map { it.id })
    }

    /**
     * A published workflow copies as a draft.
     *
     * The reason this is safe to press on a workflow with triggers on it: an
     * arriving event runs the published copy, and this has never been published,
     * so nothing starts the duplicate until somebody says so.
     */
    @Test
    fun `the copy of a published workflow is a draft, and is published nowhere`() {
        val original = drawn("Triage")
        graphQlTester.document(
            """mutation { publishWorkflow(workspaceId: $workspaceId, workflowId: $original) { status } }""",
        ).execute().path("publishWorkflow.status").entity(String::class.java).isEqualTo("PUBLISHED")

        val copy = duplicate(assignmentOf(original))

        assertThat(workflows.findById(copy).orElseThrow().status).isEqualTo(WorkflowStatus.DRAFT)
        assertThat(publications.findAll().map { it.workflowId }).doesNotContain(copy)
        // And the original is still published, which a copy has no business changing.
        assertThat(workflows.findById(original).orElseThrow().status).isEqualTo(WorkflowStatus.PUBLISHED)
    }

    /** The trigger is one trigger, named twice. */
    @Test
    fun `the trigger is pointed at rather than copied`() {
        val original = drawn("Triage")
        val copy = duplicate(assignmentOf(original))

        val theirs = nodes.findByWorkflowId(original).single { it.nodeKey == "start" }.triggerId
        val ours = nodes.findByWorkflowId(copy).single { it.nodeKey == "start" }.triggerId
        assertThat(ours).isNotNull().isEqualTo(theirs)
        assertThat(triggers.findAll()).hasSize(1)
    }

    /** Pressed twice, which is the press somebody actually makes twice. */
    @Test
    fun `duplicating twice numbers the second copy rather than refusing`() {
        val original = drawn("Triage")
        val assignment = assignmentOf(original)

        assertThat(nameOf(duplicate(assignment))).isEqualTo("Triage (copy)")
        assertThat(nameOf(duplicate(assignment))).isEqualTo("Triage (copy 2)")
        assertThat(nameOf(duplicate(assignment))).isEqualTo("Triage (copy 3)")
    }

    /** A name of the caller's own, and a refusal when it is somebody else's. */
    @Test
    fun `a name that is taken is refused rather than quietly changed`() {
        val original = drawn("Triage")
        drawn("Escalation")

        graphQlTester.document(
            """mutation { duplicateWorkflow(id: ${assignmentOf(original)}, name: "Escalation") { workflowId } }""",
        ).execute().errors().expect { it.message?.contains("Escalation") == true }.verify()
    }

    private fun nameOf(workflowId: Long): String = workflows.findById(workflowId).orElseThrow().name

    private fun assignmentOf(workflowId: Long): Long =
        requireNotNull(assignments.findByWorkspaceIdAndWorkflowId(workspaceId, workflowId)?.id)

    private fun duplicate(assignmentId: Long): Long = graphQlTester.document(
        """mutation { duplicateWorkflow(id: $assignmentId) { workflowId } }""",
    ).execute().path("duplicateWorkflow.workflowId").entity(Long::class.java).get()

    /**
     * A workflow with something of everything a copy could lose: a trigger, a
     * condition with renamed exits, two branches out of it, and positions.
     */
    private fun drawn(name: String): Long {
        val workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "$name" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()

        val objectId = graphQlTester.document(
            """
            mutation {
              createObject(input: {
                workspaceId: $workspaceId, name: "${name}Ticket",
                properties: [{ name: "id", kind: STRING }]
              }) { id }
            }
            """,
        ).execute().path("createObject.id").entity(Long::class.java).get()

        val triggerId = graphQlTester.document(
            """
            mutation {
              createTrigger(input: {
                workspaceId: $workspaceId, name: "$name Created", type: WEBHOOK,
                webhookPath: "${name.lowercase()}/created", objectId: $objectId
              }) { id }
            }
            """,
        ).execute().path("createTrigger.id").entity(Long::class.java).get()

        val conditionId = graphQlTester.document(
            """
            mutation {
              createCondition(input: {
                workspaceId: $workspaceId, name: "${name}Urgent", type: SLACK,
                property: MESSAGE_TEXT, check: CONTAINS, values: ["urgent"]
              }) { id }
            }
            """,
        ).execute().path("createCondition.id").entity(Long::class.java).get()

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "start", kind: TRIGGER, name: "Created", triggerId: $triggerId, x: 0, y: 0 },
                  {
                    key: "branch", kind: CONDITION, name: "Urgent?", conditionId: $conditionId,
                    yesLabel: "Escalate", noLabel: "File it", x: 240, y: 80
                  },
                  { key: "yes", kind: OBJECT, name: "Escalated", objectId: $objectId, x: 480, y: 0 },
                  { key: "no", kind: OBJECT, name: "Filed", objectId: $objectId, x: 480, y: 160 }
                ],
                edges: [
                  { source: "start", target: "branch" },
                  { source: "branch", target: "yes", branch: YES },
                  { source: "branch", target: "no", branch: NO }
                ]
              }) { nodes { key } }
            }
            """,
        ).execute().path("saveWorkflowGraph.nodes").entityList(Map::class.java).hasSize(4)

        return workflowId
    }
}
