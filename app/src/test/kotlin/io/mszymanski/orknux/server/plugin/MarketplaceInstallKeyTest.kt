package io.mszymanski.orknux.server.plugin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * What this installation says to the marketplace, and where it says it.
 *
 * Two claims, and the second is the one worth a test. The first is arithmetic —
 * the day's HMAC, lowercase hex, UTC — and is checked here against a value
 * computed the way the contract writes it, so a change to either side shows up
 * as a difference rather than as a 401 nobody can explain.
 *
 * The second is that the key goes to the marketplace and nowhere else. This
 * door fetches plugins from URLs somebody typed as well as from the catalog,
 * and posting the day's value to whatever host was in the box would be exactly
 * the leak the contract warns about — made by us rather than found.
 */
class MarketplaceInstallKeyTest {

    private val secret = "a-shared-secret"
    private val key = MarketplaceInstallKey(secret, "https://orknux.ai/graphql")

    /** The contract's own recipe, written out again rather than called. */
    private fun expected(day: String): String =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            doFinal(day.toByteArray()).joinToString("") { "%02x".format(it) }
        }

    @Test
    fun `the value is the day's HMAC of the secret, in lowercase hex`() {
        val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()

        val held = key.today()

        assertThat(held).isEqualTo(expected(today))
        assertThat(held).describedAs("lowercase hex, no separators").matches("[0-9a-f]{64}")
    }

    /**
     * Not the secret, which is the whole point of sending a HMAC: a value
     * caught in a proxy log is good until midnight UTC and not a day longer.
     */
    @Test
    fun `the secret itself never travels`() {
        assertThat(key.today()).doesNotContain(secret)
    }

    @Test
    fun `an installation with no key has none to send, and says so rather than throwing`() {
        val none = MarketplaceInstallKey("", "https://orknux.ai/graphql")

        assertThat(none.configured).isFalse()
        assertThat(none.today()).isNull()
        assertThat(MarketplaceInstallKey.MISSING).contains("ORKNUX_INSTALL_KEY")
    }

    @Test
    fun `the key is for the marketplace and nowhere else`() {
        // Both doors of the marketplace are the marketplace: the catalog
        // answers /graphql and the files are under /market.
        assertThat(key.own(URI.create("https://orknux.ai/graphql"))).isTrue()
        assertThat(key.own(URI.create("https://orknux.ai/market/plugin/github/files/github.js"))).isTrue()
        assertThat(key.own(URI.create("https://ORKNUX.AI/market/plugin/github/files/lib/api.js")))
            .describedAs("a host is a host however it was cased")
            .isTrue()

        // And nothing else is, including the near misses somebody could type
        // into the URL box on purpose.
        assertThat(key.own(URI.create("https://example.test/plugin.js"))).isFalse()
        assertThat(key.own(URI.create("https://orknux.ai.example.test/plugin.js"))).isFalse()
        assertThat(key.own(URI.create("https://evil.test/?x=orknux.ai"))).isFalse()
        assertThat(key.own(URI.create("http://orknux.ai/market/plugin/github/files/github.js")))
            .describedAs("plain http is not the site the catalog is on")
            .isFalse()
    }

    /**
     * The 401 that is a setting rather than a key.
     *
     * A catalog configured on one host handing out file URLs on another is
     * the way this actually goes wrong - it is how orknux.io and orknux.ai
     * first met - and a bare "it answered 401" points at neither of them.
     */
    @Test
    fun `a 401 from somewhere we sent no key names both hosts`() {
        val said = key.elsewhere(URI.create("https://orknux.ai/market/plugin/slack/files/slack.js"))

        assertThat(said).contains("orknux.ai").contains("ORKNUX_MARKETPLACE_URL")
    }

    @Test
    fun `and says there is no marketplace at all where there is none`() {
        val nowhere = MarketplaceInstallKey(secret, "")

        assertThat(nowhere.elsewhere(URI.create("https://orknux.ai/x.js")))
            .contains("no marketplace configured")
    }

    /**
     * An installation with no marketplace configured has no host to compare
     * against, so nothing is its own — which is the safe direction: a key that
     * cannot be placed is a key that is not sent.
     */
    @Test
    fun `an installation with no marketplace sends the key to nobody`() {
        val nowhere = MarketplaceInstallKey(secret, "")

        assertThat(nowhere.own(URI.create("https://orknux.ai/graphql"))).isFalse()
    }
}
