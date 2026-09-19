package io.mszymanski.orknux.server.plugin

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Installing from the marketplace, and switching a plugin off.
 *
 * The catalog is read through: what this server stores is not what a listing
 * claimed but what the *code* said when it was loaded — so the test's stub
 * offers a plugin whose declaration differs from the listing's prose, and the
 * assertions are on the code's version of events.
 *
 * Three claims carry the feature and each is a test: an install is refused
 * until what the plugin ships is accepted; an accepted install records where
 * it came from, so an update can be offered; and a plugin switched off keeps
 * everything and offers nothing.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class MarketplaceInstallTest(
    @Autowired val catalog: MarketplaceAPI,
    @Autowired val plugins: PluginRepository,
    @Autowired val libraries: PluginLibraryRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val tools: io.mszymanski.orknux.server.agent.PluginToolCaller,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    @BeforeEach
    fun reset() {
        plugins.deleteAll()
        functions.deleteAll()
        audit.deleteAll()
        offeredVersion = "1.0.0"
    }

    @Test
    fun `the catalog says what is on offer, and what is installed here`() {
        val before = catalog.marketplacePlugins().single()

        assertThat(before.key).isEqualTo("greeter")
        assertThat(before.author).isEqualTo("Orknux")
        assertThat(before.icon).endsWith("/icons/greeter.svg")
        assertThat(before.installed).isFalse()
        assertThat(before.updatable).isFalse()
    }

    @Test
    fun `an install is refused until what the plugin ships is accepted, and nothing is stored`() {
        val refused = catalog.installMarketplacePlugin("greeter", accept = null)

        assertThat(refused.plugin).isNull()
        assertThat(refused.needsLibraries).containsExactly("lib/words.js")
        assertThat(refused.message).contains("library file")
        assertThat(plugins.findAll()).isEmpty()
    }

    @Test
    fun `an accepted install loads the plugin with its files, and records where it came from`() {
        val installed = catalog.installMarketplacePlugin("greeter", accept = "lib/words.js")

        val plugin = requireNotNull(installed.plugin)
        assertThat(plugin.key).isEqualTo("greeter")
        assertThat(plugin.libraries).containsExactly("lib/words.js")
        assertThat(plugin.marketplaceKey).isEqualTo("greeter")
        assertThat(plugin.marketplaceVersion).isEqualTo("1.0.0")
        assertThat(plugin.enabled).isTrue()
        /*
         * What the listing said about it, kept.
         *
         * This plugin ships no `plugin.json`, and for a long time that meant a
         * marketplace install stored no author and no summary at all - the
         * catalog knew both and nothing asked it, so the Installed table drew
         * an em dash under Author for every plugin anybody installed.
         */
        /*
         * The catalog's words, and the catalog outranks the file.
         *
         * This plugin ships a `plugin.json` that disagrees on every point, and
         * the listing wins each of them. That way round because this is an
         * install *from the catalog*: the catalog has an account that
         * published this version, and a manifest has a string somebody typed.
         * The first-party plugins all ship `"author": "Orknux"` while their
         * listings name the person who published them - so the file's word put
         * a different name in the row than the one on the page the plugin was
         * installed from.
         *
         * For a long time neither was kept at all: the author column was empty
         * for every marketplace install, because the catalog knew and nothing
         * asked it.
         */
        assertThat(plugin.author).isEqualTo("Orknux")
        assertThat(plugin.summary).isEqualTo("Says hello.")
        assertThat(plugin.name).isEqualTo("Greeter")
        assertThat(plugin.marketplaceVersion).isEqualTo("1.0.0")
        /*
         * The face came across rather than being pointed at: what is stored is
         * the drawing, so this installation draws it whether or not it can
         * reach the marketplace again.
         */
        assertThat(plugin.icon).startsWith("<svg").contains("greeter-face")

        // What it declares is callable, under the plugin's own prefix.
        assertThat(functions.findAll().map { it.name }).contains("greeter_greet")

        // And the catalog now says so, without an update to offer.
        val listing = catalog.marketplacePlugins().single()
        assertThat(listing.installed).isTrue()
        assertThat(listing.installedVersion).isEqualTo("1.0.0")
        assertThat(listing.updatable).isFalse()
    }

    /**
     * An update is the same act as an install, and the catalog is what says
     * one is available — the version recorded here against the one offered.
     */
    @Test
    fun `a newer version in the catalog is offered as an update, and installs over the old one`() {
        catalog.installMarketplacePlugin("greeter", accept = "lib/words.js")

        offeredVersion = "1.1.0"
        val listing = catalog.marketplacePlugins().single()
        assertThat(listing.updatable).describedAs("the catalog has moved on and this installation has not").isTrue()

        val updated = requireNotNull(catalog.installMarketplacePlugin("greeter", accept = "lib/words.js").plugin)
        assertThat(updated.marketplaceVersion).isEqualTo("1.1.0")
        assertThat(plugins.findAll()).describedAs("updated in place, not installed twice").hasSize(1)
        assertThat(libraries.findByPluginIdOrderByPositionAsc(updated.id.toLong()).map { it.path })
            .containsExactly("lib/words.js")
    }

    @Test
    fun `a plugin switched off keeps everything and offers nothing`() {
        val installed = requireNotNull(catalog.installMarketplacePlugin("greeter", accept = "lib/words.js").plugin)
        assertThat(tools.all().map { it.name }).contains("greeter_greet")

        val off = catalog.setPluginEnabled(installed.id.toLong(), enabled = false)

        assertThat(off.enabled).isFalse()
        assertThat(tools.all().map { it.name })
            .describedAs("its tools leave the agents' menus")
            .doesNotContain("greeter_greet")
        assertThat(functions.findAll().map { it.name })
            .describedAs("and its functions stay, so a graph naming one still draws")
            .contains("greeter_greet")

        // Back on puts it back, which is the whole point of it being a switch.
        val on = catalog.setPluginEnabled(installed.id.toLong(), enabled = true)
        assertThat(on.enabled).isTrue()
        assertThat(tools.all().map { it.name }).contains("greeter_greet")
    }

    companion object {

        /** What the stub catalog currently offers; a test moves it. */
        var offeredVersion = "1.0.0"

        private val plugin = """
            import { HELLO } from './lib/words.js';

            export default class Greeter extends OrknuxPlugin {
              id() { return 'greeter'; }
              apiVersion() { return 1; }
              libraries() { return ['lib/words.js']; }
              functions() {
                return [new OrknuxFunction({
                  name: 'greet',
                  params: [{ name: 'who', type: 'string' }],
                  returnType: 'string',
                  run: (who) => HELLO + ', ' + who,
                })];
              }
              tools() { return [new OrknuxFunctionTool({ function: 'greet' })]; }
            }
        """.trimIndent()

        private val words = "export const HELLO = 'hello';"

        /**
         * One stub standing in for both halves of the marketplace: the GraphQL
         * catalog, and the raw files an install fetches from beside the URL it
         * answered with.
         */
        private val stub: HttpServer =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                fun answer(exchange: HttpExchange, body: String) {
                    val bytes = body.toByteArray(StandardCharsets.UTF_8)
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                    exchange.close()
                }
                createContext("/graphql") { exchange ->
                    if (!keyed(exchange)) return@createContext refuse(exchange)
                    val asked = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                    val offering = """
                        {"key":"greeter","name":"Greeter","author":"Orknux","summary":"Says hello.",
                         "description":"# Greeter","version":"$offeredVersion",
                         "url":"http://${where()}/plugins/greeter/greeter.js",
                         "icon":"http://${where()}/icons/greeter.svg",
                         "downloads":7,"rating":null,"reviews":0,"published":"2026-09-19"}
                    """.trimIndent()
                    // The two queries the client makes, told apart by name.
                    val data = if (asked.contains("marketplacePlugin(")) {
                        """{"marketplacePlugin":$offering}"""
                    } else {
                        """{"marketplacePlugins":[$offering]}"""
                    }
                    answer(exchange, """{"data":$data}""")
                }
                createContext("/plugins/greeter/greeter.js") {
                    if (keyed(it)) answer(it, plugin) else refuse(it)
                }
                createContext("/plugins/greeter/lib/words.js") {
                    if (keyed(it)) answer(it, words) else refuse(it)
                }
                /*
                 * A manifest that disagrees with the listing on every point,
                 * so which one the row keeps is a fact this test establishes
                 * rather than one it happens not to exercise.
                 */
                createContext("/plugins/greeter/plugin.json") {
                    if (keyed(it)) {
                        answer(
                            it,
                            """{"key":"greeter","name":"Not This","summary":"Nor this.",
                               "author":"Someone Else","version":"9.9.9"}""",
                        )
                    } else {
                        refuse(it)
                    }
                }
                /*
                 * The one door that answers anybody, and deliberately: a
                 * listing's icon is a picture on a page a person is reading,
                 * so the marketplace does not key it. Unguarded here so the
                 * install that fetches it keeps working without a header -
                 * and so a change that started demanding one is caught.
                 */
                createContext("/icons/greeter.svg") {
                    answer(it, """<svg xmlns="http://www.w3.org/2000/svg" id="greeter-face"><circle r="8"/></svg>""")
                }
                start()
            }

        fun where() = "${stub.address.hostString}:${stub.address.port}"

        /**
         * The secret both ends share, for the length of this test.
         *
         * The stub checks every request for the day's HMAC of it, so a door
         * that stopped sending the header fails here - which is the whole
         * reason the stub bothers rather than answering anything that asks.
         */
        const val SECRET = "a-shared-secret"

        /** Today's value, computed the way the contract writes it. */
        private fun today(): String =
            javax.crypto.Mac.getInstance("HmacSHA256").run {
                init(javax.crypto.spec.SecretKeySpec(SECRET.toByteArray(), "HmacSHA256"))
                doFinal(
                    java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString().toByteArray(),
                ).joinToString("") { "%02x".format(it) }
            }

        private fun keyed(exchange: com.sun.net.httpserver.HttpExchange): Boolean =
            exchange.requestHeaders.getFirst("X-Orknux-Install-Key") == today()

        /** A bare 401, the way the marketplace refuses: no body, nothing read. */
        private fun refuse(exchange: com.sun.net.httpserver.HttpExchange) {
            exchange.responseHeaders.add("WWW-Authenticate", "Orknux-Install-Key")
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }

        @JvmStatic
        @AfterAll
        fun stop() = stub.stop(0)

        @JvmStatic
        @DynamicPropertySource
        fun marketplace(registry: DynamicPropertyRegistry) {
            registry.add("orknux.marketplace.url") { "http://${where()}/graphql" }
            registry.add("orknux.marketplace.install-key") { SECRET }
        }
    }
}
