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
     * prints more than the output limit comes back with its middle removed and a
     * sentence in its place saying how much went - both ends are kept, because
     * the answer is at one of them and never in between; see [BoundedBuffer].
     * Something has to go, because the alternative is a gigabyte in this heap
     * and then a gigabyte in a model's context.
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
 * A stream that keeps both ends of what it is given and says what it dropped.
 *
 * Bounded here rather than trimmed afterwards, because trimming afterwards means
 * the whole gigabyte was in memory first, and the process that dies for it is
 * this one rather than the one that printed it.
 *
 * **It used to keep the beginning, and that was the bug.** Dropping everything
 * past the limit is the obvious way to bound a stream and the wrong way to bound
 * *this* one, because the thing worth keeping is almost never at the front. A
 * build prints its plugin banners, its dependency downloads and its reactor
 * summary first and `cannot find symbol` last; a test run prints the suite and
 * then the failure; `apt-get` prints two hundred packages and then the conflict.
 * So the old buffer threw away precisely the bytes somebody asked the question
 * for, and a model reading the result could not tell a build that failed from a
 * build whose output stopped - which are opposite conclusions with opposite next
 * moves. Raising the limit only moved the threshold: a long enough download
 * still pushed the error out.
 *
 * So both ends are kept and the middle goes. What is lost is now the part
 * nothing usually asks about, and a marker in its place says how much went and
 * that the end is whole, which is the sentence that lets a reader trust the last
 * line they can see.
 *
 * **A third to the head and two thirds to the tail.** Not half and half: the two
 * ends are not worth the same. The head only has to establish what ran and how
 * it was invoked, which is a few lines; the tail carries the answer, and an
 * error with a stack under it is longer than a command line. Two thirds is the
 * smallest split that reliably holds a Maven failure summary together, and the
 * remaining third is more head than any of these tools spend on saying hello.
 *
 * **Memory is exactly [limit] bytes**, which is the whole point of the class:
 * the head grows to at most its share, the tail is a ring of a fixed size that
 * overwrites itself, and nothing else is retained. A command printing a gigabyte
 * costs this what a command printing the limit costs it. The ring is allocated
 * on the first byte that goes past the head, so the ordinary command that prints
 * two lines allocates nothing for it at all. The marker is this application's
 * own words rather than the command's, so it is built at [text] time and sits on
 * top of [limit] rather than inside it - a constant hundred bytes or so, which
 * is why the bound that matters is stated in terms of what was *kept from the
 * command*.
 *
 * **Under the limit, nothing happens.** No ring is ever allocated, no marker is
 * written, and [text] hands back exactly the bytes that arrived. The common case
 * has to be untouched, or every reader of this output has to wonder whether a
 * short answer was really short.
 *
 * Not thread-safe by accident: the writes are synchronised. MINA hands one
 * stream to one I/O thread, so this is insurance rather than a requirement, but
 * a ring whose two indices disagree would be a corruption nobody could read back
 * to a cause.
 *
 * Internal rather than private so that a test can reach it directly. What it
 * does is arithmetic over a wrapping array, and the cases worth pinning - a
 * write that spans the wrap, a single write larger than the whole ring, a
 * character split across the leading edge - are ones it takes a container and a
 * command that prints a megabyte to reach from outside.
 */
internal class BoundedBuffer(private val limit: Int) : OutputStream() {

    /**
     * The head's share, and the tail's, adding up to exactly [limit].
     *
     * Worked out once. A head of zero is allowed and is what a limit of one or
     * two bytes produces - a degenerate setting no configuration this ships will
     * reach, and one that still behaves rather than dividing by nothing.
     */
    private val headLimit: Int = (limit / HEAD_SHARE).coerceAtLeast(0)
    private val tailLimit: Int = limit - headLimit

    private val head = ByteArrayOutputStream()

    /**
     * The last [tailLimit] bytes, as a ring, or null while nothing has needed
     * one. [tailStart] is where the oldest surviving byte is and [tailSize] how
     * many there are; once the ring is full those two say the whole of it.
     */
    private var tail: ByteArray? = null
    private var tailStart = 0
    private var tailSize = 0

    /** How many bytes fell out of the middle. A Long because a command can print more than two gigabytes. */
    private var dropped: Long = 0

    /** Somewhere for the one-byte write to land without allocating per byte. */
    private val single = ByteArray(1)

    /**
     * Whether anything was removed.
     *
     * Unchanged in meaning - it still says "what you are reading is not all of
     * it". What changed is that the answer is now specific, and it is specific
     * *in the output itself*, where the marker names the amount. A caller
     * reporting on this should say what is missing is the middle and leave the
     * number to the marker, rather than printing a second, vaguer version of the
     * same fact beside it.
     */
    val truncated: Boolean
        get() = dropped > 0

    @Synchronized
    override fun write(b: Int) {
        if (head.size() < headLimit) {
            head.write(b)
            return
        }
        single[0] = b.toByte()
        intoTail(single, 0, 1)
    }

    @Synchronized
    override fun write(source: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return

        var from = offset
        var left = length

        val room = headLimit - head.size()
        if (room > 0) {
            val run = minOf(left, room)
            head.write(source, from, run)
            from += run
            left -= run
        }
        if (left > 0) intoTail(source, from, left)
    }

    /**
     * Puts bytes into the ring, evicting the oldest, and counts what was evicted.
     *
     * A write longer than the whole ring is skipped forward to its last
     * [tailLimit] bytes rather than copied through: the earlier bytes could only
     * be overwritten by the later ones in the same call, and copying a megabyte
     * into a buffer in order to overwrite it is the cost this class exists to
     * avoid.
     */
    private fun intoTail(source: ByteArray, offset: Int, length: Int) {
        if (tailLimit <= 0) {
            dropped += length.toLong()
            return
        }
        val ring = tail ?: ByteArray(tailLimit).also { tail = it }

        var from = offset
        var left = length
        if (left > tailLimit) {
            dropped += (left - tailLimit).toLong()
            from += left - tailLimit
            left = tailLimit
        }

        while (left > 0) {
            val writeAt = (tailStart + tailSize) % tailLimit
            val run = minOf(left, tailLimit - writeAt)
            System.arraycopy(source, from, ring, writeAt, run)
            from += run
            left -= run

            val room = tailLimit - tailSize
            if (run > room) {
                val evicted = run - room
                dropped += evicted.toLong()
                tailStart = (tailStart + evicted) % tailLimit
                tailSize = tailLimit
            } else {
                tailSize += run
            }
        }
    }

    /**
     * Both ends, with the marker between them, decoded as UTF-8.
     *
     * Assembled as bytes and decoded once rather than decoded in three pieces
     * and joined, and that is not tidiness. The head ends at a byte offset the
     * command did not choose, and so does the ring's leading edge, so either
     * boundary can fall inside a multi-byte character. Decoding the whole thing
     * in one pass means the two boundaries are the only places that can suffer,
     * and it means that when nothing was dropped - when head and ring are simply
     * the stream in order - the result is byte-for-byte what arrived, which a
     * three-piece decode could not promise.
     *
     * What a split character costs is one replacement character. `String(bytes,
     * UTF_8)` decodes with REPLACE rather than REPORT, so malformed input
     * becomes U+FFFD and never an exception - which is also the answer to the
     * other case this has always had to survive, somebody running `cat` on a
     * binary. A result reported half way through would be worse than a result
     * with a `` in it.
     */
    @Synchronized
    fun text(): String {
        val kept = ByteArrayOutputStream(head.size() + MARKER_BOUND + tailSize)
        head.writeTo(kept)
        if (dropped > 0) kept.write(marker(dropped).toByteArray(Charsets.UTF_8))
        tail?.let { ring ->
            val first = minOf(tailSize, tailLimit - tailStart)
            kept.write(ring, tailStart, first)
            if (tailSize > first) kept.write(ring, 0, tailSize - first)
        }
        return String(kept.toByteArray(), Charsets.UTF_8)
    }

    /**
     * The sentence that stands where the middle was.
     *
     * It states the amount, because "the output was cut" and "the output ended"
     * are the two readings this is here to separate and only a number separates
     * them. And it says the end is whole, because the reader's next question
     * after "some of this is missing" is "so is the last line real", and the
     * answer is yes - the ring holds the final bytes of the stream, whatever
     * else went.
     *
     * Ordinary words rather than a machine-readable token. What reads this is a
     * model, and a model does better with a sentence than with `<TRUNCATED
     * bytes=1467321/>`.
     */
    private fun marker(bytes: Long): String =
        "\n… ${size(bytes)} of output removed from the middle. " +
            "What is above is where it started and what is below is where it ended, complete. …\n"

    /** A byte count in the units the rest of this product says limits in. */
    private fun size(bytes: Long): String = when {
        bytes < KIB -> "$bytes bytes"
        bytes < KIB * KIB -> "${round(bytes.toDouble() / KIB)} KiB"
        bytes < KIB * KIB * KIB -> "${round(bytes.toDouble() / (KIB * KIB))} MiB"
        else -> "${round(bytes.toDouble() / (KIB * KIB * KIB))} GiB"
    }

    /** One decimal place, and no trailing `.0` on a round number. */
    private fun round(value: Double): String {
        val tenths = Math.round(value * 10)
        return if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
    }

    private companion object {
        /** The head gets one part in this many; the tail gets the rest. */
        const val HEAD_SHARE = 3

        const val KIB = 1024

        /**
         * Room set aside when sizing the assembly buffer, not a limit on
         * anything: the marker is a fixed sentence and a number, and this is
         * comfortably longer than the longest one of those.
         */
        const val MARKER_BOUND = 160
    }
}
