package io.mszymanski.orknux.connector.shell

import tools.jackson.databind.ObjectMapper
import io.mszymanski.orknux.connector.connection.McpClient
import io.mszymanski.orknux.connector.connection.McpServer
import io.mszymanski.orknux.connector.connection.McpServerRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

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
        val answer = mapper.readTree(mcp.call(server, RUN_TOOL, mapper.writeValueAsString(arguments)))

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
