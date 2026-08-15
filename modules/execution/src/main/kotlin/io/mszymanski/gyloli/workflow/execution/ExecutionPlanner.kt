package io.mszymanski.gyloli.workflow.execution

import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/** A run, recorded and ready to be carried out. */
data class ExecutionPlan(
    val execution: WorkflowExecution,
    /** In the order they are to be run. */
    val steps: List<ExecutionStep>,
)

/**
 * Turns a request to run something into a recorded run: reads the graph from
 * gyloli-server, works out an order, and writes the run and its steps down.
 *
 * This happens while the caller is waiting, on purpose. A workflow that cannot
 * be read or cannot be ordered is a rejected request rather than a failed run,
 * and the caller is told so with an error it can act on. Only what comes after
 * — the running — is worth making durable.
 */
@Service
class ExecutionPlanner(
    private val graphs: WorkflowGraphSource,
    private val executions: WorkflowExecutionRepository,
    private val steps: ExecutionStepRepository,
    private val log: RunLogger,
) {

    fun plan(
        teamId: Long,
        workflowId: Long,
        trigger: ExecutionTrigger,
        input: String?,
    ): ExecutionPlan {
        val graph = graphs.graph(teamId, workflowId)
        val order = graph.runOrder()

        val execution = executions.save(
            WorkflowExecution(
                teamId = teamId,
                workflowId = graph.workflowId,
                workflowName = graph.name,
                status = ExecutionStatus.RUNNING,
                trigger = trigger,
                startedAt = OffsetDateTime.now(),
                input = input,
            ),
        )
        val executionId = requireNotNull(execution.id)

        val recorded = steps.saveAll(
            order.mapIndexed { index, node ->
                ExecutionStep(
                    executionId = executionId,
                    nodeKey = node.key,
                    kind = node.kind,
                    name = node.name,
                    description = node.description,
                    actionId = node.actionId,
                    conditionId = node.conditionId,
                    x = node.x,
                    y = node.y,
                    order = index,
                )
            },
        )

        log.write(executionId, null, LogLevel.INFO, "${graph.name} started by ${trigger.name.lowercase()}")
        return ExecutionPlan(execution, recorded)
    }
}
