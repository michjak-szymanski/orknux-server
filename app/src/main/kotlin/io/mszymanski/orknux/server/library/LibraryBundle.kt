package io.mszymanski.orknux.server.library

/**
 * Several files made into the one file a library has to be.
 *
 * **Why this exists, given what [ScriptLibrary] says about self-containment.**
 * That note is unchanged: a library is one stored artefact, evaluated in a
 * sandbox that resolves no module specifier and has no network. What was wrong
 * was not the rule but what the rule cost — a package that split itself across
 * four files, which is most packages, was refused with *build a bundle and
 * upload it*, and the person reading that had to go and install Node to do
 * something this server was already holding all the files for. Issue #319.
 *
 * So the files are made into one file **here**, once, before anything is stored,
 * and what is stored is that one file. Nothing downstream learns a new trick:
 * the bundle is CommonJS, [LibrarySource.runnable] wraps it the way it wraps any
 * other CommonJS library, and the sandbox is handed a single module exactly as
 * before.
 *
 * ## What it does, and what it refuses
 *
 * It walks the graph from an entry, following `require` literals, and emits every
 * file it reaches inside one registry with a `require` of its own. **The module
 * bodies are copied byte for byte.** Nothing is rewritten, minified or
 * transformed — which is the property that makes a bundle this server built
 * something an administrator can still read, and is why resolution is a table
 * rather than a rewrite: each module is handed a map from the specifier it
 * actually wrote to the file that answered it.
 *
 * Three things are refused rather than guessed at, all of them by name:
 *
 *   an ES module    anywhere in the graph. Turning `import` into `require` is
 *                   transpiling, and a regular expression that thinks it can is
 *                   the thing that quietly breaks somebody's library six months
 *                   later. A single ES module needs no bundling and never
 *                   reaches here at all.
 *   a missing file  a specifier nothing in the set answers, said with the file
 *                   that asked for it - which is the sentence somebody who
 *                   forgot to select a file needs
 *   a Node builtin  `fs`, `path`, `crypto`. There is no Node here and there is
 *                   not going to be, and "cannot find module 'fs'" in the middle
 *                   of a run is a much worse way to find that out
 *
 * A cycle is not refused. Two files that require each other are ordinary in
 * CommonJS and the cache below is what makes them work, exactly as it does in
 * Node: the second require of a module that is still being evaluated gets its
 * half-built exports.
 */
object LibraryBundle {

    /**
     * The files, and which one is the entry, made into a single CommonJS module.
     *
     * [modules] is keyed by path — `index.js`, `lib/parse.js`,
     * `node_modules/ms/index.js` — and those keys are what the emitted registry
     * is keyed by too, so a stack trace out of a bundle names the file the code
     * came from rather than an offset into a wall of text.
     *
     * [packages] maps a bare specifier to the file that answers it, because
     * which file `require("ms")` means is a question about a package's manifest
     * and this has none: [NpmRegistry] reads the manifest and answers it, and
     * this puts the answer in the table.
     *
     * [named] is what the whole thing is called in a refusal — a package and
     * version, or what the person called the upload.
     */
    fun of(
        modules: Map<String, String>,
        entry: String,
        named: String,
        packages: Map<String, String> = emptyMap(),
    ): String = bundle(modules, entry, named, packages).source

    /**
     * The same thing, with the list of what actually went in.
     *
     * Which files were *reached* rather than which were offered, and that is the
     * number worth showing somebody: a package ships tests and two builds it
     * never enters, and telling them their library is forty files when it is
     * nine would be counting the archive rather than the bundle.
     */
    fun bundle(
        modules: Map<String, String>,
        entry: String,
        named: String,
        packages: Map<String, String> = emptyMap(),
    ): Bundled {
        require(modules.containsKey(entry)) { "the entry $entry is not among the files" }

        val reached = LinkedHashMap<String, MutableMap<String, String>>()
        walk(entry, modules, packages, named, reached)

        val emitted = StringBuilder()
        emitted.append("/*\n")
        emitted.append(" * Bundled by Orknux from ${reached.size} ")
        emitted.append(if (reached.size == 1) "file" else "files")
        emitted.append(". Entry: $entry\n")
        emitted.append(" *\n")
        emitted.append(" * Every file below is the file as it arrived. Nothing is rewritten: each one\n")
        emitted.append(" * is handed a require that looks its own specifiers up in the table beside it.\n")
        emitted.append(" */\n")
        emitted.append("var __orknux_modules = {\n")
        for ((path, source) in reached.keys.associateWith { requireNotNull(modules[it]) }) {
            emitted.append("  ").append(quoted(path)).append(": function (module, exports, require) {\n")
            /*
             * A JSON file is data and not code: dropped in as it stands it would
             * be a block with a label in it rather than an object, which is
             * valid JavaScript that quietly does nothing. Node hands one back as
             * its exports and so does this.
             */
            if (path.endsWith(".json")) emitted.append("module.exports = ")
            emitted.append(source)
            /*
             * A newline before the brace, because a file ending in a `//`
             * comment would otherwise take the brace into the comment with it -
             * the same reason `LibrarySource.EPILOGUE` begins with one.
             */
            emitted.append("\n},\n")
        }
        emitted.append("};\n")

        emitted.append("var __orknux_paths = {\n")
        for ((path, specifiers) in reached) {
            emitted.append("  ").append(quoted(path)).append(": {")
            emitted.append(specifiers.entries.joinToString(", ") { "${quoted(it.key)}: ${quoted(it.value)}" })
            emitted.append("},\n")
        }
        emitted.append("};\n")

        emitted.append(RUNTIME)
        emitted.append("module.exports = __orknux_require(").append(quoted(entry)).append(");\n")
        return Bundled(emitted.toString(), reached.keys.toList())
    }

    /** One file, and the files that are in it. */
    data class Bundled(val source: String, val files: List<String>)

    /**
     * Which files are reached from here, and what each of their specifiers meant.
     *
     * Depth-first and remembering what it has been to, so a cycle is walked once
     * rather than for ever. What it builds is the whole of the bundle's
     * resolution: after this nothing has to be worked out again, at build time or
     * at run time.
     */
    private fun walk(
        path: String,
        modules: Map<String, String>,
        packages: Map<String, String>,
        named: String,
        reached: MutableMap<String, MutableMap<String, String>>,
    ) {
        if (reached.containsKey(path)) return
        val source = modules[path] ?: return
        val specifiers = LinkedHashMap<String, String>()
        reached[path] = specifiers

        /*
         * A JSON file requires nothing, and asking a JSON file whether it is an
         * ES module gets an answer about a `{` - so both questions are skipped
         * and it is emitted as what it is. `require("./package.json")` is
         * common enough in published packages to be worth this much.
         */
        if (path.endsWith(".json")) return

        /*
         * Asked of every file including the entry, and asked here rather than
         * left to the caller. A lone ES module that requires nothing needs no
         * bundling and never reaches this - and one that *imports* something
         * would otherwise be walked as though it required nothing, and come out
         * as a bundle of one file with its imports still in it.
         *
         * `esm` and not `formatOf`, because `formatOf` answers ESM for a file
         * that is neither spelling. A file with a `require` and no exports would
         * be refused as modern when what is wrong with it is what it requires.
         */
        if (LibrarySource.esm(source)) throw LibraryBundleEsmException(named, path)

        for (specifier in LibrarySource.requires(source)) {
            if (specifiers.containsKey(specifier)) continue
            if (specifier in BUILTINS) throw LibraryBundleBuiltinException(named, path, specifier)

            val answered = resolve(specifier, path, modules, packages)
                ?: throw LibraryBundleMissingException(named, path, specifier)
            specifiers[specifier] = answered
            walk(answered, modules, packages, named, reached)
        }
    }

    /**
     * Which file a specifier means, by CommonJS' own rules, or null.
     *
     * The subset that published packages actually use: a relative path with the
     * extension left off or an `index` under it, and a bare name whose entry the
     * caller has already worked out. What is deliberately not here is anything
     * that would need a manifest — a package's own `exports` conditions, a
     * subpath export — because those are [NpmRegistry]'s to read and this would
     * be a second, worse reading of them.
     */
    private fun resolve(
        specifier: String,
        from: String,
        modules: Map<String, String>,
        packages: Map<String, String>,
    ): String? {
        if (!specifier.startsWith(".")) {
            packages[specifier]?.let { return it }
            // `name/lib/thing`: the package's own directory, then the ordinary
            // file rules under it.
            val within = "node_modules/$specifier"
            return candidates(within).firstOrNull { modules.containsKey(it) }
        }

        val directory = from.substringBeforeLast('/', "")
        val joined = normalised(if (directory.isEmpty()) specifier else "$directory/$specifier") ?: return null
        return candidates(joined).firstOrNull { modules.containsKey(it) }
    }

    /**
     * Which file a path is, by the extensions and index rules Node would try.
     *
     * Public because [NpmRegistry] has the same question and must not answer it
     * differently: a manifest naming `./index` means `index.js`, and a package
     * whose entry was looked up by exact name alone reads as having published
     * nothing - which is how `ms` came back as unbundlable when it is the most
     * ordinary CommonJS file there is.
     */
    fun fileAt(path: String, has: (String) -> Boolean): String? = candidates(path).firstOrNull(has)

    /** What a path could be a file at, in the order Node would try them. */
    private fun candidates(path: String): List<String> = listOf(
        path,
        "$path.js",
        "$path.cjs",
        "$path.mjs",
        "$path.json",
        "$path/index.js",
        "$path/index.cjs",
        "$path/index.json",
    )

    /** `a/b/../c` as `a/c`, or null for a path that climbs out of the set. */
    private fun normalised(path: String): String? {
        val parts = ArrayDeque<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeLast()
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/").ifEmpty { null }
    }

    /**
     * A JavaScript string literal, escaped for the four things a path can hold.
     *
     * Written out rather than taken from a JSON writer because what is being
     * built here is source and not data, and a bundle whose keys were escaped by
     * one library and read by another is a bundle with a seam in it.
     */
    private fun quoted(value: String): String {
        val held = StringBuilder("\"")
        for (character in value) {
            when (character) {
                '"' -> held.append("\\\"")
                '\\' -> held.append("\\\\")
                '\n' -> held.append("\\n")
                '\r' -> held.append("\\r")
                else -> held.append(character)
            }
        }
        return held.append('"').toString()
    }

    /**
     * The whole of the run time, and it resolves nothing.
     *
     * Every specifier was answered while the bundle was being built, so this is a
     * lookup and a cache. A resolver here would be a second implementation of
     * the rules above, in another language, that nothing could test against the
     * first.
     *
     * `__orknux_missing` cannot happen through the table and is here for the file
     * being edited by hand afterwards: a `require` added to a bundle says what it
     * is and where, rather than reading as `undefined is not a function`.
     */
    private val RUNTIME = """
        var __orknux_cache = {};
        function __orknux_require(path) {
          var held = __orknux_cache[path];
          if (held !== undefined) return held.exports;
          var body = __orknux_modules[path];
          if (body === undefined) throw new Error('this bundle holds no ' + path);
          var module = { exports: {} };
          __orknux_cache[path] = module;
          var known = __orknux_paths[path] || {};
          body.call(module.exports, module, module.exports, function (specifier) {
            var answered = known[specifier];
            if (answered === undefined) {
              throw new Error('"' + specifier + '" was not bundled into this library (required by ' + path + ')');
            }
            return __orknux_require(answered);
          });
          return module.exports;
        }
    """.trimIndent() + "\n"

    /**
     * Node's own, which this is not.
     *
     * The list is the ones a published package actually reaches for. It is not
     * exhaustive and does not need to be: anything else is a specifier nothing
     * answers, which is refused by name anyway — this list only buys a better
     * sentence for the cases somebody could otherwise waste an afternoon on.
     */
    private val BUILTINS = setOf(
        "assert", "buffer", "child_process", "crypto", "events", "fs", "http", "https", "module",
        "net", "os", "path", "process", "stream", "string_decoder", "timers", "tls", "tty", "url",
        "util", "vm", "worker_threads", "zlib",
    )
}

/**
 * A file in the graph is an ES module.
 *
 * Named with the file, because the way out is to point the install at the
 * package's CommonJS build or to bundle it where bundles are built, and neither
 * is possible for somebody who has not been told which of the files it was.
 */
class LibraryBundleEsmException(named: String, path: String) : RuntimeException(
    "$named cannot be bundled here: $path is an ES module, and turning import into require is a job " +
        "for a bundler rather than for this. Point it at the package's CommonJS build, or build the " +
        "bundle elsewhere and upload the one file.",
)

/** Something was required that is not among the files. */
class LibraryBundleMissingException(named: String, path: String, specifier: String) : RuntimeException(
    "$named cannot be bundled: $path requires \"$specifier\", and nothing among the files answers it.",
)

/** It reaches for Node, which is not what it is going to run in. */
class LibraryBundleBuiltinException(named: String, path: String, specifier: String) : RuntimeException(
    "$named cannot be bundled: $path requires \"$specifier\", which is Node's own and is not here. " +
        "A library runs in a sandbox with no files, no network and no Node.",
)

/**
 * More than one file was about to be loaded and nobody said it could be.
 *
 * The permission this asks for is not about safety — the files have already been
 * fetched or chosen, and nothing runs until they are stored. It is about what the
 * row will *be*: a bundle is an artefact this server assembled, which no registry
 * published and nobody else can hash to the same thing, and the whole of the
 * provenance beside it is a claim about files that went into it rather than about
 * the file itself. That is a different bargain from the one an install usually
 * makes, so it is offered rather than taken. Issue #319.
 */
class LibraryBundleNotAllowedException(named: String, files: Int) : RuntimeException(
    "$named is $files files rather than one. Allow them to be bundled into a single library, or " +
        "upload the one file you want on its own.",
)
