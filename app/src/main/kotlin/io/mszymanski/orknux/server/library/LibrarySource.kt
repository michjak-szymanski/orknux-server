package io.mszymanski.orknux.server.library

/**
 * What a library's text says about itself: which spelling it is written in, what
 * it needs from outside, and how a CommonJS one is handed to the sandbox.
 *
 * **Why a second spelling exists at all.** The sandbox evaluates ES modules and
 * nothing else, and for a release that was also the rule for what could be
 * installed — a package with only a CommonJS build was refused. That rule was
 * wider than the reason behind it. The reason is that a library has to be one
 * self-contained file, and a CommonJS file that requires nothing is one: it is an
 * ES module with a different spelling, and the translation is mechanical. So the
 * rule is now about self-containment, which is what was ever meant, and the
 * spelling is this file's problem.
 *
 * **What is stored is the file, not the translation.** [runnable] is applied at
 * the moment the text is evaluated — once when the library is loaded, and again
 * on every run that imports it — and never to the column. That is deliberate and
 * is the decision the provenance columns hang off: `origin_integrity` is the
 * registry's hash of the archive the file came out of, and `sha256` is over the
 * stored text, so both stay claims about *what was fetched*. Store the wrapped
 * text instead and neither hash is reproducible by anybody else holding the same
 * package, which is the one thing those columns are for. It also means the
 * wrapper below is this server's code rather than something frozen into rows: a
 * correction to it reaches every library already loaded, without a re-fetch.
 *
 * `script_library.source_format` records which of the two the stored text is, so
 * the run-time path knows without re-reading a four-megabyte bundle with a
 * regular expression on every call.
 */
object LibrarySource {

    /** The stored text is an ES module and is evaluated as it stands. */
    const val ESM = "ESM"

    /** The stored text is CommonJS and is wrapped by [runnable] before it runs. */
    const val COMMONJS = "COMMONJS"

    /**
     * Which spelling this file is written in.
     *
     * Asked in that order on purpose. A file holding a real `export` is an ES
     * module whatever else it mentions — bundles routinely carry a UMD or
     * `module.exports` shim they never reach, and reading one of those as
     * CommonJS would wrap a module that needs no wrapping.
     *
     * A file that is neither — no export, no `module.exports` — answers [ESM],
     * which is not a guess so much as a refusal to invent one: it is evaluated as
     * it stands and the sandbox says it has no default export to import, which is
     * the true and useful sentence. Wrapping it would turn that into a library
     * exporting an empty object, which installs and is worth nothing.
     */
    fun formatOf(source: String): String = when {
        ESM_EXPORT.containsMatchIn(source) || STATIC_IMPORT.containsMatchIn(source) -> ESM
        COMMONJS_MARKERS.any { it.containsMatchIn(source) } -> COMMONJS
        else -> ESM
    }

    /**
     * The text as the sandbox is given it.
     *
     * An ES module is handed over untouched. A CommonJS file is given the two
     * names its code expects and its own function scope, and what it left on
     * `module.exports` becomes the default export the importer receives.
     *
     * **Node's own wrapper, as closely as an ES module allows.** The body is
     * called with `this` bound to `module.exports` and with `module` and
     * `exports` as parameters, so a file declaring a top-level `var` of its own
     * cannot collide with the module scope, and a UMD wrapper reading `this`
     * finds what it would find in Node. The preamble is one line, so an error's
     * line number is out by exactly one rather than by however long this grows.
     *
     * One thing cannot be reproduced and is worth knowing: everything inside an
     * ES module is strict, and a CommonJS file is sloppy unless it says
     * otherwise. A file that relies on sloppy mode — assigning to an undeclared
     * name, `with`, an octal literal — fails when it is evaluated, which is at
     * the moment somebody is looking at it rather than in the middle of a run.
     */
    fun runnable(source: String, format: String): String =
        if (format == COMMONJS) PREAMBLE + source + EPILOGUE else source

    /**
     * The first module this file imports, or null when it imports nothing.
     *
     * **Half of the answer on dependencies.** A library is one self-contained
     * module, so a file that imports anything at all is refused — a bare name
     * because this installation is not going to fetch a second package to satisfy
     * it, and a relative path because a package that split itself across files
     * has published a module graph rather than a module, and the sandbox resolves
     * no graph.
     *
     * Two of the three forms would be caught by evaluating the file anyway, since
     * GraalJS resolves no module specifier and says so. The third would not:
     * `import('x')` at run time loads nothing until it is called, so a package
     * using one would install cleanly and fail in the middle of somebody's
     * workflow. That case is the reason this reads the text rather than leaving
     * it all to the sandbox — and the reason the sentence it produces names the
     * specifier, which the sandbox's own message does not always do.
     */
    fun imported(source: String): String? =
        listOf(STATIC_IMPORT, EXPORT_FROM, DYNAMIC_IMPORT)
            .firstNotNullOfOrNull { pattern -> pattern.find(source)?.groupValues?.last() }

    /**
     * The first package this file requires, or null when it requires nothing.
     *
     * **The other half, and it is asked only of a CommonJS file.** That
     * distinction is the whole of the care this needs. `require` used to be
     * deliberately not looked for anywhere, because an ES bundle mentions it
     * inside a shim it never reaches and refusing on that would refuse files that
     * work — and that is still true, so [formatOf] deciding [ESM] ends the
     * question and nothing here is asked. In a file being run *as* CommonJS the
     * same text is not dead: `require("x")` is the call, it names another
     * package, and a library that made one would install cleanly and fail at its
     * first use.
     *
     * A literal is what is looked for, and only a literal. `typeof require`,
     * `require` handed about as a value and a UMD branch testing for `define` are
     * all mentions rather than calls, and a package that works is not sunk by
     * one.
     */
    fun required(source: String): String? = REQUIRE.find(source)?.groupValues?.get(1)

    /**
     * One line, so a stack trace is out by one.
     *
     * `.call(module.exports, …)` rather than a plain call: `this` at the top of a
     * CommonJS file is its exports object, and a UMD wrapper passing `this` as
     * its global is common enough that leaving it `undefined` would break files
     * for no reason.
     */
    private const val PREAMBLE =
        "const module = { exports: {} }; const exports = module.exports; (function (module, exports) {\n"

    /**
     * Begins with a newline, because a file ending in a `//` comment would
     * otherwise swallow the brace that closes it.
     */
    private const val EPILOGUE =
        "\n}).call(module.exports, module, exports);\nexport default module.exports;\n"

    /** A real `export` declaration: what makes a file an ES module. */
    private val ESM_EXPORT = Regex(
        "(?<![\\w$.])export\\s*(?:\\{|\\*|default[\\s({\\[]|(?:const|let|var|function|class|async)\\b)",
    )

    private val STATIC_IMPORT = Regex("(?<![\\w$.])import\\s*(?:[^;'\"()]*?\\bfrom\\s*)?[\"']([^\"']+)[\"']")

    private val EXPORT_FROM = Regex(
        "(?<![\\w$.])export\\s*(?:\\*(?:\\s+as\\s+[\\w$]+)?|\\{[^}]*})\\s*from\\s*[\"']([^\"']+)[\"']",
    )

    private val DYNAMIC_IMPORT = Regex("(?<![\\w$.])import\\s*\\(\\s*[\"']([^\"']+)[\"']")

    private val REQUIRE = Regex("(?<![\\w$.])require\\s*\\(\\s*[\"']([^\"']+)[\"']")

    /**
     * What a CommonJS file does with its exports, in the three spellings that
     * turn up: the whole object, one member, and the `__esModule` flag every
     * transpiler writes.
     */
    private val COMMONJS_MARKERS = listOf(
        Regex("(?<![\\w$.])module\\s*\\.\\s*exports\\b"),
        Regex("(?<![\\w$.])exports\\s*(?:\\.\\s*[\\w$]+|\\[[^\\]\\n]*\\])\\s*=(?!=)"),
        Regex("Object\\s*\\.\\s*defineProperty\\s*\\(\\s*exports\\b"),
    )
}
