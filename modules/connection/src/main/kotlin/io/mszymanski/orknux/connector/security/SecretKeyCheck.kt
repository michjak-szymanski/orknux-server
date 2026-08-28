package io.mszymanski.orknux.connector.security

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Refuses to start on a secret key that cannot work.
 *
 * **Why at startup rather than where it is used.** [SecretCipher] builds its key
 * lazily, so a key that is not valid base64, or decodes to the wrong number of
 * bytes, was found out by whichever request first needed a credential. That
 * could be hours after the deployment, it was a 500 on somebody's unrelated
 * screen, and the sentence explaining it went into the log under whatever
 * feature they were using rather than beside the boot. Meanwhile the
 * installation looked healthy: it answered, it served pages, and every
 * connection it held was quietly unusable.
 *
 * A typo in an environment variable is worth exactly one loud failure at the
 * moment it is set, which is what this is.
 *
 * **Why a missing key is not one of them.** An installation with no key yet is
 * an installation with nothing encrypted yet - it is the first run, and refusing
 * to boot would mean nobody could reach the screen that explains what to set.
 * That case is warned about here and reported by the Doctor screen, which is
 * where it belongs. What cannot be tolerated is a key that was *meant* to work:
 * somebody set it, believes secrets are being kept with it, and every one of
 * them will fail.
 */
@Component
class SecretKeyCheck(private val cipher: SecretCipher) {

    @PostConstruct
    fun check() {
        when (val status = cipher.keyStatus()) {
            SecretCipher.KeyStatus.Usable -> Unit

            SecretCipher.KeyStatus.Missing -> log.warn(
                "orknux.security.secret-key is not set, so no credential can be stored or read. " +
                    "Set ORKNUX_SECRET_KEY; generate one with: openssl rand -base64 32",
            )

            SecretCipher.KeyStatus.NotBase64 -> error(
                "orknux.security.secret-key is not valid base64, so no stored credential can be read. " +
                    "Generate one with: openssl rand -base64 32",
            )

            is SecretCipher.KeyStatus.WrongLength -> error(
                "orknux.security.secret-key decodes to ${status.bytes} bytes; AES-256 needs 32. " +
                    "Generate one with: openssl rand -base64 32",
            )
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(SecretKeyCheck::class.java)
    }
}
