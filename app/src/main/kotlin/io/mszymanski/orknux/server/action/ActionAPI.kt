package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.connector.connection.WorkspaceConnectionService
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
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
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional

/**
 * A workspace's action catalogue: the blocks its workflows are built from.
 *
 * What an action needs and what it produces are not stored. They are read off
 * its settings — the arguments of the function it calls are what it needs, and
 * what it produces follows from what it does — so the two cannot drift from the
 * settings the way a second copy would.
 */
@Controller
class ActionAPI(
    private val actions: WorkflowActionRepository,
    private val functions: WorkflowFunctionRepository,
    private val conditions: WorkflowConditionRepository,
    private val parameters: ActionParameters,
    private val connections: WorkspaceConnectionService,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun workspaceActions(@Argument workspaceId: Long, @Argument page: Int?, @Argument size: Int?): ActionPage {
        requireWorkspaceAccess(workspaceId)
        return ActionPage(actions.findByWorkspaceId(workspaceId, pageRequest(page, size, Sort.by("name"))), ::describe)
    }

    @QueryMapping
    fun action(@Argument id: Long): ActionView? {
        val action = actions.findByIdOrNull(id) ?: return null
        requireWorkspaceAccess(action.workspaceId)
        return describe(action)
    }

    @MutationMapping
    @Transactional
    fun createAction(@Argument input: CreateActionInput): ActionView {
        requireWorkspaceAccess(input.workspaceId)
        val name = input.name.trim()
        if (name.isEmpty()) throw ActionNameInvalidException()
        if (actions.findByWorkspaceIdAndName(input.workspaceId, name) != null) throw ActionNameTakenException(name)

        val action = actions.save(
            WorkflowAction(
                workspaceId = input.workspaceId,
                name = name,
                type = input.type,
                subtype = input.subtype,
                connectionId = input.connectionId,
                connectionAction = input.connectionAction,
                content = input.content?.trim()?.ifEmpty { null },
                target = input.target,
                targetName = input.targetName?.trim()?.ifEmpty { null },
                url = input.url?.trim()?.ifEmpty { null },
                method = input.method?.trim()?.uppercase()?.ifEmpty { null },
                headers = input.headers?.trim()?.ifEmpty { null },
                functionId = input.functionId,
                mappings = input.mappings.orEmpty().toMappings(),
                conditionExpression = input.conditionExpression?.trim()?.ifEmpty { null },
                conditionId = input.conditionId,
                timeoutSeconds = input.timeoutSeconds,
                retryIntervalSeconds = input.retryIntervalSeconds,
                durationSeconds = input.durationSeconds,
                icon = input.icon?.trim()?.ifEmpty { null },
            ).also(::validate),
        )

        auditRecorder.record(input.workspaceId, WorkspaceAuditCategory.WORKFLOW, "Action $name created")
        return describe(action)
    }

    /** Backs the action settings form; the type and subtype are what it is. */
    @MutationMapping
    @Transactional
    fun updateAction(@Argument id: Long, @Argument input: UpdateActionInput): ActionView {
        val action = actions.findByIdOrNull(id) ?: throw ActionNotFoundException(id)
        requireWorkspaceAccess(action.workspaceId)

        val previousName = action.name
        input.name?.trim()?.let { name ->
            if (name.isEmpty()) throw ActionNameInvalidException()
            if (name != action.name && actions.findByWorkspaceIdAndName(action.workspaceId, name) != null) {
                throw ActionNameTakenException(name)
            }
            action.name = name
        }
        input.subtype?.let { action.subtype = it }
        input.connectionId?.let { action.connectionId = it }
        input.connectionAction?.let { action.connectionAction = it }
        input.content?.let { action.content = it.trim().ifEmpty { null } }
        input.target?.let { action.target = it }
        input.targetName?.let { action.targetName = it.trim().ifEmpty { null } }
        input.url?.let { action.url = it.trim().ifEmpty { null } }
        input.method?.let { action.method = it.trim().uppercase().ifEmpty { null } }
        input.headers?.let { action.headers = it.trim().ifEmpty { null } }
        input.functionId?.let { action.functionId = it }
        input.mappings?.let { action.mappings = it.toMappings() }
        input.conditionExpression?.let { action.conditionExpression = it.trim().ifEmpty { null } }
        input.conditionId?.let { action.conditionId = it }
        input.timeoutSeconds?.let { action.timeoutSeconds = it }
        input.retryIntervalSeconds?.let { action.retryIntervalSeconds = it }
        input.durationSeconds?.let { action.durationSeconds = it }
        // Sent every time the form is saved, so null is "no icon" rather than
        // "not mentioned" — which is what lets Clear clear it.
        action.icon = input.icon?.trim()?.ifEmpty { null }
        validate(action)

        val message = if (previousName == action.name) {
            "Action ${action.name} updated"
        } else {
            "Action $previousName renamed to ${action.name}"
        }
        auditRecorder.record(action.workspaceId, WorkspaceAuditCategory.WORKFLOW, message)
        return describe(action)
    }

    @MutationMapping
    @Transactional
    fun deleteAction(@Argument id: Long): Boolean {
        val action = actions.findByIdOrNull(id) ?: return false
        requireWorkspaceAccess(action.workspaceId)

        actions.delete(action)
        auditRecorder.record(action.workspaceId, WorkspaceAuditCategory.WORKFLOW, "Action ${action.name} deleted")
        return true
    }

    /**
     * The list's Subtype column, and the settings a form has to show.
     */
    private fun describe(action: WorkflowAction): ActionView {
        val function = action.functionId?.let { functions.findByIdOrNull(it) }
        return ActionView(
            id = requireNotNull(action.id),
            workspaceId = action.workspaceId,
            name = action.name,
            type = action.type,
            subtype = action.subtype,
            subtypeLabel = label(action.subtype),
            connectionId = action.connectionId,
            connectionName = action.connectionId?.let { connections.workspaceConnection(it)?.name },
            connectionAction = action.connectionAction,
            content = action.content,
            target = action.target,
            targetName = action.targetName,
            url = action.url,
            method = action.method,
            headers = action.headers,
            functionId = action.functionId,
            functionName = function?.name,
            mappings = action.mappings.map { ArgumentMappingView(it.argument, it.expression) },
            conditionExpression = action.conditionExpression,
            conditionId = action.conditionId,
            conditionName = action.conditionId?.let { conditions.findByIdOrNull(it)?.name },
            timeoutSeconds = action.timeoutSeconds,
            retryIntervalSeconds = action.retryIntervalSeconds,
            durationSeconds = action.durationSeconds,
            icon = action.icon,
            inputParams = parameters.inputsOf(action),
            outputParams = parameters.outputsOf(action),
        )
    }

    /**
     * A subtype belongs to one type, and each one needs the setting it runs on:
     * an HTTP request without a URL is a form that was not filled in, not an
     * action that fails later.
     */
    private fun validate(action: WorkflowAction) {
        val allowed = when (action.type) {
            ActionType.EXECUTE -> setOf(
                ActionSubtype.OUTGOING_CONNECTION,
                ActionSubtype.HTTP_REQUEST,
                ActionSubtype.FUNCTION,
            )

            ActionType.WAIT -> setOf(
                ActionSubtype.INLINE_CONDITION,
                ActionSubtype.CONDITION,
                ActionSubtype.TIME,
            )
        }
        if (action.subtype !in allowed) throw ActionSubtypeMismatchException(action.type, action.subtype)

        when (action.subtype) {
            ActionSubtype.OUTGOING_CONNECTION -> {
                val connectionId = action.connectionId ?: throw ActionSettingMissingException("a connection")
                val connection = connections.workspaceConnection(connectionId)
                if (connection == null || connection.workspaceId != action.workspaceId) {
                    throw ActionSettingMissingException("a connection this workspace holds")
                }
                if (action.connectionAction == null) throw ActionSettingMissingException("an action to perform")
            }

            ActionSubtype.HTTP_REQUEST -> {
                if (action.url.isNullOrBlank()) throw ActionSettingMissingException("a URL")
                action.method = action.method?.ifBlank { null } ?: "GET"
            }

            ActionSubtype.FUNCTION -> {
                val functionId = action.functionId ?: throw ActionSettingMissingException("a function")
                val function = functions.findByIdOrNull(functionId)
                if (function == null || function.workspaceId != action.workspaceId) {
                    throw ActionSettingMissingException("a function this workspace owns")
                }
            }

            ActionSubtype.INLINE_CONDITION -> {
                if (action.conditionExpression.isNullOrBlank()) throw ActionSettingMissingException("an expression")
                action.timeoutSeconds = action.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS
                action.retryIntervalSeconds = action.retryIntervalSeconds ?: DEFAULT_RETRY_SECONDS
            }

            ActionSubtype.CONDITION -> {
                val conditionId = action.conditionId ?: throw ActionSettingMissingException("a condition")
                val condition = conditions.findByIdOrNull(conditionId)
                if (condition == null || condition.workspaceId != action.workspaceId) {
                    throw ActionSettingMissingException("a condition this workspace holds")
                }
                action.timeoutSeconds = action.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS
                action.retryIntervalSeconds = action.retryIntervalSeconds ?: DEFAULT_RETRY_SECONDS
            }

            ActionSubtype.TIME -> {
                if ((action.durationSeconds ?: 0) <= 0) throw ActionSettingMissingException("a duration in seconds")
            }
        }

        refuseHolders("the message", action.content)
        refuseHolders("who it goes to", action.targetName)
        refuseHolders("the URL", action.url)
        refuseHolders("the headers", action.headers)
        action.mappings.forEach { refuseHolders("the argument ${it.argument}", it.expression) }
    }

    /**
     * Nothing here is substituted, so nothing may look as though it is.
     *
     * A setting is used exactly as written. `{{input.text}}` used to mean "put
     * the incoming text here" and now means those fourteen characters, so a
     * definition holding one would seed nodes that send it verbatim — which is
     * how a workflow came to reply `{{llmResult}}` to somebody. A node says what
     * varies now: leave the setting empty and the node reads the field by name,
     * or point its parameter at any field the run is carrying.
     */
    private fun refuseHolders(setting: String, written: String?) {
        if (written?.contains("{{") == true) throw ActionHoldsPlaceholderException(setting)
    }

    private fun List<ArgumentMappingInput>.toMappings(): MutableList<ArgumentMapping> = this
        .map { ArgumentMapping(argument = it.argument.trim(), expression = it.expression.trim()) }
        .filter { it.argument.isNotEmpty() }
        .toMutableList()

    private fun requireWorkspaceAccess(workspaceId: Long) {
        val workspace = workspaces.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 3600
        const val DEFAULT_RETRY_SECONDS = 30
    }
}

/** "OUTGOING_CONNECTION" -> "Outgoing Connection", as the list shows it. */
fun label(subtype: ActionSubtype): String = subtype.name
    .split('_')
    .joinToString(" ") { word -> word.lowercase().replaceFirstChar(Char::uppercase) }
    .replace("Http", "HTTP")

data class ArgumentMappingInput(val argument: String, val expression: String)

data class CreateActionInput(
    val workspaceId: Long,
    val name: String,
    val type: ActionType,
    val subtype: ActionSubtype,
    val connectionId: Long? = null,
    val connectionAction: ConnectionAction? = null,
    val content: String? = null,
    val target: MessageTarget? = null,
    val targetName: String? = null,
    val url: String? = null,
    val method: String? = null,
    val headers: String? = null,
    val functionId: Long? = null,
    val mappings: List<ArgumentMappingInput>? = null,
    val conditionExpression: String? = null,
    val conditionId: Long? = null,
    val timeoutSeconds: Int? = null,
    val retryIntervalSeconds: Int? = null,
    val durationSeconds: Int? = null,
    /** Which icon a node drawn from this starts with; null draws the kind's own. */
    val icon: String? = null,
)

data class UpdateActionInput(
    val name: String? = null,
    val subtype: ActionSubtype? = null,
    val connectionId: Long? = null,
    val connectionAction: ConnectionAction? = null,
    val content: String? = null,
    val target: MessageTarget? = null,
    val targetName: String? = null,
    val url: String? = null,
    val method: String? = null,
    val headers: String? = null,
    val functionId: Long? = null,
    val mappings: List<ArgumentMappingInput>? = null,
    val conditionExpression: String? = null,
    val conditionId: Long? = null,
    val timeoutSeconds: Int? = null,
    val retryIntervalSeconds: Int? = null,
    val durationSeconds: Int? = null,
    /** Which icon a node drawn from this starts with; null draws the kind's own. */
    val icon: String? = null,
)

data class ArgumentMappingView(val argument: String, val expression: String)

data class ActionParamView(val name: String, val type: ValueType) {
    /** "message: string", which is how both the list and the form read them. */
    val display: String get() = "$name: ${type.name.lowercase()}"
}

data class ActionView(
    val id: Long,
    val workspaceId: Long,
    val name: String,
    val type: ActionType,
    val subtype: ActionSubtype,
    val subtypeLabel: String,
    val connectionId: Long?,
    val connectionName: String?,
    val connectionAction: ConnectionAction?,
    val content: String?,
    val target: MessageTarget?,
    val targetName: String?,
    val url: String?,
    val method: String?,
    val headers: String?,
    val functionId: Long?,
    val functionName: String?,
    val mappings: List<ArgumentMappingView>,
    val conditionExpression: String?,
    val conditionId: Long?,
    val conditionName: String?,
    val timeoutSeconds: Int?,
    val retryIntervalSeconds: Int?,
    val durationSeconds: Int?,
    /** Which icon a node drawn from this starts with; null draws the kind's own. */
    val icon: String?,
    /** Read off the settings, not stored; see `ActionAPI.inputsOf`. */
    val inputParams: List<ActionParamView>,
    val outputParams: List<ActionParamView>,
)

data class ActionPage(
    val content: List<ActionView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<WorkflowAction>, describe: (WorkflowAction) -> ActionView) : this(
        content = page.content.map(describe),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}
