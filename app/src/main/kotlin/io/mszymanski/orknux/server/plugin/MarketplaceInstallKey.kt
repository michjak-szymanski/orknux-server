package io.mszymanski.orknux.server.plugin

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.time.LocalDate
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * What this installation says to the marketplace to be answered at all.
 *
 * The marketplace keys both its doors — the catalog query and the plugin files
 * — and refuses anything without a header with `401`. What travels is not the
 * secret but a HMAC of the day over it, so a value taken from a proxy log or a
 * packet dump is good until midnight UTC and not a day longer.
 *
 * **It is a deterrent, not access control.** orknux-server is self-hosted, so
 * whoever runs one holds the key it sends; the marketplace is keeping crawlers
 * off bytes it pays to serve, and vouches for nothing about a plugin. What a
 * plugin asks to be allowed is still read from its code, in the sandbox, at the
 * moment somebody accepts it — no part of that moves here.
 *
 * **Computed per request, never cached.** The message is a date, so a value
 * held for the length of an install is a value that expires in the middle of
 * one: a plugin whose first file was fetched at 23:59:59 would fail on its
 * last. Two HMACs cost nothing beside a network call.
 */
@Component
class MarketplaceInstallKey(
    @Value("\${orknux.marketplace.install-key:}") private val secret: String,
    @Value("\${orknux.marketplace.url:https://orknux.ai/graphql}") private val endpoint: String,
) {

    /** Whether this installation has a key at all, for a refusal that says so. */
    val configured: Boolean get() = secret.isNotBlank()

    /**
     * Today's value, as the header wants it.
     *
     * Null where nothing is configured, which is a question for the caller
     * rather than an exception here: the catalog and the files both send it,
     * and both would rather say what is missing than throw from a getter.
     */
    fun today(): String? = secret.takeIf { it.isNotBlank() }?.let(::signed)

    /**
     * Whether this address is the marketplace's, and so may be handed the key.
     *
     * The question exists because one fetch path serves two kinds of address:
     * a plugin installed from the catalog, and a plugin at a URL somebody
     * typed. Sending the day's value to the second would hand it to whatever
     * host was named — the leak the contract's own warning describes, made by
     * us rather than found. So the header goes to the host the catalog is on
     * and nowhere else.
     *
     * Host and scheme, not the path: the catalog answers `/graphql` and the
     * files are under `/market`, and they are the same site.
     */
    fun own(address: URI): Boolean {
        val marketplace = runCatching { URI.create(endpoint) }.getOrNull() ?: return false
        val host = marketplace.host ?: return false
        return address.host?.equals(host, ignoreCase = true) == true &&
            address.scheme?.equals(marketplace.scheme, ignoreCase = true) == true
    }

    /**
     * Why a 401 arrived from somewhere we sent no key.
     *
     * Worth its own sentence because the cause is almost always a setting: the
     * catalog configured on one host, handing out file URLs on another. The
     * key is fitted by host, so the files get nothing and the refusal used to
     * read as a bare 401 with no hint at which of two hosts was wrong.
     */
    fun elsewhere(address: URI): String {
        val host = runCatching { URI.create(endpoint) }.getOrNull()?.host
        return if (host == null) {
            "it wants an install key, and this installation has no marketplace configured to send one to"
        } else {
            "it wants an install key. This installation only sends one to $host, which is where " +
                "orknux.marketplace.url points — set ORKNUX_MARKETPLACE_URL to the marketplace that " +
                "serves ${address.host}"
        }
    }

    /**
     * The day's HMAC of the secret, lowercase hex.
     *
     * UTC rather than this machine's zone, because the two ends have to agree
     * on what day it is and only one of them has a timezone. ISO-8601, which
     * is what `LocalDate.toString()` is — no time, no locale.
     */
    private fun signed(key: String, day: LocalDate = LocalDate.now(ZoneOffset.UTC)): String =
        Mac.getInstance(ALGORITHM).run {
            init(SecretKeySpec(key.toByteArray(), ALGORITHM))
            doFinal(day.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        }

    companion object {
        const val HEADER = "X-Orknux-Install-Key"

        private const val ALGORITHM = "HmacSHA256"

        /**
         * What a refusal says when there is no key, wherever it is raised.
         *
         * Said in one place because the catalog and the files both hit it and
         * both used to report a 401 as a network failure — which sends
         * somebody looking at their proxy rules for a setting they never set.
         */
        const val MISSING =
            "this installation has no marketplace install key. Set ORKNUX_INSTALL_KEY to the value the " +
                "marketplace issued, and restart"

        /** And what it says when the marketplace did not accept the one we sent. */
        const val REFUSED =
            "the marketplace did not accept this installation's install key. Check ORKNUX_INSTALL_KEY, and " +
                "that this machine's clock is right — the key is the day's, in UTC"
    }
}
