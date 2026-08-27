package io.mszymanski.orknux.server.shell

import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.connector.shell.ShellSessionService
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.workspace.AuditRedaction
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * The three things an agent can do with a shell.
 *
 * Open a session and be told its id and what the machine is; run a command in
 * that session, with the session's own directory current; close it and have the
 * directory destroyed. That is the whole bridge, and it is deliberately three
 * calls rather than one - a single "run this on a machine" tool would give an
 * agent nowhere to put a file between two commands, which is most of what it
 * would want a machine for.
 *
 * **Which machine.** None of these takes a shell name, because from an agent's
 * point of view there is one question - can I run a command somewhere - and the
 * shells are an administrator's business. Which one a session lands on is
 * decided when it opens; the answer names it, so an agent can say where it ran.
 *
 * **What a failure looks like.** A command that exits non-zero is a *result*
 * and comes back as one, with its code and both streams. `grep` finding nothing
 * exits 1 and a model told "that failed" would apologise for a working search.
 * Only the things that stop a command from running at all - no shell configured,
 * a machine that will not answer, a key the far side refuses, a session id that
 * is not open - come back as an error, and each says which of those it was.
 *
 * **What is written down.** Every one of these three, in the audit log, under
 * the agent's own name. That is not decoration. The design of this feature is
 * that the machine is the boundary and the administrator secures it, and that is
 * only a fair bargain if the administrator can afterwards read every command
 * that was run.
 *
 * **Minus its credentials.** A command line is where a live password most often
 * ends up in the clear - a token in a git remote, a `curl -u`, an exported
 * `…_TOKEN` - and the audit log is read on a page any administrator can open and
 * copied into every backup. What is written down is the command with everything
 * that reads like a credential replaced by `***`; `AuditRedaction` says what it
 * finds and, more usefully, what it does not.
 */
@Service
class ShellTools(
    private val sessions: ShellSessionService,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val mapper: ObjectMapper,
) {

    /** What to offer an agent that has been granted the shells. */
    fun specs(): List<ToolSpec> = listOf(
        ToolSpec(
            name = OPEN,
            description =
                "Opens a session on one of this installation's machines and answers with its id, what the " +
                    "operating system is, and an empty working directory that belongs to you alone. Every " +
                    "command you then run in the session starts in that directory, so files you write in one " +
                    "command are there for the next. Close the session when you are done with it - the " +
                    "directory is destroyed with it.",
            parameters = emptyList(),
        ),
        ToolSpec(
            name = RUN,
            description =
                "Runs one shell command in a session you opened, with that session's directory current. " +
                    "Answers with the exit code and both output streams. A non-zero exit is an answer and not " +
                    "an error - read the code and the streams and decide what it means. Long output is cut and " +
                    "says so; a command that has not finished in a minute is stopped and says so.",
            parameters = listOf(
                ToolParameterSpec("sessionId", "The id you were given when the session opened.", required = true),
                ToolParameterSpec(
                    "command",
                    "The command, as you would type it. It is handed to the account's own shell, so pipes, " +
                        "redirection and `&&` all work.",
                    required = true,
                ),
            ),
        ),
        ToolSpec(
            name = CLOSE,
            description =
                "Closes a session and destroys its working directory and everything in it. Do this when you " +
                    "have finished, and take anything you still need out of the directory first.",
            parameters = listOf(
                ToolParameterSpec("sessionId", "The id of the session to close.", required = true),
            ),
        ),
    )

    /**
     * Whether this is one of ours.
     *
     * The three names exactly, rather than everything starting with `shell_`. A
     * prefix would quietly swallow a workspace's own tool that happened to be
     * called `shell_deploy`, and the workspace would never find out why its tool
     * stopped being called.
     */
    fun handles(name: String): Boolean = name in NAMES

    /**
     * Runs one call and answers as JSON.
     *
     * The grant is checked here as well as at the point the tools are offered.
     * An agent that was never offered these can still name one - models invent
     * tool names - and the refusal has to come from the place that would
     * otherwise do the work, not only from the place that draws the menu.
     */
    fun run(agent: Agent, name: String, arguments: String): String {
        if (!agent.shellAccess) {
            return refuse("This agent has not been given access to the shells")
        }

        return try {
            when (name) {
                OPEN -> open(agent)
                RUN -> runCommand(agent, arguments)
                CLOSE -> close(agent, arguments)
                else -> refuse("There is no tool called $name")
            }
        } catch (failure: Exception) {
            log.warn("Shell tool {} failed for agent {}", name, agent.name, failure)
            refuse(failure.message ?: "That could not be done")
        }
    }

    private fun open(agent: Agent): String {
        val opened = sessions.open(agent.id, agent.name, agent.workspaceId)

        auditRecorder.recordAutomated(
            agent.workspaceId,
            WorkspaceAuditCategory.SHELL,
            "Shell session ${opened.sessionId} opened on ${opened.shellName} by ${agent.name}",
            agent.name,
        )

        return mapper.writeValueAsString(
            mapOf(
                "sessionId" to opened.sessionId,
                "operatingSystem" to (opened.operatingSystem ?: "unknown"),
                "shell" to opened.shellName,
                "workingDirectory" to opened.directory,
                "note" to "Commands run in this session start in ${opened.directory}. Close it when you are done.",
            ),
        )
    }

    private fun runCommand(agent: Agent, arguments: String): String {
        val sessionId = argument(arguments, "sessionId")
            ?: return refuse("Say which session to run in; open one first if you have not.")
        val command = argument(arguments, "command")
            ?: return refuse("Say what command to run.")

        val outcome = sessions.run(sessionId, command)

        /*
         * Written down after it ran and with what it did, rather than before it
         * ran. An entry saying a command was attempted and nothing saying what
         * happened is the one an administrator would most want and least trust.
         * The command is trimmed to fit the column; the point of the entry is
         * that somebody can see what was done, and a command longer than this
         * is a script, which the entry says by being cut.
         *
         * Redacted before it is trimmed, and trimmed after. The recorder redacts
         * everything it writes and would catch this anyway, but only what it is
         * given: a credential lying across the 300th character would be cut in
         * half first, and half a token in the audit log is still half a token
         * that should not be there. Doing it in this order means the marker
         * replaces the whole of it and the trim falls on ordinary text.
         */
        val written = AuditRedaction.redact(command).take(COMMAND_IN_AUDIT)

        auditRecorder.recordAutomated(
            agent.workspaceId,
            WorkspaceAuditCategory.SHELL,
            "${agent.name} ran on ${outcome.shellName}: $written " +
                "(${describeExit(outcome.run.exitCode, outcome.run.timedOut)})",
            agent.name,
        )

        return mapper.writeValueAsString(
            buildMap {
                put("sessionId", outcome.sessionId)
                put("shell", outcome.shellName)
                put("workingDirectory", outcome.directory)
                put("exitCode", outcome.run.exitCode)
                put("stdout", outcome.run.stdout)
                put("stderr", outcome.run.stderr)
                /*
                 * What is missing is the middle, and the output itself says how
                 * much - so this says where the gap is and what is still whole,
                 * and leaves the number to the marker rather than printing a
                 * second, vaguer version of the same fact beside it.
                 */
                if (outcome.run.stdoutTruncated || outcome.run.stderrTruncated) {
                    put(
                        "truncated",
                        "This output was longer than this can carry, so its middle was removed and a line in " +
                            "its place says how much. The beginning and the end are both complete, so the " +
                            "last line you can see is the last line the command printed. Run it again " +
                            "through grep if you need what was in between.",
                    )
                }
                if (outcome.run.timedOut) {
                    put(
                        "timedOut",
                        "The command had not finished when we stopped waiting, so there is no exit code. It " +
                            "may still be running on the machine - closing a channel does not stop a process.",
                    )
                }
            },
        )
    }

    private fun close(agent: Agent, arguments: String): String {
        val sessionId = argument(arguments, "sessionId") ?: return refuse("Say which session to close.")
        val closed = sessions.close(sessionId)

        auditRecorder.recordAutomated(
            agent.workspaceId,
            WorkspaceAuditCategory.SHELL,
            "Shell session ${closed.sessionId} on ${closed.shellName} closed by ${agent.name} " +
                "after ${closed.commandCount} commands",
            agent.name,
        )

        return mapper.writeValueAsString(
            mapOf(
                "sessionId" to closed.sessionId,
                "closed" to true,
                "commandsRun" to closed.commandCount,
                "note" to "${closed.directory} and everything in it has been destroyed.",
            ),
        )
    }

    private fun describeExit(exitCode: Int?, timedOut: Boolean): String = when {
        timedOut -> "still running when we stopped waiting"
        exitCode == null -> "no exit code"
        exitCode == 0 -> "exit 0"
        else -> "exit $exitCode"
    }

    /** Arguments arrive as a JSON object in a string, whichever shape asked. */
    private fun argument(arguments: String, name: String): String? = runCatching {
        mapper.readTree(arguments).path(name).stringValue()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun refuse(why: String): String = mapper.writeValueAsString(mapOf("error" to why))

    private companion object {
        val log = LoggerFactory.getLogger(ShellTools::class.java)

        const val OPEN = "shell_open_session"
        const val RUN = "shell_run_command"
        const val CLOSE = "shell_close_session"

        val NAMES = setOf(OPEN, RUN, CLOSE)

        /** The audit message column is 500 characters and holds more than this. */
        const val COMMAND_IN_AUDIT = 300
    }
}
