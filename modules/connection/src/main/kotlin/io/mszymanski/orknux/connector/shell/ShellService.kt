package io.mszymanski.orknux.connector.shell

import io.mszymanski.orknux.connector.connection.ConnectionProbe
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.util.security.SecurityUtils
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * The shells, as an administrator edits them, and the check that says whether
 * one still answers.
 *
 * Who may call this is settled before the request arrives, the same as every
 * other admin-level service in this module: orknux-server checks the caller and
 * records the audit entry.
 *
 * Two things are checked here rather than left to the first time an agent tries
 * to use one. The host goes past the same guard every outbound address goes
 * past, because a shell pointing at a link-local address is a shell pointing at
 * this host's instance metadata. And the private key is parsed, because a key
 * that will not parse is a shell that can never work, and the moment somebody
 * pastes it is the only moment they still have the right one to hand.
 */
@Service
class ShellService(
    private val shells: ShellRepository,
    private val sessions: ShellSessionRepository,
    private val client: ShellClient,
    private val probe: ConnectionProbe,
    private val properties: ShellProperties,
) {

    fun shells(): List<ShellView> = shells.findAllByOrderByNameAsc().map(::view)

    fun shell(id: Long): ShellView? = shells.findByIdOrNull(id)?.let(::view)

    /**
     * One shell as a screen may see it, with the installation's own limits
     * alongside it.
     *
     * The defaults travel on every view for the reason `account` does: a shell
     * that has not been given a timeout of its own is running on a number the
     * screen has no way to know, and a form that showed an empty box and said
     * nothing would be hiding the value actually in force. See
     * [ShellView.defaultCommandTimeoutSeconds].
     */
    private fun view(shell: Shell): ShellView = ShellView(shell, properties)

    @Transactional
    fun create(input: ShellInput): ShellView {
        val name = input.name.trim()
        if (name.isEmpty()) throw ShellNameInvalidException()
        if (shells.findByName(name) != null) throw ShellNameTakenException(name)

        val shell = shells.save(
            Shell(
                name = name,
                host = validHost(input.host),
                port = validPort(input.port),
                username = validUsername(input.username),
                privateKey = validKey(input.privateKey, input.keyPassphrase),
                keyPassphrase = input.keyPassphrase?.trim()?.ifEmpty { null },
                enabled = input.enabled ?: true,
                commandTimeoutSeconds = validTimeout(input.commandTimeoutSeconds),
                maxOutputBytes = validOutputBytes(input.maxOutputBytes),
            ),
        )
        shell.status = if (shell.configured) ShellStatus.NOT_CHECKED else ShellStatus.NOT_CONFIGURED
        return view(shell)
    }

    /**
     * A null key leaves the stored one alone and an empty one clears it, which
     * is how every other credential on this platform is edited: the screen never
     * has the key to send back, so "unchanged" has to be sayable.
     */
    @Transactional
    fun update(id: Long, input: ShellInput): ShellView {
        val name = input.name.trim()
        if (name.isEmpty()) throw ShellNameInvalidException()

        val shell = shells.findByIdOrNull(id) ?: throw ShellNotFoundException(id)
        if (name != shell.name && shells.findByName(name) != null) throw ShellNameTakenException(name)

        // Checked once and reused. Vetting a host resolves it, and doing that
        // three times to answer one question is three name lookups for a form
        // somebody pressed Save on.
        val host = validHost(input.host)
        val port = validPort(input.port)
        val movedHost = shell.host != host || shell.port != port

        shell.name = name
        shell.host = host
        shell.port = port
        shell.username = validUsername(input.username)
        input.keyPassphrase?.let { shell.keyPassphrase = it.trim().ifEmpty { null } }
        input.privateKey?.let { shell.privateKey = validKey(it, shell.keyPassphrase)?.ifEmpty { null } }
        input.enabled?.let { shell.enabled = it }

        /*
         * The limits follow the account's rule rather than the key's: absent
         * means "this machine has no limit of its own", not "leave whatever is
         * stored". There is nothing secret about a timeout, so the form always
         * has the current value to send back, and clearing one has to be sayable
         * - an empty box is how an administrator says "go back to the
         * installation's number", and it is the only way they could say it.
         */
        shell.commandTimeoutSeconds = validTimeout(input.commandTimeoutSeconds)
        shell.maxOutputBytes = validOutputBytes(input.maxOutputBytes)

        /*
         * A shell pointed at a different machine has no business carrying the
         * old one's host key: the first connection to the new address would be
         * refused for a mismatch that is not a mismatch, and the message would
         * accuse the network of something the edit did.
         */
        if (movedHost || input.forgetHostKey == true) shell.hostKey = null

        shell.status = if (shell.configured) shell.status else ShellStatus.NOT_CONFIGURED
        shell.lastModifiedAt = OffsetDateTime.now()
        return view(shell)
    }

    /** The switch on the row, without opening the shell to edit it. */
    @Transactional
    fun setEnabled(id: Long, enabled: Boolean): ShellView {
        val shell = shells.findByIdOrNull(id) ?: throw ShellNotFoundException(id)
        shell.enabled = enabled
        shell.lastModifiedAt = OffsetDateTime.now()
        return view(shell)
    }

    /**
     * Removes a shell, and everything on the far side that belonged to it.
     *
     * The sessions are closed first and their directories removed, because the
     * row is the only record that the directory exists: delete it and the
     * directory is orphaned by us rather than by a crash. A host that will not
     * answer is logged and the deletion goes ahead anyway - refusing to remove a
     * shell because the machine it names is already gone would be a page nobody
     * could tidy up.
     */
    @Transactional
    fun delete(id: Long): Boolean {
        val shell = shells.findByIdOrNull(id) ?: return false

        val open = sessions.findAllByShellIdAndState(id, ShellSessionState.OPEN)
        if (open.isNotEmpty()) {
            runCatching {
                client.connected(shell) { session, _ ->
                    open.forEach { removeDirectory(session, it) }
                }
            }.onFailure {
                log.warn(
                    "Shell {} is being deleted with {} sessions open and could not be reached to remove their " +
                        "directories: {}",
                    shell.name,
                    open.size,
                    it.message,
                )
            }
        }

        // Cascaded in the database as well; done here so the entities Hibernate
        // is holding agree with what the database is about to do.
        sessions.deleteAll(sessions.findAllByShellIdAndState(id, ShellSessionState.OPEN))
        shells.delete(shell)
        return true
    }

    /**
     * Asks a shell whether it still answers, and writes down what it said.
     *
     * The connection itself is the check - a handshake, the key accepted, and a
     * `uname` to prove a command can actually run. Anything less would report
     * "Connected" about a host that answers on port 22 and refuses every
     * account, which is the failure this is meant to catch.
     */
    fun check(id: Long): ShellView {
        val shell = shells.findByIdOrNull(id) ?: throw ShellNotFoundException(id)
        checkShell(shell)
        return view(shells.save(shell))
    }

    /**
     * The same, on an entity the caller already has, and saved by the caller.
     *
     * Deliberately outside a transaction. The whole of this method is a network
     * conversation that can take the connect timeout plus a `uname`, and a
     * database connection held open for the length of it is a pool spent on
     * waiting. The row is written afterwards, in one statement.
     */
    fun checkShell(shell: Shell) {
        shell.lastCheckedAt = OffsetDateTime.now()

        if (!shell.configured) {
            shell.status = ShellStatus.NOT_CONFIGURED
            shell.lastCheckMessage = "No private key is stored, so there is nothing to connect with"
            return
        }

        try {
            val greeting = client.connected(shell) { session, fingerprint ->
                // Written down on the first connection that got this far, which
                // is trust on first use: what answered then is what has to
                // answer from now on.
                if (shell.hostKey.isNullOrBlank() && fingerprint != null) shell.hostKey = fingerprint
                client.operatingSystem(session)
            }
            shell.status = ShellStatus.CONNECTED
            shell.lastCheckMessage = greeting?.let { "Connected to $it" } ?: "Connected"
        } catch (failure: Exception) {
            shell.status = ShellStatus.FAILED
            shell.lastCheckMessage = failure.message?.take(MESSAGE_LENGTH) ?: "It could not be reached"
        }
    }

    /**
     * Which shell answers when an agent asks for "a shell".
     *
     * The owner's design is that an agent cares only about *any* shell, so there
     * is no name to pass and no per-agent choice to configure. That leaves this
     * code to choose, and the choice has to be defensible rather than arbitrary.
     *
     * Enabled, then the ones last known to be [ShellStatus.CONNECTED], then by
     * name. Liveness first because "any shell" plainly means a working one, and
     * handing an agent a machine that has been down since Tuesday when there is
     * a healthy one beside it is not a defensible reading of "any". Name second
     * because the tie-break must be deterministic: an agent that gets a
     * different machine every time it opens a session cannot be reasoned about
     * by the person reading its transcript, and files it left in one session
     * would be on a host it never returns to. So the same shell is chosen every
     * time, until it stops answering, at which point the next one is - which is
     * also, usefully, what somebody would do by hand.
     *
     * When nothing has been checked yet, the check has not run rather than
     * failed, so those count too. Only a shell that is known to be failing is
     * passed over, and even then it is used if it is all there is - a refusal
     * that says why beats a refusal that says nothing was available.
     */
    fun choose(): Shell {
        val enabled = shells.findAllByEnabledTrueOrderByNameAsc().filter { it.configured }
        if (enabled.isEmpty()) throw NoShellAvailableException()

        return enabled.firstOrNull { it.status == ShellStatus.CONNECTED }
            ?: enabled.firstOrNull { it.status == ShellStatus.NOT_CHECKED }
            ?: enabled.first()
    }

    /** Removes a session's directory on the far side, on a connection already open. */
    internal fun removeDirectory(session: ClientSession, shellSession: ShellSession) {
        // `rm -rf` on a path this application chose and nobody else can name.
        // The quoting is not defensive dressing: the path is a fixed root and a
        // generated id, and it stays that way precisely so that this line is
        // never interpolating anything a caller supplied.
        client.run(session, "rm -rf '${shellSession.directory}'", null)
    }

    private fun validHost(host: String): String {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) throw ShellAddressInvalidException("A host is required")
        if (trimmed.contains("://")) {
            throw ShellAddressInvalidException("Give the host on its own, without a scheme")
        }
        // The same guard every outbound address goes past. A shell pointed at a
        // link-local address is a shell pointed at this host's instance
        // metadata, which is exactly the hole that guard closes.
        probe.vetHost(trimmed)?.let { throw ShellAddressInvalidException("That host cannot be used: $it") }
        return trimmed
    }

    private fun validPort(port: Int): Int {
        if (port !in 1..65535) throw ShellAddressInvalidException("A port between 1 and 65535 is required")
        return port
    }

    /**
     * A username, or null when there is none to keep.
     *
     * Nothing is refused here any more. An account is optional the way it is
     * optional at `ssh build.internal`, and what happens when it is left out is
     * settled once, on the entity, by [Shell.account].
     */
    private fun validUsername(username: String?): String? = username?.trim()?.ifEmpty { null }

    /**
     * A per-shell command timeout in seconds, or null for the installation's.
     *
     * The bounds are what the number can usefully mean rather than what the
     * column can hold. Under a second is not a timeout, it is a refusal dressed
     * as one - nothing survives an SSH handshake and a `cd` in less. A day is
     * the other end: past that the thing being waited on is not a command, it is
     * a job, and something that runs for a day needs `nohup` and a log file
     * rather than a channel held open across every deployment in between.
     */
    private fun validTimeout(seconds: Int?): Int? {
        val given = seconds ?: return null
        if (given !in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS) {
            throw ShellLimitInvalidException(
                "A command timeout between $MIN_TIMEOUT_SECONDS and $MAX_TIMEOUT_SECONDS seconds is required, " +
                    "or none at all to use this installation's",
            )
        }
        return given
    }

    /**
     * A per-shell output allowance in bytes, or null for the installation's.
     *
     * A kibibyte at the bottom because less than that cuts the answer out of
     * every command worth running, and 16 MiB at the top because that is where
     * one command's output stops being a string and starts being a fraction of
     * this process's heap - held twice over, once per stream, for every command
     * running at that moment.
     */
    private fun validOutputBytes(bytes: Int?): Int? {
        val given = bytes ?: return null
        if (given !in MIN_OUTPUT_BYTES..MAX_OUTPUT_BYTES) {
            throw ShellLimitInvalidException(
                "An output limit between $MIN_OUTPUT_BYTES and $MAX_OUTPUT_BYTES bytes is required, " +
                    "or none at all to use this installation's",
            )
        }
        return given
    }

    /**
     * Parsed where somebody can still fix it.
     *
     * A key that will not open is a shell that can never work, and the moment it
     * is pasted is the only moment the person still has the right one in front
     * of them. Finding out on the first command instead means an agent reporting
     * a connection problem about a typing mistake made a week earlier.
     */
    private fun validKey(material: String?, passphrase: String?): String? {
        val trimmed = material?.trim() ?: return null
        if (trimmed.isEmpty()) return ""

        try {
            SecurityUtils.loadKeyPairIdentities(
                null,
                NamedResource.ofName("shell"),
                trimmed.byteInputStream(Charsets.UTF_8),
                passphrase?.trim()?.ifEmpty { null }?.let { FilePasswordProvider.of(it) },
            ).firstOrNull() ?: throw ShellKeyInvalidException("it holds no key this can read")
        } catch (failure: ShellKeyInvalidException) {
            throw failure
        } catch (failure: Exception) {
            throw ShellKeyInvalidException(
                failure.message?.take(MESSAGE_LENGTH) ?: "it could not be read; check the format and the passphrase",
            )
        }
        return trimmed
    }

    private companion object {
        val log = LoggerFactory.getLogger(ShellService::class.java)

        const val MESSAGE_LENGTH = 400

        const val MIN_TIMEOUT_SECONDS = 1
        const val MAX_TIMEOUT_SECONDS = 24 * 60 * 60
        const val MIN_OUTPUT_BYTES = 1024
        const val MAX_OUTPUT_BYTES = 16 * 1024 * 1024
    }
}

data class ShellInput(
    val name: String,
    val host: String,
    val port: Int = 22,
    /**
     * The account on the far side, or null for the one this server runs as.
     *
     * Blank and absent mean the same thing here, unlike the key below: there is
     * nothing secret about an account name, so the screen always has the current
     * value to send back and never needs to say "unchanged".
     */
    val username: String? = null,
    /** Null leaves the stored key alone; empty clears it. Never read back. */
    val privateKey: String? = null,
    /** Same rule as the key. */
    val keyPassphrase: String? = null,
    val enabled: Boolean? = null,
    /**
     * How long one command on this machine may run, in seconds, or null to use
     * this installation's own limit.
     *
     * Absent and null are the same thing here, the way they are for the account
     * and unlike the key: there is nothing secret about a timeout, so the screen
     * always has the current value to send back and an empty box is how somebody
     * says "whatever the installation says".
     */
    val commandTimeoutSeconds: Int? = null,
    /** How much of a command's output to keep, per stream. Same rule as above. */
    val maxOutputBytes: Int? = null,
    /**
     * Forgets the host key this shell was first seen with, so the next
     * connection trusts whatever answers and records that instead. What somebody
     * ticks after rebuilding a machine, and the only way past a mismatch - which
     * is the point of having one.
     */
    val forgetHostKey: Boolean? = null,
)

/**
 * A shell as a screen may see it.
 *
 * There is no private key on it and no way to ask for one. [privateKeySet] is
 * what the form needs in order to say whether it is about to replace something,
 * and it is everything the outside is told.
 */
data class ShellView(
    val id: Long,
    val name: String,
    val host: String,
    val port: Int,
    /** What the administrator typed, or null when they left it out. */
    val username: String?,
    /**
     * The account commands actually run as: [username] when there is one, and
     * otherwise the account this server process runs as. On the view rather than
     * worked out again by the screen, because the screen has no way to know what
     * this process runs as and a page that guessed would be guessing about
     * privilege.
     */
    val account: String,
    val privateKeySet: Boolean,
    val passphraseSet: Boolean,
    /** The fingerprint this host was first seen with, for reading by eye. */
    val hostKey: String?,
    val enabled: Boolean,
    /**
     * This machine's own command timeout in seconds, or null when it has none
     * and runs on [defaultCommandTimeoutSeconds].
     */
    val commandTimeoutSeconds: Int?,
    /** This machine's own output allowance per stream, or null for the default. */
    val maxOutputBytes: Int?,
    /**
     * What a shell with no limit of its own actually runs on.
     *
     * Here for the same reason [account] is: nothing outside this process can
     * work it out. The number lives in configuration, is changed by an
     * environment variable at deployment time, and a screen that showed an empty
     * box beside it would be showing an administrator that no limit applies -
     * which is the one thing that is never true.
     */
    val defaultCommandTimeoutSeconds: Int,
    /** The same, for the output allowance. */
    val defaultMaxOutputBytes: Int,
    val status: ShellStatus,
    val lastCheckMessage: String?,
    val lastCheckedAt: String?,
    val createdAt: String,
    val lastModifiedAt: String,
) {
    constructor(shell: Shell, properties: ShellProperties) : this(
        id = requireNotNull(shell.id),
        name = shell.name,
        host = shell.host,
        port = shell.port,
        username = shell.username,
        account = shell.account,
        privateKeySet = !shell.privateKey.isNullOrBlank(),
        passphraseSet = !shell.keyPassphrase.isNullOrBlank(),
        hostKey = shell.hostKey,
        enabled = shell.enabled,
        commandTimeoutSeconds = shell.commandTimeoutSeconds,
        maxOutputBytes = shell.maxOutputBytes,
        // Seconds rather than the ISO-8601 a Duration prints, because the field
        // beside it on the page is a number of seconds and two spellings of one
        // limit is a screen that has to explain itself.
        defaultCommandTimeoutSeconds = properties.commandTimeout.seconds.toInt(),
        defaultMaxOutputBytes = properties.maxOutputBytes,
        status = shell.status,
        lastCheckMessage = shell.lastCheckMessage,
        lastCheckedAt = shell.lastCheckedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        createdAt = shell.createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        lastModifiedAt = shell.lastModifiedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    )
}
