package io.mszymanski.orknux.server.library

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * A package that is more than one file, installed anyway.
 *
 * Issue #319. Until this, an entry that reached a second file — which is most
 * published packages — was answered with *build a bundle and upload it*, and the
 * person reading that had to go and install Node to do something this server was
 * already holding the archive for.
 *
 * The registry is a stub on loopback for the reason [LibraryInstallTest] gives:
 * a suite that reached the real npm would go red on the day somebody unpublished
 * something. What it serves is the shape this feature is about — a package whose
 * entry requires a file beside it *and* a second package, whose version is a
 * range rather than something anybody typed.
 *
 * Five things:
 *
 *   the question    a package that needs bundling is not refused and not
 *                   silently bundled: it comes back saying what it would take,
 *                   with the packages named
 *   nothing yet     and nothing is stored while the question is unanswered
 *   the answer      `bundle: true` installs it, and what is stored *runs* -
 *                   asserted through the members, which are read off the value
 *                   in the sandbox rather than off the text
 *   the range       the dependency's range resolved to a version, and that
 *                   version is on the row rather than the range
 *   the receipt     what went in is readable afterwards, because a bundle whose
 *                   contents nobody can list is a black box in a table whose
 *                   whole purpose is answering what code is running here
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class LibraryBundleInstallTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val libraries: ScriptLibraryRepository,
) {

    @BeforeEach
    fun reset() {
        libraries.deleteAll()
    }

    @Test
    fun `a package that is more than one file asks before it is bundled`() {
        graphQlTester.document(
            """
            mutation {
              installScriptLibrary(spec: "chatty@2.0.0") {
                installed { key }
                proposed { spec why files parts { name version entry integrity } }
              }
            }
            """,
        ).execute()
            .path("installScriptLibrary.installed").valueIsNull()
            .path("installScriptLibrary.proposed.spec").entity(String::class.java).isEqualTo("chatty@2.0.0")
            // Three: the entry, the file beside it, and the package it needs.
            .path("installScriptLibrary.proposed.files").entity(Int::class.java).isEqualTo(3)
            .path("installScriptLibrary.proposed.parts[*].name").entityList(String::class.java)
            .containsExactly("chatty", "ms")
            // The range resolved, so what is being agreed to is a version.
            .path("installScriptLibrary.proposed.parts[1].version").entity(String::class.java).isEqualTo("2.1.3")

        assertThat(libraries.findAll()).describedAs("nothing is stored while it is still a question").isEmpty()
    }

    /**
     * The sentence that made it a question, kept rather than reworded.
     *
     * It names the file and the specifier, which is the part somebody who did not
     * expect a package to be several files actually needs.
     */
    @Test
    fun `the question says what made it more than one file`() {
        graphQlTester.document(
            """mutation { installScriptLibrary(spec: "chatty@2.0.0") { proposed { why } } }""",
        ).execute()
            .path("installScriptLibrary.proposed.why").entity(String::class.java)
            .satisfies({ assertThat(it).contains("./lib/say") })
    }

    @Test
    fun `saying yes bundles it into one library that runs`() {
        graphQlTester.document(
            """
            mutation {
              installScriptLibrary(spec: "chatty@2.0.0", bundle: true) {
                installed { key format members { name } bundledFrom { name version entry } }
              }
            }
            """,
        ).execute()
            .path("installScriptLibrary.installed.key").entity(String::class.java).isEqualTo("chatty")
            // A bundle is CommonJS and is wrapped on the way into the sandbox,
            // exactly as any other CommonJS library is.
            .path("installScriptLibrary.installed.format").entity(String::class.java).isEqualTo("COMMONJS")
            /*
             * The assertion that matters. These are read off the evaluated value
             * in the sandbox, so a bundle that was text-perfect and threw on
             * evaluation would fail here rather than pass.
             */
            .path("installScriptLibrary.installed.members[*].name").entityList(String::class.java)
            .containsExactly("say", "took")
            .path("installScriptLibrary.installed.bundledFrom[*].name").entityList(String::class.java)
            .containsExactly("chatty", "ms")
            .path("installScriptLibrary.installed.bundledFrom[1].version").entity(String::class.java)
            .isEqualTo("2.1.3")

        val stored = requireNotNull(libraries.findByKey("chatty"))
        // The files went in as they arrived: nothing here transpiles or minifies,
        // which is what makes a bundle something an administrator can still read.
        assertThat(stored.source).contains(SAY)
        assertThat(stored.source).contains(MS)
        assertThat(stored.origin).isEqualTo(ScriptLibrary.ORIGIN_REGISTRY)
    }

    /**
     * A dependency with no root entry is not a reason to refuse.
     *
     * `"main": false` with subpath exports is how a growing number of packages
     * publish, and what reaches for one reaches for `bits/abs` — a file like any
     * other. Refusing the whole bundle because that package could not be
     * *entered* was refusing something nothing had asked to do.
     */
    @Test
    fun `a dependency reached only by a subpath needs no entry of its own`() {
        graphQlTester.document(
            """
            mutation {
              installScriptLibrary(spec: "counts@1.0.0", bundle: true) {
                installed { key members { name } bundledFrom { name version } }
              }
            }
            """,
        ).execute()
            .path("installScriptLibrary.installed.members[*].name").entityList(String::class.java)
            .containsExactly("away")
            .path("installScriptLibrary.installed.bundledFrom[*].name").entityList(String::class.java)
            .containsExactly("counts", "bits")
    }

    /**
     * A package's own `browser` map is honoured, so a Node-only file is not a wall.
     *
     * `{"./inspect.js": false}` is the author saying that file is unavailable
     * where there is no Node — which is exactly where a library runs. Refusing
     * the bundle for a `require("util")` inside it was refusing something the
     * package had already arranged not to do.
     */
    @Test
    fun `a file a package says is not there outside Node is left out`() {
        graphQlTester.document(
            """
            mutation {
              installScriptLibrary(spec: "looks@1.0.0", bundle: true) { installed { key members { name } } }
            }
            """,
        ).execute()
            .path("installScriptLibrary.installed.members[*].name").entityList(String::class.java)
            .containsExactly("at")

        // The stub says what it is, so a bundle is not quietly different from the
        // archive whose name and hash are on the row beside it.
        assertThat(libraries.findByKey("looks")?.source).contains("browser map says")
    }

    /**
     * A package published only as an ES module, installed.
     *
     * `import` is syntax and a bundle has nothing to hand it, so this is the one
     * kind of file that does not go in as it was published: Babel rewrites it on
     * the way in. The assertion is the members, read off the evaluated value in
     * the sandbox - so a rewrite that produced text and not a working module
     * fails here rather than passes.
     */
    @Test
    fun `a package published only as an ES module is compiled and bundled`() {
        graphQlTester.document(
            """
            mutation {
              installScriptLibrary(spec: "modern@1.0.0", bundle: true) {
                installed { key members { name } }
              }
            }
            """,
        ).execute()
            .path("installScriptLibrary.installed.key").entity(String::class.java).isEqualTo("modern")
            .path("installScriptLibrary.installed.members[*].name").entityList(String::class.java)
            .containsExactly("shout")

        val stored = requireNotNull(libraries.findByKey("modern"))
        // Which files are not the published ones, said in the bundle: one nobody
        // can read back to a package is worth much less than one they can.
        assertThat(stored.source).contains("rewritten as CommonJS")
        assertThat(stored.source).contains("lib/loud.js")
    }

    /**
     * A file the compiler cannot parse is refused rather than mangled.
     *
     * Turning `import` into `require` is transpiling, and a regular expression
     * that thinks it can is the thing that breaks somebody's library quietly six
     * months later. The refusal names the file, because the way out is to point
     * the install at a CommonJS build.
     */
    @Test
    fun `a file the compiler cannot read is refused, naming it`() {
        graphQlTester.document(
            """mutation { installScriptLibrary(spec: "broken@1.0.0", bundle: true) { installed { key } } }""",
        ).execute()
            /*
             * Asserted on what somebody can act on rather than on the wording:
             * the version, that it is published in one format only, and the two
             * ways out. `http-proxy-agent@9.1.0` is the package that made this
             * message worth rewriting - "point it at the CommonJS build" names
             * something that does not exist for it.
             */
            /*
             * Asserted on what somebody can act on: which package, which file,
             * and where the reason is. The compiler's own message names the line
             * and goes to the log rather than to a person who asked to install
             * something.
             */
            .errors().expect { said ->
                val message = said.message.orEmpty()
                message.contains("broken@1.0.0") &&
                    message.contains("index.js") &&
                    message.contains("could not be read as JavaScript")
            }.verify()

        assertThat(libraries.findAll()).isEmpty()
    }

    companion object {

        private val SAY = "module.exports = function (who) { return 'hi ' + who; };"

        private val MS = "module.exports = function (n) { return n + 'ms'; };"

        /** Requires the file its own browser map says is not there outside Node. */
        private val LOOKS = """
            var inspect = require('./inspect.js');
            module.exports = { at: function (held) { return typeof inspect; } };
        """.trimIndent()

        /** Reaches its dependency by a subpath, never by the bare name. */
        private val COUNTS = """
            var abs = require('bits/abs');
            module.exports = { away: function (n) { return abs(n); } };
        """.trimIndent()

        /** Published as an ES module, with a graph: neither file goes in as it is. */
        private val MODERN = """
            import { loud } from './lib/loud.js';
            export default { shout: (what) => loud(what) };
        """.trimIndent()

        private val CHATTY = """
            var say = require('./lib/say');
            var ms = require('ms');
            module.exports = { say: say, took: function (n) { return ms(n); } };
        """.trimIndent()

        private val npm: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            .apply {
                createContext("/") { exchange ->
                    val body = served(exchange.requestURI.path)
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

        private val archives: Map<Pair<String, String>, ByteArray> = mapOf(
            ("chatty" to "2.0.0") to NpmFixture.tarball(
                mapOf(
                    "package.json" to
                        """{"name":"chatty","version":"2.0.0","main":"index.js","dependencies":{"ms":"^2.1.0"}}""",
                    "index.js" to CHATTY,
                    "lib/say.js" to SAY,
                    // Never reached from the entry, so never bundled: what goes in
                    // is what the graph touches rather than what the archive holds.
                    "test/say.test.js" to "throw new Error('a test file was bundled');",
                ),
            ),
            ("ms" to "2.1.3") to NpmFixture.tarball(
                mapOf(
                    /*
                     * `./index`, with no extension, which is what the real `ms`
                     * publishes - and looked up by exact name it was a package
                     * that had published nothing this could enter. Found against
                     * the real registry, so the fixture is the real spelling.
                     */
                    "package.json" to """{"name":"ms","version":"2.1.3","main":"./index"}""",
                    "index.js" to MS,
                ),
            ),
            /*
             * A package with no root entry at all: `"main": false` and subpaths.
             * That is npm's own spelling for it and `math-intrinsics` publishes
             * exactly this, which is how it was found - the coercion in
             * `asString("")` turned the `false` into a filename and the refusal
             * named a file called `false` that no package has ever held.
             */
            ("bits" to "1.1.0") to NpmFixture.tarball(
                mapOf(
                    "package.json" to
                        """{"name":"bits","version":"1.1.0","main":false,"exports":{"./abs":"./abs.js"}}""",
                    "abs.js" to "module.exports = function (n) { return n < 0 ? -n : n; };",
                ),
            ),
            ("counts" to "1.0.0") to NpmFixture.tarball(
                mapOf(
                    "package.json" to
                        """{"name":"counts","version":"1.0.0","main":"index.js","dependencies":{"bits":"^1.0.0"}}""",
                    "index.js" to COUNTS,
                ),
            ),
            /*
             * A package that ships a Node-only file and a `browser` map saying
             * so. `object-inspect` publishes exactly this shape, and without it
             * `qs` - four packages away - was refused for requiring `util`, which
             * the author had already said would not happen outside Node.
             */
            ("looks" to "1.0.0") to NpmFixture.tarball(
                mapOf(
                    "package.json" to
                        """{"name":"looks","version":"1.0.0","main":"index.js",""" +
                        """"browser":{"./inspect.js":false}}""",
                    "index.js" to LOOKS,
                    "inspect.js" to "module.exports = require('util').inspect;",
                ),
            ),
            /*
             * Published only as ES modules, with a graph: neither file can go
             * into a bundle as it stands, because `import` is syntax and there
             * is nothing to hand it. Babel rewrites both on the way in.
             */
            ("modern" to "1.0.0") to NpmFixture.tarball(
                mapOf(
                    "package.json" to
                        """{"name":"modern","version":"1.0.0","type":"module","main":"index.js"}""",
                    "index.js" to MODERN,
                    "lib/loud.js" to "export function loud(what) { return String(what).toUpperCase(); }",
                ),
            ),
            /* And one the compiler cannot parse, which is still a refusal. */
            ("broken" to "1.0.0") to NpmFixture.tarball(
                mapOf(
                    "package.json" to
                        """{"name":"broken","version":"1.0.0","type":"module","main":"index.js"}""",
                    "index.js" to "import { loud } from './lib/loud.js';\nexport default function ((( {}",
                    "lib/loud.js" to "export function loud(what) { return what; }",
                ),
            ),
        )

        /** What each dependency has published, which is what a range is resolved against. */
        private val published = mapOf(
            "ms" to listOf("2.0.0", "2.1.0", "2.1.3", "3.0.0"),
            "bits" to listOf("1.0.0", "1.1.0"),
        )

        private fun served(path: String): ByteArray? {
            val asked = path.trimStart('/').replace("%2f", "/")

            for ((named, archive) in archives) {
                val (name, version) = named
                if (path == tarballPath(name, version)) return archive
                if (asked == "$name/$version") {
                    return ("""{"name":"$name","version":"$version","dist":{""" +
                        """"tarball":"${registryUrl()}${tarballPath(name, version)}",""" +
                        """"integrity":"${NpmFixture.integrity(archive)}"}}""")
                        .toByteArray(StandardCharsets.UTF_8)
                }
            }

            /*
             * The packument: which versions exist. Asked only for a dependency,
             * because a dependency's version is a range npm published rather than
             * something anybody typed, and it has to be resolved against what is
             * actually there.
             */
            published[asked]?.let { all ->
                val versions = all.joinToString(",") { """"$it":{"version":"$it"}""" }
                return """{"name":"$asked","versions":{$versions}}""".toByteArray(StandardCharsets.UTF_8)
            }
            return null
        }

        private fun tarballPath(name: String, version: String) = "/$name/-/$name-$version.tgz"

        private fun registryUrl() = "http://${npm.address.hostString}:${npm.address.port}"

        @JvmStatic
        @DynamicPropertySource
        fun registry(properties: DynamicPropertyRegistry) {
            properties.add("orknux.library.registry.url") { registryUrl() }
        }
    }
}
