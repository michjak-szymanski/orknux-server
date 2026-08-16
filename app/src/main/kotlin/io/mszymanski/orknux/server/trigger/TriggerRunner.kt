package io.mszymanski.orknux.server.trigger

import io.mszymanski.orknux.server.condition.ConditionEvaluator
import io.mszymanski.orknux.server.condition.ConditionNotDecidableException
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.workflow.execution.ExecutionService
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.StartExecutionInput
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
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
    private val assignments: WorkspaceWorkflowRepository,
    private val runs: ExecutionService,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val mapper: ObjectMapper,
    private val conditions: WorkflowConditionRepository,
    private val evaluator: ConditionEvaluator,
    private val firings: TriggerFiringRepository,
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
            .filter { assignments.existsByWorkspaceIdAndWorkflowId(trigger.workspaceId, it) }

        if (workflowIds.isEmpty()) {
            log.info("Trigger {} fired, but no workflow instances it", trigger.name)
            record(trigger, FiringOutcome.NO_INSTANCE, "No workflow has a trigger node pointing at this definition")
            return 0
        }

        val payload = inputFor(trigger, context)
        val verdict = admits(trigger, payload)
        if (verdict != null) {
            record(trigger, verdict.outcome, verdict.detail)
            return 0
        }

        val started = workflowIds.count { start(trigger, it, payload) }
        if (started == workflowIds.size) {
            record(trigger, FiringOutcome.STARTED, "Started $started of ${workflowIds.size}", started)
        } else {
            // Partly started is not started: the log says which, because the
            // executions list only shows the ones that made it.
            record(
                trigger,
                FiringOutcome.FAILED,
                "Started $started of ${workflowIds.size}; the rest could not be started",
                started,
            )
        }
        return started
    }

    /**
     * A firing that came to nothing, and why — or null when it should go ahead.
     */
    private data class Refusal(val outcome: FiringOutcome, val detail: String)

    /**
     * Whether this firing is one the trigger wanted.
     *
     * Asked before anything starts, which is the point of asking here at all: a
     * condition inside the workflow only decides after a run exists, has been
     * audited, and is sitting in the executions list looking like work.
     *
     * A condition that cannot be decided does not admit the event. The question
     * was asked because not everything arriving is wanted, and an event nobody
     * could evaluate is not evidence that it was — so it is refused, and said
     * out loud rather than dropped quietly.
     */
    private fun admits(trigger: WorkflowTrigger, payload: String): Refusal? {
        val conditionId = trigger.conditionId ?: return null
        val condition = conditions.findByIdOrNull(conditionId)
        if (condition == null) {
            log.warn("Trigger {} asks a condition that has been deleted; nothing started", trigger.name)
            return Refusal(FiringOutcome.UNDECIDED, "The condition this trigger asks has been deleted")
        }

        return try {
            if (evaluator.holds(condition, payload)) {
                null
            } else {
                log.debug("Trigger {} fired, but {} did not hold", trigger.name, condition.name)
                Refusal(FiringOutcome.CONDITION_DID_NOT_HOLD, "${condition.name} did not hold")
            }
        } catch (undecided: ConditionNotDecidableException) {
            log.warn("Trigger {} could not decide {}: {}", trigger.name, condition.name, undecided.message)
            Refusal(FiringOutcome.UNDECIDED, "${condition.name} could not be decided: ${undecided.message}")
        }
    }

    /**
     * One line in the trigger's log.
     *
     * Never allowed to be the reason a firing fails: a run that started matters
     * more than the note saying it did.
     */
    private fun record(trigger: WorkflowTrigger, outcome: FiringOutcome, detail: String, started: Int = 0) {
        runCatching {
            firings.save(
                TriggerFiring(
                    triggerId = requireNotNull(trigger.id),
                    workspaceId = trigger.workspaceId,
                    outcome = outcome,
                    detail = detail,
                    runsStarted = started,
                ),
            )
        }.onFailure { log.warn("Could not record what trigger {} did", trigger.name, it) }
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
                workspaceId = trigger.workspaceId,
                workflowId = workflowId,
                trigger = when (trigger.type) {
                    TriggerType.SCHEDULED -> ExecutionTrigger.SCHEDULE
                    TriggerType.INCOMING_CONNECTION -> ExecutionTrigger.WEBHOOK
                },
                payload = payload,
            ),
        )
        auditRecorder.recordAutomated(
            workspaceId = trigger.workspaceId,
            category = WorkspaceAuditCategory.WORKFLOW,
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
