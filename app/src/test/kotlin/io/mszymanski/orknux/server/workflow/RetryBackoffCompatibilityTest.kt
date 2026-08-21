package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.workflow.execution.GraphNode
import io.mszymanski.orknux.workflow.execution.NodeKind
import io.mszymanski.orknux.workflow.execution.WorkflowGraph as RunnableGraph
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/**
 * What a graph published before the backoff was a curve still means.
 *
 * The node table was migrated - V182 wrote EXPONENTIAL out as a multiplier of
 * two on the way past - but a published snapshot is not a table. It is what a
 * workflow was on the day somebody published it, kept so a run can be told
 * exactly what it was told then, and it is never rewritten. So the ones already
 * on disk say `retryBackoff: EXPONENTIAL` and always will, and the reader is the
 * only thing that can go on understanding them.
 *
 * These are the two sentences that matter: a snapshot that doubled goes on
 * doubling, and a snapshot that said nothing about a curve goes on repeating its
 * wait. Neither is a claim about the panel or about a run - both are about a
 * workflow published last week meaning today what it meant when it was published.
 */
class RetryBackoffCompatibilityTest {

    private val mapper = JsonMapper.builder().build()

    /**
     * The whole of the compatibility claim: EXPONENTIAL was the wait times two
     * each time, and a multiplier of two is the wait times two each time.
     */
    @Test
    fun `a snapshot that says EXPONENTIAL is read as a multiplier of two`() {
        val node = readBack(
            """
            {"key":"post","kind":"ACTION","name":"Post it",
             "retryAttempts":4,"retryBackoffSeconds":30,"retryBackoff":"EXPONENTIAL"}
            """,
        )

        assertThat(node.retryAttempts).isEqualTo(4)
        assertThat(node.retryBackoffSeconds).isEqualTo(30)
        assertThat(node.retryMultiplier).isEqualTo(2.0)
        // And nothing invented alongside it: the ceiling, the jitter and the
        // budget did not exist when this was published, so the node has none.
        assertThat(node.retryMaxWaitSeconds).isNull()
        assertThat(node.retryJitter).isNull()
        assertThat(node.retryBudgetSeconds).isNull()
    }

    /** FIXED was the wait repeated, and no multiplier is the wait repeated. */
    @Test
    fun `a snapshot that says FIXED is read as no multiplier at all`() {
        val node = readBack(
            """
            {"key":"post","kind":"ACTION","name":"Post it",
             "retryAttempts":3,"retryBackoffSeconds":45,"retryBackoff":"FIXED"}
            """,
        )

        assertThat(node.retryBackoffSeconds).isEqualTo(45)
        assertThat(node.retryMultiplier).isNull()
    }

    /**
     * And the oldest snapshots of all, from before there was a word for the
     * curve because there was only one of them.
     */
    @Test
    fun `a snapshot from before curves existed keeps the wait it always had`() {
        val node = readBack(
            """{"key":"post","kind":"ACTION","name":"Post it","retryAttempts":3,"retryBackoffSeconds":10}""",
        )

        assertThat(node.retryAttempts).isEqualTo(3)
        assertThat(node.retryBackoffSeconds).isEqualTo(10)
        assertThat(node.retryMultiplier).isNull()
    }

    /** A backoff written today survives the round trip whole. */
    @Test
    fun `every number of a backoff is written down and read back`() {
        val written = WorkflowSnapshot.write(
            RunnableGraph(
                workflowId = 1,
                name = "Answer the customer",
                nodes = listOf(
                    GraphNode(
                        key = "post",
                        kind = NodeKind.ACTION,
                        name = "Post it",
                        retryAttempts = 5,
                        retryBackoffSeconds = 20,
                        retryMultiplier = 1.5,
                        retryMaxWaitSeconds = 120,
                        retryJitter = 0.25,
                        retryBudgetSeconds = 600,
                    ),
                ),
                edges = emptyList(),
            ),
            mapper,
        )

        val node = WorkflowSnapshot.read(written, mapper).nodes.single()

        assertThat(node.retryAttempts).isEqualTo(5)
        assertThat(node.retryBackoffSeconds).isEqualTo(20)
        assertThat(node.retryMultiplier).isEqualTo(1.5)
        assertThat(node.retryMaxWaitSeconds).isEqualTo(120)
        assertThat(node.retryJitter).isEqualTo(0.25)
        assertThat(node.retryBudgetSeconds).isEqualTo(600)
    }

    /** One node, as a snapshot of that shape would have held it. */
    private fun readBack(node: String): GraphNode = WorkflowSnapshot
        .read("""{"workflowId":1,"name":"Answer the customer","nodes":[$node],"edges":[]}""", mapper)
        .nodes
        .single()
}
