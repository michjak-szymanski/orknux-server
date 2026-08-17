package io.mszymanski.orknux.connector.security

import org.springframework.beans.factory.annotation.Value
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
 * environment the application runs in, so this protects the data at rest —
 * backups, disk, a replica, anyone with a database login. It does not protect
 * against someone who can already read the application's own environment; for
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
class SecretCipher(
    @param:Value("\${orknux.security.secret-key:}") private val configuredKey: String,
) {

    private val random = SecureRandom()

    private val key: SecretKeySpec by lazy {
        check(configuredKey.isNotBlank()) {
            "orknux.security.secret-key is not set. Credentials are encrypted with it, and " +
                "without it the ones already stored cannot be read. Generate one with: " +
                "openssl rand -base64 32"
        }

        val decoded = runCatching { Base64.getDecoder().decode(configuredKey.trim()) }
            .getOrElse { error("orknux.security.secret-key is not valid base64") }

        check(decoded.size == KEY_BYTES) {
            "orknux.security.secret-key decodes to ${decoded.size} bytes; AES-256 needs $KEY_BYTES"
        }

        SecretKeySpec(decoded, "AES")
    }

    /** Null and blank stay as they are: absent is not a secret worth an envelope. */
    fun encrypt(plaintext: String?): String? {
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
     */
    fun decrypt(stored: String?): String? {
        if (stored.isNullOrBlank() || !stored.startsWith(PREFIX)) return stored

        val parts = stored.removePrefix(PREFIX).split(':')
        require(parts.size == 2) { "A stored secret is not in the expected form" }

        val decoder = Base64.getDecoder()
        val iv = decoder.decode(parts[0])
        val encrypted = decoder.decode(parts[1])

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    /** Whether a stored value has already been through this. */
    fun isEncrypted(stored: String?): Boolean = stored != null && stored.startsWith(PREFIX)

    private companion object {
        const val PREFIX = "orkx1:"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
