package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional

/**
 * The marketplace, and the two things done to a plugin that is not a load.
 *
 * Installing is the same act as updating and the same load as every other
 * door: the catalog says where the plugin lives, [PluginUploadAPI] fetches it
 * with its libraries and reads its declaration from the code, and what
 * somebody must allow is what that code asks for — never what a listing
 * claimed. So a catalog entry cannot widen what a plugin may do by describing
 * itself generously; it can only say where the file is.
 */
@Controller
class MarketplaceAPI(
    private val marketplace: Marketplace,
    private val plugins: PluginRepository,
    private val upload: PluginUploadAPI,
    private val sources: PluginSources,
    private val declarations: PluginDeclarations,
    private val permissions: PluginPermissions,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    /**
     * What the catalog offers, with what is installed here folded in.
     *
     * One query rather than two and a join on the screen: whether a listing is
     * installed, and whether the version on offer is one this installation
     * does not have, are the two things the catalog is read for.
     *
     * Asked afresh every time, so refreshing is asking again rather than an
     * argument. There used to be one, and it was forwarded to a marketplace
     * that has no such argument and said so.
     */
    @QueryMapping
    fun marketplacePlugins(): List<MarketplaceListingView> {
        access.requireAdmin()

        val installed = plugins.findAll().associateBy { it.key }
        return marketplace.offerings().map { offering ->
            val here = installed[offering.key]
            MarketplaceListingView(
                key = offering.key,
                name = offering.name,
                author = offering.author,
                summary = offering.summary,
                description = offering.description,
                version = offering.version,
                icon = offering.icon,
                downloads = offering.downloads,
                rating = offering.rating,
                reviews = offering.reviews,
                published = offering.published,
                installed = here != null,
                installedVersion = here?.marketplaceVersion,
                /*
                 * Installed and not at this version — including a plugin that
                 * records no version at all, which is one loaded by hand or
                 * before this was kept. Offering to update that is right: the
                 * catalog's copy is the one this installation can reason
                 * about.
                 */
                updatable = here != null && here.marketplaceVersion != offering.version,
            )
        }
    }

    /**
     * Installs a plugin from the catalog, or updates one already installed.
     *
     * The refusal is a payload rather than an error, because a pending
     * agreement is a decision nobody has made yet — the same shape the upload
     * endpoint answers with, so a screen has one thing to draw either way.
     */
    @MutationMapping
    @Transactional
    fun installMarketplacePlugin(@Argument key: String, @Argument accept: String?): MarketplaceInstallView {
        access.requireAdmin()

        val offering = marketplace.offering(key) ?: throw MarketplaceOfferingUnknownException(key)
        val had = plugins.findByKey(offering.key)

        val answered = try {
            upload.installed(offering, accept)
        } catch (needed: PluginAgreementNeededException) {
            return MarketplaceInstallView(
                plugin = null,
                needsPermissions = needed.permissions,
                needsCapabilities = needed.capabilities,
                needsLibraries = needed.libraries,
                message = needed.message,
            )
        }

        @Suppress("UNCHECKED_CAST")
        val installed = (answered.body as Map<String, Any?>)["plugin"] as PluginView

        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            if (had == null) {
                "Plugin ${offering.key} installed from the marketplace at ${offering.version}"
            } else {
                "Plugin ${offering.key} updated from the marketplace to ${offering.version}"
            },
        )
        return MarketplaceInstallView(plugin = installed)
    }

    /**
     * Switches a plugin off, or back on.
     *
     * The reversible half of unloading: everything stays and nothing is
     * offered. What enforces it is not here — the registry keeps the function
     * rows so a graph that names one still draws, and the callers refuse a
     * call through a plugin that is off, which is where the refusal can say
     * why in a sentence.
     */
    @MutationMapping
    @Transactional
    fun setPluginEnabled(@Argument id: Long, @Argument enabled: Boolean): PluginView {
        access.requireAdmin()

        val plugin = plugins.findByIdOrNull(id) ?: throw PluginNotFoundException(id)
        plugin.enabled = enabled

        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            if (enabled) "Plugin ${plugin.key} switched on" else "Plugin ${plugin.key} switched off",
        )
        return plugin.view(
            declarations.read(plugin.declaredFunctions),
            declarations.readParameters(plugin.declaredParameters),
            permissions.viewOf(permissions.grantedTo(plugin)),
            sources.librariesOf(plugin).map { it.path },
            declarations.readSkills(plugin.declaredSkills),
            declarations.readObjects(plugin.declaredObjects),
        )
    }
}

data class MarketplaceListingView(
    val key: String,
    val name: String,
    val author: String,
    val summary: String,
    val description: String,
    val version: String,
    val icon: String?,
    val downloads: Int,
    val rating: Double?,
    val reviews: Int,
    val published: String,
    val installed: Boolean,
    val installedVersion: String?,
    val updatable: Boolean,
)

data class MarketplaceInstallView(
    val plugin: PluginView?,
    val needsPermissions: List<PluginPermissionView> = emptyList(),
    val needsCapabilities: List<PluginCapabilityView> = emptyList(),
    val needsLibraries: List<String> = emptyList(),
    val message: String? = null,
)

class MarketplaceOfferingUnknownException(val key: String) : RuntimeException(
    "The marketplace offers no plugin called \"$key\".",
), io.mszymanski.orknux.server.graphql.Refusal {

    override val arguments get() = mapOf("key" to key)
}
