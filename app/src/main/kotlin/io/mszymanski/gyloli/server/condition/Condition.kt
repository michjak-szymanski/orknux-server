package io.mszymanski.gyloli.server.condition

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

/** What a condition asks about. */
enum class ConditionType {
    /** A Slack event: who said it, where, and what it said. */
    SLACK,

    /** A Jira issue: its priority, status or type. */
    JIRA,

    /** The clock, rather than anything the run is carrying. */
    TIME,

    /**
     * One of the team's functions, which has to return a boolean. This is the
     * way out when a question needs more than a property and a check.
     */
    FUNCTION,

    /** True when any of the conditions it names is. */
    ANY_OF,

    /** True when all of them are. */
    ALL_OF,
}

/**
 * Which part of what arrived is being asked about.
 *
 * Each one reads a field of the run's input, which is what a trigger put there;
 * `ConditionEvaluator.valueOf` is where that mapping lives.
 */
enum class ConditionProperty {
    MESSAGE_AUTHOR,
    MESSAGE_CHANNEL,
    MESSAGE_TEXT,
    ISSUE_PRIORITY,
    ISSUE_STATUS,
    ISSUE_TYPE,

    /** The time the condition is asked, not something in the input. */
    CURRENT_TIME,
}

/** How the property is tested. */
enum class ConditionCheck {
    /** The value is one of the listed ones. */
    IN_LIST,
    EQUALS,
    CONTAINS,

    /** The value matches a regular expression. */
    MATCHES,

    /** The time is between the two listed times, as HH:mm. */
    BETWEEN,

    /** The value names someone in the team's directory group. */
    TEAMMATE,
}

/**
 * A question a team asks about what a run is carrying, defined once.
 *
 * Conditions are used from two places — a wait that holds until one holds, and a
 * condition node that stops a run when one does not — so they are a catalogue
 * like actions and triggers, not settings inside whatever uses them.
 *
 * [negate] is what makes "Is External User" the same definition as "Is Teammate
 * Message" with the answer turned round, which is how the design has it.
 */
@Entity
@Table(name = "workflow_condition")
class WorkflowCondition(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "team_id", nullable = false)
    val teamId: Long,

    @Column(nullable = false, length = 120)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var type: ConditionType,

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    var property: ConditionProperty? = null,

    /** `check` is reserved in SQL, hence the column name. */
    @Enumerated(EnumType.STRING)
    @Column(name = "check_by", length = 16)
    var check: ConditionCheck? = null,

    @Column(nullable = false)
    var negate: Boolean = false,

    /** The function that answers a [ConditionType.FUNCTION] condition. */
    @Column(name = "function_id")
    var functionId: Long? = null,

    /** What the check compares against; empty for one that needs nothing. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "workflow_condition_value", joinColumns = [JoinColumn(name = "condition_id")])
    @OrderColumn(name = "position")
    @Column(name = "value", length = 500)
    var values: MutableList<String> = mutableListOf(),

    /** The conditions a composite is made of, in the order they were added. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "workflow_condition_member", joinColumns = [JoinColumn(name = "condition_id")])
    @OrderColumn(name = "position")
    @Column(name = "member_id")
    var members: MutableList<Long> = mutableListOf(),
) {

    val composite: Boolean get() = type == ConditionType.ANY_OF || type == ConditionType.ALL_OF
}

interface WorkflowConditionRepository : JpaRepository<WorkflowCondition, Long> {

    fun findByTeamId(teamId: Long, pageable: Pageable): Page<WorkflowCondition>

    fun findByTeamId(teamId: Long): List<WorkflowCondition>

    fun findByTeamIdAndName(teamId: Long, name: String): WorkflowCondition?
}

class ConditionNotFoundException(id: Long) : RuntimeException("No condition with id $id")

class ConditionNameTakenException(name: String) :
    RuntimeException("A condition named \"$name\" already exists in this team")

class ConditionNameInvalidException : RuntimeException("A condition name is required")

class ConditionPropertyMismatchException(type: ConditionType, property: ConditionProperty) :
    RuntimeException("A ${type.name.lowercase()} condition cannot ask about ${property.name.lowercase()}")

class ConditionCheckMismatchException(property: ConditionProperty, check: ConditionCheck) :
    RuntimeException(
        "${property.name.lowercase().replace('_', ' ')} cannot be tested with " +
            check.name.lowercase().replace('_', ' '),
    )

class ConditionValuesRequiredException(check: ConditionCheck) :
    RuntimeException("A ${check.name.lowercase().replace('_', ' ')} check needs something to compare against")

class ConditionMembersRequiredException :
    RuntimeException("A composite condition needs at least two conditions to combine")

class ConditionCycleException(name: String) :
    RuntimeException("$name would contain itself")

class ConditionInUseException(name: String, used: List<String>) :
    RuntimeException("$name is used by ${used.joinToString(", ")}")

class ConditionFunctionRequiredException :
    RuntimeException("A function condition needs a function to call")

class ConditionFunctionNotBooleanException(name: String, returnType: String) :
    RuntimeException("$name returns $returnType; a condition needs a function that returns a boolean")
