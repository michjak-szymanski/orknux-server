package io.mszymanski.orknux.server.library

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Installing a library by naming a package, driven through the real mutation.
 *
 * **The registry is a stub on loopback, and has to be.** A suite that reached the
 * real npm would go red on the day somebody unpublished something, and would be
 * asserting about a file nobody in this repository wrote. The whole context is
 * pointed at the stub, so what is measured is the mutation an administrator
 * actually calls — access check, fetch, sandbox, row — and not a service in
 * isolation.
 *
 * What this file is for, as against [NpmRegistryTest]: that one is about the
 * rules a fetch enforces, this one is about what the installation ends up
 * holding. The two questions worth asking here are whether the stored row can
 * say what it is, and whether a package is held to exactly the same standard as
 * an uploaded file — evaluated in the sandbox, refused where it cannot be, and
 * replacing a key in place rather than duplicating it.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class LibraryInstallTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val libraries: ScriptLibraryRepository,
    @Autowired val upload: ScriptLibraryUploadAPI,
) {

    @BeforeEach
    fun reset() {
        libraries.deleteAll()
    }

    @Test
    fun `a named package is fetched, evaluated, and stored with where it came from`() {
        graphQlTester.document(
            """
            mutation {
              installScriptLibrary(spec: "slugs@1.2.3") {
                key callable members { name callable }
                registry { packageName version entry integrity url }
              }
            }
            """,
        ).execute()
            .path("installScriptLibrary.key").entity(String::class.java).isEqualTo("slugs")
            // Read off the value in the sandbox, exactly as an uploaded file is.
            .path("installScriptLibrary.members[*].name").entityList(String::class.java).containsExactly("of")
            .path("installScriptLibrary.registry.packageName").entity(String::class.java).isEqualTo("slugs")
            .path("installScriptLibrary.registry.version").entity(String::class.java).isEqualTo("1.2.3")
            .path("installScriptLibrary.registry.entry").entity(String::class.java).isEqualTo("dist/slugs.mjs")
            .path("installScriptLibrary.registry.integrity").entity(String::class.java)
            .satisfies({ assertThat(it).startsWith("sha512-") })

        // The artefact is the row, and the row is the thing that runs. Asserted
        // on the stored source rather than on the answer, because the answer
        // would still look right if nothing had been written.
        assertThat(libraries.findByKey("slugs")?.source).isEqualTo(MODULE)
        assertThat(libraries.findByKey("slugs")?.origin).isEqualTo(ScriptLibrary.ORIGIN_REGISTRY)
    }

    /** A scope is folded into the key, since a key holds no `@` and no slash. */
    @Test
    fun `a scoped package loads under a key that can be said out loud`() {
        graphQlTester.document(
            """mutation { installScriptLibrary(spec: "@acme/slugs@1.2.3") { key registry { packageName } } }""",
        ).execute()
            .path("installScriptLibrary.key").entity(String::class.java).isEqualTo("acme-slugs")
            .path("installScriptLibrary.registry.packageName").entity(String::class.java).isEqualTo("@acme/slugs")
    }

    /**
     * The same key is the same row, whichever door the file came through.
     *
     * And the provenance is rewritten both ways round: a row still claiming a
     * package whose file was replaced by hand would be believed, which is worse
     * than one claiming nothing.
     */
    @Test
    fun `installing over an uploaded library replaces it in place and rewrites where it came from`() {
        upload.upload(MockMultipartFile("file", "slugs.js", "text/plain", MINE.toByteArray()), null)
        val before = requireNotNull(libraries.findByKey("slugs")?.id)
        assertThat(libraries.findByKey("slugs")?.registryOrNull()).isNull()

        graphQlTester.document("""mutation { installScriptLibrary(spec: "slugs@1.2.3") { id } }""").execute()
            .path("installScriptLibrary.id").entity(Long::class.java).isEqualTo(before)

        assertThat(libraries.findAll()).hasSize(1)
        assertThat(libraries.findByKey("slugs")?.originVersion).isEqualTo("1.2.3")

        upload.upload(MockMultipartFile("file", "slugs.js", "text/plain", MINE.toByteArray()), null)
        val again = requireNotNull(libraries.findByKey("slugs"))
        assertThat(again.origin).isEqualTo(ScriptLibrary.ORIGIN_UPLOAD)
        assertThat(again.originPackage).isNull()
    }

    /**
     * A package is held to the same standard as a file, and by the same code.
     *
     * The sandbox is still the judge of whether a thing is a library, so a
     * package publishing a module with nothing to import is refused on the way
     * in rather than found at the moment a workflow needed it.
     */
    @Test
    fun `a package whose module exports nothing is refused where somebody is looking`() {
        graphQlTester.document("""mutation { installScriptLibrary(spec: "hollow@1.0.0") { key } }""").execute()
            .errors().expect { it.message?.contains("no default export") == true }.verify()

        assertThat(libraries.findAll()).isEmpty()
    }

    /** Refused before anything is fetched, and the sentence says what to type. */
    @Test
    fun `a version that is not one version is refused with a sentence`() {
        graphQlTester.document("""mutation { installScriptLibrary(spec: "slugs@latest") { key } }""").execute()
            .errors().expect { it.message?.contains("exact version") == true }.verify()
    }

    /** What the screen asks before it decides whether to offer the field at all. */
    @Test
    fun `the installation says whether it can fetch a package, and from where`() {
        graphQlTester.document("""query { libraryRegistry { configured url } }""").execute()
            .path("libraryRegistry.configured").entity(Boolean::class.java).isEqualTo(true)
            .path("libraryRegistry.url").entity(String::class.java).isEqualTo(registryUrl())
    }

    /** Null provenance, spelled once, so the assertions above read as English. */
    private fun ScriptLibrary.registryOrNull(): String? =
        if (origin == ScriptLibrary.ORIGIN_REGISTRY) originPackage else null

    companion object {

        /** One self-contained module with a default export: what a library is. */
        private const val MODULE = "export default { of: (t) => String(t).toLowerCase() };"

        /** A file somebody wrote, to be replaced by the package and to replace it. */
        private const val MINE = "export default { of: (t) => t, mine: true };"

        /**
         * The registry the whole context is pointed at.
         *
         * Started here rather than per test because `@DynamicPropertySource` runs
         * before anything else and has to be able to say which port. It serves
         * three packages and a 404 for everything else, which is the registry's
         * own answer to a name nobody published.
         */
        private val npm: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            .apply {
                createContext("/") { exchange ->
                    val path = exchange.requestURI.path
                    val body = served(path)
                    if (body == null) {
                        exchange.sendResponseHeaders(404, -1)
                    } else {
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    }
                    exchange.close()
                }
                start()
            }

        private val archives: Map<String, ByteArray> = mapOf(
            "slugs" to NpmFixture.tarball(
                mapOf(
                    "package.json" to """{"name":"slugs","version":"1.2.3","module":"dist/slugs.mjs"}""",
                    "dist/slugs.mjs" to MODULE,
                ),
            ),
            "@acme/slugs" to NpmFixture.tarball(
                mapOf(
                    "package.json" to """{"name":"@acme/slugs","version":"1.2.3","module":"dist/slugs.mjs"}""",
                    "dist/slugs.mjs" to MODULE,
                ),
            ),
            "hollow" to NpmFixture.tarball(
                mapOf(
                    "package.json" to """{"name":"hollow","version":"1.0.0","module":"index.mjs"}""",
                    "index.mjs" to "const held = 1;",
                ),
            ),
        )

        private fun served(path: String): ByteArray? {
            for ((name, archive) in archives) {
                if (path == tarballPath(name)) return archive
                // `/<name>/<version>`, with a scope written either way round.
                if (path.trimStart('/').replace("%2f", "/") == "$name/${versionOf(name)}") {
                    return ("""{"name":"$name","version":"${versionOf(name)}","dist":{""" +
                        """"tarball":"${registryUrl()}${tarballPath(name)}",""" +
                        """"integrity":"${NpmFixture.integrity(archive)}"}}""")
                        .toByteArray(StandardCharsets.UTF_8)
                }
            }
            return null
        }

        private fun versionOf(name: String) = if (name == "hollow") "1.0.0" else "1.2.3"

        private fun tarballPath(name: String) =
            "/$name/-/${name.substringAfterLast('/')}-${versionOf(name)}.tgz"

        private fun registryUrl() = "http://${npm.address.hostString}:${npm.address.port}"

        @JvmStatic
        @DynamicPropertySource
        fun registry(properties: DynamicPropertyRegistry) {
            properties.add("orknux.library.registry.url") { registryUrl() }
        }
    }
}
