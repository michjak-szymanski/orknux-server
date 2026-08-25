package io.mszymanski.orknux.server.library

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Reading a library's text: which spelling it is, and what it reaches for.
 *
 * No Spring and no sandbox. What is under examination is the three regular
 * expressions everything else hangs off, and the cases they have to get right are
 * cases about text — a `require` that is a call against a `require` that is a
 * mention, an `export` against an `exports`. Whether the wrapped text then
 * evaluates is `ScriptLibraryTest`'s question, and it asks it end to end.
 */
class LibrarySourceTest {

    @Test
    fun `a file with a real export is an ES module, whatever else it mentions`() {
        assertThat(LibrarySource.formatOf("export default { a: 1 };")).isEqualTo(LibrarySource.ESM)
        assertThat(LibrarySource.formatOf("export const a = 1;")).isEqualTo(LibrarySource.ESM)
        assertThat(LibrarySource.formatOf("const a = 1;\nexport { a };")).isEqualTo(LibrarySource.ESM)
        assertThat(LibrarySource.formatOf("export * from './b.mjs';")).isEqualTo(LibrarySource.ESM)

        // The case that matters: a bundle carrying a shim it never reaches.
        val bundled = "if (typeof module === 'object') { module.exports = x; }\nexport default { a: 1 };"
        assertThat(LibrarySource.formatOf(bundled)).isEqualTo(LibrarySource.ESM)
    }

    @Test
    fun `the three ways a CommonJS file says what it exports are all read`() {
        assertThat(LibrarySource.formatOf("module.exports = { a: 1 };")).isEqualTo(LibrarySource.COMMONJS)
        assertThat(LibrarySource.formatOf("exports.a = 1;")).isEqualTo(LibrarySource.COMMONJS)
        assertThat(LibrarySource.formatOf("Object.defineProperty(exports, '__esModule', { value: true });"))
            .isEqualTo(LibrarySource.COMMONJS)
    }

    /**
     * Neither is not wrapped, and that is the point.
     *
     * Wrapping a file that exports nothing would produce a library exporting an
     * empty object — one that installs and is worth nothing. Left alone, the
     * sandbox says it has no default export to import, which is the sentence
     * whoever chose the file needs.
     */
    @Test
    fun `a file that exports nothing either way is left as it is`() {
        assertThat(LibrarySource.formatOf("const held = 1;")).isEqualTo(LibrarySource.ESM)
        assertThat(LibrarySource.runnable("const held = 1;", LibrarySource.ESM)).isEqualTo("const held = 1;")
    }

    /** An ES module is handed to the sandbox untouched; a CommonJS one is not. */
    @Test
    fun `only a CommonJS file is wrapped, and the wrapper is one line`() {
        val wrapped = LibrarySource.runnable("exports.a = 1;", LibrarySource.COMMONJS)

        assertThat(wrapped).contains("exports.a = 1;")
        assertThat(wrapped).contains("export default module.exports;")
        // One line of preamble, so an error's line number is out by exactly one.
        assertThat(wrapped.substringBefore("exports.a = 1;").lines()).hasSize(2)
        // The body's own scope, and `this` bound the way Node binds it.
        assertThat(wrapped).contains(".call(module.exports, module, exports)")
    }

    @Test
    fun `a require call is found and named, and a mention of require is not`() {
        assertThat(LibrarySource.required("var b = require('buffer');")).isEqualTo("buffer")
        assertThat(LibrarySource.required("""const x = require("./inner.js");""")).isEqualTo("./inner.js")

        assertThat(LibrarySource.required("if (typeof require === 'function') { go(); }")).isNull()
        assertThat(LibrarySource.required("factory(require, exports)")).isNull()
        assertThat(LibrarySource.required("if (typeof define === 'function' && define.amd) { define([], f); }"))
            .isNull()
    }

    @Test
    fun `an import in any of its three forms is found and named`() {
        assertThat(LibrarySource.imported("import s from 'seedrandom';")).isEqualTo("seedrandom")
        assertThat(LibrarySource.imported("export { a } from './b.mjs';")).isEqualTo("./b.mjs")
        assertThat(LibrarySource.imported("const c = () => import('./c.mjs');")).isEqualTo("./c.mjs")
        assertThat(LibrarySource.imported("export default { a: 1 };")).isNull()
    }
}
