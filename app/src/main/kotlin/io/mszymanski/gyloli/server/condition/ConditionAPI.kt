package io.mszymanski.gyloli.server.condition

import io.mszymanski.gyloli.server.action.ValueType
import io.mszymanski.gyloli.server.action.WorkflowActionRepository
import io.mszymanski.gyloli.server.action.WorkflowFunctionRepository
import io.mszymanski.gyloli.server.security.TeamAccess
import io.mszymanski.gyloli.server.team.TeamAuditCategory
import io.mszymanski.gyloli.server.team.TeamAuditRecorder
import io.mszymanski.gyloli.server.team.TeamNotFoundException
import io.mszymanski.gyloli.server.team.TeamRepository
import io.mszymanski.gyloli.server.team.pageRequest
import io.mszymanski.gyloli.server.workflow.WorkflowNodeRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional

/**
 * A team's conditions: the questions its workflows ask.
 *
 * What a condition means in words is not stored — it is read off the definition,
 * the same as an action's parameters — so the description in the list and the
 * sentence under the form always describe what will actually be asked.
 */
@Controller
class ConditionAPI(
    private val conditions: WorkflowConditionRepository,
    private val functions: WorkflowFunctionRepository,
    private val actions: WorkflowActionRepository,
    private val nodes: WorkflowNodeRepository,
    private val teams: TeamRepository,
    private val access: TeamAccess,
    private val auditRecorder: TeamAuditRecorder,
) {

    @QueryMapping
    fun teamConditions(@Argument teamId: Long, @Argument page: Int?, @Argument size: Int?): ConditionPage {
        requireTeamAccess(teamId)
        return ConditionPage(conditions.findByTeamId(teamId, pageRequest(page, size, Sort.by("name"))), ::describe)
    }

    @QueryMapping
    fun condition(@Argument id: Long): ConditionView? {
        val condition = conditions.findByIdOrNull(id) ?: return null
        requireTeamAccess(condition.teamId)
        return describe(condition)
    }

    @MutationMapping
    @Transactional
    fun createCondition(@Argument input: CreateConditionInput): ConditionView {
        requireTeamAccess(input.teamId)
        val name = input.name.trim()
        if (name.isEmpty()) throw ConditionNameInvalidException()
        if (conditions.findByTeamIdAndName(input.teamId, name) != null) throw ConditionNameTakenException(name)

        val condition = conditions.save(
            WorkflowCondition(
                teamId = input.teamId,
                name = name,
                type = input.type,
                property = input.property,
                check = input.check,
                negate = input.negate ?: false,
                functionId = input.functionId,
                values = input.values.orEmpty().clean(),
                members = input.members.orEmpty().toMutableList(),
            ).also { validate(it, itsOwnId = null) },
        )

        auditRecorder.record(input.teamId, TeamAuditCategory.WORKFLOW, "Condition $name created")
        return describe(condition)
    }

    @MutationMapping
    @Transactional
    fun updateCondition(@Argument id: Long, @Argument input: UpdateConditionInput): ConditionView {
        val condition = conditions.findByIdOrNull(id) ?: throw ConditionNotFoundException(id)
        requireTeamAccess(condition.teamId)

        val previousName = condition.name
        input.name?.trim()?.let { name ->
            if (name.isEmpty()) throw ConditionNameInvalidException()
            if (name != condition.name && conditions.findByTeamIdAndName(condition.teamId, name) != null) {
                throw ConditionNameTakenException(name)
            }
            condition.name = name
        }
        input.type?.let { condition.type = it }
        input.property?.let { condition.property = it }
        input.check?.let { condition.check = it }
        input.negate?.let { condition.negate = it }
        input.functionId?.let { condition.functionId = it }
        input.values?.let { condition.values = it.clean() }
        input.members?.let { condition.members = it.toMutableList() }
        // A composite has no property to ask about, and a simple one has no members.
        if (condition.composite) {
            condition.property = null
            condition.check = null
        } else {
            condition.members = mutableListOf()
        }
        validate(condition, itsOwnId = id)

        val message = if (previousName == condition.name) {
            "Condition ${condition.name} updated"
        } else {
            "Condition $previousName renamed to ${condition.name}"
        }
        auditRecorder.record(condition.teamId, TeamAuditCategory.WORKFLOW, message)
        return describe(condition)
    }

    @MutationMapping
    @Transactional
    fun deleteCondition(@Argument id: Long): Boolean {
        val condition = conditions.findByIdOrNull(id) ?: return false
        requireTeamAccess(condition.teamId)

        // Anything pointing at a deleted condition would have nothing to ask.
        val users = buildList {
            addAll(actions.findByTeamId(condition.teamId).filter { it.conditionId == id }.map { it.name })
            addAll(
                conditions.findByTeamId(condition.teamId)
                    .filter { id in it.members }
                    .map { it.name },
            )
            addAll(nodes.findByConditionId(id).map { "a workflow node" }.distinct())
        }
        if (users.isNotEmpty()) throw ConditionInUseException(condition.name, users)

        conditions.delete(condition)
        auditRecorder.record(condition.teamId, TeamAuditCategory.WORKFLOW, "Condition ${condition.name} deleted")
        return true
    }

    /** The list's Type badge and Description, and the sentence under the form. */
    private fun describe(condition: WorkflowCondition): ConditionView {
        val function = condition.functionId?.let { functions.findByIdOrNull(it) }
        val names = condition.members.mapNotNull { conditions.findByIdOrNull(it)?.name }
        return ConditionView(
            id = requireNotNull(condition.id),
            teamId = condition.teamId,
            name = condition.name,
            type = condition.type,
            typeLabel = typeLabel(condition.type),
            property = condition.property,
            check = condition.check,
            negate = condition.negate,
            functionId = condition.functionId,
            functionName = function?.name,
            values = condition.values.toList(),
            members = condition.members.toList(),
            memberNames = names,
            description = sentence(condition, function?.name, names),
        )
    }

    /**
     * What the condition says, in words. The list shows it as the description
     * and the form shows it under the fields, so both read what is really asked.
     */
    private fun sentence(condition: WorkflowCondition, functionName: String?, memberNames: List<String>): String {
        val negated = condition.negate
        return when (condition.type) {
            ConditionType.ANY_OF ->
                "Matches when any of ${memberNames.joinToString(", ").ifEmpty { "the selected conditions" }} " +
                    "is true".prefixIfNegated(negated)

            ConditionType.ALL_OF ->
                "Matches when all of ${memberNames.joinToString(", ").ifEmpty { "the selected conditions" }} " +
                    "are true".prefixIfNegated(negated)

            ConditionType.FUNCTION ->
                "Matches when ${functionName ?: "the function"} answers true".prefixIfNegated(negated)

            else -> {
                val property = condition.property?.let(::label) ?: "what arrives"
                val phrase = when (condition.check) {
                    ConditionCheck.IN_LIST -> "is one of ${condition.values.size} listed values"
                    ConditionCheck.EQUALS -> "is ${condition.values.firstOrNull() ?: "the listed value"}"
                    ConditionCheck.CONTAINS -> "contains ${condition.values.firstOrNull() ?: "the listed text"}"
                    ConditionCheck.MATCHES -> "matches ${condition.values.firstOrNull() ?: "the pattern"}"
                    ConditionCheck.BETWEEN ->
                        "is between ${condition.values.getOrNull(0) ?: "?"} and ${condition.values.getOrNull(1) ?: "?"}"

                    ConditionCheck.TEAMMATE -> "is a teammate"
                    null -> "is checked"
                }
                if (negated) {
                    "Negated: $property must NOT be what is checked — $phrase"
                } else {
                    "Matches when $property $phrase".replaceFirstChar(Char::uppercase)
                }
            }
        }
    }

    private fun String.prefixIfNegated(negated: Boolean) = if (negated) "$this — negated" else this

    /**
     * A condition has to be answerable: the property has to belong to the type,
     * the check to the property, and a composite has to name conditions that
     * exist and do not contain it.
     */
    private fun validate(condition: WorkflowCondition, itsOwnId: Long?) {
        when (condition.type) {
            ConditionType.ANY_OF, ConditionType.ALL_OF -> {
                if (condition.members.size < 2) throw ConditionMembersRequiredException()
                condition.members.forEach { member ->
                    val held = conditions.findByIdOrNull(member)
                    if (held == null || held.teamId != condition.teamId) throw ConditionNotFoundException(member)
                    if (itsOwnId != null && contains(held, itsOwnId, depth = 0)) {
                        throw ConditionCycleException(condition.name)
                    }
                    if (member == itsOwnId) throw ConditionCycleException(condition.name)
                }
            }

            ConditionType.FUNCTION -> {
                val functionId = condition.functionId ?: throw ConditionFunctionRequiredException()
                val function = functions.findByIdOrNull(functionId)
                if (function == null || function.teamId != condition.teamId) {
                    throw ConditionFunctionRequiredException()
                }
                // The whole point of a condition is a yes or a no.
                if (function.returnType != ValueType.BOOLEAN) {
                    throw ConditionFunctionNotBooleanException(
                        function.name,
                        function.returnType.name.lowercase(),
                    )
                }
            }

            else -> {
                val property = condition.property ?: throw ConditionPropertyMismatchException(
                    condition.type,
                    ConditionProperty.MESSAGE_AUTHOR,
                )
                if (property !in propertiesOf(condition.type)) {
                    throw ConditionPropertyMismatchException(condition.type, property)
                }
                val check = condition.check ?: throw ConditionCheckMismatchException(property, ConditionCheck.EQUALS)
                if (check !in checksOf(property)) throw ConditionCheckMismatchException(property, check)

                val needed = when (check) {
                    ConditionCheck.BETWEEN -> 2
                    ConditionCheck.TEAMMATE, ConditionCheck.IN_LIST -> 1
                    else -> 1
                }
                if (condition.values.size < needed) throw ConditionValuesRequiredException(check)
            }
        }
    }

    /** Whether [candidate] is somewhere inside this condition, at any depth. */
    private fun contains(condition: WorkflowCondition, candidate: Long, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return true
        if (condition.id == candidate) return true
        return condition.members.any { member ->
            conditions.findByIdOrNull(member)?.let { contains(it, candidate, depth + 1) } == true
        }
    }

    private fun List<String>.clean(): MutableList<String> =
        map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

    private fun requireTeamAccess(teamId: Long) {
        val team = teams.findByIdOrNull(teamId) ?: throw TeamNotFoundException(teamId)
        access.requireVisible(team)
    }

    private companion object {
        const val MAX_DEPTH = 10
    }
}

/** Which properties each type can ask about; the form offers exactly these. */
fun propertiesOf(type: ConditionType): List<ConditionProperty> = when (type) {
    ConditionType.SLACK -> listOf(
        ConditionProperty.MESSAGE_AUTHOR,
        ConditionProperty.MESSAGE_CHANNEL,
        ConditionProperty.MESSAGE_TEXT,
    )

    ConditionType.JIRA -> listOf(
        ConditionProperty.ISSUE_PRIORITY,
        ConditionProperty.ISSUE_STATUS,
        ConditionProperty.ISSUE_TYPE,
    )

    ConditionType.TIME -> listOf(ConditionProperty.CURRENT_TIME)
    ConditionType.FUNCTION, ConditionType.ANY_OF, ConditionType.ALL_OF -> emptyList()
}

/** Which checks make sense for a property. */
fun checksOf(property: ConditionProperty): List<ConditionCheck> = when (property) {
    ConditionProperty.MESSAGE_AUTHOR -> listOf(
        ConditionCheck.TEAMMATE,
        ConditionCheck.IN_LIST,
        ConditionCheck.EQUALS,
        ConditionCheck.MATCHES,
    )

    ConditionProperty.MESSAGE_CHANNEL -> listOf(ConditionCheck.IN_LIST, ConditionCheck.EQUALS, ConditionCheck.MATCHES)
    ConditionProperty.MESSAGE_TEXT -> listOf(ConditionCheck.CONTAINS, ConditionCheck.MATCHES, ConditionCheck.EQUALS)
    ConditionProperty.ISSUE_PRIORITY,
    ConditionProperty.ISSUE_STATUS,
    ConditionProperty.ISSUE_TYPE,
    -> listOf(ConditionCheck.IN_LIST, ConditionCheck.EQUALS)

    ConditionProperty.CURRENT_TIME -> listOf(ConditionCheck.BETWEEN)
}

/** "ANY_OF" -> "Any Of", as the badge and the form show it. */
fun typeLabel(type: ConditionType): String = type.name
    .split('_')
    .joinToString(" ") { word -> word.lowercase().replaceFirstChar(Char::uppercase) }

data class CreateConditionInput(
    val teamId: Long,
    val name: String,
    val type: ConditionType,
    val property: ConditionProperty? = null,
    val check: ConditionCheck? = null,
    val negate: Boolean? = null,
    val functionId: Long? = null,
    val values: List<String>? = null,
    val members: List<Long>? = null,
)

data class UpdateConditionInput(
    val name: String? = null,
    val type: ConditionType? = null,
    val property: ConditionProperty? = null,
    val check: ConditionCheck? = null,
    val negate: Boolean? = null,
    val functionId: Long? = null,
    val values: List<String>? = null,
    val members: List<Long>? = null,
)

data class ConditionView(
    val id: Long,
    val teamId: Long,
    val name: String,
    val type: ConditionType,
    val typeLabel: String,
    val property: ConditionProperty?,
    val check: ConditionCheck?,
    val negate: Boolean,
    val functionId: Long?,
    val functionName: String?,
    val values: List<String>,
    val members: List<Long>,
    val memberNames: List<String>,
    /** What it asks, in words; read off the definition rather than stored. */
    val description: String,
)

data class ConditionPage(
    val content: List<ConditionView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<WorkflowCondition>, describe: (WorkflowCondition) -> ConditionView) : this(
        content = page.content.map(describe),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}
