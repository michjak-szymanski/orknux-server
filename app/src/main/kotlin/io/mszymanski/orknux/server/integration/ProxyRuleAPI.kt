package io.mszymanski.orknux.server.integration

import io.mszymanski.orknux.connector.proxy.ProxyRouteView
import io.mszymanski.orknux.connector.proxy.ProxyRuleInput
import io.mszymanski.orknux.connector.proxy.ProxyRuleService
import io.mszymanski.orknux.connector.proxy.ProxyRuleView
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

/**
 * The proxy rules, for the Networking page.
 *
 * The connection module holds them and knows nothing about who is asking; this
 * decides that and records what was done, which is the same division every other
 * admin-level integration screen uses.
 *
 * Nothing here can return a proxy password. There is no field for one on
 * [ProxyRuleView] and no query that takes an id and gives back a credential -
 * the only thing the outside is told is whether one is stored.
 */
@Controller
class ProxyRuleAPI(
    private val rules: ProxyRuleService,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun proxyRules(): List<ProxyRuleView> {
        access.requireAdmin()
        return rules.rules()
    }

    /**
     * Which rule a URL would go through, and which rules it beat to it.
     *
     * Here because the answer is only worth having from the thing that actually
     * routes requests. A page that worked it out for itself would be a second
     * implementation of the matching, and the day the two disagreed the screen
     * would be the confident one.
     */
    @QueryMapping
    fun proxyRoute(@Argument url: String): ProxyRouteView {
        access.requireAdmin()
        return rules.testRoute(url)
    }

    @MutationMapping
    fun createProxyRule(@Argument input: ProxyRuleInput): ProxyRuleView {
        access.requireAdmin()
        val created = rules.create(input)
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.INTEGRATION,
            "Proxy rule ${created.name} created for ${created.proxyHost}:${created.proxyPort}",
        )
        return created
    }

    @MutationMapping
    fun updateProxyRule(@Argument id: Long, @Argument input: ProxyRuleInput): ProxyRuleView {
        access.requireAdmin()
        val previousName = rules.rule(id)?.name
        val updated = rules.update(id, input)

        val message = if (previousName == null || previousName == updated.name) {
            "Proxy rule ${updated.name} updated"
        } else {
            "Proxy rule $previousName renamed to ${updated.name}"
        }
        auditRecorder.record(null, WorkspaceAuditCategory.INTEGRATION, message)
        return updated
    }

    /**
     * The switch on the row. Audited like any other change, because turning a
     * rule off is how an endpoint stops being reachable and somebody will want
     * to know when that happened.
     */
    @MutationMapping
    fun setProxyRuleEnabled(@Argument id: Long, @Argument enabled: Boolean): ProxyRuleView {
        access.requireAdmin()
        val updated = rules.setEnabled(id, enabled)
        val what = if (enabled) "turned on" else "turned off"
        auditRecorder.record(null, WorkspaceAuditCategory.INTEGRATION, "Proxy rule ${updated.name} $what")
        return updated
    }

    /** Gives back the whole order, because moving one rule changes what its neighbours do. */
    @MutationMapping
    fun moveProxyRule(@Argument id: Long, @Argument up: Boolean): List<ProxyRuleView> {
        access.requireAdmin()
        val name = rules.rule(id)?.name
        val ordered = rules.move(id, up)
        if (name != null) {
            val where = if (up) "earlier" else "later"
            auditRecorder.record(null, WorkspaceAuditCategory.INTEGRATION, "Proxy rule $name moved $where")
        }
        return ordered
    }

    @MutationMapping
    fun deleteProxyRule(@Argument id: Long): Boolean {
        access.requireAdmin()
        val name = rules.rule(id)?.name ?: return false
        if (!rules.delete(id)) return false

        auditRecorder.record(null, WorkspaceAuditCategory.INTEGRATION, "Proxy rule $name deleted")
        return true
    }
}
