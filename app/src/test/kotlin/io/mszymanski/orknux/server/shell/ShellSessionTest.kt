package io.mszymanski.orknux.server.shell

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.shell.Shell
import io.mszymanski.orknux.connector.shell.ShellRepository
import io.mszymanski.orknux.connector.shell.ShellService
import io.mszymanski.orknux.connector.shell.ShellSessionRepository
import io.mszymanski.orknux.connector.shell.ShellSessionSweeper
import io.mszymanski.orknux.connector.shell.ShellSessionState
import io.mszymanski.orknux.connector.shell.ShellStatus
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.chat.AgentTools
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.common.util.OsUtils
import org.apache.sshd.common.util.security.SecurityUtils
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.time.Duration
import java.time.OffsetDateTime

/**
 * The shell bridge, against a real SSH server.
 *
 * A container running OpenSSH rather than a stubbed transport, because the thing
 * most likely to be wrong here is the conversation itself - a key in the wrong
 * format, an exec channel that never closes, a working directory that is not
 * where the command actually ran - and a stub that answers what the code asks
 * for cannot tell you about any of them. Testcontainers is already how this
 * suite gets a database and a directory; this is the same argument a third time.
 *
 * The key is generated per run and handed to the container as an authorised key,
 * so nothing is checked in and nothing on the developer's machine is touched.
 */
@SpringBootTest(
    properties = [
        // The sweep is driven by hand here. Left on its own timer it would run
        // in the middle of a test and check hosts these tests are asserting the
        // status of.
        "orknux.shell.sweep-initial-delay=1h",
        // Short enough that a command which will not finish does not hold the
        // suite for a minute.
        "orknux.shell.command-timeout=5s",
        // Small enough to reach with one line of output rather than 64 KiB of it.
        "orknux.shell.max-output-bytes=512",
    ],
)
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ShellSessionTest(
    @Autowired val shells: ShellRepository,
    @Autowired val sessions: ShellSessionRepository,
    @Autowired val service: ShellService,
    @Autowired val sweeper: ShellSessionSweeper,
    @Autowired val tools: AgentTools,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var shellId: Long = 0
    private lateinit var granted: Agent
    private lateinit var refused: Agent

    @BeforeEach
    fun reset() {
        sessions.deleteAll()
        shells.deleteAll()
        agents.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        val workspaceId = requireNotNull(workspaces.save(Workspace(name = "operations")).id)
        granted = agents.save(
            Agent(workspaceId = workspaceId, name = "sre", type = AgentType.LLM, shellAccess = true),
        )
        refused = agents.save(
            Agent(workspaceId = workspaceId, name = "writer", type = AgentType.LLM, shellAccess = false),
        )

        shellId = requireNotNull(
            shells.save(
                Shell(
                    name = "box",
                    host = server.host,
                    port = server.getMappedPort(SSH_PORT),
                    username = ACCOUNT,
                    privateKey = privateKey,
                ),
            ).id,
        )
    }

    @Test
    fun `a session opens and reports the operating system it landed on`() {
        val opened = mapper.readTree(call(granted, "shell_open_session"))

        assertThat(opened.path("error").isMissingNode).isTrue()
        assertThat(opened.path("sessionId").stringValue()).isNotBlank()
        // What `uname` said, not a word this code chose. The container is Linux
        // and the value carries its kernel, which is the useful half.
        assertThat(opened.path("operatingSystem").stringValue()).startsWith("Linux")
        assertThat(opened.path("shell").stringValue()).isEqualTo("box")
    }

    @Test
    fun `a command runs in the session's own directory`() {
        val sessionId = openSession(granted)

        // `pwd` is the whole assertion: the directory the command ran in is the
        // one the session was given, and not the account's home.
        val where = mapper.readTree(run(granted, sessionId, "pwd"))
        assertThat(where.path("stdout").stringValue().trim()).isEqualTo(where.path("workingDirectory").stringValue())

        // And it persists between commands, which is the reason a session exists
        // at all rather than a single "run this somewhere" tool.
        run(granted, sessionId, "echo hello > note.txt")
        val read = mapper.readTree(run(granted, sessionId, "cat note.txt"))
        assertThat(read.path("exitCode").intValue()).isEqualTo(0)
        assertThat(read.path("stdout").stringValue().trim()).isEqualTo("hello")
    }

    @Test
    fun `closing a session destroys its directory`() {
        val sessionId = openSession(granted)
        val directory = mapper.readTree(run(granted, sessionId, "pwd")).path("workingDirectory").stringValue()

        val closed = mapper.readTree(call(granted, "shell_close_session", """{"sessionId":"$sessionId"}"""))
        assertThat(closed.path("closed").booleanValue()).isTrue()

        // Asked of the machine rather than of our own bookkeeping. A row saying
        // closed over a directory that is still there is exactly the leak this
        // whole design is arranged to prevent.
        val second = openSession(granted)
        val look = mapper.readTree(run(granted, second, "test -d '$directory'; echo gone:$?"))
        assertThat(look.path("stdout").stringValue()).contains("gone:1")

        assertThat(sessions.findById(sessionId).get().state).isEqualTo(ShellSessionState.CLOSED)
    }

    @Test
    fun `a command that exits non-zero comes back as a result rather than an error`() {
        val sessionId = openSession(granted)

        val answer = mapper.readTree(run(granted, sessionId, "grep nothing-is-here /etc/hostname"))

        // No error key at all. `grep` finding nothing exits 1, and a model told
        // "that failed" would apologise for a search that worked perfectly.
        assertThat(answer.path("error").isMissingNode).isTrue()
        assertThat(answer.path("exitCode").intValue()).isEqualTo(1)
        assertThat(answer.path("stdout").stringValue()).isEmpty()
    }

    @Test
    fun `a command that will not finish is stopped, and says so`() {
        val sessionId = openSession(granted)

        val answer = mapper.readTree(run(granted, sessionId, "sleep 60"))

        // No exit code, because there was none - and the wording does not claim
        // the process was killed, because closing a channel does not kill one.
        assertThat(answer.path("exitCode").isNull).isTrue()
        assertThat(answer.path("timedOut").stringValue()).contains("may still be running")
    }

    @Test
    fun `output longer than the limit loses its middle and keeps the last thing printed`() {
        val sessionId = openSession(granted)

        /*
         * Issue #287, over a real connection. The sentinel is printed last on
         * purpose, because that is where every tool worth running on one of
         * these machines puts its verdict - `cannot find symbol`, the failed
         * assertion, the package that conflicts - and the buffer used to keep
         * the beginning and throw exactly that away. A model handed the
         * beginning cannot tell a command that failed from one whose output
         * stopped, which are opposite conclusions.
         */
        val answer = mapper.readTree(run(granted, sessionId, "seq 1 200000; echo THE-ANSWER-IS-HERE"))
        val stdout = answer.path("stdout").stringValue()

        assertThat(answer.path("exitCode").intValue()).isEqualTo(0)
        assertThat(stdout).endsWith("THE-ANSWER-IS-HERE\n")
        // And the front is still there, which is what makes this a middle
        // removed rather than a tail kept.
        assertThat(stdout).startsWith("1\n2\n3\n")

        // The marker names an amount. That number is the difference between
        // "some of this is missing" and "this is all there was".
        assertThat(stdout).containsPattern("… [0-9.]+ (KiB|MiB) of output removed from the middle\\.")

        // Still bounded: what came from the far side is inside the installation's
        // 512 bytes, and the marker is this application's own sentence on top of
        // it rather than something the command printed.
        assertThat(stdout.length).isLessThanOrEqualTo(512 + MARKER_ROOM)

        assertThat(answer.path("truncated").stringValue()).contains("its middle was removed")
    }

    @Test
    fun `output that fits is handed back exactly as the command printed it`() {
        val sessionId = openSession(granted)

        // The common case, and it has to be untouched: no marker, nothing said
        // about truncation, and the bytes the command wrote. A short answer that
        // looked cut would make every short answer suspect.
        val answer = mapper.readTree(run(granted, sessionId, "printf 'one\\ntwo\\nthree\\n'"))

        assertThat(answer.path("stdout").stringValue()).isEqualTo("one\ntwo\nthree\n")
        assertThat(answer.path("truncated").isMissingNode).isTrue()
    }

    @Test
    fun `a machine given a timeout of its own is held to that one, not the installation's`() {
        /*
         * Both directions in one test, because a fallback has two halves and a
         * test that only drove one of them would pass on a build that ignored
         * the column entirely.
         *
         * The installation allows five seconds here. `sleep 3` is chosen to sit
         * between that and the second this machine is given, so the same command
         * has to come back two different ways depending on nothing but the row.
         */
        shells.save(shells.findById(shellId).orElseThrow().apply { commandTimeoutSeconds = 1 })

        val stopped = mapper.readTree(run(granted, openSession(granted), "sleep 3"))
        assertThat(stopped.path("exitCode").isNull).isTrue()
        assertThat(stopped.path("timedOut").stringValue()).contains("may still be running")

        // And with the column back to null the machine is on the installation's
        // five seconds again, where the same command finishes. Null has to keep
        // meaning "whatever the installation says" rather than "nothing".
        shells.save(shells.findById(shellId).orElseThrow().apply { commandTimeoutSeconds = null })

        val finished = mapper.readTree(run(granted, openSession(granted), "sleep 3"))
        assertThat(finished.path("timedOut").isMissingNode).isTrue()
        assertThat(finished.path("exitCode").intValue()).isEqualTo(0)
    }

    @Test
    fun `a machine given an output allowance of its own keeps what the installation would have cut`() {
        // 400 lines of `orknux` is 2800 bytes, which the installation's 512
        // cuts and this machine's 8 KiB does not. The same command again, and
        // the only thing that differs is the row.
        shells.save(shells.findById(shellId).orElseThrow().apply { maxOutputBytes = 8 * 1024 })

        val kept = mapper.readTree(run(granted, openSession(granted), "yes orknux | head -n 400"))

        assertThat(kept.path("exitCode").intValue()).isEqualTo(0)
        assertThat(kept.path("stdout").stringValue().length).isGreaterThan(512)
        // Not cut at all, which is the difference between an allowance that was
        // raised and one that was merely reported.
        assertThat(kept.path("truncated").isMissingNode).isTrue()

        /*
         * And it is still an allowance rather than a licence. A machine given a
         * bigger number is bounded by that number, not by nothing - the whole
         * reason the column exists is that these bytes are read by a model, and
         * an override that stopped bounding anything would be a way to hand one
         * a gigabyte.
         */
        val cut = mapper.readTree(run(granted, openSession(granted), "seq 1 200000; echo STILL-BOUNDED"))
        val stdout = cut.path("stdout").stringValue()

        assertThat(stdout).endsWith("STILL-BOUNDED\n")
        assertThat(stdout.length).isLessThanOrEqualTo(8 * 1024 + MARKER_ROOM)
        assertThat(cut.path("truncated").stringValue()).contains("its middle was removed")
    }

    @Test
    fun `an agent without the switch cannot reach the shells`() {
        // Not offered them, which is the first half: an agent handed a tool it
        // may not call spends a round trip finding out.
        assertThat(tools.specsFor(refused).map { it.name }).doesNotContain("shell_open_session")
        assertThat(tools.specsFor(granted).map { it.name })
            .contains("shell_open_session", "shell_run_command", "shell_close_session")

        // And refused when it names one anyway, which is the half that matters:
        // a model that invented the name is stopped by the thing that would
        // otherwise have done the work.
        val answer = mapper.readTree(call(refused, "shell_open_session"))
        assertThat(answer.path("error").stringValue()).contains("has not been given access to the shells")
        assertThat(sessions.findAll()).isEmpty()
    }

    @Test
    fun `every command an agent runs is written down`() {
        val sessionId = openSession(granted)
        run(granted, sessionId, "id -un")
        call(granted, "shell_close_session", """{"sessionId":"$sessionId"}""")

        val written = audit.findAll().filter { it.category.name == "SHELL" }.map { it.message }

        assertThat(written).anySatisfy { assertThat(it).contains("opened on box by sre") }
        // The command itself, and what it did. An entry saying a command was
        // attempted and nothing saying how it went is the one an administrator
        // would least trust.
        assertThat(written).anySatisfy { assertThat(it).contains("sre ran on box: id -un").contains("exit 0") }
        assertThat(written).anySatisfy { assertThat(it).contains("closed by sre after 1 commands") }
        assertThat(audit.findAll().filter { it.category.name == "SHELL" }).allSatisfy {
            // Under the agent's own name rather than a person's: nobody is at a
            // screen when a workflow runs one of these.
            assertThat(it.userId).isEqualTo("sre")
        }
    }

    @Test
    fun `a credential on the command line is not what gets written down`() {
        // The whole path, against a real machine: the command runs as typed and
        // the row it leaves behind has the password out of it. Asserted on what
        // the table holds, because a filter on the way out would leave the
        // plaintext here, in every backup of here, and in front of anyone with a
        // database client.
        val sessionId = openSession(granted)
        run(granted, sessionId, "echo pushing to https://alice:s3cr3t@github.com/acme/repo.git")

        val written = audit.findAll().filter { it.category.name == "SHELL" }.map { it.message }

        assertThat(written).allSatisfy { assertThat(it).doesNotContain("s3cr3t") }
        assertThat(written).anySatisfy {
            assertThat(it).contains("sre ran on box: echo pushing to https://alice:***@github.com/acme/repo.git")
        }
    }

    @Test
    fun `two sessions on the same shell get directories of their own`() {
        val first = openSession(granted)
        val second = openSession(granted)

        val here = mapper.readTree(run(granted, first, "pwd")).path("stdout").stringValue().trim()
        val there = mapper.readTree(run(granted, second, "pwd")).path("stdout").stringValue().trim()

        assertThat(here).isNotEqualTo(there)

        // A file written in one is not visible from the other, which is what
        // "its own directory" has to mean to be worth anything.
        run(granted, first, "echo mine > only-here.txt")
        val look = mapper.readTree(run(granted, second, "test -f only-here.txt; echo found:$?"))
        assertThat(look.path("stdout").stringValue()).contains("found:1")
    }

    @Test
    fun `a session nobody closed is swept, and its directory with it`() {
        val sessionId = openSession(granted)
        val directory = mapper.readTree(run(granted, sessionId, "pwd")).path("workingDirectory").stringValue()

        // Aged rather than waited for. The default idle timeout is two hours,
        // and a test that took two hours would be a test nobody runs.
        val session = sessions.findById(sessionId).get()
        session.lastUsedAt = OffsetDateTime.now().minus(Duration.ofHours(3))
        sessions.save(session)

        sweeper.sweep()

        assertThat(sessions.findById(sessionId).get().state).isEqualTo(ShellSessionState.EXPIRED)

        val check = openSession(granted)
        val look = mapper.readTree(run(granted, check, "test -d '$directory'; echo gone:$?"))
        assertThat(look.path("stdout").stringValue()).contains("gone:1")
    }

    @Test
    fun `a host answering with a different key than the one it was first seen with is refused`() {
        val shell = shells.findById(shellId).get()

        // What a machine standing in for another one would look like. Stored by
        // hand here rather than by swapping the container's key, which would
        // test Docker's ability to restart rather than this code's.
        shell.hostKey = "SHA256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        shells.save(shell)

        service.check(shellId)

        val checked = shells.findById(shellId).get()
        assertThat(checked.status).isEqualTo(ShellStatus.FAILED)
        assertThat(checked.lastCheckMessage).contains("different host key")

        // And the way out is a decision somebody makes, not one this makes for
        // them: forgetting the old key lets the next connection record the new.
        service.update(
            shellId,
            io.mszymanski.orknux.connector.shell.ShellInput(
                name = checked.name,
                host = checked.host,
                port = checked.port,
                username = checked.username,
                forgetHostKey = true,
            ),
        )
        service.check(shellId)
        assertThat(shells.findById(shellId).get().status).isEqualTo(ShellStatus.CONNECTED)
    }

    @Test
    fun `a shell with no username connects as the account this server runs as`() {
        val shell = shells.findById(shellId).get()
        shell.username = null
        shells.save(shell)

        /*
         * Who this process is, for the length of one connection. The container
         * only accepts ACCOUNT, so the test has to be that account for the
         * fallback to be provable at all - and this is MINA's own supported way
         * of saying so, the same accessor the fallback reads. Faking it any
         * other way would be testing a mock of the thing under test.
         */
        OsUtils.setCurrentUser(ACCOUNT)
        try {
            service.check(shellId)
        } finally {
            // Back to whatever the JVM says, and not to a name this test chose:
            // null clears the cache rather than setting a value.
            OsUtils.setCurrentUser(null)
        }

        val checked = shells.findById(shellId).get()
        // Not merely a handshake. The check runs `uname` as well, so a blank
        // user name that reached the far side and was refused could not reach
        // here.
        assertThat(checked.status).isEqualTo(ShellStatus.CONNECTED)
        assertThat(checked.lastCheckMessage).contains("Connected to Linux")
        // And the username is still nothing, because connecting is not a reason
        // to write a name into a field the administrator left empty.
        assertThat(checked.username).isNull()
    }

    @Test
    fun `a check writes down what the machine is, and the key it was first seen with`() {
        service.check(shellId)

        val checked = shells.findById(shellId).get()
        assertThat(checked.status).isEqualTo(ShellStatus.CONNECTED)
        assertThat(checked.lastCheckMessage).contains("Connected to Linux")
        // Trust on first use: nothing was known before, and what answered is
        // what has to answer from now on.
        assertThat(checked.hostKey).startsWith("SHA256:")
    }

    private fun openSession(agent: Agent): String =
        mapper.readTree(call(agent, "shell_open_session")).path("sessionId").stringValue()

    private fun run(agent: Agent, sessionId: String, command: String): String = call(
        agent,
        "shell_run_command",
        mapper.writeValueAsString(mapOf("sessionId" to sessionId, "command" to command)),
    )

    /** Through the agent's own tool loop, which is the only way an agent has. */
    private fun call(agent: Agent, name: String, arguments: String = "{}"): String =
        tools.run(agent, ToolCall(id = "1", name = name, arguments = arguments))

    companion object {

        private const val SSH_PORT = 2222
        private const val ACCOUNT = "orknux"

        /**
         * How much longer than its allowance an answer may be.
         *
         * The marker the buffer writes where the middle was is this
         * application's own sentence rather than anything the command printed,
         * so it sits on top of the allowance. The allowance governs what is kept
         * from the far side, which is the number worth asserting; this is only
         * the room the sentence takes.
         */
        private const val MARKER_ROOM = 200

        /** Ed25519 has one size; sshd wants it said anyway. */
        private const val ED25519_BITS = 256

        private lateinit var privateKey: String

        /**
         * A real OpenSSH server, given one authorised key and nothing else.
         *
         * Password authentication and sudo are both off, so the only way in is
         * the key this generated - which means a test that connects has proved
         * the key handling works rather than that something let it in.
         */
        private val server: GenericContainer<*> by lazy {
            /*
             * Generated through sshd's own Ed25519 support rather than the
             * JDK's. They are not interchangeable: the JDK has had Ed25519
             * since 15, and sshd reads and writes the net.i2p representation,
             * so a JDK-generated key cannot be written in OpenSSH form by it.
             * Which is the whole reason that library is a dependency, and
             * generating the key the same way sshd will read it is what makes
             * this a test of the path an administrator's key actually takes.
             */
            val pair = SecurityUtils.getOpenSSHEDDSAPrivateKeyEntryDecoder().generateKeyPair(ED25519_BITS)
            privateKey = openSshPrivateKey(pair)

            GenericContainer("linuxserver/openssh-server:version-10.3_p1-r0")
                .withEnv("PUBLIC_KEY", PublicKeyEntry.toString(pair.public))
                .withEnv("USER_NAME", ACCOUNT)
                .withEnv("PASSWORD_ACCESS", "false")
                .withEnv("SUDO_ACCESS", "false")
                .withExposedPorts(SSH_PORT)
                // The port opens before the authorised key is in place, so the
                // wait is for what the image says when it has finished setting
                // itself up. Waiting on the port loses that race about one run
                // in five.
                .waitingFor(
                    Wait.forLogMessage(".*\\[ls\\.io-init\\] done\\..*", 1)
                        .withStartupTimeout(Duration.ofMinutes(3)),
                )
        }

        @JvmStatic
        @BeforeAll
        fun startServer() {
            server.start()
        }

        @JvmStatic
        @AfterAll
        fun stopServer() {
            server.stop()
        }

        /** The key as somebody would paste it, which is the form being tested. */
        private fun openSshPrivateKey(pair: KeyPair): String {
            val written = ByteArrayOutputStream()
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(pair, "orknux-test", null, written)
            return written.toString(Charsets.UTF_8)
        }
    }
}
