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
import java.util.concurrent.atomic.AtomicReference

/**
 * Loads a plugin, asks it what it is, and runs what it declared.
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
 *
 * **A plugin knows only what it was told.** Its parameters arrive as `this.settings`
 * and are the whole of what it can see of the workspace it is running for. There is
 * no clock, no host, no way to ask — so what a plugin can reach is a list somebody
 * filled in, which is the point of it having to declare them.
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

    /**
     * Stops a run that outstays its time, its heap, or its turn. Its own, with
     * its own bounds: a plugin is bigger than a function and is given longer and
     * more room, and the two must not be able to borrow each other's.
     */
    private val guard = ScriptGuard(
        "plugin",
        Bounds(
            timeoutMillis = properties.timeoutMillis,
            heapPressurePercent = properties.heapPressurePercent,
            suspectAfterBytes = properties.suspectAfterBytes,
            concurrency = properties.concurrency,
            queueMillis = properties.queueMillis,
        ),
    )

    /**
     * Everything the server needs to know about a plugin, in one evaluation.
     *
     * Asked together because the answers come from one object: the plugin is
     * constructed once and then questioned, which is both cheaper than loading it
     * three times and the only way the three answers are guaranteed to come from
     * the same instance.
     */
    fun inspect(source: String): PluginInspection {
        val stopped = AtomicReference<Overrun?>(null)
        return try {
            /*
             * Read with nothing relaxed, always, and there is no parameter to say
             * otherwise. This is the call that finds out what the plugin is asking
             * for, and running it under permissions would mean granting something
             * in order to discover whether it should be granted.
             *
             * What it costs a plugin author is that the module body has to load
             * without the permissions the plugin needs - a bundle that touches
             * Intl at the top level cannot be loaded here. That is the safe
             * direction to be wrong in, and the template says so.
             */
            guard.bounded(stopped, { newContext(emptySet()) }) { read(it, source) }
        } catch (failure: PolyglotException) {
            PluginInspection.Unreadable(describe(failure, stopped = stopped.get()))
        } catch (failure: ScriptBusyException) {
            PluginInspection.Unreadable(failure.message ?: "could not be loaded")
        } catch (failure: IllegalStateException) {
            // Closing a cancelled context races with the call that was inside it.
            val overrun = guard.overrunReason(stopped.get())
            PluginInspection.Unreadable(overrun?.plus(" while loading") ?: failure.message ?: "could not be loaded")
        }
    }

    /**
     * Runs one function the plugin declared.
     *
     * @param arguments JSON for each argument, in the order the function declares
     *   them, exactly as [ScriptRunner.call] takes them.
     * @param settings what this workspace set the plugin's parameters to, as a JSON
     *   object of name to value. It arrives frozen as `this.settings`, and it is
     *   the only thing a plugin is told about the workspace it is running for.
     * @param permissions what a person accepted for **this** plugin. Nothing else
     *   is relaxed, and nothing is relaxed for any other plugin: the context is
     *   built here, per call, from this set. Empty is the default and the answer
     *   for every plugin nobody has accepted anything for.
     *
     * The plugin is constructed for the call and thrown away with the context, so
     * one run cannot leave anything behind for the next — including the settings,
     * which differ per workspace and must not survive into another one's run.
     */
    fun call(
        source: String,
        functionName: String,
        arguments: List<String>,
        settings: String = "{}",
        permissions: Set<PluginPermission> = emptySet(),
    ): ScriptResult {
        val started = System.nanoTime()
        val stopped = AtomicReference<Overrun?>(null)
        return try {
            guard.bounded(stopped, { newContext(permissions) }) {
                ScriptResult.Returned(invoke(it, source, functionName, arguments, settings), millis(started))
            }
        } catch (failure: PolyglotException) {
            ScriptResult.Failed(
                describe(failure, doing = "running", stopped = stopped.get()),
                millis(started),
                settled = !(failure.isCancelled || failure.isResourceExhausted) && stopped.get() == null,
            )
        } catch (failure: ScriptBusyException) {
            ScriptResult.Failed(failure.message ?: "could not be run", millis(started), settled = false)
        } catch (failure: ScriptContractException) {
            ScriptResult.Failed(failure.message ?: "did not return", millis(started))
        } catch (failure: IllegalStateException) {
            val overrun = guard.overrunReason(stopped.get())
            if (overrun != null) {
                ScriptResult.Failed("$overrun while running", millis(started), settled = false)
            } else {
                ScriptResult.Failed(failure.message ?: "could not be run", millis(started))
            }
        }
    }

    private fun invoke(
        polyglot: Context,
        source: String,
        functionName: String,
        arguments: List<String>,
        settings: String,
    ): String? {
        polyglot.eval("js", CONTRACT)

        val bindings = polyglot.getBindings("js")
        // Put in before the plugin is constructed: the contract's helper reads it
        // while it is defining `settings` on the instance.
        bindings.putMember(SETTINGS, settings)

        val exported = polyglot.eval(module(source)).getMember("default")
            ?: throw ScriptContractException("$functionName's plugin has no default export")

        bindings.putMember(PLUGIN, bindings.getMember(CONSTRUCT).execute(exported))
        bindings.putMember(WANTED, functionName)
        bindings.putMember(ARGUMENTS, "[${arguments.joinToString(",")}]")
        // As text, like everything else that crosses, so the harness stays one
        // cached source rather than being respliced per call.
        bindings.putMember(RESULT_LIMIT, properties.resultLimitChars.toString())
        polyglot.eval("js", CALL)

        val error = bindings.getMember(ERROR)
        if (error != null && !error.isNull) throw ScriptContractException(error.asString())

        val result = bindings.getMember(RESULT)
        return if (result == null || result.isNull) null else result.asString()
    }

    private fun millis(started: Long): Long = (System.nanoTime() - started) / 1_000_000

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

        /*
         * What the plugin needs to be told before it can do anything. Read here
         * rather than discovered on the first call, because the point of declaring
         * them is that a workspace can be shown what a plugin will be given before
         * it is given anything.
         */
        val wanted = plugin.invokeMember("parameters")
        if (!wanted.hasArrayElements()) {
            return PluginInspection.Unreadable("parameters() did not answer with an array")
        }
        if (wanted.arraySize > MAX_PARAMETERS) {
            return PluginInspection.Unreadable("parameters() declared more than $MAX_PARAMETERS parameters")
        }

        val parameters = (0 until wanted.arraySize).map { at ->
            val one = wanted.getArrayElement(at)
            // Every element passed through OrknuxParameter, which has already
            // refused anything without a name or a type.
            DeclaredParameter(
                name = text(one, "name") ?: return PluginInspection.Unreadable("a parameter has no name"),
                description = text(one, "description"),
                type = text(one, "type") ?: return PluginInspection.Unreadable("a parameter has no type"),
                required = flag(one, "required", default = true),
                secret = flag(one, "secret", default = false),
            )
        }

        /*
         * What JavaScript it says it needs. Read here, before anything is stored
         * and before anything is granted, because the whole arrangement is that a
         * person is shown this list and agrees to it — and a list discovered on the
         * first call would be a list nobody was ever shown.
         */
        val asked = plugin.invokeMember("permissions")
        if (!asked.hasArrayElements()) {
            return PluginInspection.Unreadable("permissions() did not answer with an array")
        }
        if (asked.arraySize > MAX_PERMISSIONS) {
            return PluginInspection.Unreadable("permissions() asked for more than $MAX_PERMISSIONS things")
        }
        val permissions = (0 until asked.arraySize).map { at ->
            val one = asked.getArrayElement(at)
            if (!one.isString) return PluginInspection.Unreadable("permissions() answered with something that is not a name")
            one.asString().trim()
        }.filter { it.isNotEmpty() }.distinct()

        return PluginInspection.Read(
            id = id.asString().trim(),
            apiVersion = version.asInt(),
            functions = functions,
            parameters = parameters,
            permissions = permissions,
        )
    }

    /** A member that has to be a boolean to be worth reading. */
    private fun flag(holder: Value, member: String, default: Boolean): Boolean {
        val value = holder.getMember(member) ?: return default
        if (!value.isBoolean) return default
        return value.asBoolean()
    }

    /** A member that has to be a string to be worth reading. */
    private fun text(holder: Value, member: String): String? {
        val value = holder.getMember(member) ?: return null
        if (!value.isString) return null
        return value.asString().trim().ifEmpty { null }
    }

    /**
     * What went wrong, said in terms of what the sandbox was doing at the time.
     *
     * [doing] is the difference between "took too long to load" and "took too long
     * to run", and whoever reads the sentence needs to know which of the two they
     * are looking at.
     */
    private fun describe(failure: PolyglotException, doing: String = "loading", stopped: Overrun? = null): String = when {
        // A cancelled context says only that somebody stopped it; the guard is
        // the one who knows whether that was the clock or the heap.
        stopped != null -> "${guard.overrunReason(stopped)} while $doing"
        failure.isCancelled -> "took longer than ${properties.timeoutMillis} ms while $doing"
        failure.isResourceExhausted -> exhausted(failure, doing)
        // A guest exception here is usually the contract refusing something, and its
        // message says what — so it is passed on rather than summarised.
        failure.isGuestException -> failure.message ?: "threw while $doing"
        else -> failure.message ?: "could not be run"
    }

    /**
     * Which budget it was that ran out.
     *
     * `isResourceExhausted` covers both the statement limit and a guest heap that
     * could not grow. They read the same to the flag and mean opposite things to
     * whoever has to fix the plugin, so the message is taken from what the
     * failure said: the heap one arrives as "Java heap space".
     */
    private fun exhausted(failure: PolyglotException, doing: String): String {
        val said = failure.message ?: ""
        val memory = said.contains("heap space", ignoreCase = true) ||
            said.contains("out of memory", ignoreCase = true)
        return if (memory) {
            "asked for more memory than it was given while $doing"
        } else {
            "ran more than ${properties.statementLimit} statements while $doing"
        }
    }

    /**
     * No host access, and this time none of it.
     *
     * `HostAccess.NONE` denies every host method and field and then stops, one
     * short of the default mappings of guest values onto mutable host types: a
     * guest array handed to host code that asks it for a `List` still becomes
     * one, and what backs it is whatever the guest felt like. Today nothing
     * asks — a plugin's answers are read out a string, a number, an array
     * element at a time, never converted wholesale — but "a plugin will one day
     * be given authority a function must not have" is the premise of this class,
     * and a mapping like this is exactly what would be found already switched on
     * when that day came.
     *
     * Its own copy, like every other line here. A constant shared with
     * [ScriptRunner] would be one place that configures both, which is what this
     * file exists to avoid.
     */
    internal val hostAccess: HostAccess = HostAccess.newBuilder(HostAccess.NONE)
        // Reads backwards: the argument lists the mappings to allow, so the
        // empty call denies them all. Omitted, the builder allows every one.
        .allowMutableTargetMappings()
        .build()

    /**
     * The sandbox. Every `allow…` is a decision to say no, written out even where
     * the builder would have denied it anyway — so the day a plugin is given a
     * capability, it is a visible line in this file and not a default that moved.
     */
    /**
     * The sandbox, built for one plugin and one call.
     *
     * Every `allow…` is a decision to say no, written out even where the builder
     * would have denied it anyway — so the day a plugin is given a capability, it
     * is a visible line in this file and not a default that moved.
     *
     * [permissions] is the only thing that varies, it varies per plugin, and it can
     * only ever add a language builtin: see [PluginPermission] for why the
     * vocabulary cannot express anything else. Two options are turned *off* here
     * that GraalJS has on by default — `js.console` and `js.intl-402` — because
     * "nothing is relaxed unless it was accepted" is not true of a default that
     * happened to be on, and a plugin that wants either now has to say so.
     *
     * The options are applied last and only from the enumeration, so nothing a
     * plugin wrote reaches this builder as text.
     */
    private fun newContext(permissions: Set<PluginPermission>): Context = Context.newBuilder("js")
        .engine(engine)
        .allowExperimentalOptions(true)
        .allowHostAccess(hostAccess)
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
        // Denied unless accepted, though GraalJS gives both away by default.
        .option("js.console", "false")
        .option("js.intl-402", "false")
        .granting(permissions)
        .build()

    /**
     * Turns on exactly what was accepted, and can turn on nothing else.
     *
     * The option name comes off the enumeration rather than out of anything the
     * plugin wrote, so no string a plugin controls reaches the builder.
     */
    private fun Context.Builder.granting(permissions: Set<PluginPermission>): Context.Builder =
        permissions.fold(this) { builder, granted -> builder.option(granted.option, "true") }

    private fun module(source: String): Source = Source.newBuilder("js", source, "plugin.mjs")
        .mimeType("application/javascript+module")
        .buildLiteral()

    private companion object {
        const val CONSTRUCT = "__orknuxConstruct"

        /** What the workspace set the plugin's parameters to, as JSON, on its way in. */
        const val SETTINGS = "__orknuxSettings"

        const val PLUGIN = "__orknuxPlugin"
        const val WANTED = "__orknuxWanted"
        const val ARGUMENTS = "__orknuxPluginArguments"
        const val RESULT = "__orknuxPluginResult"
        const val ERROR = "__orknuxPluginError"
        const val RESULT_LIMIT = "__orknuxPluginResultLimit"

        /** More than a plugin has any business offering, and a bound on the answer. */
        const val MAX_FUNCTIONS = 100

        /**
         * More than a plugin has any business asking for.
         *
         * Lower than the function bound on purpose: every one of these is something
         * a person has to sit down and fill in, and a plugin asking for fifty pieces
         * of configuration is asking the wrong question.
         */
        const val MAX_PARAMETERS = 50

        /**
         * More than there are permissions to ask for.
         *
         * A bound on the answer rather than a rule about plugins: what is actually
         * allowed is decided against [PluginPermission], and a plugin naming
         * something that is not on it is refused whatever the length of the list.
         */
        const val MAX_PERMISSIONS = 32

        /**
         * Runs one of the plugin's declared functions and leaves JSON behind.
         *
         * The plugin is asked for its declarations again rather than the function
         * being looked up by name on the instance: what the plugin offers is what
         * `functions()` answers, and a method that happens to share a name with a
         * declaration is not the same thing as the declaration's `run`.
         *
         * Called with the plugin as `this`, so a `run` written as a method reaches
         * `this.settings`. One written as an arrow function inside `functions()`
         * already closes over the same instance, so both spellings see the same
         * parameters.
         */
        val CALL = """
            (function () {
              globalThis.$RESULT = null;
              globalThis.$ERROR = null;
              try {
                var plugin = globalThis.$PLUGIN;
                var declared = plugin.functions();
                var wanted = null;
                for (var at = 0; at < declared.length; at++) {
                  if (declared[at].name === globalThis.$WANTED) { wanted = declared[at]; break; }
                }
                if (wanted === null) {
                  globalThis.$ERROR = 'the plugin no longer declares ' + globalThis.$WANTED;
                  return;
                }
                var args = JSON.parse(globalThis.$ARGUMENTS);
                var limit = Number(globalThis.$RESULT_LIMIT);
                Promise.resolve(wanted.run.apply(plugin, args)).then(
                  function (value) {
                    var json = value === undefined ? null : JSON.stringify(value);
                    // Measured before it crosses. What a function answers with is
                    // written to the step, parsed into a tree, and handed to the
                    // next node; the cheap place to refuse an oversized one is
                    // here, where the string has only just been made.
                    if (json !== null && json.length > limit) {
                      globalThis.$ERROR = 'returned ' + json.length +
                        ' characters of JSON, more than the ' + limit + ' it is allowed';
                      return;
                    }
                    globalThis.$RESULT = json;
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

              parameters() {
                return [];
              }

              permissions() {
                return [];
              }
            };

            globalThis.OrknuxParameter = class OrknuxParameter {
              constructor(declared) {
                if (declared === null || typeof declared !== 'object') {
                  throw new Error('an OrknuxParameter needs a declaration');
                }

                this.name = declared.name;
                this.description = declared.description === undefined ? null : declared.description;
                this.type = declared.type;
                this.required = declared.required === undefined ? true : declared.required;
                this.secret = declared.secret === undefined ? false : declared.secret;

                if (typeof this.name !== 'string' || this.name.length === 0) {
                  throw new Error('an OrknuxParameter needs a name');
                }
                if (typeof this.type !== 'string') {
                  throw new Error(this.name + ' needs a type');
                }
                if (typeof this.required !== 'boolean') {
                  throw new Error(this.name + ' says required is neither true nor false');
                }
                if (typeof this.secret !== 'boolean') {
                  throw new Error(this.name + ' says secret is neither true nor false');
                }
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
              var plugin = new exported();

              /*
               * What this workspace set the plugin's parameters to, put on the
               * instance rather than passed to the constructor: a plugin that writes
               * its own constructor would have to remember to forward them, and one
               * that forgot would be handed nothing with no sign of why.
               *
               * Frozen and not configurable, so a run cannot rewrite what it was
               * given and hand the altered version to whatever it calls next. Empty
               * while the plugin is only being asked what it is.
               */
              Object.defineProperty(plugin, 'settings', {
                value: Object.freeze(JSON.parse(globalThis.$SETTINGS || '{}')),
                writable: false,
                enumerable: true,
                configurable: false,
              });
              return plugin;
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
        val parameters: List<DeclaredParameter> = emptyList(),
        /**
         * What it says it needs, exactly as it wrote it.
         *
         * Names, not [PluginPermission]s, because a name this server does not have
         * is a refusal with a sentence in it rather than something to drop
         * quietly — and dropping it would load a plugin having granted it less
         * than it asked for, which is a plugin that fails later for no stated
         * reason.
         */
        val permissions: List<String> = emptyList(),
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

/**
 * One thing a plugin says it has to be told before it can work.
 *
 * Not a function's parameter: a function's is filled in by whoever calls it, node
 * by node, while this is filled in once by the workspace and is the same for every
 * call. A plugin that needs an address to talk to, or a token to talk with, is
 * asking for one of these.
 *
 * [secret] is the plugin saying it is asking for something that should not be
 * typed into a form and stored in the clear. What the server does about that is
 * the server's decision, not the plugin's.
 */
data class DeclaredParameter(
    val name: String,
    val description: String?,
    /** As the plugin wrote it. Whether it names a type this server has is decided elsewhere. */
    val type: String,
    val required: Boolean,
    val secret: Boolean,
)

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

    /**
     * How full the heap may be, after a collection, before loads and calls start
     * being stopped to save the server.
     */
    val heapPressurePercent: Int = 85,

    /**
     * How much one load or call must have allocated before the heap's trouble is
     * put down to it. Higher than a function's, for the same reason a plugin is
     * given longer: a plugin is a bundle, and evaluating one costs more than
     * calling a small export.
     */
    val suspectAfterBytes: Long = 128L * 1024 * 1024,

    /** How many plugin loads or calls may be in a sandbox at once. */
    val concurrency: Int = 4,

    /** How long one waits for its turn before it is told the server is full. */
    val queueMillis: Long = 10_000,

    /** How much JSON one call may hand back, for the server to carry and store. */
    val resultLimitChars: Long = 4L * 1024 * 1024,
)
