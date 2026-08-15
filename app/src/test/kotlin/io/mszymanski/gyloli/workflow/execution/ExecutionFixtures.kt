package io.mszymanski.gyloli.workflow.execution

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order

/**
 * A graph the tests hand over directly, instead of one read from workflow rows.
 * What the engine does with a graph is this module's business; where the graph
 * came from is the app's.
 */
class FakeWorkflowGraphSource : WorkflowGraphSource {

    val graphs = mutableMapOf<Long, WorkflowGraph>()

    override fun graph(teamId: Long, workflowId: Long): WorkflowGraph =
        graphs[workflowId] ?: throw WorkflowNotFoundException(teamId, workflowId)
}

/**
 * A runner for the tests to steer: nodes named `ok…` do work and hand something
 * on, `boom` fails. Ahead of [UnimplementedNodeRunner], which claims everything.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
class ScriptedNodeRunner : NodeRunner {

    override fun supports(kind: NodeKind): Boolean = true

    override fun run(step: ExecutionStep, input: String?): StepResult = when {
        step.name == "boom" -> throw IllegalStateException("boom has no answer")
        step.name.startsWith("ok") -> StepResult(StepStatus.COMPLETED, "${step.name} did the work")
        else -> UnimplementedNodeRunner().run(step, input)
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
