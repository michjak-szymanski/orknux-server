package io.mszymanski.orknux.connector.shell

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/**
 * One agent's session on one shell, and the directory that belongs to it.
 *
 * **A row, not a socket.** This is the decision the rest of the feature hangs
 * off, so it is worth stating plainly. The obvious implementation keeps the SSH
 * connection open for the life of the session and hands it back for each
 * command. That is faster and it leaks: the connection dies with the JVM, and
 * when it does, the directory it made on the far side outlives it with nothing
 * left anywhere that knows the directory was ever ours. Every crash, every
 * deployment, every restart would leave one behind on somebody's disk.
 *
 * A row outlives all three. A restart loses nothing but a socket; the next
 * command reconnects, and the sweep can still find a directory nobody closed and
 * remove it - see [ShellSessionSweeper]. The price is one handshake per command,
 * which is a fraction of a second against a model turn that is several, and
 * nothing here holds a channel open while an agent thinks.
 *
 * **Concurrency.** Nothing stops two sessions existing at once, and nothing
 * should: an agent comparing two hosts, or keeping a build directory while it
 * reads a log, is doing something reasonable. There is a cap per agent, because
 * an agent in a loop makes a directory a turn and the disk that fills is
 * somebody else's. Two commands in one session are possible too - each is its
 * own connection and its own channel - and they share the directory, which is
 * what a working directory is for. Nothing serialises them, because the thing
 * they would be serialised against is a shell, and a shell is already perfectly
 * able to have two people typing in the same directory.
 */
@Entity
@Table(name = "shell_session")
class ShellSession(
    /**
     * The id the agent is given and hands back.
     *
     * A random string rather than a number, so it cannot be walked, and it is
     * also the directory's name on the far side - which is what makes an
     * orphaned directory identifiable by sight rather than by guesswork.
     */
    @Id
    @Column(length = 64)
    val id: String = "",

    @Column(name = "shell_id", nullable = false)
    val shellId: Long = 0,

    /** Null once the agent has been deleted; the session outlives it. */
    @Column(name = "agent_id")
    val agentId: Long? = null,

    /** Kept beside the id so an audit entry still names somebody afterwards. */
    @Column(name = "agent_name", nullable = false, length = 120)
    val agentName: String = "",

    @Column(name = "workspace_id")
    val workspaceId: Long? = null,

    /**
     * The absolute path on the far side.
     *
     * Stored rather than worked out again from the id, so that a change to how
     * paths are chosen cannot orphan every directory already out there under the
     * old scheme.
     */
    @Column(nullable = false, length = 500)
    val directory: String = "",

    @Column(name = "operating_system", length = 200)
    var operatingSystem: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var state: ShellSessionState = ShellSessionState.OPEN,

    @Column(name = "opened_at", nullable = false)
    val openedAt: OffsetDateTime = OffsetDateTime.now(),

    /**
     * Touched by every command, because idleness is what the sweep measures. An
     * agent working for an hour keeps its directory; one that opened a session
     * and wandered off does not keep it forever.
     */
    @Column(name = "last_used_at", nullable = false)
    var lastUsedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "closed_at")
    var closedAt: OffsetDateTime? = null,

    @Column(name = "command_count", nullable = false)
    var commandCount: Int = 0,
)

enum class ShellSessionState {

    OPEN,

    /** Closed by whoever opened it, and its directory removed. */
    CLOSED,

    /** Nobody closed it, so the sweep did. Its directory is removed too. */
    EXPIRED,
}

interface ShellSessionRepository : JpaRepository<ShellSession, String> {

    fun findByIdAndState(id: String, state: ShellSessionState): ShellSession?

    fun countByAgentIdAndState(agentId: Long?, state: ShellSessionState): Long

    fun findAllByStateOrderByLastUsedAtAsc(state: ShellSessionState): List<ShellSession>

    fun findAllByShellIdAndState(shellId: Long, state: ShellSessionState): List<ShellSession>
}

class ShellSessionNotFoundException(id: String) :
    RuntimeException("There is no open shell session called $id")

class NoShellAvailableException :
    RuntimeException("No shell is configured on this installation, or none of them is switched on")

class TooManyShellSessionsException(limit: Int) :
    RuntimeException("You already have $limit shell sessions open. Close one before opening another.")
