package io.mszymanski.orknux.workflow.script

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * One script calling another, in a sandbox that resolves nothing.
 *
 * The host does the resolving and hands the modules over already ordered, so what
 * is under test here is the other half: that a module evaluated into the registry
 * is reachable under the name the importer chose, that a module's own imports are
 * there by the time its body runs, and that none of this loosened the walls the
 * rest of [ScriptRunnerTest] holds.
 */
class ScriptImportTest {

    private val runner = ScriptRunner(ScriptProperties(timeoutMillis = 2_000, statementLimit = 200_000))

    @Test
    fun `an imported function is callable under the name the importer chose`() {
        val result = runner.call(
            "export default function shout(word) { return { said: imports.upper(word) }; }",
            "shout",
            listOf(""""hello""""),
            modules = listOf(
                ScriptModule("f1", "toUpper", "export default function toUpper(word) { return word.toUpperCase(); }"),
            ),
            imports = mapOf("upper" to "f1"),
        )

        assertThat(result).isInstanceOf(ScriptResult.Returned::class.java)
        assertThat((result as ScriptResult.Returned).json).isEqualTo("""{"said":"HELLO"}""")
    }

    /**
     * The name is the importer's, not the imported thing's.
     *
     * This is the whole reason an import is stored as an id and a local name rather
     * than as a name: the module below is called `toUpper` and answers to `shout`,
     * which is what lets a rename cost nothing.
     */
    @Test
    fun `the imported module's own name is not what the code says`() {
        val result = runner.call(
            "export default function f() { return { has: typeof toUpper, used: imports.anything('x') }; }",
            "f",
            emptyList(),
            modules = listOf(
                ScriptModule("f1", "toUpper", "export default function toUpper(word) { return word + '!'; }"),
            ),
            imports = mapOf("anything" to "f1"),
        )

        assertThat((result as ScriptResult.Returned).json).isEqualTo("""{"has":"undefined","used":"x!"}""")
    }

    /** A module may import another, and the deeper one is there when it runs. */
    @Test
    fun `an import may itself import`() {
        val result = runner.call(
            "export default function f(word) { return imports.middle(word); }",
            "f",
            listOf(""""a""""),
            modules = listOf(
                ScriptModule("f1", "inner", "export default function inner(w) { return w + '-inner'; }"),
                ScriptModule(
                    "f2",
                    "middle",
                    "export default function middle(w) { return imports.deep(w) + '-middle'; }",
                    imports = mapOf("deep" to "f1"),
                ),
            ),
            imports = mapOf("middle" to "f2"),
        )

        assertThat((result as ScriptResult.Returned).json).isEqualTo(""""a-inner-middle"""")
    }

    /**
     * A module reached by two importers is evaluated once.
     *
     * Not an optimisation. A module's top-level state is one state, and a diamond
     * that evaluated the shared module twice would give the two sides a counter
     * each — which is not what anybody writing `import` means.
     */
    @Test
    fun `a module two others import is evaluated once`() {
        val counter = "let seen = 0; export default function bump() { seen += 1; return seen; }"
        val result = runner.call(
            "export default function f() { return [imports.left(), imports.right()]; }",
            "f",
            emptyList(),
            modules = listOf(
                ScriptModule("f1", "counter", counter),
                ScriptModule("f2", "left", "export default function l() { return imports.c(); }", mapOf("c" to "f1")),
                ScriptModule("f3", "right", "export default function r() { return imports.c(); }", mapOf("c" to "f1")),
            ),
            imports = mapOf("left" to "f2", "right" to "f3"),
        )

        // One module, one counter: 1 then 2. Two copies would answer 1 twice.
        assertThat((result as ScriptResult.Returned).json).isEqualTo("[1,2]")
    }

    /** What was imported cannot be swapped out for something else halfway through. */
    @Test
    fun `the imports object is frozen`() {
        val result = runner.call(
            """
            export default function f() {
              try { imports.upper = () => 'hijacked'; } catch (ignored) { /* strict mode throws */ }
              return imports.upper('x');
            }
            """.trimIndent(),
            "f",
            emptyList(),
            modules = listOf(ScriptModule("f1", "u", "export default function u(w) { return w.toUpperCase(); }")),
            imports = mapOf("upper" to "f1"),
        )

        assertThat((result as ScriptResult.Returned).json).isEqualTo(""""X"""")
    }

    /** A library is an object rather than a function, and imports the same way. */
    @Test
    fun `an imported module may export an object`() {
        val result = runner.call(
            "export default function f() { return { n: imports.lib.add(2, 3), t: imports.lib.tag }; }",
            "f",
            emptyList(),
            modules = listOf(
                ScriptModule("l1", "arith", "export default { tag: 'arith', add: (a, b) => a + b };"),
            ),
            imports = mapOf("lib" to "l1"),
        )

        assertThat((result as ScriptResult.Returned).json).isEqualTo("""{"n":5,"t":"arith"}""")
    }

    /**
     * Importing changes nothing about the walls.
     *
     * The modules go into the same context the importer runs in, so the question
     * worth asking is whether that context is still the one the rest of the suite
     * checks. An imported module that could reach a file would be a way round every
     * denial in [ScriptRunner], reached by a script that imports it.
     */
    @Test
    fun `an imported module is in the same sandbox and reaches nothing`() {
        val result = runner.call(
            "export default function f() { return imports.probe(); }",
            "f",
            emptyList(),
            modules = listOf(
                ScriptModule(
                    "f1",
                    "probe",
                    """
                    export default function probe() {
                      return { polyglot: typeof Polyglot, load: typeof load, print: typeof print };
                    }
                    """.trimIndent(),
                ),
            ),
            imports = mapOf("probe" to "f1"),
        )

        assertThat((result as ScriptResult.Returned).json)
            .isEqualTo("""{"polyglot":"undefined","load":"undefined","print":"undefined"}""")
    }

    /**
     * And the host's classes are as far out of reach from an import as from the
     * importer. Written as its own test because it is the one that would matter:
     * an import that could reach `Java.type` would be a way round every denial in
     * [ScriptRunner], taken by anybody who could write a function.
     */
    @Test
    fun `an imported module cannot reach the host either`() {
        val result = runner.call(
            "export default function f() { return imports.escape(); }",
            "f",
            emptyList(),
            modules = listOf(
                ScriptModule(
                    "f1",
                    "escape",
                    """export default function escape() { return Java.type("java.lang.System").getenv("PATH"); }""",
                ),
            ),
            imports = mapOf("escape" to "f1"),
        )

        assertThat(result).isInstanceOf(ScriptResult.Failed::class.java)
        assertThat((result as ScriptResult.Failed).reason).contains("not allowed")
    }

    /**
     * A library is read for what it exports, not asked what it is.
     *
     * It is somebody else's code, very often a bundle nobody here wrote, so it is
     * never made to answer a question about itself the way a plugin is. What its
     * default export turned out to hold is read off the value, and a member is
     * either something to call or something to read — which is the whole of what
     * anything can honestly say about a bundle.
     */
    @Test
    fun `a library is read for the members its export holds`() {
        val read = runner.library("export default { tag: 'arith', add: (a, b) => a + b };")

        assertThat(read).isInstanceOf(LibraryInspection.Read::class.java)
        with(read as LibraryInspection.Read) {
            assertThat(callable).isFalse()
            assertThat(members.map { it.name }).containsExactly("add", "tag")
            assertThat(members.single { it.name == "add" }.callable).isTrue()
            assertThat(members.single { it.name == "tag" }.callable).isFalse()
        }
    }

    /** A bundle that exports one function is the other spelling, and says so. */
    @Test
    fun `a library whose export is a function is callable`() {
        val read = runner.library("export default function shout(t) { return t.toUpperCase(); }")

        assertThat((read as LibraryInspection.Read).callable).isTrue()
    }

    @Test
    fun `a file with no default export is not a library`() {
        val read = runner.library("const x = 1;")

        assertThat(read).isInstanceOf(LibraryInspection.Unreadable::class.java)
        assertThat((read as LibraryInspection.Unreadable).reason).contains("no default export")
    }

    /**
     * Reading a library runs its module body, and that body gets no more than a
     * function's does. A bundle that reached the host while being inspected would
     * be a way in taken by uploading a file.
     */
    @Test
    fun `a library that reaches for the host while loading is refused`() {
        val read = runner.library("""const f = Java.type("java.lang.System"); export default { f };""")

        assertThat(read).isInstanceOf(LibraryInspection.Unreadable::class.java)
    }

    /**
     * A grant is the module's, and the importer never sees it.
     *
     * The code takes its externals as ordinary parameters after the ones it
     * declares, so somebody has to append them. The importer cannot: it is shown
     * a signature without them and writes a call to match.
     */
    @Test
    fun `an imported module is handed the externals it declares`() {
        val result = runner.call(
            "export default function f(word) { return imports.read(word); }",
            "f",
            listOf(""""hello""""),
            modules = listOf(
                ScriptModule(
                    key = "f1",
                    name = "readToken",
                    source = "export default function readToken(word, token) { return { word, token }; }",
                    declared = 1,
                    externals = listOf(""""s3cret""""),
                ),
            ),
            imports = mapOf("read" to "f1"),
        )

        assertThat((result as ScriptResult.Returned).json).isEqualTo("""{"word":"hello","token":"s3cret"}""")
    }

    /**
     * And it lands where the module declared it, not after whatever arrived.
     *
     * An importer that passed one argument to a module declaring two would
     * otherwise have its argument followed straight by a variable, and the module
     * would read a secret as its second parameter — silently, and with the wrong
     * value in the answer rather than an error anywhere.
     */
    @Test
    fun `a grant keeps its position when the importer passes fewer arguments`() {
        val result = runner.call(
            "export default function f() { return imports.read('one'); }",
            "f",
            emptyList(),
            modules = listOf(
                ScriptModule(
                    key = "f1",
                    name = "readToken",
                    source = "export default function readToken(a, b, token) { return { a, b: b === undefined, token }; }",
                    declared = 2,
                    externals = listOf(""""s3cret""""),
                ),
            ),
            imports = mapOf("read" to "f1"),
        )

        assertThat((result as ScriptResult.Returned).json).isEqualTo("""{"a":"one","b":true,"token":"s3cret"}""")
    }

    /**
     * What a bundle offers is not always its own property.
     *
     * The `random` package exports an instance of a class: `_cache` and `_rng`
     * are fields on it and `int`, `float` and the rest live on the prototype. Read
     * as own properties it offered the two internals and none of the API, which is
     * the whole of what anybody imports it for.
     */
    @Test
    fun `a library exporting an instance is read for what its prototype offers`() {
        val read = runner.library(
            """
            class Random {
              constructor() { this._cache = {}; this.tag = 'random'; }
              int(low, high) { return low; }
              float() { return 0.5; }
              get seed() { return 1; }
            }
            export default new Random();
            """.trimIndent(),
        )

        with(read as LibraryInspection.Read) {
            assertThat(callable).isFalse()
            assertThat(members.map { it.name }).containsExactly("float", "int", "seed", "tag")
            assertThat(members.single { it.name == "int" }.callable).isTrue()
            assertThat(members.single { it.name == "float" }.callable).isTrue()
            // Read, not called: a getter's answer is a value like any other.
            assertThat(members.single { it.name == "seed" }.callable).isFalse()
            assertThat(members.single { it.name == "tag" }.callable).isFalse()
        }
    }

    /**
     * The chain stops before the language's own.
     *
     * `hasOwnProperty` and `toString` are on everything there is, so offering them
     * would be offering the same six names for every library ever loaded — and an
     * underscore is the only way JavaScript has of saying a name is not for you.
     */
    @Test
    fun `a library offers neither the language's members nor its own internals`() {
        val read = runner.library(
            """
            class Bag {
              constructor() { this._secret = 1; }
              open() { return 2; }
            }
            export default new Bag();
            """.trimIndent(),
        )

        assertThat((read as LibraryInspection.Read).members.map { it.name }).containsExactly("open")
    }

    /** A function's `length` and `name` are what being a function is, not an export. */
    @Test
    fun `a callable library offers its statics and not the shape of a function`() {
        val read = runner.library(
            """
            function shout(t) { return t.toUpperCase(); }
            shout.version = '1.0';
            export default shout;
            """.trimIndent(),
        )

        with(read as LibraryInspection.Read) {
            assertThat(callable).isTrue()
            assertThat(members.map { it.name }).containsExactly("version")
        }
    }

    /** One member nobody can read is one member, not a library nobody can read. */
    @Test
    fun `a member that throws when it is read is listed as something to read`() {
        val read = runner.library(
            """
            class Awkward {
              get broken() { throw new Error('no'); }
              fine() { return 1; }
            }
            export default new Awkward();
            """.trimIndent(),
        )

        with(read as LibraryInspection.Read) {
            assertThat(members.map { it.name }).containsExactly("broken", "fine")
            assertThat(members.single { it.name == "broken" }.callable).isFalse()
            assertThat(members.single { it.name == "fine" }.callable).isTrue()
        }
    }

    /** A script that imports nothing is handed no prelude and is exactly as it was. */
    @Test
    fun `a script with no imports has no imports object`() {
        val result = runner.call(
            "export default function f() { return typeof imports; }",
            "f",
            emptyList(),
        )

        assertThat((result as ScriptResult.Returned).json).isEqualTo(""""undefined"""")
    }
}
