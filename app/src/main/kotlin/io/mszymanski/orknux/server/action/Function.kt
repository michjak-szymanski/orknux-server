package io.mszymanski.orknux.server.action

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embeddable
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
import java.time.OffsetDateTime

/** The shape of a value crossing the boundary between a workflow and a script. */
enum class ValueType {
    STRING,
    NUMBER,
    BOOLEAN,
    OBJECT,
    ARRAY,

    /**
     * Nothing at all.
     *
     * A function that posts a message or writes a row has no answer to give,
     * and making it declare an object it does not have leaves a node with an
     * output port nothing will ever read.
     */
    NONE,
}

/** One argument a function takes, in the order it takes them. */
@Embeddable
class FunctionParam(
    @Column(name = "name", nullable = false, length = 64)
    var name: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    var type: ValueType = ValueType.STRING,
)

/**
 * A variable this function is handed, whatever calls it.
 *
 * A declared parameter is the caller's to fill; an external one is not. The
 * workspace decides what it holds, and the function receives it as an argument
 * after the ones it declares — which is how a script gets at a secret without
 * anybody pasting the secret into a graph.
 */
@Embeddable
class FunctionExternal(
    @Column(name = "variable_id", nullable = false)
    var variableId: Long = 0,
)

/**
 * A named piece of JavaScript a workspace wrote, callable from an action.
 *
 * The source is a module whose default export is the function; it runs in the
 * sandbox `ScriptRunner` builds, with no host, no files and no network, so what
 * is stored here is only ever text.
 */
@Entity
@Table(name = "workflow_function")
class WorkflowFunction(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(nullable = false, length = 120)
    var name: String,

    @Column(length = 500)
    var description: String? = null,

    @Column(nullable = false, columnDefinition = "text")
    var source: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "return_type", nullable = false, length = 16)
    var returnType: ValueType = ValueType.OBJECT,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "workflow_function_param", joinColumns = [JoinColumn(name = "function_id")])
    @OrderColumn(name = "position")
    var params: MutableList<FunctionParam> = mutableListOf(),

    /**
     * The workspace's variables this function is handed, in the order it
     * receives them — after everything it declares.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "workflow_function_external", joinColumns = [JoinColumn(name = "function_id")])
    @OrderColumn(name = "position")
    var externals: MutableList<FunctionExternal> = mutableListOf(),

    @Column(name = "last_modified_at", nullable = false)
    var lastModifiedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_by", nullable = false, length = 120)
    var lastModifiedBy: String = "",
) {

    /** "(input: object, format: string)", as the list shows it. */
    val signature: String
        get() = params.joinToString(", ", "(", ")") { "${it.name}: ${it.type.name.lowercase()}" }
}

interface WorkflowFunctionRepository : JpaRepository<WorkflowFunction, Long> {

    fun findByWorkspaceId(workspaceId: Long, pageable: Pageable): Page<WorkflowFunction>

    fun findByWorkspaceId(workspaceId: Long): List<WorkflowFunction>

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): WorkflowFunction?
}

class FunctionNotFoundException(id: Long) : RuntimeException("No function with id $id")

class FunctionNameTakenException(name: String) :
    RuntimeException("A function named \"$name\" already exists in this workspace")

class FunctionNameInvalidException(name: String) :
    RuntimeException("\"$name\" is not a name a script can be called by")

class FunctionParamInvalidException(name: String) :
    RuntimeException("\"$name\" is not a name a parameter can have")

class FunctionSourceInvalidException(reason: String) : RuntimeException(reason)

class FunctionInUseException(name: String, actions: List<String>) :
    RuntimeException("$name is called by ${actions.joinToString(", ")}")
