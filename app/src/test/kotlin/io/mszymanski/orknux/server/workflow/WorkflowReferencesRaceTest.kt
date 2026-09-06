package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.workflow.execution.GraphVersion
import io.mszymanski.orknux.workflow.execution.WorkflowGraph as RunnableGraph
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * Deleting a condition failed with "Workflow 424 is not assigned to workspace 9".
 *
 * The caller was deleting a **condition** and had never named workflow 424. The
 * runs either side of it succeeded on the same code, so it was intermittent
 * rather than a plain bug in the delete path. Issue #194.
 *
 * The shape, once found: the delete asks what still uses the condition, which
 * lists the workspace's assignments and then reads each workflow's published
 * graph - and `graph` checks the assignment *again* for itself, raising when it
 * has gone. Between the list and the read, another transaction removing a
 * workflow leaves a row in hand whose assignment no longer exists. A browser
 * check tidying up does exactly that, which is how it was noticed.
 *
 * A workflow that has stopped being this workspace's is not a workflow using
 * this condition. So the assignment is asked as a question before the graph is
 * read, and the answer is no nodes.
 *
 * Fakes rather than a database, because the window is between two statements
 * and cannot be opened on demand: what is reproduced here is the state that
 * window produces - a listed assignment whose existence check says no.
 */
class WorkflowReferencesRaceTest {

    private val assignments = mock(WorkspaceWorkflowRepository::class.java)
    private val nodes = mock(WorkflowNodeRepository::class.java)
    private val graphs = mock(AppWorkflowGraphSource::class.java)

    private val references = WorkflowReferences(assignments, nodes, graphs)

    private val workflow = Workflow(id = 424, name = "Answer a question asked in Slack")

    @Test
    fun `a workflow unassigned between the list and the read is not a dependant, and does not raise`() {
        // Listed: the read that happened a moment ago still has it.
        `when`(assignments.findByWorkspaceId(9)).thenReturn(
            listOf(WorkspaceWorkflow(id = 1, workspaceId = 9, workflow = workflow)),
        )
        `when`(graphs.published(424)).thenReturn(true)
        // Gone: the assignment was removed in between, which is the whole race.
        `when`(assignments.existsByWorkspaceIdAndWorkflowId(9, 424)).thenReturn(false)
        `when`(nodes.findByWorkflowId(424)).thenReturn(emptyList())

        assertThatCode { references.toCondition(9, 77) }.doesNotThrowAnyException()
        assertThat(references.toCondition(9, 77)).isEmpty()

        // And the graph was never asked, which is what would have raised.
        verify(graphs, never()).graph(9, 424, GraphVersion.PUBLISHED)
    }

    /**
     * The ordinary case still reads the graph.
     *
     * A guard that answered "not assigned" for everything would make every
     * delete succeed and every published workflow invisible to it, which is a
     * worse bug than the one being fixed.
     */
    @Test
    fun `a workflow this workspace still has is read as it always was`() {
        `when`(assignments.findByWorkspaceId(9)).thenReturn(
            listOf(WorkspaceWorkflow(id = 1, workspaceId = 9, workflow = workflow)),
        )
        `when`(graphs.published(424)).thenReturn(true)
        `when`(assignments.existsByWorkspaceIdAndWorkflowId(9, 424)).thenReturn(true)
        `when`(nodes.findByWorkflowId(424)).thenReturn(emptyList())
        `when`(graphs.graph(9, 424, GraphVersion.PUBLISHED)).thenReturn(
            RunnableGraph(workflowId = 424, name = "Answer", nodes = emptyList(), edges = emptyList()),
        )

        references.toCondition(9, 77)

        verify(graphs).graph(9, 424, GraphVersion.PUBLISHED)
    }
}
