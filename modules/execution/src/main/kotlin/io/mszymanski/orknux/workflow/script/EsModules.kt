package io.mszymanski.orknux.workflow.script

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.PolyglotException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * An ES module, rewritten as CommonJS, by a compiler that does that for a living.
 *
 * **Why this exists, and why it is not written here.** A library has to be one
 * self-contained file, and [LibraryBundle] makes one by putting every module in
 * a function and handing each a `require`. That works for CommonJS because
 * `require` is a call: it can be given. `import` is syntax — hoisted, resolved
 * by the engine before a line runs — and there is no giving it anything. So an
 * ES module could not be bundled, and a package publishing only one was refused.
 *
 * The refusal was right and the reason people were given was the wrong one. There
 * were always three ways to lift it:
 *
 *   by hand      turn `import` into `require` with regular expressions. This is
 *                the one to refuse: it works on every example anybody tests and
 *                breaks on the first file with the word `import` inside a string,
 *                quietly, months later
 *   by engine    give GraalJS a filesystem and let it resolve the graph itself.
 *                Elegant, and it means the sandbox gains a filesystem and a
 *                library stops being one stored file
 *   by compiler  hand it to something that parses JavaScript properly
 *
 * This is the third. Babel is the compiler the ecosystem uses for exactly this
 * transform, `transform-modules-commonjs` is the plugin that does it, and both
 * are somebody else's problem to keep correct — which is the entire point. It is
 * vendored as one file rather than fetched, because an installation with no
 * network still has to be able to install a library, and because what compiles
 * a library should not change under it on a Tuesday.
 *
 * ## What it costs
 *
 * Babel is three megabytes of JavaScript and takes about two and a half seconds
 * to evaluate in this engine. That happens **once**, on the first library that
 * needs it, and never on a run: the context is kept and every transform after
 * the first costs about eighty milliseconds. An installation that never bundles
 * an ES module never loads it at all.
 *
 * ## What it is allowed to do
 *
 * Nothing. It is a compiler running on text: the context below has the same
 * denials the script sandbox has, and what crosses is a string in and a string
 * out. It is not the sandbox a library runs in and it never evaluates what it is
 * compiling.
 */
@Component
class EsModules {

    private val log = LoggerFactory.getLogger(javaClass)

    private val engine: Engine = Engine.newBuilder("js")
        .option("engine.WarnInterpreterOnly", "false")
        .build()

    /**
     * Babel, loaded the first time something needs it and kept afterwards.
     *
     * `lazy` rather than a field because most installations never bundle an ES
     * module, and three megabytes of parse on every boot to be ready for
     * something that may never happen is a cost paid by everybody for the few.
     */
    private val babel: Context by lazy {
        val started = System.nanoTime()
        val source = requireNotNull(javaClass.getResourceAsStream(BABEL)) {
            "$BABEL is missing from this build"
        }.reader().use { it.readText() }

        val context = context()
        context.eval("js", source)
        log.info("Loaded the module compiler in {} ms", (System.nanoTime() - started) / 1_000_000)
        context
    }

    /**
     * [source] as CommonJS, or null with the reason if it cannot be.
     *
     * Synchronised because one compiler is shared and a polyglot context is not
     * to be entered from two threads at once. Installing a library is a thing an
     * administrator does by hand, one at a time, so a lock here costs nothing
     * anybody can feel — and the alternative, a context per call, is two and a
     * half seconds each.
     */
    @Synchronized
    fun toCommonJs(source: String, named: String): Rewritten = try {
        babel.getBindings("js").putMember(SUBJECT, source)
        val code = babel.eval("js", TRANSFORM).asString()
        Rewritten.Done(code)
    } catch (failure: PolyglotException) {
        /*
         * Babel's own message, which names the line and column. A file this
         * cannot parse is a file with something in it that is not JavaScript,
         * and the compiler is the only thing here that knows what.
         */
        val why = failure.message?.substringAfter("SyntaxError: ")?.lineSequence()?.firstOrNull()
        log.warn("Could not rewrite {} as CommonJS: {}", named, why)
        Rewritten.Refused(why ?: "it could not be parsed")
    }

    /** A context with the same denials the script sandbox has. It compiles text. */
    private fun context(): Context = Context.newBuilder("js")
        .engine(engine)
        .allowIO(org.graalvm.polyglot.io.IOAccess.NONE)
        .allowCreateThread(false)
        .allowCreateProcess(false)
        .allowNativeAccess(false)
        .allowHostClassLoading(false)
        .allowHostClassLookup { false }
        .allowPolyglotAccess(org.graalvm.polyglot.PolyglotAccess.NONE)
        .allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.NONE)
        .build()

    private companion object {
        const val BABEL = "/js/babel.min.js"

        const val SUBJECT = "__orknuxModuleSource"

        /**
         * The one transform, and only that one.
         *
         * No preset and no other plugin: this is not compiling anybody's
         * TypeScript or lowering their syntax, it is turning `import` into
         * `require` so a graph can be bundled. `compact: false` because the
         * result goes into a bundle an administrator can still read, and
         * minifying somebody's library on the way past is not this feature's
         * decision to make.
         */
        val TRANSFORM = """
            Babel.transform(globalThis.$SUBJECT, {
              plugins: ['transform-modules-commonjs'],
              sourceType: 'module',
              compact: false,
              babelrc: false,
              configFile: false,
            }).code
        """.trimIndent()
    }
}

/** What became of one file handed to the compiler. */
sealed interface Rewritten {

    /** The same module, in the spelling a bundle can hold. */
    data class Done(val source: String) : Rewritten

    /**
     * It could not be parsed, in the compiler's own words.
     *
     * Carried rather than reworded: Babel names the line and the column, and
     * nothing else here knows what is wrong with somebody's file.
     */
    data class Refused(val why: String) : Rewritten
}
