package io.mszymanski.orknux.server.trigger

import io.mszymanski.orknux.connector.connection.DeliverableActions
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionService
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.action.FunctionScope
import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.workflow.ConditionNotInCatalogueException
import io.mszymanski.orknux.server.workflow.WorkflowReferences
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
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
import java.time.OffsetDateTime
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
    private val objects: WorkflowObjectRepository,
    private val functions: WorkflowFunctionRepository,
    private val firings: TriggerFiringRepository,
    private val mapper: ObjectMapper,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val references: WorkflowReferences,
) {

    @QueryMapping
    fun workspaceTriggers(@Argument workspaceId: Long, @Argument page: Int?, @Argument size: Int?): TriggerPage {
        requireWorkspaceAccess(workspaceId)
        return TriggerPage(triggers.findByWorkspaceId(workspaceId, pageRequest(page, size, Sort.by("name"))), ::describe)
    }

    @QueryMapping
    fun trigger(@Argument id: Long): TriggerView? {
        val trigger = triggers.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return null
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
                webhookPath = input.webhookPath?.let(::pathOf).takeIf { input.type == TriggerType.WEBHOOK },
                objectId = input.objectId.takeIf { input.type == TriggerType.WEBHOOK },
                authType = input.authType.takeIf { input.type == TriggerType.WEBHOOK } ?: WebhookAuthType.NONE,
                authFunctionId = input.authFunctionId.takeIf {
                    input.type == TriggerType.WEBHOOK && input.authType == WebhookAuthType.FUNCTION
                },
                payload = input.payload?.trim()?.ifEmpty { null }?.also(::requireJsonObject),
                conditionId = input.conditionId?.also { requireConditionInWorkspace(input.workspaceId, it) },
                icon = input.icon?.trim()?.ifEmpty { null },
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
        // Somebody else's trigger reads as a trigger that is not there: the page
        // cannot be null, so the two answers are made the same one instead.
        triggers.findByIdOrNull(triggerId)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw TriggerNotFoundException(triggerId)
        return TriggerFiringPage(
            firings.findByTriggerIdOrderByAtDesc(triggerId, pageRequest(page, size, Sort.by("at").descending())),
            ::describe,
        )
    }

    /**
     * What every trigger in this workspace has done, newest first.
     *
     * The same entries the per-trigger log holds, read the other way round: not
     * "what did this trigger do" but "what has happened here" — which is the
     * question somebody has when a workflow did not run and they do not yet know
     * which trigger to blame.
     */
    @QueryMapping
    fun workspaceTriggerFirings(
        @Argument workspaceId: Long,
        @Argument page: Int?,
        @Argument size: Int?,
    ): TriggerFiringPage {
        requireWorkspaceAccess(workspaceId)
        val held = firings.findByWorkspaceIdOrderByAtDesc(
            workspaceId,
            pageRequest(page, size, Sort.by("at").descending()),
        )
        // The names in one query rather than one each: a page of twenty entries
        // from three triggers should not be twenty lookups.
        val names = triggers.findAllById(held.content.map { it.triggerId }.distinct())
            .associate { requireNotNull(it.id) to it.name }
        return TriggerFiringPage(held) { firing -> describe(firing, names[firing.triggerId]) }
    }

    /** Backs the trigger settings form; the workspace and the type are what it is. */
    @MutationMapping
    @Transactional
    fun updateTrigger(@Argument id: Long, @Argument input: UpdateTriggerInput): TriggerView {
        val trigger = triggers.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw TriggerNotFoundException(id)

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
        } else if (trigger.type == TriggerType.WEBHOOK) {
            input.webhookPath?.let { trigger.webhookPath = pathOf(it) }
            // Null takes the contract off, which the form has to be able to say.
            trigger.objectId = input.objectId
            input.authType?.let { trigger.authType = it }
            // Kept only while something asks for it, so switching back to open
            // does not leave a function nothing calls.
            trigger.authFunctionId = input.authFunctionId
                ?.takeIf { trigger.authType == WebhookAuthType.FUNCTION }
        } else {
            input.cron?.let { trigger.cron = it.trim().ifEmpty { null } }
            input.timezone?.let { trigger.timezone = it.trim().ifEmpty { null } }
        }
        input.payload?.let { trigger.payload = it.trim().ifEmpty { null }?.also(::requireJsonObject) }
        // Null is "ask nothing", which the form has to be able to say, so this
        // one is assigned rather than only applied when it was sent.
        trigger.conditionId = input.conditionId?.also { requireConditionInWorkspace(trigger.workspaceId, it) }
        // Null clears it, the way the condition above clears: the form sends
        // what it holds, and holding nothing is a choice.
        trigger.icon = input.icon?.trim()?.ifEmpty { null }
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
        val trigger = triggers.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw TriggerNotFoundException(id)

        trigger.enabled = enabled
        auditRecorder.record(
            trigger.workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            "Trigger ${trigger.name} ${if (enabled) "enabled" else "disabled"}",
        )
        return describe(trigger)
    }

    /** Refused while a workflow starts from it; see [TriggerInUseException]. */
    @MutationMapping
    @Transactional
    fun deleteTrigger(@Argument id: Long): Boolean {
        val trigger = triggers.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return false

        val users = references.toTrigger(trigger.workspaceId, id)
        if (users.isNotEmpty()) throw TriggerInUseException(trigger.name, users)

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
            icon = trigger.icon,
            webhookPath = trigger.webhookPath,
            objectId = trigger.objectId,
            objectName = trigger.objectId?.let { objects.findByIdOrNull(it) }?.name,
            authType = trigger.authType,
            authFunctionId = trigger.authFunctionId,
            authFunctionName = trigger.authFunctionId?.let { functions.findByIdOrNull(it) }?.name,
            source = when (trigger.type) {
                TriggerType.SCHEDULED -> "Cron"
                // The connection may have been disconnected since.
                TriggerType.INCOMING_CONNECTION -> connection?.name ?: "—"
                TriggerType.WEBHOOK -> "Webhook"
            },
            event = when (trigger.type) {
                TriggerType.SCHEDULED -> trigger.cron ?: "—"
                TriggerType.INCOMING_CONNECTION -> trigger.action?.let(::actionLabel) ?: "—"
                // What somebody has to call, which is the thing worth showing.
                TriggerType.WEBHOOK -> trigger.webhookPath?.let { "/api/webhooks/$it" } ?: "—"
            },
        )
    }

    private fun describe(firing: TriggerFiring, triggerName: String? = null) = TriggerFiringView(
        id = requireNotNull(firing.id),
        at = firing.at.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        outcome = firing.outcome,
        detail = firing.detail,
        runsStarted = firing.runsStarted,
        triggerId = firing.triggerId,
        // Only worth carrying where the list holds more than one trigger's
        // entries; a trigger's own log already says whose they are.
        triggerName = triggerName,
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
                /*
                 * And that it will ever come round.
                 *
                 * Parsing is not the same question. "0 0 30 2 *" is a valid
                 * expression naming the thirtieth of February, and Spring
                 * answers `next` with null rather than refusing it - so it
                 * saved, sat in the list looking like a schedule, and was
                 * silently skipped on every tick for ever. A trigger that can
                 * never fire is worth refusing at the door, because the only
                 * other way to find out is to wait.
                 */
                val ever = runCatching {
                    CronExpression.parse(sixField(cron)).next(OffsetDateTime.now())
                }.getOrNull()
                if (ever == null) throw TriggerScheduleUnreachableException(cron)
            }

            TriggerType.WEBHOOK -> {
                val path = trigger.webhookPath ?: throw TriggerWebhookPathRequiredException()
                val held = triggers.findByWebhookPath(path)
                if (held != null && held.id != trigger.id) throw TriggerWebhookPathTakenException(path)

                // The shape is what a request is answered by; without one there
                // is nothing to check a request against and nothing to promise a
                // workflow about what it will be handed.
                val objectId = trigger.objectId ?: throw TriggerWebhookShapeRequiredException()
                val shape = objects.findByIdOrNull(objectId)
                if (shape == null || shape.workspaceId != trigger.workspaceId) {
                    throw TriggerWebhookShapeRequiredException()
                }

                /*
                 * A function that authenticates has to be able to say no.
                 *
                 * Checked when it is chosen rather than when a request arrives:
                 * a webhook whose gatekeeper answers an object would refuse
                 * everything, at the one moment nobody is watching.
                 *
                 * A plugin's functions belong to no workspace and are offered in
                 * every one — the picker lists them and the endpoint asks them in
                 * the plugin's own sandbox — so only another workspace's own
                 * function is out of reach. Refusing a plugin's as "no function
                 * chosen" described a box somebody had already filled in.
                 */
                if (trigger.authType == WebhookAuthType.FUNCTION) {
                    val functionId = trigger.authFunctionId ?: throw TriggerWebhookAuthFunctionRequiredException()
                    val function = functions.findByIdOrNull(functionId)
                        ?: throw TriggerWebhookAuthFunctionRequiredException()
                    if (function.scope != FunctionScope.PLUGIN && function.workspaceId != trigger.workspaceId) {
                        throw TriggerWebhookAuthFunctionRequiredException()
                    }
                    if (function.returnType != ValueType.BOOLEAN) {
                        throw TriggerWebhookAuthFunctionNotBooleanException(function.name)
                    }
                }
            }
        }
    }

    /**
     * The path a webhook answers on, as it is stored: no leading slash, no host.
     *
     * This installation is what answers, so what is being named is somewhere on
     * it. Anything that looks like it points elsewhere — a scheme, a host, a
     * `..` climbing out of the endpoint — is refused rather than quietly
     * rewritten, because somebody typing a URL of their own means something by
     * it and should be told it is not what this asks for.
     */
    private fun pathOf(said: String): String {
        val path = said.trim().removePrefix("/")
        if (path.isEmpty()) throw TriggerWebhookPathRequiredException()
        if (path.contains("://") || path.startsWith("/") || path.contains("..")) {
            throw TriggerWebhookPathInvalidException(said.trim())
        }
        if (!WEBHOOK_PATH.matches(path)) throw TriggerWebhookPathInvalidException(said.trim())
        return path
    }

    private fun requireWorkspaceAccess(workspaceId: Long) {
        access.requireVisible(workspaceId)
    }

    private companion object {
        /** Letters, digits and the punctuation a URL carries plainly. */
        val WEBHOOK_PATH = Regex("[A-Za-z0-9][A-Za-z0-9._~-]*(/[A-Za-z0-9._~-]+)*")
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
    /** Which icon a node drawn from this starts with; null draws the kind's own. */
    val icon: String? = null,
    /** Where a webhook answers, relative to this installation: `build/finished`. */
    val webhookPath: String? = null,
    /** The shape a webhook's request has to have; anything else is answered 404. */
    val objectId: Long? = null,
    /** How a webhook decides whether its caller may start anything. */
    val authType: WebhookAuthType = WebhookAuthType.NONE,
    /** The function that answers that, when one does. */
    val authFunctionId: Long? = null,
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
    /** Which icon a node drawn from this starts with; null draws the kind's own. */
    val icon: String? = null,
    /** Where a webhook answers, relative to this installation. */
    val webhookPath: String? = null,
    /** The shape a webhook's request has to have; null takes the contract off. */
    val objectId: Long? = null,
    /** How a webhook decides whether its caller may start anything. */
    val authType: WebhookAuthType? = null,
    /** The function that answers that; null when nothing does. */
    val authFunctionId: Long? = null,
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
    /** Which icon a node drawn from this starts with; null draws the kind's own. */
    val icon: String?,
    /** Where a webhook answers, relative to this installation; null on other kinds. */
    val webhookPath: String?,
    /** The shape a webhook's request has to have. */
    val objectId: Long?,
    /** What that shape is called, for the list. */
    val objectName: String?,
    /** How a webhook decides whether its caller may start anything. */
    val authType: WebhookAuthType,
    val authFunctionId: Long?,
    /** What that function is called, for the form. */
    val authFunctionName: String?,
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
    /** Which trigger did it; null where the answer is already known from context. */
    val triggerId: Long? = null,
    val triggerName: String? = null,
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
