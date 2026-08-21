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
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/** The shape of a value crossing the boundary between a workflow and a script. */
enum class ValueType {
    STRING,
    NUMBER,
    BOOLEAN,

    /**
     * One of the workspace's objects, named by id alongside this.
     *
     * A shape somebody defined, so the editor can annotate a parameter with it and
     * the language service can check what the code does to it. Without the id this
     * says nothing, which is why anything carrying it must carry that too.
     */
    OBJECT,

    /**
     * Keys and values, with no promise about either.
     *
     * What OBJECT used to mean on its own. Kept as its own type rather than as
     * OBJECT-with-nothing-attached, because "I have not said what this is" and "this
     * is genuinely free-form" are different statements, and only one of them is
     * worth prompting somebody to fix.
     */
    MAP,

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

/**
 * How a value's shape is written in TypeScript.
 *
 * The editor's annotations, and the stubs a new function starts from, are written
 * against this — so a parameter declared as an object is annotated the way the
 * language service will actually check it.
 *
 * An object is `Record<string, unknown>` and an array `unknown[]`, not `object` and
 * `any[]`: everything crossing into the sandbox arrived as JSON, so what is inside
 * is genuinely unknown until the code looks. `unknown` makes the code look;
 * `any` would let a typo through with no complaint, which is the whole reason for
 * having types here at all.
 */
fun typeScriptType(type: ValueType, objectName: String? = null): String = when (type) {
    ValueType.STRING -> "string"
    ValueType.NUMBER -> "number"
    ValueType.BOOLEAN -> "boolean"
    // The object's own name, which the editor declares as an interface. Falls back
    // to the loose shape if the object it named has since been deleted — an
    // annotation that does not resolve would light up code that still runs.
    ValueType.OBJECT -> objectName ?: "Record<string, unknown>"
    ValueType.MAP -> "Record<string, unknown>"
    ValueType.ARRAY -> "unknown[]"
    // Only ever a return type, and a function returning nothing returns void.
    ValueType.NONE -> "void"
}

/** One argument a function takes, in the order it takes them. */
@Embeddable
class FunctionParam(
    @Column(name = "name", nullable = false, length = 64)
    var name: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    var type: ValueType = ValueType.STRING,

    /**
     * Which object, when the type is one. Null for everything else.
     *
     * Spelled the same way an object's own property spells it — a column holding an
     * id, no foreign key — so the two places that point at an object point at it
     * alike, and a deleted object leaves a dangling id that is reported rather than
     * a delete that is refused.
     */
    @Column(name = "object_id")
    var objectId: Long? = null,
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

/** Where a function came from, and therefore who may change it. */
enum class FunctionScope {

    /** Written in a workspace, editable there. */
    WORKSPACE,

    /**
     * Declared by a plugin. Available in every workspace and externally managed:
     * usable from actions, triggers and conditions, but not editable — the plugin
     * that declared it is the only thing that can change it, by being loaded again.
     *
     * Named for where it came from rather than how far it reaches. "Organisation"
     * would describe the visibility and hide the useful part: a picker offering
     * `isTeammate` should say which plugin brought it, because that is what tells
     * somebody what it does and who to ask.
     */
    PLUGIN,
}

/**
 * A named piece of JavaScript a workspace wrote, callable from an action.
 *
 * The source is a module whose default export is the function; it runs in the
 * sandbox `ScriptRunner` builds, with no host, no files and no network, so what
 * is stored here is only ever text.
 *
 * Or it is a function a plugin declared, in which case it belongs to the
 * organisation rather than to a workspace and nothing here is editable. The two
 * are exclusive, and the database says so as well as this comment does.
 */
@Entity
@Table(name = "workflow_function")
class WorkflowFunction(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /** Null for an organisation function: it belongs to no single workspace. */
    @Column(name = "workspace_id")
    val workspaceId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var scope: FunctionScope = FunctionScope.WORKSPACE,

    /** The plugin that declared it, and whose unloading takes it away. */
    @Column(name = "plugin_id")
    var pluginId: Long? = null,

    @Column(nullable = false, length = 120)
    var name: String,

    @Column(length = 500)
    var description: String? = null,

    /**
     * The JavaScript that runs.
     *
     * Compiled from [typescript] for anything a workspace wrote — the sandbox runs
     * JavaScript and nothing compiles at run time, so what runs is stored compiled.
     */
    @Column(nullable = false, columnDefinition = "text")
    var source: String,

    /**
     * The TypeScript it was written in, or null when it was not written here.
     *
     * The editor's left column. Kept beside the compiled JavaScript rather than
     * instead of it, because neither can be recovered from the other: types are
     * gone by the time it is JavaScript, and nothing in the sandbox could compile
     * it back. They are written in the same save, and the editor compiles the one
     * it is saving rather than reusing an earlier compile — so what is stored here
     * is always the source of what is stored above.
     *
     * Null for a plugin's function: it was written in the plugin, and this is not
     * where it changes.
     */
    @Column(columnDefinition = "text")
    var typescript: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "return_type", nullable = false, length = 16)
    var returnType: ValueType = ValueType.MAP,

    /** Which object it returns, when it returns one. Null for everything else. */
    @Column(name = "return_object_id")
    var returnObjectId: Long? = null,

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

    /** "(input: map, format: string)", as the entity can say it without names. */
    val signature: String
        get() = params.joinToString(", ", "(", ")") { "${it.name}: ${it.type.name.lowercase()}" }

    /**
     * Whether a workspace may change this.
     *
     * False for anything a plugin declared. Asked by the API before every edit and
     * reported to the screen, so the button that is hidden and the mutation that
     * refuses are answering the same question.
     */
    val editable: Boolean
        get() = scope == FunctionScope.WORKSPACE
}

interface WorkflowFunctionRepository : JpaRepository<WorkflowFunction, Long> {

    fun findByWorkspaceId(workspaceId: Long, pageable: Pageable): Page<WorkflowFunction>

    fun findByWorkspaceId(workspaceId: Long): List<WorkflowFunction>

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): WorkflowFunction?

    /**
     * One workspace's functions and the organisation's together.
     *
     * A single query, so the page and the ordering are the database's answer
     * rather than something reassembled afterwards from two of them.
     */
    @Query("select f from WorkflowFunction f where f.workspaceId = :workspaceId or f.scope = 'PLUGIN'")
    fun findByWorkspaceIdOrPlugin(workspaceId: Long, pageable: Pageable): Page<WorkflowFunction>

    fun findByScopeAndName(scope: FunctionScope, name: String): WorkflowFunction?

    /** Everything one plugin declared, which is what a reload reconciles against. */
    fun findByPluginId(pluginId: Long): List<WorkflowFunction>
}

class FunctionNotFoundException(id: Long) : RuntimeException("No function with id $id")

class FunctionNameTakenException(name: String) :
    RuntimeException("A function named \"$name\" already exists in this workspace")

/**
 * Somebody tried to change a function a plugin declared.
 *
 * These are the plugin's to define. Loading the plugin again is how they change;
 * nothing in a workspace may edit or delete one.
 */
class FunctionExternallyManagedException(name: String) : RuntimeException(
    "\"$name\" is provided by a plugin and cannot be changed here. " +
        "Load the plugin again to change what it declares.",
)

class FunctionNameInvalidException(name: String) :
    RuntimeException("\"$name\" is not a name a script can be called by")

class FunctionParamInvalidException(name: String) :
    RuntimeException("\"$name\" is not a name a parameter can have")

/**
 * A parameter says it takes an object without saying which.
 *
 * OBJECT is a reference to something the workspace defined; on its own it is not a
 * type at all. MAP is what to use for a shape nobody has written down — and the
 * message says so, because that is the choice being made.
 */
class FunctionObjectRequiredException(name: String) : RuntimeException(
    "\"$name\" is declared as an object but no object is chosen. Pick one of this " +
        "workspace's objects, or use map for a structure without a defined shape.",
)

class FunctionSourceInvalidException(reason: String) : RuntimeException(reason)

/**
 * One half of a function's code arrived without the other.
 *
 * A function is written in TypeScript and runs as the JavaScript compiled from it,
 * and the two are stored together for one reason: either on its own is a lie.
 * Storing JavaScript alone would show the next person the compiler's output as
 * though they had written it; storing TypeScript alone would leave the sandbox
 * running the version before the edit.
 *
 * So they are saved in the same write or not at all. Refused here rather than
 * guessed at, because there is no way to derive the missing one — the compiler
 * lives in the editor, and this side cannot type-strip or un-type-strip anything.
 */
class FunctionCodeIncompleteException(missing: String) : RuntimeException(
    "The $missing is missing. A function's TypeScript and the JavaScript compiled " +
        "from it are saved together, so that what runs is always what was written.",
)

/**
 * The code and the declared parameters disagree about how many arguments there are.
 *
 * Says both numbers and where the second one comes from, because the mismatch is
 * usually an external somebody added in the panel and did not add to the code —
 * and "expected 2, found 1" on its own does not point at that.
 */
class FunctionSignatureMismatchException(
    found: Int,
    params: Int,
    externals: Int,
) : RuntimeException(
    "The code takes $found ${argument(found)}, but this function is handed " +
        "${params + externals}: $params declared" +
        (if (externals > 0) " and $externals from the workspace" else "") +
        ". They are passed in that order, so the code has to accept all of them.",
) {
    private companion object {
        fun argument(count: Int): String = if (count == 1) "argument" else "arguments"
    }
}

/**
 * A function something still calls is not one to delete.
 *
 * The callers are actions, conditions and the webhooks that authenticate with
 * one. Actions and conditions arrive as bare names because a workspace's lists
 * are what somebody is looking at when they read this; a webhook is said as "the
 * webhook Nightly", because it is not in any of those lists and a bare name
 * would send the reader looking for an action that does not exist.
 */
class FunctionInUseException(name: String, callers: List<String>) :
    RuntimeException("$name is called by ${callers.joinToString(", ")}")
