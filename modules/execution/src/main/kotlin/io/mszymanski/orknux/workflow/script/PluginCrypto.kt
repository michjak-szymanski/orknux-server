package io.mszymanski.orknux.workflow.script

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The arithmetic a plugin cannot do for itself.
 *
 * GraalJS has no crypto — not switched off, absent: the engine offers a
 * hundred-odd `js.*` options and not one of them is a digest, an HMAC or a
 * random number, and Node's `crypto` belongs to the Node launcher rather than
 * to an embedded context. So a plugin cannot hash anything, which is a wall
 * rather than an inconvenience: a database's authentication handshake needs
 * HMAC-SHA256 and PBKDF2 before it can send a single query, and a plugin
 * verifying a webhook signature needs the same.
 *
 * **Nothing here is granted.** A digest reaches nothing, sends nothing and
 * learns nothing — it is arithmetic, in the same class as `JSON.parse`, and
 * the server does it only because the sandbox has no instruction for it. That
 * is the line the plugin model already draws: a permission turns on a language
 * builtin, a capability is the server making a call on the plugin's behalf,
 * and this is neither. A dialog nobody can answer meaningfully is one they
 * learn to click through.
 *
 * What is bounded is size and cost, because those are real. See [MOST_BYTES]
 * and [MOST_ITERATIONS].
 */
object PluginCrypto {

    /**
     * What a plugin may ask for, and what each is called in Java.
     *
     * `sha1` and `md5` are here because protocols need them — Postgres's older
     * `md5` authentication, S3 signatures, Git object ids — and refusing them
     * would send plugin authors to hand-written implementations that are worse
     * in every way. They are not for anything new.
     */
    private val DIGESTS = mapOf(
        "sha256" to "SHA-256",
        "sha384" to "SHA-384",
        "sha512" to "SHA-512",
        "sha1" to "SHA-1",
        "md5" to "MD5",
    )

    private val MACS = mapOf(
        "sha256" to "HmacSHA256",
        "sha384" to "HmacSHA384",
        "sha512" to "HmacSHA512",
        "sha1" to "HmacSHA1",
        "md5" to "HmacMD5",
    )

    private val DERIVATIONS = mapOf(
        "sha256" to "PBKDF2WithHmacSHA256",
        "sha384" to "PBKDF2WithHmacSHA384",
        "sha512" to "PBKDF2WithHmacSHA512",
        "sha1" to "PBKDF2WithHmacSHA1",
    )

    /**
     * What one call may be handed.
     *
     * It crosses as base64 and is held in memory more than once on the way, so
     * this is about the server rather than about the plugin. Generous for
     * anything anybody actually hashes.
     */
    const val MOST_BYTES = 8 * 1024 * 1024

    /**
     * How much work a plugin may ask for in one derivation.
     *
     * PBKDF2 is deliberately slow, and this work happens on the server's side
     * of the sandbox where [ScriptGuard]'s wall-clock bound does not reach it -
     * so a plugin naming a large enough number is a denial of service against
     * the whole installation rather than against its own call. Postgres asks
     * for 4096.
     *
     * Over it is refused rather than clamped. A plugin that believes it did
     * ten million rounds and got a million has a security bug nobody can see,
     * and a silent clamp is how that bug is written.
     */
    const val MOST_ITERATIONS = 1_000_000

    /** Past any key anybody derives, and past any nonce anybody needs. */
    const val MOST_LENGTH = 1024

    private val random = SecureRandom()

    /** The digest of [input], or a refusal saying which part was wrong. */
    fun hash(algorithm: String, input: ByteArray): Result {
        val named = DIGESTS[algorithm.lowercase().trim()]
            ?: return Result.Refused(unknown(algorithm, DIGESTS.keys))
        if (input.size > MOST_BYTES) return Result.Refused(tooMuch(input.size))
        return Result.Answer(MessageDigest.getInstance(named).digest(input))
    }

    fun hmac(algorithm: String, key: ByteArray, input: ByteArray): Result {
        val named = MACS[algorithm.lowercase().trim()]
            ?: return Result.Refused(unknown(algorithm, MACS.keys))
        if (input.size > MOST_BYTES) return Result.Refused(tooMuch(input.size))
        if (key.isEmpty()) return Result.Refused("an HMAC needs a key")
        return Result.Answer(
            Mac.getInstance(named).run {
                init(SecretKeySpec(key, named))
                doFinal(input)
            },
        )
    }

    /**
     * [length] bytes derived from [password] and [salt].
     *
     * The password crosses as bytes and is turned back into characters here,
     * because that is the shape `PBEKeySpec` takes. It is ISO-8859-1 rather
     * than UTF-8 on purpose: it maps every byte to exactly one character and
     * back, so what is derived is a function of the bytes the plugin sent
     * rather than of how they decoded.
     */
    fun pbkdf2(algorithm: String, password: ByteArray, salt: ByteArray, iterations: Int, length: Int): Result {
        val named = DERIVATIONS[algorithm.lowercase().trim()]
            ?: return Result.Refused(unknown(algorithm, DERIVATIONS.keys))
        if (iterations < 1) return Result.Refused("iterations has to be at least 1")
        if (iterations > MOST_ITERATIONS) {
            return Result.Refused(
                "$iterations iterations is more than this server will do in one call " +
                    "(at most $MOST_ITERATIONS)",
            )
        }
        if (length < 1 || length > MOST_LENGTH) {
            return Result.Refused("a derived key is 1 to $MOST_LENGTH bytes, and $length was asked for")
        }
        if (salt.isEmpty()) return Result.Refused("a derivation needs a salt")

        val characters = String(password, Charsets.ISO_8859_1).toCharArray()
        val derived = SecretKeyFactory.getInstance(named)
            .generateSecret(PBEKeySpec(characters, salt, iterations, length * 8))
            .encoded
        return Result.Answer(derived)
    }

    /** [bytes] from the platform's secure source, never `Math.random`. */
    fun random(bytes: Int): Result {
        if (bytes < 1 || bytes > MOST_LENGTH) {
            return Result.Refused("between 1 and $MOST_LENGTH bytes, and $bytes was asked for")
        }
        return Result.Answer(ByteArray(bytes).also(random::nextBytes))
    }

    /**
     * Whether two byte strings are equal, in time that does not depend on how
     * far along they differ.
     *
     * Here rather than left to the plugin because a constant-time comparison
     * written in JavaScript stops being constant-time the moment a JIT has
     * seen it — and comparing a signature with `===` leaks its prefix through
     * how long the comparison took.
     */
    fun equal(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    /**
     * Bytes as the text they spell, or a refusal where they spell none.
     *
     * The one conversion here that can fail. Base64 is bytes and text is
     * characters, and not every sequence of bytes is a sequence of characters
     * - a plugin decoding a digest expecting to read it has made a mistake,
     * and the mistake is worth a sentence rather than a string of replacement
     * marks that looks like data.
     */
    fun text(bytes: ByteArray): Result {
        val decoder = Charsets.UTF_8.newDecoder()
        val said = runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrNull()
            ?: return Result.Refused("those bytes are not UTF-8 text")
        return Result.Answer(said.toByteArray())
    }

    private fun unknown(asked: String, known: Set<String>): String =
        "\"$asked\" is not an algorithm this server has; it has ${known.sorted().joinToString(", ")}"

    private fun tooMuch(size: Int): String =
        "that is $size bytes, and one call takes at most $MOST_BYTES"

    /** An answer or a sentence, because a refusal is data rather than a throw. */
    sealed interface Result {

        data class Answer(val bytes: ByteArray) : Result {

            /** Generated because the class holds an array, which has no useful equals. */
            override fun equals(other: Any?): Boolean =
                this === other || (other is Answer && bytes.contentEquals(other.bytes))

            override fun hashCode(): Int = bytes.contentHashCode()
        }

        data class Refused(val why: String) : Result
    }

    /** Base64 in, bytes out; null where the text is not base64 at all. */
    fun decoded(base64: String): ByteArray? = runCatching { Base64.getDecoder().decode(base64) }.getOrNull()

    fun encoded(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
