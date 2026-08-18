package io.mszymanski.orknux.workflow.execution

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import java.time.Duration
import java.time.OffsetDateTime

/**
 * A graph the tests hand over directly, instead of one read from workflow rows.
 * What the engine does with a graph is this module's business; where the graph
 * came from is the app's.
 */
class FakeWorkflowGraphSource : WorkflowGraphSource {

    val graphs = mutableMapOf<Long, WorkflowGraph>()

    /** One graph per workflow: a fake has no draft to tell from a publication. */
    override fun graph(workspaceId: Long, workflowId: Long, version: GraphVersion): WorkflowGraph =
        graphs[workflowId] ?: throw WorkflowNotFoundException(workspaceId, workflowId)
}

/**
 * A runner for the tests to steer: nodes named `ok…` do work and hand something
 * on, `wait…` parks for an hour the first time and is done the second, `boom`
 * fails. Ahead of [UnimplementedNodeRunner], which claims everything.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
class ScriptedNodeRunner : NodeRunner {

    override fun supports(kind: NodeKind): Boolean = true

    override fun run(step: ExecutionStep, input: String?, trigger: String?): StepResult = when {
        step.name == "boom" -> throw IllegalStateException("boom has no answer")
        step.name.startsWith("ok") -> StepResult(StepStatus.COMPLETED, "${step.name} did the work")
        step.name.startsWith("wait") -> park(step)
        else -> UnimplementedNodeRunner().run(step, input)
    }

    /**
     * An hour is far longer than any timeout a test worker is registered with,
     * so a run that gets past this parked rather than blocked.
     */
    private fun park(step: ExecutionStep): StepResult {
        if (step.waitUntil != null) return StepResult(StepStatus.COMPLETED, "${step.name} did the work")

        step.waitUntil = OffsetDateTime.now().plus(WAIT)
        return StepResult.waiting(WAIT, "${step.name} is waiting")
    }

    private companion object {
        val WAIT: Duration = Duration.ofHours(1)
    }
}

@TestConfiguration
class ExecutionTestConfig {

    @Bean
    @Primary
    fun fakeWorkflowGraphSource(): WorkflowGraphSource = FakeWorkflowGraphSource()

    @Bean
    fun scriptedNodeRunner(): NodeRunner = ScriptedNodeRunner()
}
