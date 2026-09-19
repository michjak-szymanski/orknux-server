package io.mszymanski.orknux.workflow.script

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * What a plugin can compute, run through the sandbox rather than around it.
 *
 * Called through a real plugin every time, because the interesting half is the
 * boundary: five doors, a `(shape, value)` pair on each argument, and an
 * answer split on a colon. A test of [PluginCrypto] alone would prove the JCA
 * works, which nobody doubted.
 *
 * The values are the published ones where a protocol publishes them, so a
 * change of algorithm name or encoding shows up as a difference against the
 * world rather than against yesterday's output.
 */
class PluginCryptoTest {

    private val runner = PluginRunner(PluginProperties())

    /** A plugin whose every function hands one crypto call back as JSON. */
    private fun plugin(body: String): String = """
        export default class Crypto extends OrknuxPlugin {
          id() { return 'crypto'; }
          apiVersion() { return 1; }
          functions() {
            return [new OrknuxFunction({
              name: 'go',
              params: [],
              returnType: 'map',
              run: () => { $body },
            })];
          }
        }
    """.trimIndent()

    /**
     * The JSON one call answered with, and a failure said out loud.
     *
     * A refusal from inside the sandbox arrives as a `Failed` rather than as
     * an answer, and a test that read `null` off it would report "expected
     * this, got nothing" about a plugin that said exactly what was wrong.
     */
    private fun run(body: String): String =
        when (val said = runner.call(plugin(body), "go", emptyList(), settings = "{}")) {
            is ScriptResult.Returned -> requireNotNull(said.json) { "the plugin returned nothing" }
            is ScriptResult.Failed -> error("the plugin failed: ${said.reason}")
        }

    /**
     * The published hex as the base64 that would carry it.
     *
     * Written this way round because the published value is hex everywhere it
     * is published - the RFCs, every other implementation - and a base64
     * string typed out by hand is a value only this file believes. One of
     * these was wrong the first time for exactly that reason.
     */
    private fun asBase64(hex: String): String =
        Base64.getEncoder().encodeToString(
            ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() },
        )

    @Test
    fun `a digest is the published one, so a change of encoding shows as a difference`() {
        // The SHA-256 of "abc", which every implementation agrees on.
        val said = run("return orknux.crypto.hash('sha256', 'abc');")

        assertThat(said)
            .contains(asBase64("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
    }

    @Test
    fun `an HMAC is the published one too`() {
        // RFC 4231's first case: a key of twenty 0x0b bytes over "Hi There".
        val key = Base64.getEncoder().encodeToString(ByteArray(20) { 0x0b })
        val said = run("return orknux.crypto.hmac('sha256', { base64: '$key' }, 'Hi There');")

        assertThat(said)
            .contains(asBase64("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"))
    }

    @Test
    fun `text becomes base64 and comes back, which is what makes the rest reachable`() {
        val said = run(
            """
            const encoded = orknux.crypto.encodeBase64('hello');
            const back = orknux.crypto.decodeBase64(encoded.base64);
            return { encoded: encoded.base64, back: back.text };
            """.trimIndent(),
        )

        assertThat(said).contains("\"encoded\":\"aGVsbG8=\"").contains("\"back\":\"hello\"")
    }

    /** Bytes are not always text, and saying so beats returning replacement marks. */
    @Test
    fun `decoding bytes that spell nothing is refused in a sentence`() {
        val said = run("return orknux.crypto.decodeBase64('/w==');")

        assertThat(said).contains("not UTF-8 text")
    }

    @Test
    fun `random is the length asked for, and is not the same twice`() {
        val said = run(
            """
            const a = orknux.crypto.random(16);
            const b = orknux.crypto.random(16);
            return { a: a.base64, b: b.base64, same: a.base64 === b.base64 };
            """.trimIndent(),
        )

        assertThat(said).contains("\"same\":false")
        // 16 bytes is 24 base64 characters with padding.
        assertThat(Regex("\"a\":\"([^\"]+)\"").find(said)!!.groupValues[1]).hasSize(24)
    }

    @Test
    fun `a comparison answers whether they match, without saying where`() {
        val said = run(
            """
            const one = orknux.crypto.hash('sha256', 'abc');
            const two = orknux.crypto.hash('sha256', 'abc');
            const other = orknux.crypto.hash('sha256', 'abd');
            return {
              same: orknux.crypto.timingSafeEqual({ base64: one.base64 }, { base64: two.base64 }).equal,
              different: orknux.crypto.timingSafeEqual({ base64: one.base64 }, { base64: other.base64 }).equal,
            };
            """.trimIndent(),
        )

        assertThat(said).contains("\"same\":true").contains("\"different\":false")
    }

    /**
     * The whole reason this exists: a Postgres handshake, to the point where
     * the client proof would be sent. Five calls, no network.
     */
    @Test
    fun `a SCRAM-SHA-256 chain runs end to end`() {
        val said = run(
            """
            const salt = orknux.crypto.encodeBase64('salt');
            const salted = orknux.crypto.pbkdf2('sha256', 'pencil', { base64: salt.base64 }, 4096, 32);
            const clientKey = orknux.crypto.hmac('sha256', { base64: salted.base64 }, 'Client Key');
            const storedKey = orknux.crypto.hash('sha256', { base64: clientKey.base64 });
            const signature = orknux.crypto.hmac('sha256', { base64: storedKey.base64 }, 'n=,r=abc');
            return { salted: salted.base64, signature: signature.base64 };
            """.trimIndent(),
        )

        // Every step answered rather than refusing, and the derivation is the
        // 32 bytes it was asked for.
        assertThat(said).doesNotContain("error")
        assertThat(Regex("\"salted\":\"([^\"]+)\"").find(said)!!.groupValues[1]).hasSize(44)
        assertThat(Regex("\"signature\":\"([^\"]+)\"").find(said)!!.groupValues[1]).hasSize(44)
    }

    /**
     * The bound that is not about size.
     *
     * PBKDF2 runs on this side of the sandbox, where the script guard's
     * wall-clock bound does not reach it, so the number is refused rather than
     * quietly reduced: a plugin believing it did ten million rounds and
     * getting a million has a security bug nobody can see.
     */
    @Test
    fun `too many iterations is refused, and says the number`() {
        val said = run(
            "return orknux.crypto.pbkdf2('sha256', 'pw', 'salt', 50000000, 32);",
        )

        assertThat(said).contains("50000000").contains("at most ${PluginCrypto.MOST_ITERATIONS}")
    }

    @Test
    fun `an algorithm this server does not have is refused with the ones it does`() {
        val said = run("return orknux.crypto.hash('sha3-512', 'abc');")

        assertThat(said).contains("sha3-512").contains("sha256")
    }

    /**
     * Nothing here was granted, which is the design: a plugin that declared no
     * permissions and no capabilities can still hash. A plugin cannot ask for
     * crypto and cannot be refused it, because there is nothing to ask for.
     */
    @Test
    fun `it works for a plugin that declared nothing at all`() {
        val inspected = runner.inspect(plugin("return orknux.crypto.hash('sha256', 'abc');"))

        assertThat(inspected).isInstanceOf(PluginInspection.Read::class.java)
        val read = inspected as PluginInspection.Read
        assertThat(read.permissions).isEmpty()
        assertThat(read.capabilities).isEmpty()
    }
}
