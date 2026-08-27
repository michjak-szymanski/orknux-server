package io.mszymanski.orknux.connector.shell

import io.mszymanski.orknux.connector.security.SECRET_COLUMN_LENGTH
import io.mszymanski.orknux.connector.security.SecretCipher
import io.mszymanski.orknux.connector.security.SecretConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.apache.sshd.common.util.OsUtils
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Duration
import java.time.OffsetDateTime

/**
 * A machine this installation can run commands on, over SSH.
 *
 * Installation-wide rather than per workspace, for the reason a proxy rule is:
 * a host belongs to the infrastructure this process sits in rather than to the
 * team whose agent asked about it, and deciding which hosts this application may
 * reach is an administrator's decision and nobody else's.
 *
 * **What contains this.** The SSH target, and nothing here. There is no list of
 * forbidden commands and no classifier deciding which are safe, because reading
 * a shell command and saying what it will do is not a solvable problem - a
 * denylist that is nearly right is worse than none, since it tells an
 * administrator they are protected while `sh -c "$(curl …)"` walks straight
 * past it. The design is that a shell points at a virtual machine or a container
 * the administrator chose and is willing to lose, that the account in [account]
 * has exactly the privileges they meant to give away, and that everything run
 * through it is written down where they can read it.
 */
@Entity
@Table(name = "shell")
class Shell(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, length = 120)
    var name: String = "",

    @Column(nullable = false, length = 255)
    var host: String = "",

    @Column(nullable = false)
    var port: Int = 22,

    /**
     * The account on the far side, or null to let [account] decide.
     *
     * Optional, because `ssh build.internal` is optional about it too: leaving
     * it out is a thing an administrator already knows the meaning of, and the
     * meaning is "the account I am". Made nullable rather than blank-means-unset
     * so that the database says which it is, and so that a screen can tell an
     * unset username apart from one somebody cleared.
     */
    @Column(length = 255)
    var username: String? = null,

    /**
     * The private key, in OpenSSH or PEM form, encrypted in the database; see
     * [SecretCipher]. Never returned by the API - the only thing said about it
     * outside is whether there is one.
     *
     * A key rather than a password because a password is a thing a person types
     * and this is a machine talking to a machine, and because an SSH key can be
     * issued for exactly one account on exactly one host and withdrawn there
     * without anybody having to change a password somebody else also knows.
     */
    @Convert(converter = SecretConverter::class)
    @Column(name = "private_key", columnDefinition = "text")
    var privateKey: String? = null,

    /** The key's passphrase, if it has one. Encrypted, and never returned. */
    @Convert(converter = SecretConverter::class)
    @Column(name = "key_passphrase", length = SECRET_COLUMN_LENGTH)
    var keyPassphrase: String? = null,

    @Column(nullable = false)
    var enabled: Boolean = true,

    /**
     * The host key this shell was first seen with, as a `SHA256:` fingerprint.
     *
     * Trust on first use, which is what a person does by hand the first time
     * they type yes at `ssh`. The first connection that gets as far as a server
     * key records it; every one afterwards compares and refuses on a mismatch.
     *
     * It is the one thing standing between "the administrator secures the box"
     * and this application handing a private key to whatever happens to answer
     * on that address today. Cleared deliberately when a host is rebuilt, which
     * is a decision somebody makes rather than one this makes for them.
     */
    @Column(name = "host_key", length = 500)
    var hostKey: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ShellStatus = ShellStatus.NOT_CHECKED,

    /**
     * How long one command on this machine may run, in seconds, or null for the
     * installation's own limit in [ShellProperties.commandTimeout].
     *
     * Here because the limit is a property of the machine rather than of the
     * installation. A container built to compile things and a router that
     * answers `show interfaces` want opposite numbers, and an installation that
     * has both has to choose one - which meant, before this, choosing the number
     * that lets a build finish and applying it to the box where a command that
     * hangs should have been noticed in a minute.
     *
     * Null rather than the default copied in when the row is written. A copy
     * would be a promise made on the day the shell was added: raise the
     * installation's limit afterwards and every machine that never asked for
     * anything different would stay on the old number, and nobody would be able
     * to tell those apart from the ones somebody chose deliberately.
     */
    @Column(name = "command_timeout_seconds")
    var commandTimeoutSeconds: Int? = null,

    /**
     * How much of a command's output this machine keeps, per stream, in bytes,
     * or null for [ShellProperties.maxOutputBytes].
     *
     * Same argument as the timeout above, and the same reason for null. What
     * makes it worth setting per machine is that the cost is not paid here: the
     * bytes go into a model's context, and a machine whose commands are `df` and
     * `systemctl status` has no business being allowed the allowance a build
     * machine needs.
     */
    @Column(name = "max_output_bytes")
    var maxOutputBytes: Int? = null,

    @Column(name = "last_check_message", length = 500)
    var lastCheckMessage: String? = null,

    @Column(name = "last_checked_at")
    var lastCheckedAt: OffsetDateTime? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_at", nullable = false)
    var lastModifiedAt: OffsetDateTime = OffsetDateTime.now(),
) {

    /**
     * The account commands actually run as.
     *
     * [username] when there is one, and otherwise the account this server
     * process runs as - which is what `ssh build.internal` does with no user in
     * front of it, and what MINA SSHD itself falls back to when an `ssh_config`
     * entry matches but names no user. Doing it here rather than leaving the
     * username empty is the difference between a decision and an accident: the
     * empty string is not a fallback to MINA on the path this code takes, it is
     * a user name of zero length put on the wire for the far side to refuse.
     *
     * Resolved on every read rather than cached, because [OsUtils] caches it
     * already and a value frozen at class-load time would be a value no test
     * could change.
     */
    val account: String
        get() = username?.trim()?.ifEmpty { null } ?: localAccount()

    /**
     * [commandTimeoutSeconds] as a duration, or null when this machine has not
     * been given one.
     *
     * Stored as seconds and read as a [Duration] because those are two different
     * audiences. A column of seconds is a number an administrator can read in a
     * database dump and a form can put in a box; a [Duration] is what the thing
     * that waits actually takes, and converting it at every call site would be
     * the same line written four times.
     */
    val commandTimeout: Duration?
        get() = commandTimeoutSeconds?.let { Duration.ofSeconds(it.toLong()) }

    /** Whether there is anything to connect with. */
    val configured: Boolean
        get() = host.isNotBlank() && !privateKey.isNullOrBlank()
}

/**
 * The account this server process runs as, as `ssh` would read it.
 *
 * Through MINA's own accessor rather than `user.name` directly, so that a shell
 * with no username resolves to exactly what the library would have resolved -
 * including the `org.apache.sshd.currentUser` override, which is the only way a
 * test can say who it is pretending to be.
 *
 * Empty when there is no answer, which no JVM this runs on gives. Empty rather
 * than an invented name all the same: a zero-length user the far side refuses by
 * name is a truer report than `root` tried on a machine nobody meant to give
 * root on.
 */
internal fun localAccount(): String =
    runCatching { OsUtils.getCurrentUser() }.getOrNull()?.trim().orEmpty()

/**
 * What the last probe found.
 *
 * The same four words the connections use, because it is the same question and
 * a second vocabulary for it would only be a second thing to learn.
 */
enum class ShellStatus {

    /** No key stored, so there is nothing to connect with. */
    NOT_CONFIGURED,

    /** Configured, but nothing has reached the far side yet. */
    NOT_CHECKED,
    CONNECTED,
    FAILED,
}

interface ShellRepository : JpaRepository<Shell, Long> {

    fun findByName(name: String): Shell?

    fun findAllByOrderByNameAsc(): List<Shell>

    /** The candidates when an agent asks for "a shell"; see `ShellService.choose`. */
    fun findAllByEnabledTrueOrderByNameAsc(): List<Shell>
}

@ConfigurationProperties(prefix = "orknux.shell")
data class ShellProperties(

    /**
     * How long the SSH handshake may take before it counts as a failure.
     *
     * Shorter than a command's timeout, because this measures whether anything
     * is there rather than how long the work takes. A host that has not
     * completed a handshake in ten seconds is one to report on rather than one
     * to keep waiting for.
     */
    val connectTimeout: Duration = Duration.ofSeconds(10),

    /**
     * How long one command may run before the channel is closed under it, on a
     * shell that has not been given a limit of its own.
     *
     * A command that never returns must not take the thread that started it with
     * it, and the same argument the script runner's watchdog makes applies here
     * with more force, because this one is running on somebody else's machine.
     *
     * Closing the channel is not the same as killing the process on the far
     * side, and nothing here pretends otherwise: what comes back says the
     * command was still running when we stopped waiting, which is the true
     * statement. Killing it would mean tracking a process id through a shell
     * that may not have given us one.
     *
     * **Ten minutes, and it was sixty seconds.** Sixty was chosen against the
     * commands somebody types by hand - `df`, `systemctl status`, `tail` - and
     * it is right for those. It is wrong for the thing this product ships a box
     * for: `docker/coder` arrives with an empty Maven repository, so the first
     * build an agent runs on it downloads Spring Boot before it compiles a line,
     * and every machine's repository is empty exactly once. The old default did
     * not make that slow, it made it impossible - the agent was handed a failure
     * it did not cause and could not fix, and no message anywhere said the
     * number was ours. Ten minutes is what the coding task was given on that
     * box and finished inside; it is not a measurement of the worst build
     * anybody will ever run, and a shell that hosts one of those should be
     * given a limit of its own.
     *
     * What ten minutes costs is that a command which hangs holds its channel for
     * ten minutes rather than one. That is the trade, taken deliberately: the
     * agent is waiting either way, and a wait that ends in an answer is worth
     * more than one that ends on time. A machine where a hang should be noticed
     * in a minute, and one where a build wants half an hour, are both what
     * [Shell.commandTimeoutSeconds] is for.
     */
    val commandTimeout: Duration = Duration.ofMinutes(10),

    /**
     * How much of a command's output is kept, per stream, on a shell that has
     * not been given a limit of its own.
     *
     * A command that prints a gigabyte would otherwise be a gigabyte in this
     * process's heap and then a gigabyte in a model's context, and neither is
     * survivable. What goes when this is reached is the *middle*: [BoundedBuffer]
     * keeps the first third of the allowance and the last two thirds and writes
     * a sentence between them saying how much was removed.
     *
     * **256 KiB, and it was 64.** 64 KiB is generous for a command that
     * answers and much too small for one that fails, which is the case that
     * matters: a build that works prints a line, and a build that breaks prints
     * the download log, the reactor summary and the compile error. 256 KiB is
     * the allowance the coding task was proved with on the coder box, and it is
     * still a fraction of a model's context rather than a multiple of it, which
     * is the ceiling that matters: these bytes are not bound for a log file,
     * they are bound for something that has to read them.
     *
     * **Raising this is now a comfort rather than a rescue.** It was the other
     * way round while the buffer kept only the beginning, because then a long
     * enough preamble pushed the answer out however large the number was, and
     * the number was the only defence. Both ends survive now at any size, so
     * this decides how much context comes with the answer rather than whether
     * the answer arrives.
     *
     * What a two-ended buffer still cannot save is a single line longer than the
     * allowance - one line is cut in the middle of itself, and there is no end
     * of it to keep separately - and anything whose meaning is genuinely in the
     * middle: the one warning among four thousand, the middle frames of a very
     * deep stack. Those want `grep` on the far side rather than a larger number
     * here, and the marker's byte count is what tells a reader to go and get
     * them.
     */
    val maxOutputBytes: Int = 256 * 1024,

    /**
     * How long a session may sit unused before it is swept and its directory
     * removed.
     *
     * An agent that opens a session and never closes it is the ordinary case,
     * not the exception - a run fails, a model changes its mind, a conversation
     * ends. Without this, every one of those leaves a directory on somebody's
     * machine forever.
     */
    val sessionIdleTimeout: Duration = Duration.ofHours(2),

    /**
     * How long the sweep keeps trying to remove the directory of a session whose
     * host will not answer.
     *
     * A machine that has been decommissioned is never going to let us tidy up,
     * and retrying its sessions on every sweep for the rest of the
     * installation's life is a log line every few minutes about a host that no
     * longer exists. After this the session is closed in the database and the
     * fact that its directory may still be out there is logged once, plainly.
     */
    val sessionAbandonAfter: Duration = Duration.ofDays(7),

    /**
     * Whether the sweep runs at all, and how often.
     *
     * False leaves every directory where it is, which only a test wants. An
     * installation that turns this off has decided to clean up by hand and
     * should know that is what it decided.
     */
    val sweepEnabled: Boolean = true,
    val sweepInterval: Duration = Duration.ofMinutes(10),
    val sweepInitialDelay: Duration = Duration.ofMinutes(1),

    /**
     * How many sessions one agent may hold open at once.
     *
     * Concurrent sessions are allowed on purpose - an agent comparing two hosts,
     * or keeping a build directory while it reads a log, is doing something
     * reasonable. A cap all the same, because an agent in a loop opening
     * sessions makes a directory per turn, and the failure would be somebody
     * else's disk rather than anything visible here.
     */
    val maxSessionsPerAgent: Int = 5,

    /**
     * Where a session's directory is made on the far side.
     *
     * Under a fixed root with the session id as the name rather than `mktemp -d`,
     * so that a directory whose row was somehow lost is still recognisably ours
     * and an administrator clearing up by hand knows what they are looking at.
     */
    val directoryRoot: String = "/tmp/orknux",
)

class ShellNotFoundException(id: Long) : RuntimeException("No shell with id $id")

class ShellNameTakenException(name: String) : RuntimeException("A shell called $name already exists")

class ShellNameInvalidException : RuntimeException("A shell name is required")

class ShellAddressInvalidException(reason: String) : RuntimeException(reason)

class ShellKeyInvalidException(reason: String) : RuntimeException("That private key cannot be used: $reason")

/**
 * A per-shell limit outside what a limit can usefully be.
 *
 * Refused rather than clamped. A number silently moved to the nearest one this
 * will accept is a form that says it saved what somebody typed and did not, and
 * the next person to read the page has no way to tell which of the two it is.
 */
class ShellLimitInvalidException(reason: String) : RuntimeException(reason)
