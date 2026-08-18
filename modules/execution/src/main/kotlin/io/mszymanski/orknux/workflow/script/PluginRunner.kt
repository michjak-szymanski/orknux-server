package io.mszymanski.orknux.workflow.script

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.ResourceLimits
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.IOAccess
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Loads a plugin and asks it what it is.
 *
 * A deliberate copy of [ScriptRunner] rather than a generalisation of it, and the
 * duplication is the point. Plugins are going to be given authority a workspace's
 * functions must never have, and if one class configured both, granting a plugin
 * something would mean writing a condition — and a mistake in that condition would
 * hand it to every function anybody has written. Two classes, two flat
 * configurations, and the function one has no branch that could turn a capability
 * on.
 *
 * **The contract is a class, not a shape.** The sandbox defines `OrknuxPlugin`
 * before the plugin is evaluated, and a plugin has to extend it. That is checked by
 * prototype rather than by probing for keys, so "this is not a plugin" is answered
 * before anything is called, and a method left unimplemented fails with the base
 * class saying which one — rather than the server guessing why a key was missing.
 */
@Service
class PluginRunner(private val properties: PluginProperties) {

    /**
     * Its own engine, so plugin sources — which are bundles, and large — do not
     * evict the parsed functions a workspace runs all day from a shared cache.
     */
    private val engine: Engine = Engine.newBuilder("js")
        .option("engine.WarnInterpreterOnly", "false")
        .build()

    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "plugin-watchdog").apply { isDaemon = true }
    }

    /**
     * Everything the server needs to know about a plugin, in one evaluation.
     *
     * Asked together because the answers come from one object: the plugin is
     * constructed once and then questioned, which is both cheaper than loading it
     * three times and the only way the three answers are guaranteed to come from
     * the same instance.
     */
    fun inspect(source: String): PluginInspection = try {
        newContext().use { polyglot ->
            val cancel = watchdog.schedule(
                { runCatching { polyglot.close(true) } },
                properties.timeoutMillis,
                TimeUnit.MILLISECONDS,
            )
            try {
                read(polyglot, source)
            } finally {
                cancel.cancel(false)
            }
        }
    } catch (failure: PolyglotException) {
        PluginInspection.Unreadable(describe(failure))
    } catch (failure: IllegalStateException) {
        // Closing a cancelled context races with the call that was inside it.
        PluginInspection.Unreadable(failure.message ?: "could not be loaded")
    }

    private fun read(polyglot: Context, source: String): PluginInspection {
        // The contract first: the plugin is evaluated against a sandbox that already
        // has OrknuxPlugin in it, so `extends OrknuxPlugin` resolves.
        polyglot.eval("js", CONTRACT)

        val exported = polyglot.eval(module(source)).getMember("default")
            ?: return PluginInspection.Unreadable("it has no default export")

        // Constructed by the contract's own helper, which is what refuses anything
        // that is not an OrknuxPlugin — and does it by prototype, not by shape.
        val plugin = polyglot.getBindings("js").getMember(CONSTRUCT).execute(exported)

        val id = plugin.invokeMember("id")
        if (!id.isString || id.asString().isBlank()) {
            return PluginInspection.Unreadable("id() did not answer with a name")
        }

        val version = plugin.invokeMember("apiVersion")
        if (!version.isNumber || !version.fitsInInt()) {
            return PluginInspection.Unreadable("apiVersion() did not answer with a whole number")
        }

        val declared = plugin.invokeMember("functions")
        if (!declared.hasArrayElements()) return PluginInspection.Unreadable("functions() did not answer with an array")
        if (declared.arraySize > MAX_FUNCTIONS) {
            return PluginInspection.Unreadable("functions() declared more than $MAX_FUNCTIONS functions")
        }

        val functions = (0 until declared.arraySize).map { at ->
            val one = declared.getArrayElement(at)
            // Every element passed through OrknuxFunction, which has already refused
            // anything without a name, a return type or something to run.
            val params = one.getMember("params")
            val read = (0 until (params?.arraySize ?: 0)).map { index ->
                val param = params.getArrayElement(index)
                DeclaredParam(
                    name = text(param, "name") ?: return PluginInspection.Unreadable("a parameter has no name"),
                    type = text(param, "type") ?: return PluginInspection.Unreadable("a parameter has no type"),
                )
            }
            DeclaredFunction(
                name = text(one, "name") ?: return PluginInspection.Unreadable("a function has no name"),
                description = text(one, "description"),
                params = read,
                returnType = text(one, "returnType") ?: return PluginInspection.Unreadable("a function has no returnType"),
            )
        }

        return PluginInspection.Read(id = id.asString().trim(), apiVersion = version.asInt(), functions = functions)
    }

    /** A member that has to be a string to be worth reading. */
    private fun text(holder: Value, member: String): String? {
        val value = holder.getMember(member) ?: return null
        if (!value.isString) return null
        return value.asString().trim().ifEmpty { null }
    }

    private fun describe(failure: PolyglotException): String = when {
        failure.isCancelled -> "took longer than ${properties.timeoutMillis} ms to load"
        failure.isResourceExhausted -> "ran more than ${properties.statementLimit} statements while loading"
        // A guest exception here is usually the contract refusing something, and its
        // message says what — so it is passed on rather than summarised.
        failure.isGuestException -> failure.message ?: "threw while loading"
        else -> failure.message ?: "could not be loaded"
    }

    /**
     * The sandbox. Every `allow…` is a decision to say no, written out even where
     * the builder would have denied it anyway — so the day a plugin is given a
     * capability, it is a visible line in this file and not a default that moved.
     */
    private fun newContext(): Context = Context.newBuilder("js")
        .engine(engine)
        .allowExperimentalOptions(true)
        .allowHostAccess(HostAccess.NONE)
        .allowHostClassLookup { false }
        .allowHostClassLoading(false)
        .allowIO(IOAccess.NONE)
        .allowCreateThread(false)
        .allowCreateProcess(false)
        .allowNativeAccess(false)
        .allowPolyglotAccess(PolyglotAccess.NONE)
        .allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.NONE)
        .allowValueSharing(false)
        .resourceLimits(
            ResourceLimits.newBuilder()
                .statementLimit(properties.statementLimit, null)
                .build(),
        )
        .option("js.load", "false")
        .option("js.print", "false")
        .option("js.java-package-globals", "false")
        .option("js.polyglot-builtin", "false")
        .option("js.graal-builtin", "false")
        .option("js.ecmascript-version", "2023")
        .option("js.esm-eval-returns-exports", "true")
        .build()

    private fun module(source: String): Source = Source.newBuilder("js", source, "plugin.mjs")
        .mimeType("application/javascript+module")
        .buildLiteral()

    private companion object {
        const val CONSTRUCT = "__orknuxConstruct"

        /** More than a plugin has any business offering, and a bound on the answer. */
        const val MAX_FUNCTIONS = 100

        /**
         * What a plugin extends, and what refuses anything that does not.
         *
         * Put in the sandbox before the plugin is evaluated, so `extends
         * OrknuxPlugin` resolves without the plugin importing anything — there is no
         * module resolution in here, and there should not be.
         *
         * The base methods throw rather than returning nothing. A plugin that has
         * not implemented one gets an error naming it, which is a better answer than
         * the server reporting that some key was absent.
         *
         * `OrknuxFunction` checks a declaration as it is constructed, so a function
         * missing a name or something to run fails where it was written rather than
         * on the way into the database.
         */
        val CONTRACT = """
            globalThis.OrknuxPlugin = class OrknuxPlugin {
              id() {
                throw new Error('a plugin must implement id(), answering what it is called');
              }

              apiVersion() {
                throw new Error('a plugin must implement apiVersion(), answering which plugin API it uses');
              }

              functions() {
                return [];
              }
            };

            globalThis.OrknuxFunction = class OrknuxFunction {
              constructor(declared) {
                if (declared === null || typeof declared !== 'object') {
                  throw new Error('an OrknuxFunction needs a declaration');
                }

                this.name = declared.name;
                this.description = declared.description === undefined ? null : declared.description;
                this.params = declared.params === undefined ? [] : declared.params;
                this.returnType = declared.returnType;
                this.run = declared.run;

                if (typeof this.name !== 'string' || this.name.length === 0) {
                  throw new Error('an OrknuxFunction needs a name');
                }
                if (typeof this.returnType !== 'string') {
                  throw new Error(this.name + ' needs a returnType');
                }
                if (typeof this.run !== 'function') {
                  throw new Error(this.name + ' needs a run function; it is what the function does');
                }
                if (!Array.isArray(this.params)) {
                  throw new Error(this.name + ' declares params that are not an array');
                }
              }
            };

            globalThis.$CONSTRUCT = function (exported) {
              if (typeof exported !== 'function') {
                throw new Error('the default export must be a class that extends OrknuxPlugin');
              }
              if (!(exported.prototype instanceof globalThis.OrknuxPlugin)) {
                throw new Error('the default export must extend OrknuxPlugin');
              }
              return new exported();
            };
        """.trimIndent()
    }
}

/** What a plugin answered when it was loaded and asked. */
sealed interface PluginInspection {

    data class Read(
        val id: String,
        val apiVersion: Int,
        val functions: List<DeclaredFunction>,
    ) : PluginInspection

    /** It is not a plugin, or it did not hold up its end of the contract. */
    data class Unreadable(val reason: String) : PluginInspection
}

data class DeclaredFunction(
    val name: String,
    val description: String?,
    val params: List<DeclaredParam>,
    /** As the plugin wrote it. Whether it names a real value type is decided elsewhere. */
    val returnType: String,
)

data class DeclaredParam(val name: String, val type: String)

@ConfigurationProperties(prefix = "orknux.plugin")
data class PluginProperties(
    /**
     * How long a plugin may take to load. Longer than a function is given: a
     * plugin is a bundle, and evaluating it is more work than calling one small
     * exported function.
     */
    val timeoutMillis: Long = 10_000,

    /** How much of a plugin may run while it is being loaded. */
    val statementLimit: Long = 10_000_000,
)
