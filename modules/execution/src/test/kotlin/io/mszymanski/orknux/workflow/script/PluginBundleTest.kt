package io.mszymanski.orknux.workflow.script

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The bundle's promises: the import graph is settled outside the sandbox, an
 * import means the file it would mean in an editor, and everything that cannot
 * be carried is refused in a sentence rather than discovered at run time.
 */
class PluginBundleTest {

    @Test
    fun `libraries are ordered so a module follows what it imports`() {
        val bundle = PluginBundle.of(
            "import { top } from './lib/a.js';\nexport default class {}",
            listOf(
                PluginLibraryFile("lib/a.js", "import { base } from './b.js';\nexport const top = base + 1;"),
                PluginLibraryFile("lib/b.js", "export const base = 1;"),
            ),
        ) as PluginBundle.Ordered

        assertThat(bundle.libraries.map { it.path }).containsExactly("lib/b.js", "lib/a.js")
    }

    /** `./b.js` inside `lib/a.js` is `lib/b.js` - the editor's meaning, kept. */
    @Test
    fun `a specifier resolves against the importing file's own directory`() {
        val bundle = PluginBundle.of(
            "import { x } from './lib/a.js';",
            listOf(
                PluginLibraryFile("lib/a.js", "import { root } from '../shared.js';\nexport const x = root;"),
                PluginLibraryFile("shared.js", "export const root = 7;"),
            ),
        )

        assertThat(bundle).isInstanceOf(PluginBundle.Ordered::class.java)
    }

    @Test
    fun `a circle is refused rather than half-evaluated`() {
        val bundle = PluginBundle.of(
            "import './a.js';",
            listOf(
                PluginLibraryFile("a.js", "import './b.js';\nexport const a = 1;"),
                PluginLibraryFile("b.js", "import './a.js';\nexport const b = 2;"),
            ),
        )

        assertThat((bundle as PluginBundle.Refused).reason).contains("circle")
    }

    @Test
    fun `a file nothing imports is refused - nobody is asked to allow dead weight`() {
        val bundle = PluginBundle.of(
            "export default class {}",
            listOf(PluginLibraryFile("lib/unused.js", "export const x = 1;")),
        )

        assertThat((bundle as PluginBundle.Refused).reason).contains("nothing imports lib/unused.js")
    }

    @Test
    fun `what cannot be carried is refused in its own words`() {
        val refusals = mapOf(
            "import _ from 'lodash';" to "bundled in, not imported",
            "import { x } from './missing.js';" to "does not declare",
            "import { x } from '../../outside.js';" to "climbs out",
            "export { x } from './lib/a.js';" to "re-exports",
            "const m = await import('./lib/a.js');" to "import()",
        )
        for ((main, said) in refusals) {
            val bundle = PluginBundle.of(main, listOf(PluginLibraryFile("lib/a.js", "export const x = 1;")))
            assertThat((bundle as PluginBundle.Refused).reason).describedAs(main).contains(said)
        }
    }

    @Test
    fun `every supported import form rewrites into a registry read`() {
        val source = """
            import def from './lib/a.js';
            import { one, two } from './lib/a.js';
            import * as ns from './lib/a.js';
            import both, { three } from './lib/a.js';
            import './lib/a.js';
        """.trimIndent()

        val rewritten = PluginBundle.rewritten(source)

        assertThat(rewritten).contains("""const def = globalThis.__orknuxLibraries["lib/a.js"].default;""")
        assertThat(rewritten).contains("""const { one, two } = globalThis.__orknuxLibraries["lib/a.js"];""")
        assertThat(rewritten).contains("""const ns = globalThis.__orknuxLibraries["lib/a.js"];""")
        assertThat(rewritten).contains("""const both = globalThis.__orknuxLibraries["lib/a.js"].default;""")
        assertThat(rewritten).contains("""const { three } = globalThis.__orknuxLibraries["lib/a.js"];""")
        assertThat(rewritten).doesNotContain("import ")
    }

    /** The rewriting keeps the author's line numbers: statement for statement. */
    @Test
    fun `rewriting moves nothing`() {
        val source = "import { a } from './x.js';\nconst kept = 1;"
        assertThat(PluginBundle.rewritten(source).lines()).hasSize(2)
        assertThat(PluginBundle.rewritten(source).lines()[1]).isEqualTo("const kept = 1;")
    }
}
