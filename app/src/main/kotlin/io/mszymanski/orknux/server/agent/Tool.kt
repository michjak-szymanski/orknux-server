package io.mszymanski.orknux.server.agent

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

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

    /** Off leaves it defined but out of reach, which a delete would not. */
    @Column(nullable = false)
    var enabled: Boolean = true,

    @Column(name = "last_modified_at", nullable = false)
    var lastModifiedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_by", nullable = false, length = 120)
    var lastModifiedBy: String = "",
)

interface AgentToolRepository : JpaRepository<AgentTool, Long> {

    fun findByWorkspaceId(workspaceId: Long, pageable: Pageable): Page<AgentTool>

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): AgentTool?
}

class ToolNotFoundException(id: Long) : RuntimeException("No tool with id $id")

class ToolNameTakenException(name: String) :
    RuntimeException("A tool named \"$name\" already exists in this workspace")

class ToolNameInvalidException(name: String) :
    RuntimeException("\"$name\" is not a name a script can be called by")

class ToolSourceInvalidException(reason: String) : RuntimeException(reason)

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
