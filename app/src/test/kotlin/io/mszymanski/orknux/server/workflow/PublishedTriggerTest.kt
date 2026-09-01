package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.workflow.execution.GraphNode
import io.mszymanski.orknux.workflow.execution.NodeKind
import io.mszymanski.orknux.workflow.execution.WorkflowGraph as RunnableGraph
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/**
 * Whether a published workflow can still say which trigger a node stands for.
 *
 * A run that begins at one of two triggers has to know which node the trigger
 * that fired is, and a snapshot is the only thing an event ever runs. So the id
 * has to survive publication — and, just as importantly, has to be allowed to be
 * missing: a snapshot is what a workflow was on the day it was published and is
 * never rewritten, so the ones already on disk say nothing about it and always
 * will. Those go on doing what they did, which is running both branches, until
 * somebody publishes the workflow again.
 */
class PublishedTriggerTest {

    private val mapper = JsonMapper.builder().build()

    @Test
    fun `a trigger node's id is written down and read back`() {
        val written = WorkflowSnapshot.write(
            RunnableGraph(
                workflowId = 1,
                name = "Answer the customer",
                nodes = listOf(
                    GraphNode(key = "slack", kind = NodeKind.TRIGGER, name = "Slack message", triggerId = 11),
                    GraphNode(key = "mail", kind = NodeKind.TRIGGER, name = "Mail arrived", triggerId = 22),
                ),
                edges = emptyList(),
            ),
            mapper,
        )

        val nodes = WorkflowSnapshot.read(written, mapper).nodes.associateBy { it.key }

        assertThat(nodes.getValue("slack").triggerId).isEqualTo(11)
        assertThat(nodes.getValue("mail").triggerId).isEqualTo(22)
    }

    @Test
    fun `a snapshot published before triggers were told apart says nothing about it`() {
        val graph = WorkflowSnapshot.read(
            """
            {"workflowId":1,"name":"Answer the customer","nodes":[
              {"key":"slack","kind":"TRIGGER","name":"Slack message"}
            ],"edges":[]}
            """,
            mapper,
        )

        // Null rather than a guess. A run cannot tell this node from any other
        // trigger, so it silences none of them and both branches go, exactly as
        // they did the day this was published.
        assertThat(graph.nodes.single().triggerId).isNull()
    }
}
