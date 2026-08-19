package io.mszymanski.orknux.connector.proxy

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient

/**
 * Decides which proxy, if any, an outbound request goes through, and hands out
 * HTTP clients that ask.
 *
 * **Why this is one object and not a setting on each client.** Every outbound
 * call in this application is built from [builder], which is the only reason the
 * rules can be trusted to cover anything. A proxy rule that applies to some
 * calls and not others is worse than no rule at all: the ones it misses fail
 * against an endpoint nobody can reach, and nothing on the screen that lists the
 * rules says so. So the seam is the client itself - a client that was not built
 * here is a client the rules do not reach, and there are none.
 *
 * **Why first match wins, by a position an administrator sets.** Two other
 * orderings were considered. "Most specific wins" needs a definition of specific
 * that regular expressions do not have; comparing two patterns for which is
 * narrower is not something that can be done soundly, and a rule silently losing
 * to another because of a guess would be exactly the invisible failure this page
 * exists to prevent. "Refuse overlapping rules when they are saved" needs to
 * decide whether two regular expressions can match the same string, which is
 * expensive and, worse, would refuse the arrangement people actually want: one
 * narrow rule for the endpoint that needs a particular proxy and a broad one
 * behind it for everything else. So the order is stated rather than inferred,
 * and the screen shows it, moves it, and will tell you for any URL which rule
 * answers and which rules were beaten to it.
 *
 * **Why a rule whose pattern does not compile is dropped rather than thrown.**
 * A pattern is validated when it is saved, so a broken one should not be here at
 * all - but "should not" is not a guarantee once rows can arrive from a restore
 * or a hand-edited database. Compiling on every request and letting the failure
 * out would mean one bad row stops every outbound call this installation makes,
 * including the ones that would tell somebody about it. The rule is left out of
 * the snapshot and logged instead, which costs that one rule and nothing else.
 */
@Component
class ProxyRouter(private val source: ProxyRuleSource) {

    /**
     * The rules with their patterns already compiled. Rebuilt on [reload], which
     * every write goes through, rather than read per request: this is consulted
     * once for every outbound call in the process.
     */
    @Volatile
    private var snapshot: List<CompiledRule>? = null

    private val selector = RuleSelector()
    private val authenticator = ProxyCredentials()

    /** A client that consults the rules. Everything outbound is built from this. */
    fun builder(): HttpClient.Builder = HttpClient.newBuilder()
        .proxy(selector)
        .authenticator(authenticator)

    /** Forget the compiled rules, so the next request reads them again. */
    fun reload() {
        snapshot = null
    }

    /**
     * Every enabled rule matching this URL, in the order they are consulted.
     *
     * The first is the one that will be used and the rest are the ones it beats,
     * which is the whole answer to "why does my rule never fire". Empty means
     * the request goes out directly.
     */
    fun matching(url: String): List<ProxyChoice> =
        compiled().filter { it.rule.enabled && it.matches(url) }.map(CompiledRule::choice)

    /** The rule that will be used for this URL, or null to go direct. */
    fun resolve(url: String): ProxyChoice? = matching(url).firstOrNull()

    private fun compiled(): List<CompiledRule> = snapshot ?: build().also { snapshot = it }

    private fun build(): List<CompiledRule> = source.rules().mapNotNull { rule ->
        try {
            CompiledRule(rule, Regex(rule.pattern, RegexOption.IGNORE_CASE))
        } catch (failure: Exception) {
            log.warn(
                "Proxy rule {} is ignored because its pattern will not compile: {}",
                rule.name,
                failure.message,
            )
            null
        }
    }

    private class CompiledRule(val rule: ProxyRule, private val expression: Regex) {

        /**
         * Found anywhere in the URL rather than matched end to end. See
         * [ProxyRule.pattern] for why, and note that a pattern which backtracks
         * badly costs this thread and not the whole application, because it is
         * one request's own call.
         */
        fun matches(url: String): Boolean = expression.containsMatchIn(url)

        /** Whether this rule is the reason a particular proxy is being talked to. */
        fun answersFor(host: String?, port: Int): Boolean =
            rule.proxyHost.equals(host, ignoreCase = true) && rule.proxyPort == port && !rule.username.isNullOrBlank()

        val choice: ProxyChoice
            get() = ProxyChoice(
                ruleId = rule.id,
                ruleName = rule.name,
                host = rule.proxyHost,
                port = rule.proxyPort,
                username = rule.username?.ifBlank { null },
                password = rule.password?.ifBlank { null },
            )
    }

    /**
     * The rules as the JDK's HTTP client asks about them.
     *
     * [ProxySelector] cannot refuse a request - it returns proxies or it returns
     * none - so this never decides whether a call is allowed. That stays with
     * the address guard, which every caller asks before it gets this far.
     */
    private inner class RuleSelector : ProxySelector() {

        override fun select(uri: URI?): List<Proxy> {
            val choice = uri?.let { resolve(it.toString()) } ?: return DIRECT
            return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(choice.host, choice.port)))
        }

        /**
         * A proxy that would not take the connection. Logged and nothing else:
         * the alternative is remembering it and going direct next time, which
         * would send a request out on the path an administrator said it must
         * not take. Failing where the rule pointed is the honest answer, and it
         * fails the same way every time until somebody fixes the rule.
         */
        override fun connectFailed(uri: URI?, address: SocketAddress?, failure: IOException?) {
            log.warn("A proxy rule pointed {} at {}, which could not be reached", uri, address, failure)
        }
    }

    /**
     * Answers a proxy's `407`.
     *
     * Keyed off the URL that was being fetched rather than off the proxy's own
     * address, so two rules pointing at one proxy with different accounts each
     * get their own. Only a proxy is ever answered; a `401` from the service at
     * the other end is the caller's business and gets nothing from here.
     */
    private inner class ProxyCredentials : Authenticator() {

        override fun getPasswordAuthentication(): PasswordAuthentication? {
            if (requestorType != RequestorType.PROXY) return null

            /*
             * The rule the request was routed by, when the JDK says which request
             * it was. When it does not, the only thing left to go on is which
             * proxy is asking, so the first enabled rule pointing at it answers -
             * which is exact unless two rules share one proxy under different
             * accounts, and that is the ambiguity, not a wrong guess.
             */
            val choice = requestingURL?.let { resolve(it.toString()) }
                ?: compiled().firstOrNull { it.rule.enabled && it.answersFor(requestingHost, requestingPort) }?.choice
                ?: return null

            // Whatever it was found by, the credentials only go to the proxy the
            // rule names, never to whoever happened to send the challenge.
            if (!choice.host.equals(requestingHost, ignoreCase = true) || choice.port != requestingPort) return null

            val user = choice.username ?: return null
            return PasswordAuthentication(user, choice.password.orEmpty().toCharArray())
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(ProxyRouter::class.java)

        val DIRECT = listOf(Proxy.NO_PROXY)

        /**
         * Lets a proxy password be sent when opening an HTTPS tunnel.
         *
         * The JDK refuses Basic proxy authentication on a `CONNECT` by default,
         * because the credential would cross an unencrypted hop to the proxy.
         * That default makes this feature useless for the case it was asked
         * for - the endpoint that needs a proxy here is an HTTPS one - so it is
         * lifted, and only when the operator has not already said what they want
         * by setting the property themselves.
         *
         * It has to happen before the JDK reads it, which it does once, when its
         * HTTP client machinery is first loaded. Every client in this
         * application is built by [builder], so this class is loaded before any
         * of them exists.
         */
        init {
            if (System.getProperty(TUNNELING_SCHEMES) == null) System.setProperty(TUNNELING_SCHEMES, "")
        }

        const val TUNNELING_SCHEMES = "jdk.http.auth.tunneling.disabledSchemes"
    }
}

/**
 * A proxy a request will go through, and the rule that said so.
 *
 * [password] never leaves this module: it is here because the authenticator
 * needs it, and everything the API returns is built from the fields beside it.
 */
data class ProxyChoice(
    val ruleId: Long?,
    val ruleName: String,
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
)
