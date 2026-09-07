package io.mszymanski.orknux.connector.shell

import tools.jackson.databind.ObjectMapper
import io.mszymanski.orknux.connector.connection.McpClient
import io.mszymanski.orknux.connector.connection.McpHandshake
import io.mszymanski.orknux.connector.connection.McpServer
import io.mszymanski.orknux.connector.connection.McpServerRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/** An opened MCP shell session: the id to thread through runs, and whether it jails. */
data class OpenedMcpShell(val session: String, val isolated: Boolean)

/**
 * A shell whose far side is DesktopCommander, spoken to as an MCP server.
 *
 * The one place in the product that knows which server implements an MCP shell.
 * Everything above works in shell terms - open a session, run a command, close
 * it - and this maps those onto the tools DesktopCommander exposes:
 * `start_process` and `read_process_output` for a command, `search_code` and
 * the file tools available to whoever writes against it directly. The name
 * DesktopCommander appears here and nowhere else; the abstraction above is
 * "MCP shell", so a different grep-tier server could sit behind these rows by
 * rewriting this file alone. Issue #337.
 *
 * It holds no session of its own. DesktopCommander runs each command in the
 * working directory it is told, so a "session" here is the directory the
 * caller carries, the same one the SSH side makes - which is why open() only
 * has to create it and run() only has to pass it.
 *
 * Nothing here reaches the network itself. Every call goes through [McpClient],
 * which carries the stored credential and obeys the proxy rules, exactly as an
 * agent's own MCP tool call does.
 */
@Component
class McpShellClient(
    private val mcp: McpClient,
    private val servers: McpServerRepository,
    private val mapper: ObjectMapper,
) {

    /** The server this shell speaks through, or a refusal that reads in the log. */
    private fun serverOf(shell: Shell): McpServer =
        shell.mcpServerId?.let { servers.findByIdOrNull(it) }
            ?: throw ShellUnreachableException("${shell.name} names no MCP server, or one that has been removed")

    /**
     * Runs one command and returns it in the shape the SSH side returns.
     *
     * DesktopCommander answers a run as a block of text rather than a structured
     * exit; a tool that could not be called comes back as an MCP error, which
     * [McpClient] has already turned into `{ "error": ... }`. So a call that
     * carries an error is unreachable, and one that does not is an exit of zero
     * with the text as stdout - which is the honest reading of a transport that
     * does not separate the two, and matches what a plugin's own `orknux.http`
     * does with the same ambiguity.
     */
    fun run(shell: Shell, command: String, directory: String?): ShellRun {
        val server = serverOf(shell)
        val arguments = mapper.createObjectNode().apply {
            put("command", command)
            directory?.let { put("cwd", it) }
        }
        return outcome(shell, mcp.call(server, RUN_TOOL, mapper.writeValueAsString(arguments)))
    }

    /**
     * Opens a session, learning whether the server jails it.
     *
     * When it does ([OpenedMcpShell.isolated]), the session is the sandbox and a
     * shell keeps it open and runs on it - no directory of its own to make or
     * remove. When it does not, the caller lets this session go and falls back
     * to the directory a command carries, the way it always did. Issue #337.
     */
    fun open(shell: Shell): OpenedMcpShell {
        val server = serverOf(shell)
        return when (val handshake = mcp.openSession(server)) {
            is McpHandshake.Open -> OpenedMcpShell(handshake.session, handshake.sessionIsolation)
            is McpHandshake.Refused -> throw ShellUnreachableException("${shell.name}: ${handshake.reason}")
        }
    }

    /** Runs one command on an open session, so it lands in that session's own root. */
    fun runOn(shell: Shell, command: String, session: String): ShellRun {
        val server = serverOf(shell)
        val arguments = mapper.createObjectNode().put("command", command)
        return outcome(shell, mcp.callOn(server, session, RUN_TOOL, mapper.writeValueAsString(arguments)))
    }

    /** What the far side is on an open session, for the note the session carries. */
    fun operatingSystemOn(shell: Shell, session: String): String? =
        runCatching { runOn(shell, "uname -sr", session).stdout.trim().ifEmpty { null } }.getOrNull()

    /** Ends a session, so the server destroys the root it jailed for it. */
    fun close(shell: Shell, session: String) {
        runCatching { mcp.closeSession(serverOf(shell), session) }
    }

    /**
     * A run's answer, in the shape the SSH side returns.
     *
     * DesktopCommander answers a run as a block of text rather than a structured
     * exit; a tool that could not be called comes back as an MCP error, which
     * [McpClient] has already turned into `{ "error": ... }`. So a call that
     * carries an error is unreachable, and one that does not is an exit of zero
     * with the text as stdout.
     */
    private fun outcome(shell: Shell, result: String): ShellRun {
        val answer = mapper.readTree(result)
        answer.path("error").takeIf { !it.isMissingNode }?.let {
            throw ShellUnreachableException("${shell.name}: ${it.stringValue() ?: "the MCP shell refused the command"}")
        }
        val text = answer.path("result").stringValue().orEmpty()
        return ShellRun(
            exitCode = 0,
            stdout = text,
            stderr = "",
            stdoutTruncated = false,
            stderrTruncated = false,
            timedOut = false,
        )
    }

    /** What the far side is, best effort, for the note an opened session carries. */
    fun operatingSystem(shell: Shell): String? =
        runCatching { run(shell, "uname -sr", null).stdout.trim().ifEmpty { null } }.getOrNull()

    /** Whether the server answers at all, for the Check button and the pool. */
    fun reachable(shell: Shell): Boolean =
        runCatching {
            val server = serverOf(shell)
            mapper.readTree(mcp.call(server, RUN_TOOL, mapper.writeValueAsString(mapper.createObjectNode().put("command", "true"))))
                .path("error").isMissingNode
        }.getOrDefault(false)

    private companion object {
        /**
         * DesktopCommander's command tool. Named here once; if the server behind
         * these rows changes, this is the line that changes with it.
         */
        const val RUN_TOOL = "start_process"
    }
}
