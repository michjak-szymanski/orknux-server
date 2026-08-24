package io.mszymanski.orknux.workflow.script

/**
 * A piece of JavaScript a plugin may ask for, and nobody gets by default.
 *
 * A plugin embeds its libraries — that is what makes it portable, and it is why a
 * plugin cannot import one the way a function can. The cost is that a bundle
 * written for a browser or for Node expects language features this sandbox does
 * not switch on, and a plugin that needs `TextDecoder` has, until now, simply not
 * worked.
 *
 * **This enumeration is the whole of what can be asked for, and that is the
 * security property.** It is a closed list, checked at upload: a plugin naming
 * anything else is refused rather than half-granted. So the vocabulary itself
 * cannot express "give me files", "give me a socket" or "give me the host's
 * classes" — there is no spelling for those, and adding one would be a visible
 * edit to this file rather than a plugin asking nicely.
 *
 * Every entry maps to exactly one GraalJS option, and every one of those options
 * only defines a language builtin. None of them opens a door: `js.load` reads
 * files, `js.polyglot-builtin` and `js.java-package-globals` reach the host, and
 * `allowIO`, `allowCreateThread`, `allowHostAccess` and the rest are not options a
 * plugin could name even if it knew them. Those stay written out as denials in
 * [PluginRunner] and are not on this list, because a permission somebody can grant
 * is a permission somebody will grant.
 *
 * What a plugin was granted is exactly what it declared and a person accepted, and
 * it is applied to that one plugin's context. Nothing here reaches the engine, and
 * nothing here reaches [ScriptRunner], which is why a workspace's own functions
 * cannot be given any of it — the two sandboxes are two classes with two flat
 * configurations for precisely this reason.
 */
enum class PluginPermission(
    /** The GraalJS option that turns it on. Nothing else is affected by it. */
    val option: String,

    /** What it gives, as a person deciding whether to accept it reads it. */
    val summary: String,
) {

    /** `console.log` and its siblings, which write to the server's own output. */
    CONSOLE("js.console", "Write to the server's log"),

    /** `Intl`: locale-aware dates, numbers and collation. */
    INTL("js.intl-402", "Format dates and numbers for a locale"),

    /** `TextEncoder` and `TextDecoder`: bytes to text and back. */
    TEXT_ENCODING("js.text-encoding", "Convert between text and bytes"),

    /** `performance.now`, for measuring how long its own work took. */
    PERFORMANCE("js.performance", "Measure elapsed time"),

    /** The `Temporal` date and time API. */
    TEMPORAL("js.temporal", "Use the Temporal date and time API"),

    ;

    companion object {

        /**
         * The permission that name stands for, or null if this server has none.
         *
         * Matched exactly, in upper case as the enum spells it. Not case-folded and
         * not trimmed of punctuation: a plugin asking for something is making a
         * precise request, and being generous about how it is spelled is how a
         * typo becomes a grant of something adjacent.
         */
        fun named(name: String): PluginPermission? = entries.firstOrNull { it.name == name }

        /** Every name a plugin may use, for the template and for a refusal's message. */
        val names: List<String> get() = entries.map { it.name }
    }
}
