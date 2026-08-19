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
import io.mszymanski.orknux.workflow.execution.WorkflowNotPublishedException
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
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
 * trigger rather than to a person. That is also why the workspace's switch on a
 * workflow is honoured here: this is the path nobody is watching, and a
 * workflow somebody has switched off is one they have said should not start
 * without them.
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
    /**
     * The same firing, for something that arrived as JSON rather than as a
     * handful of strings.
     *
     * A webhook's body is the thing the workflow was written against, so it is
     * carried in with its shape intact: a nested object stays an object, and a
     * reference to `order.total` finds a number rather than the text of one.
     */
    fun fire(trigger: WorkflowTrigger, body: JsonNode): Int = fire(trigger) { input ->
        if (body.isObject) {
            body.properties().forEach { (name, value) -> input.set(name, value) }
        }
    }

    fun fire(trigger: WorkflowTrigger, context: Map<String, String?>): Int = fire(trigger) { input ->
        // What arrived describes this firing, so it wins.
        context.forEach { (name, value) -> if (value != null) input.put(name, value) }
    }

    /** What both ways of firing have in common, once the input is decided. */
    private fun fire(trigger: WorkflowTrigger, fill: (ObjectNode) -> Unit): Int {
        val triggerId = requireNotNull(trigger.id)
        val assigned = instances.findByTriggerId(triggerId)
            .map { it.workflowId }
            .distinct()
            .mapNotNull { assignments.findByWorkspaceIdAndWorkflowId(trigger.workspaceId, it) }

        if (assigned.isEmpty()) {
            log.info("Trigger {} fired, but no workflow instances it", trigger.name)
            record(trigger, FiringOutcome.NO_INSTANCE, "No workflow has a trigger node pointing at this definition")
            return 0
        }

        /*
         * A workflow the workspace has switched off is not started by anything
         * that starts by itself, which is what the switch is for.
         *
         * Asked before the condition, and before the payload is assembled,
         * because a condition can call a function and there is nothing left for
         * its answer to decide: whichever way it went, nothing would run.
         */
        val (runnable, switchedOff) = assigned.partition { it.enabled }
        val offNames = switchedOff.map { "${it.workflow.name} is switched off in this workspace" }
        if (runnable.isEmpty()) {
            log.info("Trigger {} fired at workflows that are all switched off", trigger.name)
            record(trigger, FiringOutcome.WORKFLOW_DISABLED, offNames.joinToString("; "))
            return 0
        }

        val payload = inputFor(trigger, fill)
        val verdict = admits(trigger, payload)
        if (verdict != null) {
            record(trigger, verdict.outcome, verdict.detail)
            return 0
        }

        // The ones that are off are refusals like any other, so a firing that
        // started two of three says which one it left alone and why.
        val refusals = offNames.toMutableList()
        val started = runnable.count { start(trigger, requireNotNull(it.workflow.id), payload, refusals) }
        if (started == assigned.size) {
            record(trigger, FiringOutcome.STARTED, "Started $started of ${assigned.size}", started)
        } else {
            /*
             * Partly started is not started, and the record says why rather
             * than only how many. "Started 0 of 1" is what somebody reads when
             * a trigger fires at a workflow nobody has published, and it tells
             * them nothing they can act on - the reason exists, it was simply
             * being swallowed here.
             */
            record(
                trigger,
                FiringOutcome.FAILED,
                "Started $started of ${assigned.size}: ${refusals.joinToString("; ")}",
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
     * One line in the trigger's log, written by something outside this class.
     *
     * The endpoint answers a machine, so a request it turned down leaves no
     * trace anywhere a person looks. This is that trace.
     */
    fun note(trigger: WorkflowTrigger, outcome: FiringOutcome, detail: String) = record(trigger, outcome, detail)

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
    private fun inputFor(trigger: WorkflowTrigger, fill: (ObjectNode) -> Unit): String {
        val input = trigger.payload
            ?.let { runCatching { mapper.readTree(it) }.getOrNull() }
            ?.takeIf { it.isObject }
            ?.let { (it as ObjectNode).deepCopy() }
            ?: mapper.createObjectNode()

        fill(input)
        return mapper.writeValueAsString(input)
    }

    private fun start(
        trigger: WorkflowTrigger,
        workflowId: Long,
        payload: String,
        refusals: MutableList<String>,
    ): Boolean = try {
        val started = runs.startExecution(
            StartExecutionInput(
                workspaceId = trigger.workspaceId,
                workflowId = workflowId,
                trigger = when (trigger.type) {
                    TriggerType.SCHEDULED -> ExecutionTrigger.SCHEDULE
                    TriggerType.INCOMING_CONNECTION, TriggerType.WEBHOOK -> ExecutionTrigger.WEBHOOK
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
    } catch (notPublished: WorkflowNotPublishedException) {
        /*
         * Not an error worth a stack trace: a graph that has never been
         * published is a graph somebody is still drawing, and a trigger firing
         * at one is a thing to be told about plainly.
         */
        log.info("Trigger {} found workflow {} unpublished; nothing started", trigger.name, workflowId)
        refusals += notPublished.message ?: "a workflow that has never been published"
        false
    } catch (failure: Exception) {
        // One workflow failing is no reason for the others to miss the trigger.
        log.error("Trigger {} could not start workflow {}", trigger.name, workflowId, failure)
        refusals += failure.message ?: failure::class.simpleName.orEmpty()
        false
    }

    private companion object {
        val log = LoggerFactory.getLogger(TriggerRunner::class.java)
    }
}
