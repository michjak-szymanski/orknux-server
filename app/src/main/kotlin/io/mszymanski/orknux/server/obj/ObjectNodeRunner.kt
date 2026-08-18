package io.mszymanski.orknux.server.obj

import io.mszymanski.orknux.server.workflow.NodeExpressions
import io.mszymanski.orknux.workflow.execution.ExecutionStep
import io.mszymanski.orknux.workflow.execution.KIND_RUNNER_ORDER
import io.mszymanski.orknux.workflow.execution.NodeKind
import io.mszymanski.orknux.workflow.execution.NodeRunner
import io.mszymanski.orknux.workflow.execution.StepResult
import io.mszymanski.orknux.workflow.execution.StepStatus
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Puts values together into one object and hands it on.
 *
 * Everything a run carries until now is whatever some step happened to produce.
 * Making a ticket out of a Slack message and an agent's answer meant writing a
 * function whose whole body was building an object — a script to do what the
 * graph could say plainly.
 *
 * Each field is filled the way every other parameter is: a written value, or a
 * reference to a field the run is carrying. Nothing here evaluates anything;
 * [NodeExpressions] answers both, and this decides only what the answer is
 * called.
 */
@Component
@Order(KIND_RUNNER_ORDER)
class ObjectNodeRunner(
    private val expressions: NodeExpressions,
    private val mapper: ObjectMapper,
) : NodeRunner {

    override fun supports(kind: NodeKind): Boolean = kind == NodeKind.OBJECT

    override fun run(step: ExecutionStep, input: String?, trigger: String?): StepResult {
        val fields = expressions.mappingsOf(step)
        if (fields.isEmpty()) {
            return StepResult(StepStatus.SKIPPED, "${step.name} has no fields, so there was nothing to make.")
        }

        val given = expressions.parse(input)
        val started = expressions.parse(trigger)

        val made: ObjectNode = mapper.createObjectNode()
        fields.forEach { (name, binding) ->
            /*
             * Read as JSON, so a field pointed at an object stays an object.
             * Reading it as text would put the source of one into the field —
             * `{"ticket":"{\"id\":1}"}` where `{"ticket":{"id":1}}` was meant —
             * and a later reference into it would find nothing.
             */
            val value = runCatching { mapper.readTree(expressions.jsonOf(binding, given, started)) }.getOrNull()
            if (value != null) made.set(name, value)
        }

        // Named, the object is one field of that name; unnamed, its fields are
        // what goes on, which is what the run was already carrying around.
        return StepResult(StepStatus.COMPLETED, expressions.namedJson(step.outputName, made.toString()))
    }
}
