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
    /** The listings' faces, fetched here rather than by the browser. */
    private val icons: MarketplaceIcons,
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
        val offered = marketplace.offerings()

        /*
         * The faces, brought across before the rows are built.
         *
         * A listing's icon is a URL on the marketplace, and a screen putting
         * that URL in an `<img>` is the one call the catalog makes that this
         * server does not - so on an installation whose egress is a proxy the
         * rules were right, the catalog loaded, and every icon was a broken
         * square. Fetched together rather than one per row, so a dozen small
         * files cost one wait instead of a dozen.
         */
        icons.warm(offered.flatMap { listOf(it.icon, it.iconDark) })

        return offered.map { offering ->
            val here = installed[offering.key]
            MarketplaceListingView(
                key = offering.key,
                name = offering.name,
                author = offering.author,
                summary = offering.summary,
                description = offering.description,
                version = offering.version,
                icon = icons.drawn(offering.icon),
                iconDark = icons.drawn(offering.iconDark),
                downloads = offering.downloads,
                rating = offering.rating,
                reviews = offering.reviews,
                published = offering.published,
                installed = here != null,
                /*
                 * What is actually installed, however it got here.
                 *
                 * The catalog's own record first - that is the version this
                 * installation took from the marketplace - and the plugin's
                 * own claim where there is none, which is every plugin loaded
                 * from a file. Reading only the first said "installed: nothing"
                 * about a plugin sitting right there with its version printed
                 * on its own row.
                 */
                installedVersion = here?.let { it.marketplaceVersion ?: it.version },
                /*
                 * Installed, and not at the version on offer.
                 *
                 * Held against what is installed rather than against what the
                 * catalog last handed over, because those differ for a plugin
                 * loaded from a file: the marketplace version is null there,
                 * so a file-installed 0.13.1 was offered an "update" to 0.13.1
                 * - the same bytes, under a mark that exists to say something
                 * has moved on.
                 *
                 * A plugin that records no version anywhere is still
                 * updatable, which is the case the old note was about: nothing
                 * is known about it, and the catalog's copy is the one this
                 * installation can reason about.
                 */
                updatable = here != null && (here.marketplaceVersion ?: here.version) != offering.version,
                tags = offering.tags,
                versions = offering.versions.map {
                    MarketplaceReleaseView(
                        version = it.version,
                        published = it.published,
                        replaced = it.replaced,
                        files = it.files,
                        available = it.available,
                        notes = it.notes,
                    )
                },
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
    /**
     * The drawing itself, or an emoji - see [MarketplaceIcons] for why this is
     * not the URL the marketplace answered with.
     */
    val icon: String?,
    /** The same glyph for a dark ground; null where there is only the one. */
    val iconDark: String?,
    val downloads: Int,
    val rating: Double?,
    val reviews: Int,
    val published: String,
    val installed: Boolean,
    val installedVersion: String?,
    val updatable: Boolean,
    /** What the plugin is for, in its author's words. Empty, never null. */
    val tags: List<String> = emptyList(),
    val versions: List<MarketplaceReleaseView> = emptyList(),
)

/**
 * One release of a plugin, as the catalog remembers it.
 *
 * Without the digest. It is what an install checks the downloaded bytes
 * against and has no reader on a screen, and a hash printed next to a version
 * number is noise that looks like information.
 */
data class MarketplaceReleaseView(
    val version: String,
    val published: String,
    val replaced: String,
    val files: Int,
    val available: Boolean,
    /**
     * What changed in it, as the author wrote it when publishing. Markdown,
     * rendered where it is shown; empty for a release published without any.
     */
    val notes: String = "",
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
