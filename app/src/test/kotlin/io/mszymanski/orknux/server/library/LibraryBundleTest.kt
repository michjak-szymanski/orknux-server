package io.mszymanski.orknux.server.library

import io.mszymanski.orknux.workflow.script.LibraryInspection
import io.mszymanski.orknux.workflow.script.ScriptProperties
import io.mszymanski.orknux.workflow.script.ScriptRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Several files made into the one file a library has to be.
 *
 * Issue #319. The assertions that matter here are not about the text: they are
 * that the text **runs**, in the sandbox a library actually runs in, and exports
 * what the entry exported. So most of these go through [ScriptRunner.library] —
 * the same call [LibraryStore] makes before storing anything — rather than
 * reading the bundle with a regular expression, because a bundle that looks
 * right and throws on evaluation is the failure this feature would have.
 *
 * What each one is for:
 *
 *   the shape       a graph of files comes out as one module exporting the
 *                   entry's exports
 *   untouched       the module bodies are the files, byte for byte. A bundler
 *                   that rewrote them would be a transpiler nobody asked for
 *   resolution      `./x`, `./x.js`, a directory's index, a JSON file and a
 *                   bare package name all land on the right file
 *   a cycle         two files requiring each other work, as they do in Node
 *   the refusals    an ES module, a missing file and a Node builtin are refused
 *                   by name, because each has a different thing to do about it
 */
class LibraryBundleTest {

    private val scripts = ScriptRunner(ScriptProperties(timeoutMillis = 10_000, statementLimit = 5_000_000))

    /** What the sandbox makes of a bundle: the same door [LibraryStore] uses. */
    private fun read(bundle: String): LibraryInspection =
        scripts.library(LibrarySource.runnable(bundle, LibrarySource.COMMONJS))

    private fun members(bundle: String): List<String> =
        (read(bundle) as LibraryInspection.Read).members.map { it.name }

    @Test
    fun `files that require each other come out as one module`() {
        val bundle = LibraryBundle.of(
            mapOf(
                "index.js" to """
                    var greet = require('./lib/greet');
                    module.exports = { hello: greet.hello, version: '1' };
                """.trimIndent(),
                "lib/greet.js" to "module.exports = { hello: function (who) { return 'hi ' + who; } };",
            ),
            entry = "index.js",
            named = "greeter",
        )

        assertThat(members(bundle)).containsExactlyInAnyOrder("hello", "version")
    }

    /**
     * The property the whole design hangs off.
     *
     * A bundle an administrator cannot read back to the files that went into it
     * is a black box, and every provenance column beside it would be a claim
     * about something nobody can check. So the bodies go in as they arrived.
     */
    @Test
    fun `the files go in exactly as they arrived`() {
        val written = "module.exports = { kept: 1 }; // a trailing comment with a } in it"
        val bundle = LibraryBundle.of(mapOf("index.js" to written), "index.js", "one")

        assertThat(bundle).contains(written)
        assertThat(members(bundle)).containsExactly("kept")
    }

    /** A file ending in a line comment must not swallow the brace after it. */
    @Test
    fun `a file ending in a comment still closes`() {
        val bundle = LibraryBundle.of(
            mapOf("index.js" to "module.exports = { a: 1 };\n// the end"),
            "index.js",
            "commented",
        )

        assertThat(members(bundle)).containsExactly("a")
    }

    @Test
    fun `a specifier with no extension, a directory index and a json file all resolve`() {
        val bundle = LibraryBundle.of(
            mapOf(
                "index.js" to """
                    module.exports = {
                      one: require('./one'),
                      two: require('./two'),
                      called: require('./package.json').name,
                    };
                """.trimIndent(),
                "one.js" to "module.exports = 1;",
                "two/index.js" to "module.exports = 2;",
                "package.json" to """{ "name": "resolved" }""",
            ),
            entry = "index.js",
            named = "resolving",
        )

        assertThat(members(bundle)).containsExactlyInAnyOrder("one", "two", "called")
    }

    @Test
    fun `a bare specifier lands on the package the caller resolved`() {
        val bundle = LibraryBundle.of(
            mapOf(
                "index.js" to "module.exports = { ms: require('ms') };",
                "node_modules/ms/index.js" to "module.exports = function (n) { return n + 'ms'; };",
            ),
            entry = "index.js",
            named = "debug@4.3.4",
            packages = mapOf("ms" to "node_modules/ms/index.js"),
        )

        assertThat(members(bundle)).containsExactly("ms")
    }

    /**
     * Two files that require each other, which is ordinary in CommonJS.
     *
     * Node answers the second require with the half-built exports of a module
     * still being evaluated, and so does the cache in the bundle. Refusing a
     * cycle would refuse packages that work.
     */
    @Test
    fun `a cycle is bundled rather than refused`() {
        val bundle = LibraryBundle.of(
            mapOf(
                "a.js" to "exports.who = 'a'; var b = require('./b'); exports.sees = b.who;",
                "b.js" to "exports.who = 'b'; var a = require('./a'); exports.sees = a.who;",
            ),
            entry = "a.js",
            named = "circular",
        )

        assertThat(members(bundle)).containsExactlyInAnyOrder("who", "sees")
    }

    /** Nothing that was not reached from the entry is carried along. */
    @Test
    fun `a file nothing requires is left out`() {
        val bundle = LibraryBundle.of(
            mapOf(
                "index.js" to "module.exports = 1;",
                "unused.js" to "module.exports = 'THIS SHOULD NOT BE HERE';",
            ),
            entry = "index.js",
            named = "trimmed",
        )

        assertThat(bundle).doesNotContain("THIS SHOULD NOT BE HERE")
    }

    @Test
    fun `an ES module is refused, by name`() {
        assertThatThrownBy {
            LibraryBundle.of(
                mapOf(
                    "index.js" to "import x from './x.js';\nexport default x;",
                    "x.js" to "export default 1;",
                ),
                entry = "index.js",
                named = "modern",
            )
        }
            .isInstanceOf(LibraryBundleEsmException::class.java)
            .hasMessageContaining("index.js")
    }

    @Test
    fun `a file that is not there is refused with what asked for it`() {
        assertThatThrownBy {
            LibraryBundle.of(mapOf("index.js" to "module.exports = require('./gone');"), "index.js", "partial")
        }
            .isInstanceOf(LibraryBundleMissingException::class.java)
            .hasMessageContaining("./gone")
            .hasMessageContaining("index.js")
    }

    /**
     * Node's own, said as such.
     *
     * It would be refused anyway as a specifier nothing answers; what this buys
     * is the sentence, because "requires fs, which is Node's own and is not
     * here" is the difference between a fixable mistake and an afternoon.
     */
    @Test
    fun `something that reaches for Node is refused as that`() {
        assertThatThrownBy {
            LibraryBundle.of(mapOf("index.js" to "var fs = require('fs');"), "index.js", "nodeish")
        }
            .isInstanceOf(LibraryBundleBuiltinException::class.java)
            .hasMessageContaining("fs")
    }

    /** What is stored has to be recognised as CommonJS, or it is run unwrapped. */
    @Test
    fun `a bundle reads as CommonJS`() {
        val bundle = LibraryBundle.of(mapOf("index.js" to "module.exports = {};"), "index.js", "one")

        assertThat(LibrarySource.formatOf(bundle)).isEqualTo(LibrarySource.COMMONJS)
    }
}
