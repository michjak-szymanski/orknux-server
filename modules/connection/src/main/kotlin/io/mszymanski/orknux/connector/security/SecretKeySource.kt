package io.mszymanski.orknux.connector.security

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.Base64

/**
 * Where the encryption key comes from, and what to do when it comes from nowhere.
 *
 * [SecretCipher] needs 32 base64 bytes and used to accept them from exactly one
 * place: `ORKNUX_SECRET_KEY`. That is correct for a deployment somebody set up
 * on purpose and wrong for every other way this server gets started. An
 * installation with nothing supplied came up, reported itself healthy, and then
 * failed the first time anybody saved a credential — and a developer restarting
 * their own server got the same, one stored secret at a time.
 *
 * So there are three sources, in this order, and the order is the whole design:
 *
 * 1. **Supplied.** `orknux.security.secret-key` is set. It wins, always, and
 *    nothing is written down — an operator keeping their own key is keeping it
 *    somewhere better than this.
 * 2. **Kept.** A key file exists at `orknux.security.secret-key-file`. Read it.
 * 3. **Generated.** Neither, so make one and write it to that path.
 *
 * ## Why a file and not a fresh key each start
 *
 * Because generating a *different* key on the next start does not fail loudly.
 * The server boots, the doctor says the key is usable, and every credential
 * stored under the old one is unreadable from then on — discovered one at a
 * time by whoever presses Save on them. A generated key is only safe if it
 * survives a restart, so it is written beside the data it protects and the two
 * travel together or not at all.
 *
 * That puts the burden on the *path* being persistent, which this class cannot
 * check. What it can do is say which of the three happened, so [SecretMigration]
 * can look at the database on the way up and shout when a key was generated
 * beside credentials that were not written with it — which is exactly the shape
 * of a key file on a filesystem that does not survive `docker run`.
 *
 * ## Why not in the database
 *
 * The point of the key is that a stolen dump is not enough to use the
 * credentials in it. A key stored in the same database it protects is not a key.
 *
 * The shell entrypoint of `orknux-one` did all of this correctly and did it in
 * `sh`, for that image alone. It is here now because the reason applies to every
 * way the server is started, and because two spellings of one rule is one too
 * many.
 */
@Component
class SecretKeySource(
    @param:Value("\${orknux.security.secret-key:}") private val supplied: String,
    @param:Value("\${orknux.security.secret-key-file:}") private val keyFile: String,
    @param:Value("\${orknux.security.previous-secret-key:}") private val previousSupplied: String = "",
) {

    /**
     * Resolved once and remembered.
     *
     * Lazily, because a key nobody ever needs should not make a server fail to
     * start — and because the alternative is writing a file from a constructor,
     * which runs in tests that never asked for one. Once, because generating
     * twice would generate two different keys.
     */
    @Volatile
    private var remembered: Resolved? = null

    private val resolved: Resolved
        get() = remembered ?: synchronized(this) { remembered ?: resolve().also { remembered = it } }

    /** The key itself, base64, or blank when there is none to be had. */
    val key: String get() = resolved.key

    /** Where [key] came from, for anything that has to explain it. */
    val origin: Origin get() = resolved.origin

    /**
     * The key the stored values were written with, when that is not [key].
     *
     * Only ever set part-way through a rotation. Re-encrypting every credential
     * in the database is not one write, so there is a window in which some rows
     * are on the new key and some are still on the old one — and a server that
     * stopped in that window would come up holding a key that opens half of what
     * it has, with no way to name the other half.
     *
     * So the old key is written down *before* the new one takes its place, and
     * it is what makes an interrupted rotation finishable rather than fatal:
     * whatever is still on it gets swept on the next start. It is deleted by
     * [rotationFinished] once nothing needs it, because a superseded key kept
     * next to the current one is a spare key to a lock that was changed.
     *
     * `ORKNUX_SECRET_KEY_PREVIOUS` is the same thing for a deployment that
     * supplies its own key: this server cannot rewrite somebody's environment,
     * so an operator rotating a supplied key sets the new one and the old one
     * together for one start.
     */
    val previousKey: String?
        get() = previousSupplied.trim().takeIf { it.isNotEmpty() } ?: previousAt()?.let { readKept(it) }

    /**
     * Puts a new key in place, keeping the one it replaces.
     *
     * The order is the only interesting thing about it. The old key is written
     * to the previous-key file *first*, then the current file is replaced, and
     * only then does anything re-encrypt. Every other order loses data on a
     * crash: replace first and an interrupted rotation leaves rows nothing can
     * open; re-encrypt first and a restart reads them with the old key.
     *
     * Refused when this server does not own a key file — a supplied key lives in
     * somebody's environment and cannot be changed from in here. That deployment
     * rotates by setting the new key and `ORKNUX_SECRET_KEY_PREVIOUS` together
     * for one start, which reaches the same sweep.
     */
    fun beginRotation(newKey: String) {
        val at = currentPath() ?: error(
            if (origin is Origin.Supplied) {
                "This installation's key comes from orknux.security.secret-key, which is set outside " +
                    "the server and cannot be changed from inside it. Set the new key and the old one " +
                    "as orknux.security.previous-secret-key together, and restart."
            } else {
                "This installation keeps no key file, so there is nowhere to put a new key. Set " +
                    "orknux.security.secret-key-file to a path that survives a restart."
            },
        )

        val replacing = key
        val previous = previousAt() ?: error("There is nowhere to keep the key being replaced")

        if (replacing.isNotBlank()) {
            Files.writeString(previous, replacing)
            ownerOnly(previous)
        }

        Files.writeString(at, newKey)
        ownerOnly(at)
        remembered = Resolved(newKey, Origin.Kept(at))
    }

    /**
     * The rotation is complete: nothing in the database is on the old key.
     *
     * Deleting it is part of the job rather than tidying. A key file left beside
     * the one that replaced it opens every backup taken before the rotation, so
     * an installation that rotated because a key leaked would still be keeping
     * the leaked one on disk.
     */
    fun rotationFinished() {
        previousAt()?.let { runCatching { Files.deleteIfExists(it) } }
    }

    /** The key file this server owns, or null when the key was supplied. */
    private fun currentPath(): Path? = (origin as? Origin.Kept)?.at ?: (origin as? Origin.Generated)?.at

    private fun previousAt(): Path? {
        val at = currentPath() ?: return null
        return at.resolveSibling(at.fileName.toString() + PREVIOUS_SUFFIX)
    }

    private fun resolve(): Resolved {
        if (supplied.isNotBlank()) return Resolved(supplied.trim(), Origin.Supplied)

        val path = keyFile.trim().takeIf { it.isNotEmpty() }
            ?: return Resolved("", Origin.None("no key was supplied and no key file is configured"))

        val at = runCatching { Paths.get(path).toAbsolutePath() }
            .getOrElse { return Resolved("", Origin.None("$path is not a usable path")) }

        readKept(at)?.let { return Resolved(it, Origin.Kept(at)) }

        return generate(at)
    }

    /** A key somebody — or an earlier start — already put there. */
    private fun readKept(at: Path): String? {
        if (!Files.isRegularFile(at)) return null

        return try {
            Files.readString(at).trim().takeIf { it.isNotEmpty() }
        } catch (failure: IOException) {
            log.warn("The key file at {} could not be read: {}", at, failure.javaClass.simpleName)
            null
        }
    }

    /**
     * Make one, and keep it.
     *
     * `CREATE_NEW` rather than `CREATE`, so two servers starting against the same
     * directory cannot both write and leave one of them holding a key the file no
     * longer contains. The loser reads what the winner wrote, which is the same
     * key, which is the point.
     *
     * A key that cannot be written is not generated at all. Holding one only in
     * memory would encrypt this session's credentials with something that ceases
     * to exist when the process does — worse than having no key, because with no
     * key they stay readable.
     */
    private fun generate(at: Path): Resolved {
        val generated = freshKey()

        return try {
            at.parent?.let { Files.createDirectories(it) }
            Files.write(at, generated.toByteArray(Charsets.UTF_8), StandardOpenOption.CREATE_NEW)
            ownerOnly(at)

            log.info(
                "No secret key was supplied, so one was generated and kept at {}. It belongs with the " +
                    "database it protects: back the two up together, and restore them together. " +
                    "Set ORKNUX_SECRET_KEY to supply your own instead, and this file is never read.",
                at,
            )
            Resolved(generated, Origin.Generated(at))
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            // Somebody else got there between the check and the write.
            readKept(at)
                ?.let { Resolved(it, Origin.Kept(at)) }
                ?: Resolved("", Origin.None("$at exists but is empty"))
        } catch (failure: IOException) {
            log.warn(
                "No secret key was supplied and one could not be written to {} ({}). Credentials will " +
                    "be stored as plain text until a key is configured.",
                at,
                failure.javaClass.simpleName,
            )
            Resolved("", Origin.None("$at could not be written"))
        }
    }

    /**
     * Readable by the account that runs the server and nobody else.
     *
     * Best effort on purpose: this is not POSIX everywhere, and a key that is
     * kept with the wrong permissions is still better than a server that will
     * not start on Windows.
     */
    private fun ownerOnly(at: Path) {
        runCatching {
            Files.setPosixFilePermissions(at, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        }
    }

    private data class Resolved(val key: String, val origin: Origin)

    /** Which of the three happened, in words something else can print. */
    sealed interface Origin {

        /** Set in the environment. Nothing was written down. */
        data object Supplied : Origin

        /** Read from a file an earlier start, or an operator, put there. */
        data class Kept(val at: Path) : Origin

        /** Made on this start, and written to [at]. */
        data class Generated(val at: Path) : Origin

        /** There is no key, and [why] says what stopped there being one. */
        data class None(val why: String) : Origin
    }

    companion object {

        private val log = LoggerFactory.getLogger(SecretKeySource::class.java)

        private const val KEY_BYTES = 32

        /**
         * A fresh key, for whoever is replacing one.
         *
         * Here rather than in the rotation, so the key this generates on a first
         * start and the key it generates on a rotation are the same 32 bytes from
         * the same generator, and there is one place to change if that is ever
         * wrong.
         */
        fun freshKey(): String = ByteArray(KEY_BYTES)
            .also(SecureRandom()::nextBytes)
            .let { Base64.getEncoder().encodeToString(it) }

        /**
         * What the key being replaced is kept as, beside the current one.
         *
         * A suffix rather than a second configured path, so an operator who moved
         * the key file has moved this with it and cannot end up with the two in
         * different places.
         */
        private const val PREVIOUS_SUFFIX = ".previous"

        /**
         * A source that is only ever the key it is given.
         *
         * For callers holding a key already — the tests, and anything
         * constructing a [SecretCipher] against a known value. It configures no
         * file, so it reads nothing and writes nothing.
         */
        fun of(key: String): SecretKeySource = SecretKeySource(key, "")
    }
}
