package io.mszymanski.orknux.connector.proxy

import io.mszymanski.orknux.connector.connection.ConnectionProbe
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * The proxy rules, as an administrator edits them.
 *
 * Who may call this is settled before the request arrives, the same as every
 * other admin-level service in this module: orknux-server checks the caller and
 * records the audit entry.
 *
 * Two things are checked here rather than left to the moment a request is made.
 * A pattern is compiled, because a regular expression that will not compile is a
 * rule that can never fire and the person who typed it is the only one who can
 * fix it. And the proxy's own address is put past the same guard every outbound
 * address goes past, because a proxy is where the connection actually lands - a
 * rule pointing at `169.254.169.254` would turn every URL it matches into a
 * request to this host's instance metadata, which is the exact hole the guard
 * closes. Checking it at the point somebody types it is also where the answer is
 * of any use; asking again on every request would add a name lookup to every
 * call this installation makes, and could not refuse anything anyway - see
 * [ProxyRouter] for why the selector has no way to say no.
 */
@Service
class ProxyRuleService(
    private val rules: ProxyRuleRepository,
    private val router: ProxyRouter,
    private val probe: ConnectionProbe,
) {

    fun rules(): List<ProxyRuleView> = rules.findAllByOrderByPositionAscIdAsc().map(::ProxyRuleView)

    fun rule(id: Long): ProxyRuleView? = rules.findByIdOrNull(id)?.let(::ProxyRuleView)

    @Transactional
    fun create(input: ProxyRuleInput): ProxyRuleView {
        val name = input.name.trim()
        if (name.isEmpty()) throw ProxyRuleNameInvalidException()
        if (rules.findByName(name) != null) throw ProxyRuleNameTakenException(name)

        val pattern = validPattern(input.pattern)
        val host = validProxyHost(input.proxyHost)
        val port = validPort(input.proxyPort)

        // At the end, because a new rule that jumped the queue would change what
        // every existing one does the moment it was added.
        val last = rules.findAllByOrderByPositionAscIdAsc().lastOrNull()?.position ?: -1

        val rule = rules.save(
            ProxyRule(
                name = name,
                pattern = pattern,
                proxyHost = host,
                proxyPort = port,
                username = input.username?.trim()?.ifEmpty { null },
                password = input.password?.trim()?.ifEmpty { null },
                enabled = input.enabled ?: true,
                position = last + 1,
            ),
        )
        forgetCachedRules()
        return ProxyRuleView(rule)
    }

    /**
     * A null password leaves the stored one alone and an empty one clears it,
     * which is how every other credential on this platform is edited: the screen
     * never has the password to send back, so "unchanged" has to be sayable.
     */
    @Transactional
    fun update(id: Long, input: ProxyRuleInput): ProxyRuleView {
        val name = input.name.trim()
        if (name.isEmpty()) throw ProxyRuleNameInvalidException()

        val rule = rules.findByIdOrNull(id) ?: throw ProxyRuleNotFoundException(id)
        if (name != rule.name && rules.findByName(name) != null) throw ProxyRuleNameTakenException(name)

        rule.name = name
        rule.pattern = validPattern(input.pattern)
        rule.proxyHost = validProxyHost(input.proxyHost)
        rule.proxyPort = validPort(input.proxyPort)
        rule.username = input.username?.trim()?.ifEmpty { null }
        input.password?.let { rule.password = it.trim().ifEmpty { null } }
        input.enabled?.let { rule.enabled = it }
        rule.lastModifiedAt = OffsetDateTime.now()

        forgetCachedRules()
        return ProxyRuleView(rule)
    }

    /** The on/off switch on the row, without opening the rule to edit it. */
    @Transactional
    fun setEnabled(id: Long, enabled: Boolean): ProxyRuleView {
        val rule = rules.findByIdOrNull(id) ?: throw ProxyRuleNotFoundException(id)
        rule.enabled = enabled
        rule.lastModifiedAt = OffsetDateTime.now()
        forgetCachedRules()
        return ProxyRuleView(rule)
    }

    @Transactional
    fun delete(id: Long): Boolean {
        val rule = rules.findByIdOrNull(id) ?: return false
        rules.delete(rule)
        renumber(rules.findAllByOrderByPositionAscIdAsc().filter { it.id != id })
        forgetCachedRules()
        return true
    }

    /**
     * Moves a rule one place, and gives back the whole order.
     *
     * The whole order rather than the rule, because moving one changes what the
     * ones around it do, and a screen that only heard about the rule it moved
     * would show an order that is no longer true.
     */
    @Transactional
    fun move(id: Long, up: Boolean): List<ProxyRuleView> {
        val ordered = rules.findAllByOrderByPositionAscIdAsc().toMutableList()
        val at = ordered.indexOfFirst { it.id == id }
        if (at < 0) throw ProxyRuleNotFoundException(id)

        val to = if (up) at - 1 else at + 1
        if (to in ordered.indices) {
            ordered.add(to, ordered.removeAt(at))
            renumber(ordered)
            forgetCachedRules()
        }
        return ordered.map(::ProxyRuleView)
    }

    /**
     * Which rule a URL would go through, and which rules it beat.
     *
     * The question somebody will have the moment there is more than one rule,
     * and the only honest way to answer it is to ask the thing that actually
     * routes requests rather than to re-implement the matching next to the
     * screen. What comes back also carries what the address guard thinks of both
     * ends, because "which rule fires" is not much use about a URL that would be
     * refused before any rule was consulted.
     */
    fun testRoute(url: String): ProxyRouteView {
        val address = url.trim()
        val matched = router.matching(address)
        val chosen = matched.firstOrNull()

        return ProxyRouteView(
            url = address,
            matched = chosen?.ruleId?.let { rule(it) },
            beaten = matched.drop(1).mapNotNull { it.ruleId?.let(::rule) },
            refusedBecause = probe.vet(address),
            proxyProblem = chosen?.let { probe.vetHost(it.host) },
        )
    }

    /**
     * Tells the router its rules have changed, once they actually have.
     *
     * After the commit rather than during it. Clearing the cache mid-transaction
     * looks right and is a race: the next outbound call could rebuild the cache
     * from a database that has not seen the change yet, and would then hold the
     * old rules until somebody edited something else. Registering it here means
     * the cache is dropped when there is something new to read.
     */
    private fun forgetCachedRules() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            router.reload()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCompletion(status: Int) = router.reload()
            },
        )
    }

    /** Positions are the list's own indices, so they never drift or collide. */
    private fun renumber(ordered: List<ProxyRule>) {
        ordered.forEachIndexed { index, rule -> rule.position = index }
    }

    private fun validPattern(pattern: String): String {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) throw ProxyRulePatternInvalidException("a pattern is required")
        try {
            Regex(trimmed)
        } catch (failure: Exception) {
            throw ProxyRulePatternInvalidException(failure.message ?: "it will not compile")
        }
        return trimmed
    }

    private fun validProxyHost(host: String): String {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) throw ProxyRuleProxyInvalidException("A proxy host is required")
        if (trimmed.contains("://")) {
            throw ProxyRuleProxyInvalidException(
                "Give the proxy as a host name, without a scheme: a proxy is spoken to over " +
                    "plain HTTP whatever the request going through it is",
            )
        }
        probe.vetHost(trimmed)?.let { throw ProxyRuleProxyInvalidException("That proxy cannot be used: $it") }
        return trimmed
    }

    private fun validPort(port: Int): Int {
        if (port !in 1..65535) throw ProxyRuleProxyInvalidException("A proxy port between 1 and 65535 is required")
        return port
    }
}

data class ProxyRuleInput(
    val name: String,
    val pattern: String,
    val proxyHost: String,
    val proxyPort: Int,
    val username: String? = null,
    /** Null leaves the stored password alone; empty clears it. Never read back. */
    val password: String? = null,
    val enabled: Boolean? = null,
)

/**
 * A rule as a screen may see it.
 *
 * There is no password on it, and no way to ask for one. [passwordSet] is what
 * the form needs in order to say whether it is about to replace something, and
 * it is everything the outside is told.
 */
data class ProxyRuleView(
    val id: Long,
    val name: String,
    val pattern: String,
    val proxyHost: String,
    val proxyPort: Int,
    val username: String?,
    val passwordSet: Boolean,
    val enabled: Boolean,
    val position: Int,
    val createdAt: String,
    val lastModifiedAt: String,
) {
    constructor(rule: ProxyRule) : this(
        id = requireNotNull(rule.id),
        name = rule.name,
        pattern = rule.pattern,
        proxyHost = rule.proxyHost,
        proxyPort = rule.proxyPort,
        username = rule.username,
        passwordSet = !rule.password.isNullOrBlank(),
        enabled = rule.enabled,
        position = rule.position,
        createdAt = rule.createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        lastModifiedAt = rule.lastModifiedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    )
}

/** What would happen to one URL, for the box on the page that asks. */
data class ProxyRouteView(
    val url: String,
    /** The rule that answers, or null for a request that goes out directly. */
    val matched: ProxyRuleView?,
    /** Rules that also match but never get the chance, in the order they are consulted. */
    val beaten: List<ProxyRuleView>,
    /** Why the address guard would refuse this URL before any rule mattered. */
    val refusedBecause: String?,
    /** What the same guard thinks of the matched rule's proxy. */
    val proxyProblem: String?,
)
