package io.mszymanski.orknux.server.trigger

import io.mszymanski.orknux.connector.connection.DeliverableActions
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionService
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.workflow.ConditionNotInCatalogueException
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceNotFoundException
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.workspace.pageRequest
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.time.format.DateTimeFormatter

/**
 * A workspace's trigger catalogue: the events it can start work from, each defined
 * once. Nothing here names a workflow — a workflow points one of its trigger
 * nodes at a definition, and that instance is what wires the two together.
 */
@Controller
class TriggerAPI(
    private val triggers: WorkflowTriggerRepository,
    private val connections: WorkspaceConnectionService,
    private val conditions: WorkflowConditionRepository,
    private val firings: TriggerFiringRepository,
    private val mapper: ObjectMapper,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun workspaceTriggers(@Argument workspaceId: Long, @Argument page: Int?, @Argument size: Int?): TriggerPage {
        requireWorkspaceAccess(workspaceId)
        return TriggerPage(triggers.findByWorkspaceId(workspaceId, pageRequest(page, size, Sort.by("name"))), ::describe)
    }

    @QueryMapping
    fun trigger(@Argument id: Long): TriggerView? {
        val trigger = triggers.findByIdOrNull(id) ?: return null
        requireWorkspaceAccess(trigger.workspaceId)
        return describe(trigger)
    }

    @MutationMapping
    @Transactional
    fun createTrigger(@Argument input: CreateTriggerInput): TriggerView {
        val name = input.name.trim()
        if (name.isEmpty()) throw TriggerNameInvalidException()
        requireWorkspaceAccess(input.workspaceId)
        if (triggers.findByWorkspaceIdAndName(input.workspaceId, name) != null) throw TriggerNameTakenException(name)

        val trigger = triggers.save(
            WorkflowTrigger(
                workspaceId = input.workspaceId,
                name = name,
                type = input.type,
                connectionId = input.connectionId.takeIf { input.type == TriggerType.INCOMING_CONNECTION },
                action = input.action
                    ?.also(::requireDeliverable)
                    .takeIf { input.type == TriggerType.INCOMING_CONNECTION },
                cron = input.cron?.trim()?.ifEmpty { null }.takeIf { input.type == TriggerType.SCHEDULED },
                timezone = input.timezone?.trim()?.ifEmpty { null }.takeIf { input.type == TriggerType.SCHEDULED },
                payload = input.payload?.trim()?.ifEmpty { null }?.also(::requireJsonObject),
                conditionId = input.conditionId?.also { requireConditionInWorkspace(input.workspaceId, it) },
            ).also(::validate),
        )

        auditRecorder.record(input.workspaceId, WorkspaceAuditCategory.WORKFLOW, "Trigger $name created")
        return describe(trigger)
    }

    /**
     * What this trigger has done, newest first.
     *
     * The entries that matter are mostly the ones no run came of — an event the
     * condition turned down, or a definition nothing instances. Those appear
     * nowhere else, which is what makes a working trigger and a silent one look
     * the same from outside.
     */
    @QueryMapping
    fun triggerFirings(@Argument triggerId: Long, @Argument page: Int?, @Argument size: Int?): TriggerFiringPage {
        val trigger = triggers.findByIdOrNull(triggerId) ?: throw TriggerNotFoundException(triggerId)
        requireWorkspaceAccess(trigger.workspaceId)
        return TriggerFiringPage(
            firings.findByTriggerIdOrderByAtDesc(triggerId, pageRequest(page, size, Sort.by("at").descending())),
            ::describe,
        )
    }

    /** Backs the trigger settings form; the workspace and the type are what it is. */
    @MutationMapping
    @Transactional
    fun updateTrigger(@Argument id: Long, @Argument input: UpdateTriggerInput): TriggerView {
        val trigger = triggers.findByIdOrNull(id) ?: throw TriggerNotFoundException(id)
        requireWorkspaceAccess(trigger.workspaceId)

        val name = input.name.trim()
        if (name.isEmpty()) throw TriggerNameInvalidException()
        if (name != trigger.name && triggers.findByWorkspaceIdAndName(trigger.workspaceId, name) != null) {
            throw TriggerNameTakenException(name)
        }
        val previousName = trigger.name
        trigger.name = name
        if (trigger.type == TriggerType.INCOMING_CONNECTION) {
            input.connectionId?.let { trigger.connectionId = it }
            input.action?.let {
                requireDeliverable(it)
                trigger.action = it
            }
        } else {
            input.cron?.let { trigger.cron = it.trim().ifEmpty { null } }
            input.timezone?.let { trigger.timezone = it.trim().ifEmpty { null } }
        }
        input.payload?.let { trigger.payload = it.trim().ifEmpty { null }?.also(::requireJsonObject) }
        // Null is "ask nothing", which the form has to be able to say, so this
        // one is assigned rather than only applied when it was sent.
        trigger.conditionId = input.conditionId?.also { requireConditionInWorkspace(trigger.workspaceId, it) }
        validate(trigger)

        val message = if (name == previousName) "Trigger $name updated" else "Trigger $previousName renamed to $name"
        auditRecorder.record(trigger.workspaceId, WorkspaceAuditCategory.WORKFLOW, message)
        return describe(trigger)
    }

    /**
     * The events this installation can actually deliver.
     *
     * The screen asks rather than hard-coding a list, because what is wired is
     * the connection module's business — and a picker offering an event nothing
     * publishes is how somebody ends up with a trigger that looks configured and
     * never fires.
     */
    @QueryMapping
    fun supportedTriggerActions(): List<TriggerAction> =
        DeliverableActions.published.map { TriggerAction.valueOf(it.name) }.sortedBy { it.name }

    /** The toggle in the list. A disabled trigger stays, and stops firing. */
    @MutationMapping
    @Transactional
    fun setTriggerEnabled(@Argument id: Long, @Argument enabled: Boolean): TriggerView {
        val trigger = triggers.findByIdOrNull(id) ?: throw TriggerNotFoundException(id)
        requireWorkspaceAccess(trigger.workspaceId)

        trigger.enabled = enabled
        auditRecorder.record(
            trigger.workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            "Trigger ${trigger.name} ${if (enabled) "enabled" else "disabled"}",
        )
        return describe(trigger)
    }

    @MutationMapping
    @Transactional
    fun deleteTrigger(@Argument id: Long): Boolean {
        val trigger = triggers.findByIdOrNull(id) ?: return false
        requireWorkspaceAccess(trigger.workspaceId)

        triggers.delete(trigger)
        auditRecorder.record(trigger.workspaceId, WorkspaceAuditCategory.WORKFLOW, "Trigger ${trigger.name} deleted")
        return true
    }

    /**
     * The list shows where an event comes from and what it is: the connection
     * for an incoming trigger, the clock and the expression for a scheduled one.
     */
    private fun describe(trigger: WorkflowTrigger): TriggerView {
        val connection = trigger.connectionId?.let { connections.workspaceConnection(it) }
        return TriggerView(
            id = requireNotNull(trigger.id),
            workspaceId = trigger.workspaceId,
            name = trigger.name,
            type = trigger.type,
            connectionId = trigger.connectionId,
            action = trigger.action,
            cron = trigger.cron,
            timezone = trigger.timezone,
            payload = trigger.payload,
            conditionId = trigger.conditionId,
            lastFiring = trigger.id?.let { firings.findFirstByTriggerIdOrderByAtDesc(it) }?.let(::describe),
            conditionName = trigger.conditionId?.let { conditions.findByIdOrNull(it) }?.name,
            enabled = trigger.enabled,
            source = when (trigger.type) {
                TriggerType.SCHEDULED -> "Cron"
                // The connection may have been disconnected since.
                TriggerType.INCOMING_CONNECTION -> connection?.name ?: "—"
            },
            event = when (trigger.type) {
                TriggerType.SCHEDULED -> trigger.cron ?: "—"
                TriggerType.INCOMING_CONNECTION -> trigger.action?.let(::actionLabel) ?: "—"
            },
        )
    }

    private fun describe(firing: TriggerFiring) = TriggerFiringView(
        id = requireNotNull(firing.id),
        at = firing.at.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        outcome = firing.outcome,
        detail = firing.detail,
        runsStarted = firing.runsStarted,
    )

    /**
     * An event nothing raises is not one to wait for.
     *
     * The enum holds the vocabulary the product intends; only some of it is
     * wired. Saving a trigger on an unwired event would produce a definition
     * that is enabled, instanced, and silent for ever.
     */
    private fun requireDeliverable(action: TriggerAction) {
        if (action.name !in DeliverableActions.published.map { it.name }) {
            throw TriggerActionUnsupportedException(action.name)
        }
    }

    /**
     * A trigger asks its own workspace's question and no other's — the same rule
     * a workflow node follows when it points at a definition.
     */
    private fun requireConditionInWorkspace(workspaceId: Long, conditionId: Long) {
        val condition = conditions.findByIdOrNull(conditionId) ?: throw ConditionNotInCatalogueException(conditionId)
        if (condition.workspaceId != workspaceId) throw ConditionNotInCatalogueException(conditionId)
    }

    /**
     * The payload is read field by field into what the run is handed, so it has
     * to be an object: an array or a bare number has no fields to read.
     */
    private fun requireJsonObject(payload: String) {
        val parsed = try {
            mapper.readTree(payload)
        } catch (failure: JacksonException) {
            throw TriggerPayloadInvalidException()
        }
        if (!parsed.isObject) throw TriggerPayloadInvalidException()
    }

    private fun validate(trigger: WorkflowTrigger) {
        when (trigger.type) {
            TriggerType.INCOMING_CONNECTION -> {
                val connectionId = trigger.connectionId ?: throw TriggerConnectionRequiredException()
                if (trigger.action == null) throw TriggerConnectionRequiredException()
                // The connection has to be one this workspace holds.
                val connection = connections.workspaceConnection(connectionId)
                if (connection == null || connection.workspaceId != trigger.workspaceId) {
                    throw TriggerConnectionRequiredException()
                }
            }

            TriggerType.SCHEDULED -> {
                val cron = trigger.cron ?: throw TriggerScheduleRequiredException()
                // Five fields is what a person writes; Spring wants six.
                if (!CronExpression.isValidExpression(sixField(cron))) throw TriggerScheduleInvalidException(cron)
            }
        }
    }

    private fun requireWorkspaceAccess(workspaceId: Long) {
        val workspace = workspaces.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)
    }
}

/** "MENTION" -> "Mention", "ISSUE_CREATED" -> "Issue Created", as the list shows it. */
fun actionLabel(action: TriggerAction): String = action.name
    .split('_')
    .joinToString(" ") { word -> word.lowercase().replaceFirstChar(Char::uppercase) }

/** Cron the way people write it is five fields; Spring's parser wants seconds too. */
fun sixField(cron: String): String {
    val fields = cron.trim().split(Regex("\\s+"))
    return if (fields.size == 5) "0 $cron".trim() else cron.trim()
}

data class CreateTriggerInput(
    val workspaceId: Long,
    val name: String,
    val type: TriggerType,
    val connectionId: Long? = null,
    val action: TriggerAction? = null,
    val cron: String? = null,
    val timezone: String? = null,
    /** JSON object handed to the runs this starts. */
    val payload: String? = null,
    /** Asked of the event before anything starts; null fires on everything. */
    val conditionId: Long? = null,
)

data class UpdateTriggerInput(
    val name: String,
    val connectionId: Long? = null,
    val action: TriggerAction? = null,
    val cron: String? = null,
    val timezone: String? = null,
    val payload: String? = null,
    /** Null takes the condition off, so the form can stop asking. */
    val conditionId: Long? = null,
)

data class TriggerView(
    val id: Long,
    val workspaceId: Long,
    val name: String,
    val type: TriggerType,
    val connectionId: Long?,
    val action: TriggerAction?,
    val cron: String?,
    val timezone: String?,
    /** JSON object handed to the runs this starts; null when it says nothing. */
    val payload: String?,
    val conditionId: Long?,
    /** The most recent thing this trigger did; null when it has never been asked. */
    val lastFiring: TriggerFiringView?,
    /** What the list shows: the condition's name, or nothing when it asks none. */
    val conditionName: String?,
    val enabled: Boolean,
    /** Where the event comes from, ready to show: the connection, or "Cron". */
    val source: String,
    /** What the event is, ready to show: "Mention", or the cron expression. */
    val event: String,
)

data class TriggerFiringPage(
    val content: List<TriggerFiringView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<TriggerFiring>, describe: (TriggerFiring) -> TriggerFiringView) : this(
        content = page.content.map(describe),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}

data class TriggerFiringView(
    val id: Long,
    /** ISO-8601, the way every other timestamp crosses this API. */
    val at: String,
    val outcome: FiringOutcome,
    val detail: String?,
    val runsStarted: Int,
)

data class TriggerPage(
    val content: List<TriggerView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<WorkflowTrigger>, describe: (WorkflowTrigger) -> TriggerView) : this(
        content = page.content.map(describe),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}
