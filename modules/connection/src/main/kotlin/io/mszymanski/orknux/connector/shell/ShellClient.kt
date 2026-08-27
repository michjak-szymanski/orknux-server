package io.mszymanski.orknux.connector.shell

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ClientChannelEvent
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.AttributeRepository
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.common.util.security.SecurityUtils
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.KeyPair
import java.util.EnumSet

/**
 * The SSH half of a shell: connect, run one command, disconnect.
 *
 * **Apache MINA SSHD**, and not because it was the first result. It is an Apache
 * Software Foundation project under the same licence this one is, released
 * regularly, and what Gerrit and Jenkins talk SSH with. What decided it is the
 * dependency list: `sshd-core` brings `sshd-common` and slf4j, and the only
 * other thing needed is 85 KB of Ed25519 support. The obvious alternative,
 * sshj, is a pleasanter API sitting on a BouncyCastle requirement, and putting
 * a general-purpose cryptography provider into a process that has none is a
 * change to how every other piece of cryptography here resolves.
 *
 * **Nothing here is held open between commands.** A connection is made, the
 * command runs, and the connection is closed - see [ShellSession] for why a
 * session is a row in the database rather than a socket in this process's
 * memory. What that costs is a handshake per command; what it buys is a session
 * that survives a deployment and a directory that can still be tidied up
 * afterwards.
 *
 * Proxy rules do not apply. They are about HTTP requests and the CONNECT tunnels
 * underneath them, and SSH is neither, in exactly the way SMTP is neither.
 */
@Component
@EnableConfigurationProperties(ShellProperties::class)
class ShellClient(private val properties: ShellProperties) {

    private val client: SshClient = SshClient.setUpDefaultClient()

    @PostConstruct
    fun start() {
        /*
         * Trust on first use, decided here because here is where the server's
         * key is offered - during the handshake, before a single byte of our
         * private key has been sent. A verifier set on the session afterwards
         * would be set after the moment it was needed.
         *
         * What it needs to know about this particular connection travels in the
         * connection context, which is the repository handed to `connect` and
         * read back off the session. A field on this object could not do it:
         * two shells can be connecting at once and the verifier runs on an I/O
         * thread rather than on either caller's.
         */
        client.serverKeyVerifier = ServerKeyVerifier { session, _, serverKey ->
            val expectation = session.connectionContext?.getAttribute(EXPECTATION)
            val fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, serverKey)
            if (expectation == null) {
                // Nobody said what to expect, which is not a case this code
                // creates. Refusing is the only safe reading of it.
                return@ServerKeyVerifier false
            }
            expectation.fingerprint = fingerprint
            expectation.trusted = expectation.known.isNullOrBlank() || expectation.known == fingerprint
            expectation.trusted
        }
        client.start()
    }

    @PreDestroy
    fun stop() {
        runCatching { client.stop() }
    }

    /**
     * Opens a connection, does something with it, and closes it whatever
     * happened.
     *
     * Every failure on the way in - a refused socket, a key that will not parse,
     * an account the far side will not accept, a host key that is not the one
     * this shell was first seen with - arrives as a [ShellUnreachableException]
     * carrying a sentence somebody can act on. That is the whole of the contract
     * here: this throws when it could not talk to the machine, and never for
     * anything the machine itself said.
     *
     * [work] is also handed the fingerprint the handshake saw, because a shell
     * connecting for the first time has one to write down.
     */
    fun <T> connected(shell: Shell, work: (ClientSession, String?) -> T): T {
        val key = privateKeyOf(shell)
        val expectation = HostKeyExpectation(shell.hostKey)
        val context = AttributeRepository.ofKeyValuePair(EXPECTATION, expectation)

        /*
         * A shell with no username connects as the account this process runs
         * as, which is [Shell.account] and is what `ssh build.internal` does.
         *
         * Worked out here rather than handed to MINA as an empty string,
         * because MINA does not fall back on this path. `SshClient.connect`
         * only consults `OsUtils.getCurrentUser` inside a host config entry
         * resolver, and the default resolver matches nothing and builds a
         * synthetic entry out of whatever it was given - so an empty username
         * stays empty, goes on the wire as a user name of zero length, and
         * comes back as an authentication failure that names nobody. The
         * fallback being ours means it is the same account the page shows.
         */
        val account = shell.account

        val session = try {
            client.connect(account, shell.host.trim(), shell.port, context, null)
                .verify(properties.connectTimeout)
                .session
        } catch (failure: Exception) {
            throw unreachable(shell, expectation, "${shell.host}:${shell.port} could not be reached", failure)
        }

        return session.use { open ->
            try {
                open.addPublicKeyIdentity(key)
                open.auth().verify(properties.connectTimeout)
            } catch (failure: Exception) {
                throw unreachable(shell, expectation, "$account@${shell.host} was refused", failure)
            }
            work(open, expectation.fingerprint)
        }
    }

    /**
     * Runs one command and answers with what happened, whatever that was.
     *
     * A non-zero exit is a result and not a failure, and nothing here throws
     * about one. `grep` finding nothing exits 1, `test -f` answers by exiting,
     * and a caller told "that failed" about either has been told something
     * untrue. What comes back carries the code and both streams, and lets
     * whoever asked decide what it means.
     *
     * The two things that can go wrong on our side are bounded rather than
     * fatal. A command that never returns has its channel closed after the
     * timeout and comes back with [ShellRun.timedOut] set. Closing a channel is
     * not killing a process on the far side, and the wording says so rather than
     * claiming a kill that did not happen - killing it would mean following a
     * process id through a login shell that never gave us one. A command that
     * prints more than the output limit has the rest dropped and comes back
     * truncated, because the alternative is a gigabyte in this heap and then a
     * gigabyte in a model's context.
     *
     * Both bounds come from [shell] when it has been given them and from
     * [ShellProperties] when it has not, which is what makes an installation
     * default a default: raise the property and every machine that never asked
     * for anything different moves with it.
     *
     * [shell] is null only for the three commands this application runs on its
     * own behalf - the `mkdir` that makes a session's directory, the `uname`
     * that says what answered, and the `rm -rf` that tidies the directory away.
     * Those are ours rather than an agent's and each of them finishes at once,
     * so the machine's own numbers have nothing to say about them: a build box
     * allowed half an hour for a build has not thereby asked for half an hour
     * of `uname`.
     */
    fun run(session: ClientSession, command: String, directory: String?, shell: Shell? = null): ShellRun {
        val timeout = shell?.commandTimeout ?: properties.commandTimeout
        val limit = shell?.maxOutputBytes ?: properties.maxOutputBytes

        val stdout = BoundedBuffer(limit)
        val stderr = BoundedBuffer(limit)

        return session.createExecChannel(withDirectory(command, directory)).use { exec ->
            exec.out = stdout
            exec.err = stderr
            // An empty stdin already at end of file, so a command that reads
            // from it finishes rather than waiting out the whole timeout for
            // input nobody is going to type.
            exec.setIn(ByteArrayInputStream(ByteArray(0)))

            exec.open().verify(properties.connectTimeout)
            val finished = exec.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), timeout)
                .contains(ClientChannelEvent.CLOSED)

            ShellRun(
                exitCode = if (finished) exec.exitStatus else null,
                stdout = stdout.text(),
                stderr = stderr.text(),
                stdoutTruncated = stdout.truncated,
                stderrTruncated = stderr.truncated,
                timedOut = !finished,
            )
        }
    }

    /**
     * What the far side calls itself, or null when it would not say.
     *
     * Nothing is tried after `uname`. A shell here is a Linux or BSD machine or
     * a container, which is what this is for, and a Windows target would fail at
     * the directory a moment later anyway - reporting a cheerful "Windows" and
     * then failing on `mkdir -p` would be a worse answer than admitting we do
     * not know.
     */
    fun operatingSystem(session: ClientSession): String? =
        runCatching { run(session, "uname -sr", null) }
            .getOrNull()
            ?.takeIf { it.exitCode == 0 }
            ?.stdout
            ?.trim()
            ?.ifEmpty { null }
            ?.take(OS_LENGTH)

    /**
     * The command, with the session's directory made current first.
     *
     * Prefixed rather than passed as an option, because there is no option: an
     * exec channel hands a string to the account's login shell, and there is
     * nowhere in that to say "start here". A directory that has gone - swept
     * from under a long-idle session, or removed by a command the agent itself
     * ran - says so rather than letting the command run somewhere else, which is
     * the failure worth being loud about.
     */
    private fun withDirectory(command: String, directory: String?): String {
        if (directory.isNullOrBlank()) return command
        return "cd '$directory' || { echo \"orknux: the session directory $directory is gone\" >&2; exit 1; }\n" +
            command
    }

    /**
     * The refusal to hand back, which depends on what the handshake saw.
     *
     * A host key that changed is worth its own sentence. It arrives here looking
     * like an ordinary connection failure, and reported as one it would send
     * somebody to check a firewall about a machine that answered perfectly well
     * with the wrong identity.
     */
    private fun unreachable(
        shell: Shell,
        expectation: HostKeyExpectation,
        what: String,
        failure: Throwable,
    ): ShellUnreachableException {
        if (expectation.fingerprint != null && !expectation.trusted) {
            return ShellUnreachableException(
                "${shell.host}:${shell.port} answered with a different host key than the one this shell was " +
                    "first seen with. It was ${expectation.known} and it is now ${expectation.fingerprint}. " +
                    "Either the machine was rebuilt, in which case forget the stored host key on the shell, or " +
                    "something else is answering on that address.",
                failure,
            )
        }
        return ShellUnreachableException("$what: ${reason(failure)}", failure)
    }

    private fun privateKeyOf(shell: Shell): KeyPair {
        val material = shell.privateKey?.trim()?.ifEmpty { null }
            ?: throw ShellUnreachableException("${shell.name} has no private key stored")

        val loaded = try {
            SecurityUtils.loadKeyPairIdentities(
                null,
                NamedResource.ofName(shell.name),
                ByteArrayInputStream(material.toByteArray(Charsets.UTF_8)),
                shell.keyPassphrase?.ifBlank { null }?.let { FilePasswordProvider.of(it) },
            )
        } catch (failure: Exception) {
            // A wrong passphrase and a corrupt key look the same from here, so
            // the message names both rather than guessing between them.
            throw ShellKeyInvalidException(
                failure.message?.take(REASON_LENGTH)
                    ?: "it could not be read; check the format and the passphrase",
            )
        }

        return loaded.firstOrNull() ?: throw ShellKeyInvalidException("it holds no key this can read")
    }

    /** The useful sentence out of an exception that may be wrapping three others. */
    private fun reason(failure: Throwable): String {
        var deepest: Throwable = failure
        while (deepest.cause != null && deepest.cause !== deepest) deepest = deepest.cause!!
        return (deepest.message ?: deepest.javaClass.simpleName).take(REASON_LENGTH)
    }

    /**
     * What one connection expects of the far side, and what it found.
     *
     * Mutable and carried through the connection context because the verifier
     * has nowhere to return a value to: it runs inside the handshake, on an I/O
     * thread, and the fingerprint it saw is the thing a first connection needs
     * to write down afterwards.
     */
    private class HostKeyExpectation(val known: String?) {
        var fingerprint: String? = null
        var trusted: Boolean = true
    }

    private companion object {
        val EXPECTATION = AttributeRepository.AttributeKey<HostKeyExpectation>()

        const val REASON_LENGTH = 300
        const val OS_LENGTH = 200
    }
}

/**
 * What one command did.
 *
 * [exitCode] is null only when the command was still running when we stopped
 * waiting. Every other case has one, including the codes a shell invents for
 * "no such command" and "killed by a signal", and nothing here interprets any of
 * them.
 */
data class ShellRun(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val timedOut: Boolean,
)

/**
 * The machine could not be talked to.
 *
 * Deliberately distinct from a command that ran and failed, which is not an
 * exception at all - see [ShellClient.run].
 */
class ShellUnreachableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * A stream that stops accepting after a while and remembers that it did.
 *
 * Bounded here rather than trimmed afterwards, because trimming afterwards means
 * the whole gigabyte was in memory first, and the process that dies for it is
 * this one rather than the one that printed it.
 */
private class BoundedBuffer(private val limit: Int) : OutputStream() {

    private val bytes = ByteArrayOutputStream()

    var truncated = false
        private set

    override fun write(b: Int) {
        if (bytes.size() >= limit) {
            truncated = true
            return
        }
        bytes.write(b)
    }

    override fun write(source: ByteArray, offset: Int, length: Int) {
        val room = limit - bytes.size()
        if (room <= 0) {
            truncated = true
            return
        }
        if (length > room) truncated = true
        bytes.write(source, offset, minOf(length, room))
    }

    /**
     * Decoded as UTF-8, with whatever the far side sent that is not, replaced.
     *
     * A command's output is not promised to be text - somebody will `cat` a
     * binary sooner or later - and the answer to that is a replacement character
     * rather than an exception thrown halfway through reporting a result.
     */
    fun text(): String = String(bytes.toByteArray(), Charsets.UTF_8)
}
