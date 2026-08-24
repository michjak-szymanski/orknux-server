package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.action.ScriptImport
import io.mszymanski.orknux.server.action.ValueType
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
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime

/**
 * One argument a tool takes, in the order it takes them.
 *
 * The same shape a function's parameter has, and deliberately so: both are
 * arguments to a script in the same sandbox, and a workspace that has learnt
 * what a parameter is in one editor should not have to learn it again in the
 * other. [ValueType] is borrowed from the workflow side rather than copied for
 * the same reason — two enumerations of the same six shapes would drift.
 *
 * What it is *not* is a schema the model is held to. Everything a provider is
 * told about a tool's arguments is a string, so the type here is what the
 * editor annotates the code with and what the agent is told the argument means;
 * it is not a promise the argument arrives already shaped.
 */
@Embeddable
class AgentToolParam(
    @Column(name = "name", nullable = false, length = 64)
    var name: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    var type: ValueType = ValueType.STRING,

    /**
     * Which object, when the type is one. Null for everything else.
     *
     * Spelled the way a function's parameter spells it — a column holding an id,
     * no foreign key — so a deleted object leaves a dangling id that is reported
     * rather than a delete that is refused.
     */
    @Column(name = "object_id")
    var objectId: Long? = null,
)

/**
 * A named piece of JavaScript an agent may call while it runs.
 *
 * The difference from a workflow function is who calls it. A function is called
 * by an action node, at a point the graph fixed in advance; a tool is offered to
 * an agent, which calls it if it judges that it should. The sandbox is the same
 * one — `ScriptRunner` — so what is stored here is only ever text.
 */
@Entity
@Table(name = "agent_tool")
class AgentTool(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(nullable = false, length = 120)
    var name: String,

    /**
     * What the tool is for, in a sentence. This is not decoration: it is what an
     * agent reads to decide whether to call it.
     */
    @Column(length = 500)
    var description: String? = null,

    /** What runs: the JavaScript the editor compiled from [typescript]. */
    @Column(nullable = false, columnDefinition = "text")
    var source: String,

    /**
     * What was written, kept beside what runs.
     *
     * Reopening a tool has to show the author their own code rather than the
     * compiler's output, and the sandbox has to be handed JavaScript — so both
     * are stored, and they are only ever written together.
     */
    @Column(nullable = false, columnDefinition = "text")
    var typescript: String,

    /**
     * What it takes, in the order the sandbox passes it.
     *
     * A tool used to take exactly one argument called `input`, hard-coded in two
     * places: the schema the model was shown and the single-element list the
     * sandbox was handed. That was not a signature anybody could read or change
     * — the only account of what a tool wanted was a sentence in its
     * description, and the model had to guess the rest.
     *
     * So it is stored, like a function's. Existing tools were given the one
     * parameter they always had, which is why nothing about them changed.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_tool_param", joinColumns = [JoinColumn(name = "tool_id")])
    @OrderColumn(name = "position")
    var params: MutableList<AgentToolParam> = mutableListOf(),

    /**
     * The workspace's functions this tool calls, under the names it calls them.
     *
     * The same [ScriptImport] a function's list holds, and pointing at the same
     * table: a tool and a function are the same JavaScript in the same sandbox, and
     * a workspace that has worked out how importing goes in one editor should not
     * have to work it out again in the other.
     *
     * One direction only. A tool may import a function; nothing imports a tool,
     * because a tool is what an agent decides to call and not a piece anybody
     * builds out of.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_tool_import", joinColumns = [JoinColumn(name = "tool_id")])
    @OrderColumn(name = "position")
    var imports: MutableList<ScriptImport> = mutableListOf(),

    /** Off leaves it defined but out of reach, which a delete would not. */
    @Column(nullable = false)
    var enabled: Boolean = true,

    @Column(name = "last_modified_at", nullable = false)
    var lastModifiedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_by", nullable = false, length = 120)
    var lastModifiedBy: String = "",
) {

    /** "(city: string, days: number)", as the entity can say it without names. */
    val signature: String
        get() = params.joinToString(", ", "(", ")") { "${it.name}: ${it.type.name.lowercase()}" }
}

interface AgentToolRepository : JpaRepository<AgentTool, Long> {

    fun findByWorkspaceId(workspaceId: Long, pageable: Pageable): Page<AgentTool>

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): AgentTool?

    /** Every tool that imports the function with this id, asked before a delete. */
    @Query("select t from AgentTool t join t.imports i where i.importedId = :functionId")
    fun findByImportedFunctionId(functionId: Long): List<AgentTool>
}

class ToolNotFoundException(id: Long) : RuntimeException("No tool with id $id")

class ToolNameTakenException(name: String) :
    RuntimeException("A tool named \"$name\" already exists in this workspace")

class ToolNameInvalidException(name: String) :
    RuntimeException("\"$name\" is not a name a script can be called by")

class ToolSourceInvalidException(reason: String) : RuntimeException(reason)

class ToolParamInvalidException(name: String) :
    RuntimeException("\"$name\" is not a name a parameter can have")

/**
 * Two of a tool's parameters answer to the same name.
 *
 * Refused rather than kept, because the model addresses them by name: two
 * called `query` are one the agent can fill and one it cannot reach, and which
 * is which is decided by whatever the provider's JSON does with a repeated key.
 */
class ToolParamDuplicateException(name: String) :
    RuntimeException("This tool already takes a parameter called \"$name\"")

/**
 * A parameter says it takes an object without saying which.
 *
 * OBJECT is a reference to something the workspace defined; on its own it is not
 * a type at all. MAP is what to use for a shape nobody has written down — and the
 * message says so, because that is the choice being made.
 */
class ToolObjectRequiredException(name: String) : RuntimeException(
    "\"$name\" is declared as an object but no object is chosen. Pick one of this " +
        "workspace's objects, or use map for a structure without a defined shape.",
)

/**
 * One half of a tool's code arrived without the other.
 *
 * Refused rather than guessed at: compiling TypeScript is the editor's job and
 * nothing on this side can strip types or put them back, so a save that carried
 * only one half would leave the two permanently out of step.
 */
class ToolCodeIncompleteException(missing: String) : RuntimeException(
    "The $missing is missing. A tool's TypeScript and the JavaScript compiled " +
        "from it are saved together, so that what runs is always what was written.",
)

/**
 * A tool an agent may call is not one to delete.
 *
 * Named agents rather than a count, because the way out is to go and take the
 * grant off each of them and "2 agents" does not say which. The agents are said
 * as "the agent Answerer" for the reason a workflow is: the sentence is read on
 * the tool list, where nothing else on the screen is an agent.
 */
class ToolInUseException(name: String, agents: List<String>) : RuntimeException(
    "$name is granted to ${agents.joinToString(", ")}, so it cannot be deleted",
)
