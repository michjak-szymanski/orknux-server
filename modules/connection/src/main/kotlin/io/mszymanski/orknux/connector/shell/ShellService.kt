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
) {

    fun shells(): List<ShellView> = shells.findAllByOrderByNameAsc().map(::ShellView)

    fun shell(id: Long): ShellView? = shells.findByIdOrNull(id)?.let(::ShellView)

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
            ),
        )
        shell.status = if (shell.configured) ShellStatus.NOT_CHECKED else ShellStatus.NOT_CONFIGURED
        return ShellView(shell)
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
         * A shell pointed at a different machine has no business carrying the
         * old one's host key: the first connection to the new address would be
         * refused for a mismatch that is not a mismatch, and the message would
         * accuse the network of something the edit did.
         */
        if (movedHost || input.forgetHostKey == true) shell.hostKey = null

        shell.status = if (shell.configured) shell.status else ShellStatus.NOT_CONFIGURED
        shell.lastModifiedAt = OffsetDateTime.now()
        return ShellView(shell)
    }

    /** The switch on the row, without opening the shell to edit it. */
    @Transactional
    fun setEnabled(id: Long, enabled: Boolean): ShellView {
        val shell = shells.findByIdOrNull(id) ?: throw ShellNotFoundException(id)
        shell.enabled = enabled
        shell.lastModifiedAt = OffsetDateTime.now()
        return ShellView(shell)
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
        return ShellView(shells.save(shell))
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
    val status: ShellStatus,
    val lastCheckMessage: String?,
    val lastCheckedAt: String?,
    val createdAt: String,
    val lastModifiedAt: String,
) {
    constructor(shell: Shell) : this(
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
        status = shell.status,
        lastCheckMessage = shell.lastCheckMessage,
        lastCheckedAt = shell.lastCheckedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        createdAt = shell.createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        lastModifiedAt = shell.lastModifiedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    )
}
