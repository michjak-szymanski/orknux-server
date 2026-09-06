package io.mszymanski.orknux.server.action

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The stub a new function starts from, and the two copies of it agreeing.
 *
 * A function starts with an empty body and a sandbox whose whole surface is one
 * global nobody has heard of. Somebody writing their first one reaches for
 * `fetch`, gets a refusal, and learns what is there one refusal at a time - the
 * editor's completion only helps once they have typed `orknux.`, which they
 * have no reason to. So the stub names the calls. Asked for on 2026-09-06.
 *
 * There are two copies of that stub, and they have to say the same thing. The
 * editor prints one for a function that has no id yet - nothing on the server
 * can be asked about a function that does not exist - and the server writes the
 * other into anything it stores. If they drift, the code changes underneath
 * somebody the first time they press Save, which is the one thing an editor
 * must not do.
 *
 * Read out of the two sources rather than by calling either: one of them is
 * TypeScript in a bundle this cannot load, and the lines are what has to agree
 * anyway.
 */
class StubSurfaceTest {

    private val call = Regex("//\\s{3}orknux\\.[a-z]+\\.[a-zA-Z]+[^\\n']*")

    private fun namedIn(path: String): List<String>? {
        val file = File(path)
        if (!file.isFile) return null
        return call.findAll(file.readText())
            .map { it.value.replace(Regex("\\s+"), " ").trim() }
            .distinct()
            .sorted()
            .toList()
    }

    @Test
    fun `the stub names what the sandbox offers, and both copies name the same`() {
        val server = namedIn("src/main/kotlin/io/mszymanski/orknux/server/action/FunctionAPI.kt")
            ?: namedIn("app/src/main/kotlin/io/mszymanski/orknux/server/action/FunctionAPI.kt")
        assertThat(server).isNotNull

        assertThat(server).isNotEmpty()
        assertThat(server!!.joinToString("\n")).contains("orknux.log.")
        assertThat(server.joinToString("\n")).contains("orknux.http.")
        assertThat(server.joinToString("\n")).contains("orknux.slack.")

        /*
         * The editor's copy, where the interface is checked out beside this.
         * Skipped rather than failed where it is not: `orknux-ui` is its own
         * repository, and a server-only checkout is a legitimate way to build.
         */
        val editor = namedIn("../orknux-ui/src/api/functions.ts") ?: namedIn("orknux-ui/src/api/functions.ts")
        assumeTrue(editor != null, "the interface is not checked out beside this one")

        assertThat(editor).describedAs("the editor's stub and the server's name the same calls").isEqualTo(server)
    }
}
