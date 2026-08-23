package io.mszymanski.orknux.connector.security

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts the credentials this application is trusted with.
 *
 * Every provider key, Slack token and MCP credential used to sit in the database
 * as the text it is, which makes a stolen backup or a `pg_dump` the same thing
 * as handing over every key the installation holds. Encrypting them here means
 * the database on its own is not enough.
 *
 * **What this does and does not defend against.** The key comes from the
 * environment the application runs in, or from a file beside the database when
 * nothing was supplied - [SecretKeySource] decides which - so this protects the
 * data at rest: backups, disk, a replica, anyone with a database login. It does
 * not protect against someone who can already read the application's own
 * environment, or the key file it keeps one in; for
 * that the key has to live somewhere the application only borrows it from, which
 * is a different job and a different deployment.
 *
 * AES-256-GCM, a fresh initialisation vector for every value, and the vector
 * carried with the ciphertext. GCM rather than CBC because it authenticates:
 * a tampered value fails to decrypt instead of decrypting into rubbish.
 *
 * The envelope is prefixed and versioned — `orkx1:iv:ciphertext` — so a later
 * scheme can be told apart from this one, and so a value that has never been
 * encrypted can be recognised on sight.
 */
@Component
class SecretCipher @Autowired constructor(private val keys: SecretKeySource) {

    /**
     * For a caller that already holds a key rather than a way of finding one.
     *
     * The primary constructor is annotated because of this one. A class with two
     * constructors and no mark on either is a class Spring will not build, and
     * the failure is `NoSuchMethodException: SecretCipher.<init>()` at context
     * load - so every test in the suite, rather than anything that looks like
     * this file.
     *
     * The tests, mostly, and anything encrypting against a key it was handed —
     * a rotation reading values back with the key they were written with. It
     * configures no key file, so this constructor reads nothing off disk and
     * writes nothing to it.
     */
    constructor(key: String) : this(SecretKeySource.of(key))

    private val configuredKey: String get() = keys.key

    /** Where the key came from, for anything that has to explain it. */
    val origin: SecretKeySource.Origin get() = keys.origin

    private val random = SecureRandom()

    private val key: SecretKeySpec by lazy {
        check(configuredKey.isNotBlank()) {
            "There is no secret key. Credentials are encrypted with it, and without it the ones " +
                "already stored cannot be read. One is generated on first start unless " +
                "orknux.security.secret-key-file is empty; set orknux.security.secret-key to " +
                "supply your own (openssl rand -base64 32)"
        }

        val decoded = runCatching { Base64.getDecoder().decode(configuredKey.trim()) }
            .getOrElse { error("orknux.security.secret-key is not valid base64") }

        check(decoded.size == KEY_BYTES) {
            "orknux.security.secret-key decodes to ${decoded.size} bytes; AES-256 needs $KEY_BYTES"
        }

        SecretKeySpec(decoded, "AES")
    }

    /**
     * Null and blank stay as they are: absent is not a secret worth an envelope.
     *
     * A value already in the envelope is returned untouched rather than wrapped
     * again. That makes this idempotent, which is what lets a credential nobody
     * can read survive being loaded and written back: it goes to the column as
     * the same bytes it came from, still recoverable by whoever has the key it
     * was written with.
     */
    fun encrypt(plaintext: String?): String? {
        if (isEncrypted(plaintext)) return plaintext
        if (plaintext.isNullOrBlank()) return plaintext

        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))

        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        return "$PREFIX${encoder.encodeToString(iv)}:${encoder.encodeToString(encrypted)}"
    }

    /**
     * Reads a stored value.
     *
     * Anything without the envelope is returned untouched: rows written before
     * this existed are plaintext, and refusing to read them would take an
     * installation's integrations offline at the moment it upgrades. They are
     * rewritten encrypted by [SecretMigration].
     *
     * One that will not open comes back as it was stored — still in its
     * envelope, so [isEncrypted] says so and nothing mistakes it for a usable
     * credential. It does not throw, and that is the whole point: a single
     * credential written with a key this installation no longer has used to
     * take down every screen that listed the thing it belonged to, including
     * the screen somebody would go to in order to enter it again. The one
     * unreadable value is a fact about that value, not about the page.
     *
     * What went wrong is not lost — the doctor reads these columns directly and
     * reports exactly which cannot be opened. It asks [canRead], which is the
     * only honest way to ask: this never throws, so a caller testing for a thrown
     * failure is testing for something that cannot happen.
     */
    fun decrypt(stored: String?): String? {
        if (stored.isNullOrBlank() || !stored.startsWith(PREFIX)) return stored

        return try {
            val parts = stored.removePrefix(PREFIX).split(':')
            require(parts.size == 2) { "A stored secret is not in the expected form" }

            val decoder = Base64.getDecoder()
            val iv = decoder.decode(parts[0])
            val encrypted = decoder.decode(parts[1])

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (failure: Exception) {
            // Nothing of the value itself is logged, readable or not.
            log.warn("A stored secret could not be read with the configured key: {}", failure.javaClass.simpleName)
            stored
        }
    }

    /** Whether a stored value has already been through this. */
    fun isEncrypted(stored: String?): Boolean = stored != null && stored.startsWith(PREFIX)

    /**
     * Whether [decrypt] can actually open this value with the configured key.
     *
     * It has to be asked, because it cannot be caught. [decrypt] swallows its own
     * failure on purpose — one credential nobody can read must not take down the
     * screen somebody would go to in order to enter it again — so wrapping a call
     * in `runCatching` and testing `isFailure` answers "readable" for every value
     * on earth, including the ones written with a key this installation lost. A
     * check built that way cannot report the one thing it exists to report, and
     * one was.
     *
     * So the question is answered where the swallowing happens. What comes back
     * from a failed read is the stored value, still in its envelope; what comes
     * back from a successful one is the plaintext, which can never be in an
     * envelope because [encrypt] refuses to wrap a value that already looks like
     * one. Still sealed after decrypting therefore means it did not open.
     *
     * Anything that was never encrypted — plaintext from before this existed,
     * null, blank — reads as readable, because it is: [decrypt] hands it back as
     * it is and callers get the value they stored.
     */
    fun canRead(stored: String?): Boolean = !isEncrypted(decrypt(stored))

    /**
     * Whether the key is usable, without using it.
     *
     * The key is checked lazily, on first encrypt or decrypt, which is the right
     * moment for a value nobody may ever need — but it means an installation with no
     * key starts perfectly and then fails the first time somebody saves a credential.
     * That cost forty minutes to find in a stack trace once, and nothing on any
     * screen said a word about it.
     *
     * So the same checks are available as an answer rather than as an exception, for
     * whatever wants to ask before anything breaks.
     */
    fun keyStatus(): KeyStatus {
        if (configuredKey.isBlank()) return KeyStatus.Missing

        val decoded = runCatching { Base64.getDecoder().decode(configuredKey.trim()) }
            .getOrElse { return KeyStatus.NotBase64 }

        return if (decoded.size == KEY_BYTES) KeyStatus.Usable else KeyStatus.WrongLength(decoded.size)
    }

    /** What is wrong with the configured key, if anything. */
    sealed interface KeyStatus {

        data object Usable : KeyStatus

        data object Missing : KeyStatus

        data object NotBase64 : KeyStatus

        data class WrongLength(val bytes: Int) : KeyStatus
    }

    private companion object {
        val log = LoggerFactory.getLogger(SecretCipher::class.java)

        const val PREFIX = "orkx1:"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
