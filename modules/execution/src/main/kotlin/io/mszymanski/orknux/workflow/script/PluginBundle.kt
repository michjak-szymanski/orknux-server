package io.mszymanski.orknux.workflow.script

/** One file a plugin ships beside itself: its declared path, and its code. */
data class PluginLibraryFile(val path: String, val source: String)

/**
 * The shape a multi-file plugin has to hold together, worked out once.
 *
 * A plugin's libraries are ordinary ES modules and its imports are ordinary
 * import statements - that is the contract's promise, because it is what an
 * author's editor already understands. What the sandbox actually evaluates is
 * not ordinary: it has no filesystem and resolves no paths, on purpose. This
 * class is the bridge, and it bridges by *rewriting* rather than by giving the
 * sandbox a resolver: each import of a relative path becomes a read from a
 * registry the runner fills, one evaluated module at a time, in an order this
 * class computed from the import graph.
 *
 * What that costs is a dialect: imports have to be static, top-of-module
 * statements naming a declared file - `import x from './lib/a.js'`,
 * `import { a, b } from './lib/a.js'`, `import * as ns from './lib/a.js'`,
 * or a bare `import './lib/a.js'` for its side effects. `export ... from` and
 * `import(...)` are refused in words, because nothing here can resolve one at
 * run time. What it buys is that no path is ever resolved inside the sandbox:
 * the import graph is settled here, against the declared files and nothing
 * else, before anything is evaluated.
 */
sealed interface PluginBundle {

    /** The files in the order they have to be evaluated, the plugin's own last. */
    data class Ordered(val libraries: List<PluginLibraryFile>) : PluginBundle

    /** What stops this being a loadable bundle, in a sentence. */
    data class Refused(val reason: String) : PluginBundle

    companion object {

        /** Where the registry lives in the sandbox; the runner fills it. */
        const val REGISTRY = "__orknuxLibraries"

        /**
         * One static import statement, in the forms the contract supports.
         *
         * Group 1: the binding clause (absent for a side-effect import).
         * Group 2: the specifier. Multiline, because a named clause may wrap.
         */
        private val IMPORT = Regex(
            """(?m)^\s*import\s+(?:([\w$]+|\*\s+as\s+[\w$]+|\{[^}]*}|[\w$]+\s*,\s*\{[^}]*})\s+from\s+)?["']([^"']+)["']\s*;?""",
        )

        /** The two spellings this cannot rewrite, each refused with its own sentence. */
        private val REEXPORT = Regex("""(?m)^\s*export\s+(?:\{[^}]*}|\*(?:\s+as\s+[\w$]+)?)\s+from\s+["']""")
        private val DYNAMIC = Regex("""\bimport\s*\(""")

        /**
         * Settles a plugin and its files into an evaluation order, or says why
         * it cannot be one.
         *
         * @param main the plugin's own source; it is not in [files] and is
         *   always evaluated last.
         * @param files the libraries, under their declared (normalised) paths.
         */
        fun of(main: String, files: List<PluginLibraryFile>): PluginBundle {
            val byPath = files.associateBy { it.path }
            if (byPath.size != files.size) return Refused("the same library path arrived more than once")

            // What each file imports, resolved to declared paths - or the
            // reason it cannot be.
            val needs = mutableMapOf<String, List<String>>()
            for ((who, source) in files.map { it.path to it.source } + listOf(MAIN to main)) {
                when (val read = imports(who, source, byPath.keys)) {
                    is Imports.Named -> needs[who] = read.paths
                    is Imports.Refused -> return Refused(read.reason)
                }
            }

            // Every declared file has to be reachable from the plugin, or it
            // is a file somebody was asked to allow for nothing.
            val reached = mutableSetOf<String>()
            fun reach(from: String) {
                for (path in needs[from].orEmpty()) {
                    if (reached.add(path)) reach(path)
                }
            }
            reach(MAIN)
            val unreached = byPath.keys - reached
            if (unreached.isNotEmpty()) {
                return Refused(
                    "nothing imports ${unreached.sorted().joinToString(", ")} - a file that ships has to be used",
                )
            }

            /*
             * The evaluation order: a module after everything it imports.
             * Cycles are refused rather than broken, because the registry
             * evaluates whole modules and a half-evaluated module is not
             * something to hand an importer.
             */
            val ordered = mutableListOf<String>()
            val placed = mutableSetOf<String>()
            val walking = mutableSetOf<String>()
            fun place(path: String): String? {
                if (path in placed) return null
                if (!walking.add(path)) return "the libraries import each other in a circle at $path"
                for (needed in needs[path].orEmpty()) place(needed)?.let { return it }
                walking.remove(path)
                placed.add(path)
                if (path != MAIN) ordered.add(path)
                return null
            }
            place(MAIN)?.let { return Refused(it) }

            return Ordered(ordered.map { byPath.getValue(it) })
        }

        /**
         * The source with its imports turned into registry reads.
         *
         * Statement for statement, so nothing moves and a stack trace's line
         * numbers stay the author's. A specifier that does not resolve was
         * already refused by [of], so nothing here decides anything - it only
         * spells what was decided.
         *
         * @param at the file's own declared path, which its specifiers resolve
         *   against; empty for the plugin itself, whose place is the root.
         */
        fun rewritten(source: String, at: String = MAIN): String = IMPORT.replace(source) { found ->
            val clause = found.groupValues[1]
            val path = resolved(found.groupValues[2], at)
            when {
                clause.isEmpty() -> "/* imported for its effects: $path */"
                clause.startsWith("{") -> "const $clause = globalThis.$REGISTRY[${quoted(path)}];"
                clause.startsWith("*") ->
                    "const ${clause.substringAfter("as").trim()} = globalThis.$REGISTRY[${quoted(path)}];"
                clause.contains(",") -> {
                    // `import def, { a, b } from ...`: two reads off one entry.
                    val default = clause.substringBefore(",").trim()
                    val named = clause.substringAfter(",").trim()
                    "const $default = globalThis.$REGISTRY[${quoted(path)}].default; " +
                        "const $named = globalThis.$REGISTRY[${quoted(path)}];"
                }
                else -> "const $clause = globalThis.$REGISTRY[${quoted(path)}].default;"
            }
        }

        /**
         * What one file imports, before the files it imports are in hand.
         *
         * The URL loader's question: it has fetched one file and needs to know
         * what to fetch next, so nothing here checks the paths against a
         * declared set - only that each is relative, stays inside the plugin's
         * directory, and is spelled in a form the rewriting can carry.
         *
         * @param at the file's own plugin-relative path; empty for the plugin.
         */
        fun scan(source: String, at: String = MAIN): Scan = when (val read = imports(at, source, declared = null)) {
            is Imports.Named -> Scan.Paths(read.paths)
            is Imports.Refused -> Scan.Refused(read.reason)
        }

        /**
         * What one file imports, each specifier held to the contract: relative,
         * declared, and spelled in a form the rewriting can carry.
         *
         * @param declared the set an import may land in, or null when nothing
         *   is declared yet and only the shape is being judged - see [scan].
         */
        private fun imports(who: String, source: String, declared: Set<String>?): Imports {
            val name = if (who == MAIN) "the plugin" else who
            if (REEXPORT.containsMatchIn(source)) {
                return Imports.Refused("$name re-exports from another file, which a plugin bundle cannot say - import it, then export it")
            }
            if (DYNAMIC.containsMatchIn(source)) {
                return Imports.Refused("$name calls import(), and nothing resolves a path at run time - imports are static here")
            }

            val paths = mutableListOf<String>()
            for (found in IMPORT.findAll(source)) {
                val specifier = found.groupValues[2]
                if (!specifier.startsWith("./") && !specifier.startsWith("../")) {
                    return Imports.Refused(
                        "$name imports \"$specifier\", which is not one of its files - an npm dependency is bundled in, not imported",
                    )
                }
                val path = resolved(specifier, who)
                    ?: return Imports.Refused("$name imports \"$specifier\", which climbs out of the plugin's own directory")
                if (declared != null && path !in declared) {
                    return Imports.Refused("$name imports $path, which libraries() does not declare")
                }
                paths.add(path)
            }
            return Imports.Named(paths)
        }

        /**
         * A specifier as the registry keys it: resolved against the importing
         * file's own directory, because `./b.js` inside `lib/a.js` means
         * `lib/b.js` - and normalised to the plugin-relative spelling every
         * declared path already uses. Null where it climbs above the plugin's
         * root, which has nowhere to land.
         */
        private fun resolved(specifier: String, from: String): String? {
            val parts = from.split('/').dropLast(1).filter { it.isNotEmpty() }.toMutableList()
            for (piece in specifier.split('/')) {
                when (piece) {
                    "", "." -> {}
                    ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
                    else -> parts.add(piece)
                }
            }
            return parts.joinToString("/").ifEmpty { null }
        }

        private fun quoted(path: String?): String = "\"${path.orEmpty()}\""

        /** The plugin's own place in the graph; never a registry key. */
        private const val MAIN = ""
    }

    private sealed interface Imports {
        data class Named(val paths: List<String>) : Imports
        data class Refused(val reason: String) : Imports
    }

    /** What [Companion.scan] found: the resolved paths, or why it could not look. */
    sealed interface Scan {
        data class Paths(val paths: List<String>) : Scan
        data class Refused(val reason: String) : Scan
    }
}
