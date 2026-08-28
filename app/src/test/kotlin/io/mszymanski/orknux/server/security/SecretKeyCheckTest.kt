package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.connector.security.SecretCipher
import io.mszymanski.orknux.connector.security.SecretKeyCheck
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * A key that cannot work stops the boot, and says which way it is wrong.
 *
 * The lazy key inside [SecretCipher] is what this exists to get ahead of: it
 * built itself on first use, so a typo in `ORKNUX_SECRET_KEY` surfaced as a 500
 * on somebody's unrelated screen, hours after a deployment that had looked
 * entirely healthy - answering, serving pages, and holding connections that
 * were all quietly unusable.
 *
 * Driven directly rather than through a context that fails to start, because
 * what is worth pinning is the decision and the sentence: a broken context tells
 * a reader that something refused, and not which key, nor what to do about it.
 */
class SecretKeyCheckTest {

    @Test
    fun `a key that is not base64 refuses to start, and says so`() {
        assertThatThrownBy { SecretKeyCheck(SecretCipher("not base64 at all!!")).check() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not valid base64")
            // The sentence carries the fix, because whoever reads it is at a
            // shell with the variable in front of them.
            .hasMessageContaining("openssl rand -base64 32")
    }

    @Test
    fun `a key of the wrong length refuses to start, and says what length it was`() {
        // Sixteen bytes: a perfectly good AES-128 key, and useless here.
        val short = java.util.Base64.getEncoder().encodeToString(ByteArray(16))

        assertThatThrownBy { SecretKeyCheck(SecretCipher(short)).check() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("16 bytes")
            .hasMessageContaining("AES-256 needs 32")
    }

    @Test
    fun `a usable key says nothing and lets the boot continue`() {
        val good = java.util.Base64.getEncoder().encodeToString(ByteArray(32))

        assertThatCode { SecretKeyCheck(SecretCipher(good)).check() }.doesNotThrowAnyException()
        assertThat(SecretCipher(good).keyStatus()).isEqualTo(SecretCipher.KeyStatus.Usable)
    }

    /**
     * The one broken-looking case that is not broken: nothing is encrypted yet,
     * and refusing to boot would keep somebody off the screen that tells them
     * what to set.
     */
    @Test
    fun `no key at all is a warning rather than a refusal`() {
        assertThatCode { SecretKeyCheck(SecretCipher("")).check() }.doesNotThrowAnyException()
    }
}
