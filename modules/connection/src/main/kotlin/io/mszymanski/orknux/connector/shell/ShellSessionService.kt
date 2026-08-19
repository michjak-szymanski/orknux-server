package io.mszymanski.orknux.connector.shell

import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Opening a session, running a command in it, and closing it.
 *
 * The three things an agent can do with a shell, and the whole of what the
 * bridge is. Nothing here decides who may call it: the grant on the agent is
 * checked in orknux-server, which is also where the audit entry is written,
 * because this module has no idea who is asking.
 *
 * Nothing here is inside a transaction on purpose. A command may take a minute,
 * and a database connection held open for the length of somebody's `apt-get
 * update` is a pool exhausted by four agents doing ordinary work. The SSH is
 * done first and the row is written afterwards, in the one call that needs it.
 */
@Service
class ShellSessionService(
    private val shells: ShellRepository,
    private val sessions: ShellSessionRepository,
    private val service: ShellService,
    private val client: ShellClient,
    private val properties: ShellProperties,
) {

    /**
     * Opens a session on whichever shell answers, and makes it a directory.
     *
     * Which shell is [ShellService.choose]'s decision and is explained there.
     * The directory is made before the row is written, so a session that exists
     * in the database is one whose directory exists on the far side - the other
     * order gives an agent a session id for a place that is not there.
     */
    fun open(agentId: Long?, agentName: String, workspaceId: Long?): OpenedShellSession {
        val open = sessions.countByAgentIdAndState(agentId, ShellSessionState.OPEN)
        if (open >= properties.maxSessionsPerAgent) {
            throw TooManyShellSessionsException(properties.maxSessionsPerAgent)
        }

        val shell = service.choose()
        val id = UUID.randomUUID().toString().replace("-", "")
        val directory = "${properties.directoryRoot.trimEnd('/')}/$id"

        val opened = client.connected(shell) { session, fingerprint ->
            if (shell.hostKey.isNullOrBlank() && fingerprint != null) {
                shell.hostKey = fingerprint
                shells.save(shell)
            }

            val made = client.run(session, makeDirectory(directory), null)
            if (made.exitCode != 0 || !made.stdout.contains(READY)) {
                throw ShellUnreachableException(
                    "${shell.name} would not make a working directory at $directory: " +
                        (made.stderr.trim().ifEmpty { made.stdout.trim() }.ifEmpty { "it said nothing" }),
                )
            }
            client.operatingSystem(session)
        }

        val stored = sessions.save(
            ShellSession(
                id = id,
                shellId = requireNotNull(shell.id),
                agentId = agentId,
                agentName = agentName.take(NAME_LENGTH),
                workspaceId = workspaceId,
                directory = directory,
                operatingSystem = opened,
            ),
        )

        return OpenedShellSession(
            sessionId = stored.id,
            shellName = shell.name,
            operatingSystem = opened,
            directory = directory,
        )
    }

    /**
     * Runs a command in a session, with that session's directory current.
     *
     * A non-zero exit comes back in [ShellRun.exitCode] rather than as an
     * exception, and it is the caller's job to say so plainly. The only things
     * that throw here are a session that is not open and a machine that cannot
     * be reached.
     */
    fun run(sessionId: String, command: String): ShellCommandOutcome {
        val session = sessions.findByIdAndState(sessionId, ShellSessionState.OPEN)
            ?: throw ShellSessionNotFoundException(sessionId)
        val shell = shells.findByIdOrNull(session.shellId)
            ?: throw ShellUnreachableException("The shell this session was opened on has been removed")

        val outcome = client.connected(shell) { open, _ -> client.run(open, command, session.directory) }

        session.lastUsedAt = OffsetDateTime.now()
        session.commandCount += 1
        sessions.save(session)

        return ShellCommandOutcome(
            sessionId = session.id,
            shellName = shell.name,
            directory = session.directory,
            run = outcome,
        )
    }

    /**
     * Closes a session and destroys its directory.
     *
     * The directory goes first and the row is only marked closed if it did. A
     * row marked closed over a directory that is still there is a leak nothing
     * would ever look for again - the sweep only reads the open ones, which is
     * what makes the row the record of the directory's existence.
     */
    fun close(sessionId: String): ClosedShellSession {
        val session = sessions.findByIdAndState(sessionId, ShellSessionState.OPEN)
            ?: throw ShellSessionNotFoundException(sessionId)
        val shell = shells.findByIdOrNull(session.shellId)
            ?: throw ShellUnreachableException("The shell this session was opened on has been removed")

        client.connected(shell) { open, _ -> service.removeDirectory(open, session) }

        session.state = ShellSessionState.CLOSED
        session.closedAt = OffsetDateTime.now()
        sessions.save(session)

        return ClosedShellSession(session.id, shell.name, session.directory, session.commandCount)
    }

    /**
     * Closes a session the sweep found, rather than one an agent asked about.
     *
     * Separated from [close] because the failure means something different. An
     * agent that cannot close its session should hear about it; a sweep that
     * cannot reach a host should note it and come back in ten minutes, and
     * eventually give up - see [ShellSessionSweeper].
     */
    internal fun expire(session: ShellSession): Boolean {
        val shell = shells.findByIdOrNull(session.shellId)
        if (shell == null) {
            // The shell is gone, so its directory went with the machine as far
            // as anything here can tell. Nothing left to remove.
            session.state = ShellSessionState.EXPIRED
            session.closedAt = OffsetDateTime.now()
            sessions.save(session)
            return true
        }

        return try {
            client.connected(shell) { open, _ -> service.removeDirectory(open, session) }
            session.state = ShellSessionState.EXPIRED
            session.closedAt = OffsetDateTime.now()
            sessions.save(session)
            true
        } catch (failure: Exception) {
            log.warn(
                "Shell session {} on {} could not be tidied up: {}",
                session.id,
                shell.name,
                failure.message,
            )
            false
        }
    }

    /**
     * Made before the row exists, and made restrictive.
     *
     * `mkdir -p` on the root as well, because the first session on a machine
     * has nowhere to be made yet. The mode is set on both and failures on the
     * root are swallowed: a root that already exists and belongs to somebody
     * else is not this session's problem, and its own directory is what has to
     * be private.
     *
     * The echo at the end is how we know: `mkdir -p` on a path that already
     * exists succeeds silently, and an exit code alone would not tell an
     * unwritable filesystem from a working one.
     */
    private fun makeDirectory(directory: String): String {
        val root = properties.directoryRoot.trimEnd('/')
        return "mkdir -p '$root' 2>/dev/null; chmod 700 '$root' 2>/dev/null; " +
            "mkdir -p '$directory' && chmod 700 '$directory' && echo $READY"
    }

    private companion object {
        val log = LoggerFactory.getLogger(ShellSessionService::class.java)

        const val READY = "orknux-session-ready"
        const val NAME_LENGTH = 120
    }
}

/** What an agent is told when a session opens: an id, and what it is talking to. */
data class OpenedShellSession(
    val sessionId: String,
    val shellName: String,
    /** What `uname -sr` said, or null when the far side would not answer it. */
    val operatingSystem: String?,
    val directory: String,
)

/** One command, and where it ran. */
data class ShellCommandOutcome(
    val sessionId: String,
    val shellName: String,
    val directory: String,
    val run: ShellRun,
)

data class ClosedShellSession(
    val sessionId: String,
    val shellName: String,
    val directory: String,
    val commandCount: Int,
)
