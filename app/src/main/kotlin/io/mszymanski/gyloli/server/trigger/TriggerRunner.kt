package io.mszymanski.gyloli.server.trigger

import io.mszymanski.gyloli.server.team.TeamAuditCategory
import io.mszymanski.gyloli.server.team.TeamAuditRecorder
import io.mszymanski.gyloli.server.workflow.TeamWorkflowRepository
import io.mszymanski.gyloli.server.workflow.WorkflowNodeRepository
import io.mszymanski.gyloli.workflow.execution.ExecutionService
import io.mszymanski.gyloli.workflow.execution.ExecutionTrigger
import io.mszymanski.gyloli.workflow.execution.StartExecutionInput
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Starts what a trigger definition is wired to.
 *
 * A definition is a catalogue entry and starts nothing by itself; what runs is
 * every workflow with a trigger node instancing it. Both ways a trigger fires —
 * an event arriving on a connection, and the clock — end up here, so they start
 * runs the same way and are audited the same way.
 *
 * Nobody is signed in when a trigger fires, so a run is attributed to the
 * trigger rather than to a person.
 */
@Service
class TriggerRunner(
    private val instances: WorkflowNodeRepository,
    private val assignments: TeamWorkflowRepository,
    private val runs: ExecutionService,
    private val auditRecorder: TeamAuditRecorder,
    private val mapper: ObjectMapper,
) {

    /**
     * @param context what the run is handed: the message for an incoming event,
     *   the schedule for a scheduled one.
     * @return how many runs were started.
     */
    fun fire(trigger: WorkflowTrigger, context: Map<String, String?>): Int {
        val triggerId = requireNotNull(trigger.id)
        val workflowIds = instances.findByTriggerId(triggerId)
            .map { it.workflowId }
            .distinct()
            .filter { assignments.existsByTeamIdAndWorkflowId(trigger.teamId, it) }

        if (workflowIds.isEmpty()) {
            log.info("Trigger {} fired, but no workflow instances it", trigger.name)
            return 0
        }

        val payload = inputFor(trigger, context)
        return workflowIds.count { start(trigger, it, payload) }
    }

    /**
     * What the run is handed: the trigger's own payload, with what happened on
     * top of it. The payload keeps its shape — a nested object stays an object —
     * so a function can be handed something to work on rather than a flat set of
     * strings about the trigger.
     */
    private fun inputFor(trigger: WorkflowTrigger, context: Map<String, String?>): String {
        val input = trigger.payload
            ?.let { runCatching { mapper.readTree(it) }.getOrNull() }
            ?.takeIf { it.isObject }
            ?.let { (it as ObjectNode).deepCopy() }
            ?: mapper.createObjectNode()

        // What arrived describes this firing, so it wins.
        context.forEach { (name, value) -> if (value != null) input.put(name, value) }
        return mapper.writeValueAsString(input)
    }

    private fun start(trigger: WorkflowTrigger, workflowId: Long, payload: String): Boolean = try {
        val started = runs.startExecution(
            StartExecutionInput(
                teamId = trigger.teamId,
                workflowId = workflowId,
                trigger = when (trigger.type) {
                    TriggerType.SCHEDULED -> ExecutionTrigger.SCHEDULE
                    TriggerType.INCOMING_CONNECTION -> ExecutionTrigger.WEBHOOK
                },
                payload = payload,
            ),
        )
        auditRecorder.recordAutomated(
            teamId = trigger.teamId,
            category = TeamAuditCategory.WORKFLOW,
            message = "Workflow ${started.workflowName} run started by trigger ${trigger.name}",
            actor = "trigger:${trigger.name}",
        )
        true
    } catch (failure: Exception) {
        // One workflow failing is no reason for the others to miss the trigger.
        log.error("Trigger {} could not start workflow {}", trigger.name, workflowId, failure)
        false
    }

    private companion object {
        val log = LoggerFactory.getLogger(TriggerRunner::class.java)
    }
}
