package io.mszymanski.gyloli.server.trigger

import io.mszymanski.gyloli.connector.connection.TeamConnectionService
import io.mszymanski.gyloli.server.security.TeamAccess
import io.mszymanski.gyloli.server.team.TeamAuditCategory
import io.mszymanski.gyloli.server.team.TeamAuditRecorder
import io.mszymanski.gyloli.server.team.TeamNotFoundException
import io.mszymanski.gyloli.server.team.TeamRepository
import io.mszymanski.gyloli.server.team.pageRequest
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

/**
 * A team's trigger catalogue: the events it can start work from, each defined
 * once. Nothing here names a workflow — a workflow points one of its trigger
 * nodes at a definition, and that instance is what wires the two together.
 */
@Controller
class TriggerAPI(
    private val triggers: WorkflowTriggerRepository,
    private val connections: TeamConnectionService,
    private val mapper: ObjectMapper,
    private val teams: TeamRepository,
    private val access: TeamAccess,
    private val auditRecorder: TeamAuditRecorder,
) {

    @QueryMapping
    fun teamTriggers(@Argument teamId: Long, @Argument page: Int?, @Argument size: Int?): TriggerPage {
        requireTeamAccess(teamId)
        return TriggerPage(triggers.findByTeamId(teamId, pageRequest(page, size, Sort.by("name"))), ::describe)
    }

    @QueryMapping
    fun trigger(@Argument id: Long): TriggerView? {
        val trigger = triggers.findByIdOrNull(id) ?: return null
        requireTeamAccess(trigger.teamId)
        return describe(trigger)
    }

    @MutationMapping
    @Transactional
    fun createTrigger(@Argument input: CreateTriggerInput): TriggerView {
        val name = input.name.trim()
        if (name.isEmpty()) throw TriggerNameInvalidException()
        requireTeamAccess(input.teamId)
        if (triggers.findByTeamIdAndName(input.teamId, name) != null) throw TriggerNameTakenException(name)

        val trigger = triggers.save(
            WorkflowTrigger(
                teamId = input.teamId,
                name = name,
                type = input.type,
                connectionId = input.connectionId.takeIf { input.type == TriggerType.INCOMING_CONNECTION },
                action = input.action.takeIf { input.type == TriggerType.INCOMING_CONNECTION },
                cron = input.cron?.trim()?.ifEmpty { null }.takeIf { input.type == TriggerType.SCHEDULED },
                timezone = input.timezone?.trim()?.ifEmpty { null }.takeIf { input.type == TriggerType.SCHEDULED },
                payload = input.payload?.trim()?.ifEmpty { null }?.also(::requireJsonObject),
            ).also(::validate),
        )

        auditRecorder.record(input.teamId, TeamAuditCategory.WORKFLOW, "Trigger $name created")
        return describe(trigger)
    }

    /** Backs the trigger settings form; the team and the type are what it is. */
    @MutationMapping
    @Transactional
    fun updateTrigger(@Argument id: Long, @Argument input: UpdateTriggerInput): TriggerView {
        val trigger = triggers.findByIdOrNull(id) ?: throw TriggerNotFoundException(id)
        requireTeamAccess(trigger.teamId)

        val name = input.name.trim()
        if (name.isEmpty()) throw TriggerNameInvalidException()
        if (name != trigger.name && triggers.findByTeamIdAndName(trigger.teamId, name) != null) {
            throw TriggerNameTakenException(name)
        }
        val previousName = trigger.name
        trigger.name = name
        if (trigger.type == TriggerType.INCOMING_CONNECTION) {
            input.connectionId?.let { trigger.connectionId = it }
            input.action?.let { trigger.action = it }
        } else {
            input.cron?.let { trigger.cron = it.trim().ifEmpty { null } }
            input.timezone?.let { trigger.timezone = it.trim().ifEmpty { null } }
        }
        input.payload?.let { trigger.payload = it.trim().ifEmpty { null }?.also(::requireJsonObject) }
        validate(trigger)

        val message = if (name == previousName) "Trigger $name updated" else "Trigger $previousName renamed to $name"
        auditRecorder.record(trigger.teamId, TeamAuditCategory.WORKFLOW, message)
        return describe(trigger)
    }

    /** The toggle in the list. A disabled trigger stays, and stops firing. */
    @MutationMapping
    @Transactional
    fun setTriggerEnabled(@Argument id: Long, @Argument enabled: Boolean): TriggerView {
        val trigger = triggers.findByIdOrNull(id) ?: throw TriggerNotFoundException(id)
        requireTeamAccess(trigger.teamId)

        trigger.enabled = enabled
        auditRecorder.record(
            trigger.teamId,
            TeamAuditCategory.WORKFLOW,
            "Trigger ${trigger.name} ${if (enabled) "enabled" else "disabled"}",
        )
        return describe(trigger)
    }

    @MutationMapping
    @Transactional
    fun deleteTrigger(@Argument id: Long): Boolean {
        val trigger = triggers.findByIdOrNull(id) ?: return false
        requireTeamAccess(trigger.teamId)

        triggers.delete(trigger)
        auditRecorder.record(trigger.teamId, TeamAuditCategory.WORKFLOW, "Trigger ${trigger.name} deleted")
        return true
    }

    /**
     * The list shows where an event comes from and what it is: the connection
     * for an incoming trigger, the clock and the expression for a scheduled one.
     */
    private fun describe(trigger: WorkflowTrigger): TriggerView {
        val connection = trigger.connectionId?.let { connections.teamConnection(it) }
        return TriggerView(
            id = requireNotNull(trigger.id),
            teamId = trigger.teamId,
            name = trigger.name,
            type = trigger.type,
            connectionId = trigger.connectionId,
            action = trigger.action,
            cron = trigger.cron,
            timezone = trigger.timezone,
            payload = trigger.payload,
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
                // The connection has to be one this team holds.
                val connection = connections.teamConnection(connectionId)
                if (connection == null || connection.teamId != trigger.teamId) {
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

    private fun requireTeamAccess(teamId: Long) {
        val team = teams.findByIdOrNull(teamId) ?: throw TeamNotFoundException(teamId)
        access.requireVisible(team)
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
    val teamId: Long,
    val name: String,
    val type: TriggerType,
    val connectionId: Long? = null,
    val action: TriggerAction? = null,
    val cron: String? = null,
    val timezone: String? = null,
    /** JSON object handed to the runs this starts. */
    val payload: String? = null,
)

data class UpdateTriggerInput(
    val name: String,
    val connectionId: Long? = null,
    val action: TriggerAction? = null,
    val cron: String? = null,
    val timezone: String? = null,
    val payload: String? = null,
)

data class TriggerView(
    val id: Long,
    val teamId: Long,
    val name: String,
    val type: TriggerType,
    val connectionId: Long?,
    val action: TriggerAction?,
    val cron: String?,
    val timezone: String?,
    /** JSON object handed to the runs this starts; null when it says nothing. */
    val payload: String?,
    val enabled: Boolean,
    /** Where the event comes from, ready to show: the connection, or "Cron". */
    val source: String,
    /** What the event is, ready to show: "Mention", or the cron expression. */
    val event: String,
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
