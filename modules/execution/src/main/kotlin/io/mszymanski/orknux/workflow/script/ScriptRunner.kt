package io.mszymanski.orknux.workflow.script

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.ResourceLimits
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.io.IOAccess
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs a workspace's JavaScript.
 *
 * The code comes from whoever can edit a workspace's functions, so it is treated as
 * hostile: the context is given no host classes, no files, no network, no
 * threads, no processes and no environment, and everything crossing the
 * boundary is a JSON string rather than an object. What comes back is text this
 * side parses; nothing the script touches is a live Java object.
 *
 * Two limits stop a script that never finishes: a statement limit, which the
 * language enforces, and a wall-clock timeout, after which the context is
 * cancelled from another thread. A script that spins in a tight loop hits the
 * first; one that blocks in a way statements do not count hits the second.
 *
 * The engine is shared, so parsed sources are cached across runs, but every run
 * gets a fresh context: two runs of a function must not be able to see each
 * other's globals.
 */
@Service
class ScriptRunner(private val properties: ScriptProperties) {

    private val engine: Engine = Engine.newBuilder("js")
        // Community GraalJS runs in the interpreter on a stock JDK, which is
        // fine for scripts this size and not worth a warning per run.
        .option("engine.WarnInterpreterOnly", "false")
        .build()

    /** Cancels a context that has outstayed its timeout. */
    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "script-watchdog").apply { isDaemon = true }
    }

    /**
     * Calls one function from [source].
     *
     * @param source the module the workspace wrote; its default export is called.
     * @param arguments JSON for each argument, in the order the function takes
     *   them. `null` is a valid JSON document and means the argument is absent.
     * @param context what the script may know about where it is running, as
     *   JSON. It arrives frozen as `context`, and always carries the time.
     * @return what the function returned, as JSON, or what went wrong.
     */
    fun call(
        source: String,
        functionName: String,
        arguments: List<String>,
        context: String = "{}",
    ): ScriptResult {
        val started = System.nanoTime()
        return try {
            newContext().use {
                val cancel = watchdog.schedule(
                    { runCatching { it.close(true) } },
                    properties.timeoutMillis,
                    TimeUnit.MILLISECONDS,
                )
                try {
                    val output = evaluate(it, source, functionName, arguments, context)
                    ScriptResult.Returned(output, millisSince(started))
                } finally {
                    cancel.cancel(false)
                }
            }
        } catch (failure: PolyglotException) {
            val reason = when {
                failure.isCancelled -> "took longer than ${properties.timeoutMillis} ms and was stopped"
                failure.isResourceExhausted -> exhausted(failure)
                failure.isGuestException -> failure.message ?: "threw"
                else -> failure.message ?: "could not be run"
            }
            ScriptResult.Failed(reason, millisSince(started))
        } catch (failure: ScriptContractException) {
            // The script ran and did not hold up its end: it threw, or there was
            // nothing to call. Both are answers about the script, not faults here.
            ScriptResult.Failed(failure.message ?: "did not return", millisSince(started))
        } catch (failure: IllegalStateException) {
            // Closing a cancelled context races with the call that was in it.
            ScriptResult.Failed(failure.message ?: "could not be run", millisSince(started))
        }
    }

    /**
     * Which budget it was that ran out.
     *
     * `isResourceExhausted` is raised both by the statement limit and by a guest
     * heap that could not grow, and those are not the same thing to whoever
     * reads the sentence: one says the script runs too long, the other that it
     * asked for too much at once. Telling the author of a script that ran out of
     * memory that it "ran more than 5,000,000 statements" sends them to count
     * loops that were never the problem.
     *
     * Read off the message because the flag does not distinguish them; the guest
     * heap failure arrives as "Java heap space".
     */
    private fun exhausted(failure: PolyglotException): String {
        val said = failure.message ?: ""
        val memory = said.contains("heap space", ignoreCase = true) ||
            said.contains("out of memory", ignoreCase = true)
        return if (memory) {
            "asked for more memory than it was given and was stopped"
        } else {
            "ran more than ${properties.statementLimit} statements and was stopped"
        }
    }

    /**
     * Parses [source] without running it, for the editor's Validate.
     *
     * A syntax error is what this catches; a script that only fails when it runs
     * is not something parsing can tell anyone about.
     */
    fun validate(source: String): ScriptValidation = try {
        newContext().use {
            /*
             * Parsing is bounded too, for the same reason running is. It is the
             * one entry point that had no watchdog, which made it the one entry
             * point where a source that took the parser a long time would hold a
             * request thread for as long as it liked - and this one is reached
             * from the editor, by anybody who may write a function.
             */
            val cancel = watchdog.schedule(
                { runCatching { it.close(true) } },
                properties.timeoutMillis,
                TimeUnit.MILLISECONDS,
            )
            try {
                it.parse(module(source))
            } finally {
                cancel.cancel(false)
            }
        }
        ScriptValidation(valid = true)
    } catch (failure: PolyglotException) {
        val location = failure.sourceLocation
        ScriptValidation(
            valid = false,
            message = failure.message ?: "the script could not be parsed",
            line = location?.startLine,
            column = location?.startColumn,
        )
    }

    /**
     * How many arguments the script's default export accepts.
     *
     * Asked so a function can be refused before it is stored when the code and the
     * declared parameters disagree. Arguments are passed positionally — the declared
     * ones, then the workspace's variables — so the count is the whole of the
     * contract; what the code calls them is its own business.
     *
     * Counted from the function's own text rather than from `length`, because
     * `length` stops at the first parameter with a default: `(a, b = 2)` reports one
     * and accepts two, and refusing that would be refusing correct code.
     */
    fun arity(source: String): ScriptArity = try {
        newContext().use { polyglot ->
            val cancel = watchdog.schedule(
                { runCatching { polyglot.close(true) } },
                properties.timeoutMillis,
                TimeUnit.MILLISECONDS,
            )
            try {
                val exported = polyglot.eval(module(source)).getMember("default")
                    ?: return@use ScriptArity.Unreadable("it has no default export to call")
                if (!exported.canExecute()) return@use ScriptArity.Unreadable("its default export is not a function")

                polyglot.getBindings("js").putMember(SUBJECT, exported)
                polyglot.eval("js", ARITY)
                val counted = polyglot.getBindings("js").getMember(ARITY_RESULT)
                if (counted == null || !counted.isNumber) {
                    return@use ScriptArity.Unreadable("its parameters could not be read")
                }
                ScriptArity.Counted(counted.asInt())
            } finally {
                cancel.cancel(false)
            }
        }
    } catch (failure: PolyglotException) {
        ScriptArity.Unreadable(failure.message ?: "the script could not be read")
    } catch (failure: IllegalStateException) {
        ScriptArity.Unreadable(failure.message ?: "the script could not be read")
    }

    /**
     * The sandbox itself. Every `allow…` here is a decision to say no; the
     * builder's defaults are already restrictive, and they are repeated so that
     * loosening one is a visible edit rather than an upgrade's side effect.
     */
    private fun newContext(): Context = Context.newBuilder("js")
        .engine(engine)
        // The options below that remove host globals are marked experimental by
        // GraalJS; they only ever take capability away, so they are worth the
        // acknowledgement.
        .allowExperimentalOptions(true)
        .allowHostAccess(HostAccess.NONE)
        .allowHostClassLookup { false }
        .allowHostClassLoading(false)
        .allowIO(IOAccess.NONE)
        .allowCreateThread(false)
        .allowCreateProcess(false)
        .allowNativeAccess(false)
        .allowPolyglotAccess(org.graalvm.polyglot.PolyglotAccess.NONE)
        .allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.NONE)
        .allowValueSharing(false)
        .resourceLimits(
            ResourceLimits.newBuilder()
                .statementLimit(properties.statementLimit, null)
                .build(),
        )
        // `Java`, `Packages` and `Polyglot` are gone with host and polyglot
        // access; `load` and `print` are turned off by name, since a script has
        // no business reading a file or writing to the process's output.
        .option("js.load", "false")
        .option("js.print", "false")
        // `java` and `Packages` reach nothing once host class lookup is denied,
        // but a global that exists is a global someone will find a use for.
        .option("js.java-package-globals", "false")
        .option("js.polyglot-builtin", "false")
        .option("js.graal-builtin", "false")
        .option("js.ecmascript-version", "2023")
        // Evaluating a module hands back what it exports, which is how the
        // default export is found without the script having to register itself.
        .option("js.esm-eval-returns-exports", "true")
        .build()

    /**
     * Runs the call inside the guest and brings the answer back as text.
     *
     * The result travels through a global that JavaScript writes and this side
     * reads, rather than as a returned value: an async function answers with a
     * promise, and a promise is only settled once the job queue has drained,
     * which happens when the evaluation returns. With no timers, no I/O and no
     * host callbacks in the sandbox, nothing can still be pending by then.
     */
    private fun evaluate(
        polyglot: Context,
        source: String,
        functionName: String,
        arguments: List<String>,
        context: String,
    ): String? {
        val module = polyglot.eval(module(source))
        val function = module.getMember("default")
            ?: throw ScriptContractException("$functionName has no default export to call")
        if (!function.canExecute()) throw ScriptContractException("The default export of $functionName is not a function")

        polyglot.getBindings("js").putMember(CALLEE, function)
        polyglot.getBindings("js").putMember(ARGUMENTS, "[${arguments.joinToString(",")}]")
        polyglot.getBindings("js").putMember(CONTEXT, context)
        polyglot.eval("js", HARNESS)

        val bindings = polyglot.getBindings("js")
        val error = bindings.getMember(ERROR)
        if (error != null && !error.isNull) throw ScriptContractException(error.asString())

        val result = bindings.getMember(RESULT)
        return if (result == null || result.isNull) null else result.asString()
    }

    private fun module(source: String): Source = Source.newBuilder("js", source, "function.mjs")
        .mimeType("application/javascript+module")
        .buildLiteral()

    private fun millisSince(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    private companion object {
        val log = LoggerFactory.getLogger(ScriptRunner::class.java)

        const val SUBJECT = "__orknuxSubject"
        const val ARITY_RESULT = "__orknuxArity"

        /**
         * Counts the parameters the subject declares.
         *
         * Reads the function's own text, so a default value or a destructured
         * parameter counts once, as the caller sees it. Only commas at the top level
         * of the parameter list separate parameters — the ones inside a default
         * value, an object pattern or a string do not — which is why this scans
         * rather than splits.
         */
        val ARITY = """
            (function () {
              var fn = globalThis.$SUBJECT;
              var text = String(fn);
              var open = text.indexOf('(');
              var arrow = text.indexOf('=>');

              // `x => x`: one parameter, and no brackets to look inside.
              if (open === -1 || (arrow !== -1 && arrow < open)) {
                globalThis.$ARITY_RESULT = 1;
                return;
              }

              var depth = 0;
              var commas = 0;
              var anything = false;
              var quote = null;

              for (var at = open; at < text.length; at++) {
                var ch = text[at];

                if (quote !== null) {
                  if (ch === '\\') at++;
                  else if (ch === quote) quote = null;
                  continue;
                }
                if (ch === '"' || ch === "'" || ch === '`') { quote = ch; continue; }

                if (ch === '(' || ch === '[' || ch === '{') { depth++; if (depth === 1) continue; }
                else if (ch === ')' || ch === ']' || ch === '}') { depth--; if (depth === 0) break; }

                if (depth === 1) {
                  if (ch === ',') commas++;
                  else if (ch !== ' ' && ch !== '\n' && ch !== '\r' && ch !== '\t') anything = true;
                }
              }

              globalThis.$ARITY_RESULT = anything ? commas + 1 : 0;
            })();
        """.trimIndent()

        const val CALLEE = "__orknuxCallee"
        const val CONTEXT = "__orknuxContext"
        const val ARGUMENTS = "__orknuxArguments"
        const val RESULT = "__orknuxResult"
        const val ERROR = "__orknuxError"

        /**
         * Calls the function and settles whatever it answered with, leaving JSON
         * behind. Written as one expression so a script cannot shadow it.
         */
        val HARNESS = """
            (function () {
              globalThis.$RESULT = null;
              globalThis.$ERROR = null;
              try {
                // What the script may know about where it runs. Frozen, so one
                // call cannot leave anything behind for the next.
                globalThis.context = Object.freeze(JSON.parse(globalThis.$CONTEXT));
                var args = JSON.parse(globalThis.$ARGUMENTS);
                Promise.resolve(globalThis.$CALLEE.apply(null, args)).then(
                  function (value) {
                    globalThis.$RESULT = value === undefined ? null : JSON.stringify(value);
                  },
                  function (failure) {
                    globalThis.$ERROR = String((failure && failure.message) || failure);
                  }
                );
              } catch (failure) {
                globalThis.$ERROR = String((failure && failure.message) || failure);
              }
            })();
        """.trimIndent()
    }
}

/** What a run of a script produced. */
sealed interface ScriptResult {

    val durationMillis: Long

    /** JSON for what the function returned; null when it returned nothing. */
    data class Returned(val json: String?, override val durationMillis: Long) : ScriptResult

    /** The script threw, ran too long, or never got as far as running. */
    data class Failed(val reason: String, override val durationMillis: Long) : ScriptResult
}

/** How many arguments a script's default export takes, or why that is not knowable. */
sealed interface ScriptArity {

    data class Counted(val parameters: Int) : ScriptArity

    /** No default export, not a function, or it could not be read. */
    data class Unreadable(val reason: String) : ScriptArity
}

data class ScriptValidation(
    val valid: Boolean,
    val message: String? = null,
    val line: Int? = null,
    val column: Int? = null,
)

/** The script ran but did not hold up its end: no default export, or it threw. */
class ScriptContractException(message: String) : RuntimeException(message)

@ConfigurationProperties(prefix = "orknux.script")
data class ScriptProperties(
    /**
     * How long one call may take. The context is cancelled from another thread
     * when it runs out, which stops a script that has stopped making progress.
     */
    val timeoutMillis: Long = 5_000,

    /**
     * How many statements one call may run. This is what catches a tight loop
     * before the clock does, and it is counted by the language rather than
     * observed from outside.
     */
    val statementLimit: Long = 5_000_000,
)
