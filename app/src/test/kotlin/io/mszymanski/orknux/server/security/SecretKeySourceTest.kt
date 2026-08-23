package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.connector.security.SecretCipher
import io.mszymanski.orknux.connector.security.SecretKeySource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * Where the key comes from, and the one failure in here that loses data.
 *
 * No Spring: this is a class that reads a property, reads a file and writes a
 * file, and every case worth testing is a case about which of those three
 * happened. A context would only make the interesting ones harder to set up —
 * the interesting ones need a directory that is empty, or a directory holding
 * yesterday's key, and a `@SpringBootTest` gets one directory for the whole
 * suite.
 *
 * The case this class exists for is the third test down. A generated key that
 * is not the same key on the next start does not fail: the server boots,
 * reports itself healthy, and cannot read a credential it wrote an hour ago.
 * Everything else here is arrangement around proving that cannot happen.
 */
class SecretKeySourceTest {

    @TempDir
    lateinit var directory: Path

    /**
     * A key in the environment wins, and leaves nothing behind.
     *
     * Both halves matter. An operator who supplies their own key is keeping it
     * somewhere better than this server's disk, and a copy written down beside
     * the database would quietly undo that decision.
     */
    @Test
    fun `a supplied key is used and never written down`() {
        val at = directory.resolve("secret.key")

        val source = SecretKeySource(SUPPLIED, at.toString())

        assertThat(source.key).isEqualTo(SUPPLIED)
        assertThat(source.origin).isEqualTo(SecretKeySource.Origin.Supplied)
        assertThat(Files.exists(at)).isFalse()
    }

    /** Nothing supplied and nothing kept: one is made, and it is a usable one. */
    @Test
    fun `a key is generated when there is none, and kept`() {
        val at = directory.resolve("secret.key")

        val source = SecretKeySource("", at.toString())

        assertThat(source.origin).isInstanceOf(SecretKeySource.Origin.Generated::class.java)
        assertThat(Files.readString(at).trim()).isEqualTo(source.key)
        assertThat(Base64.getDecoder().decode(source.key)).hasSize(32)
        assertThat(SecretCipher(source.key).keyStatus()).isEqualTo(SecretCipher.KeyStatus.Usable)
    }

    /**
     * The one that matters: a restart is the same key, not a new one.
     *
     * Two sources against one directory is what a second start looks like from
     * in here. If the second generated its own, every credential the first wrote
     * would be unreadable — and nothing would say so, which is why this is a
     * test and not a comment.
     */
    @Test
    fun `a second start reads the key the first one generated`() {
        val at = directory.resolve("secret.key")

        // Read the first one's key before the second exists. Resolution is lazy,
        // so constructing both and then asking would have the *second* generate
        // and the first read it back - the two starts in the wrong order.
        val first = SecretKeySource("", at.toString())
        val written = first.key

        val second = SecretKeySource("", at.toString())

        assertThat(second.key).isEqualTo(written)
        assertThat(second.origin).isInstanceOf(SecretKeySource.Origin.Kept::class.java)

        val sealed = SecretCipher(written).encrypt("a-provider-key")
        assertThat(SecretCipher(second.key).decrypt(sealed)).isEqualTo("a-provider-key")
    }

    /** A key an operator put there by hand is read, not replaced. */
    @Test
    fun `a key file that is already there is read`() {
        val at = directory.resolve("secret.key")
        Files.writeString(at, "$SUPPLIED\n")

        val source = SecretKeySource("", at.toString())

        assertThat(source.key).isEqualTo(SUPPLIED)
        assertThat(source.origin).isInstanceOf(SecretKeySource.Origin.Kept::class.java)
    }

    /** The directory the key goes in does not have to exist yet. */
    @Test
    fun `the directory holding the key is created`() {
        val at = directory.resolve("data").resolve("nested").resolve("secret.key")

        val source = SecretKeySource("", at.toString())

        assertThat(source.key).isNotBlank()
        assertThat(Files.exists(at)).isTrue()
    }

    /**
     * No path configured means no key, deliberately.
     *
     * A deployment that supplies its own key and wants no copy of one on disk
     * turns generation off by emptying the path, and what it gets is the
     * behaviour that existed before any of this: no key, and a doctor that says
     * so.
     */
    @Test
    fun `no key and no key file leaves the installation without one`() {
        val source = SecretKeySource("", "")

        assertThat(source.key).isEmpty()
        assertThat(source.origin).isInstanceOf(SecretKeySource.Origin.None::class.java)
        assertThat(SecretCipher(source.key).keyStatus()).isEqualTo(SecretCipher.KeyStatus.Missing)
    }

    /** An empty file is not a key, and is treated as though it were not there. */
    @Test
    fun `an empty key file is replaced rather than read`() {
        val at = directory.resolve("secret.key")
        Files.writeString(at, "   \n")

        val source = SecretKeySource("", at.toString())

        assertThat(source.key).isNotBlank()
        assertThat(source.origin).isInstanceOf(SecretKeySource.Origin.Generated::class.java)
    }

    /**
     * A rotation writes the key being replaced down first.
     *
     * The order is the point. Re-encrypting the database is not one write, so a
     * server that stops part-way has rows on both keys; the previous-key file is
     * the only thing that makes the rest of them findable on the next start.
     */
    @Test
    fun `beginning a rotation keeps the key it replaces`() {
        val at = directory.resolve("secret.key")
        val source = SecretKeySource("", at.toString())
        val was = source.key
        val next = SecretKeySource.freshKey()

        source.beginRotation(next)

        assertThat(source.key).isEqualTo(next)
        assertThat(Files.readString(at).trim()).isEqualTo(next)
        assertThat(source.previousKey).isEqualTo(was)
        assertThat(Files.readString(directory.resolve("secret.key.previous")).trim()).isEqualTo(was)
    }

    /**
     * A restart part-way through a rotation still knows both keys.
     *
     * This is the state a crash leaves, and the sweep that finishes the job
     * needs exactly these two facts: which key is current, and which one the
     * rows nobody has reached yet are still on.
     */
    @Test
    fun `an interrupted rotation is visible to the next start`() {
        val at = directory.resolve("secret.key")
        val first = SecretKeySource("", at.toString())
        val was = first.key
        val next = SecretKeySource.freshKey()
        first.beginRotation(next)

        val restarted = SecretKeySource("", at.toString())

        assertThat(restarted.key).isEqualTo(next)
        assertThat(restarted.previousKey).isEqualTo(was)
    }

    /**
     * And once nothing is on the old key it stops being kept.
     *
     * A superseded key left beside the current one opens every backup taken
     * before the rotation, so an installation that rotated because a key leaked
     * would still be holding the leaked one.
     */
    @Test
    fun `finishing a rotation removes the key it replaced`() {
        val at = directory.resolve("secret.key")
        val source = SecretKeySource("", at.toString())
        source.beginRotation(SecretKeySource.freshKey())

        source.rotationFinished()

        assertThat(Files.exists(directory.resolve("secret.key.previous"))).isFalse()
        assertThat(source.previousKey).isNull()
    }

    /**
     * A supplied key cannot be rotated from in here, and says why.
     *
     * The server does not own that value — it is in somebody's environment or
     * their orchestrator's — so writing a new one to disk would leave the
     * database on a key the next start does not use. The refusal names the way
     * that deployment does rotate instead.
     */
    @Test
    fun `a supplied key refuses to be rotated, and names the alternative`() {
        val source = SecretKeySource(SUPPLIED, directory.resolve("secret.key").toString())

        assertThatThrownBy { source.beginRotation(SecretKeySource.freshKey()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("previous-secret-key")
    }

    /** The environment is the other half of that: a previous key can be handed in. */
    @Test
    fun `a previous key supplied in the environment is offered to the sweep`() {
        val source = SecretKeySource(SUPPLIED, "", OTHER)

        assertThat(source.previousKey).isEqualTo(OTHER)
    }

    private companion object {
        const val SUPPLIED = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        const val OTHER = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA="
    }
}
