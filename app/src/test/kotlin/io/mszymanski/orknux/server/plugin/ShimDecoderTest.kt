package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.workflow.script.PluginPermission
import io.mszymanski.orknux.workflow.script.PluginProperties
import io.mszymanski.orknux.workflow.script.PluginRunner
import io.mszymanski.orknux.workflow.script.ScriptResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The bundles' shim, under both of the decoders it can find itself beside.
 *
 * Without TEXT_ENCODING there is no decoder at all and the shim's own is used.
 * With it, GraalJS installs one that reads utf-8 and refuses latin1 - which is
 * the encoding jsPDF reads its font tables in, so every PDF failed on exactly
 * the installations that had granted the permission.
 */
class ShimDecoderTest {

    /*
     * The shim as it ships, read from the other repository. Lazily and through
     * [Extension], so a machine without that checkout skips these rather than
     * failing on a path that only ever existed on one developer's disk.
     */
    private val shim by lazy { Extension.read("plugins/shim.mjs") }

    private fun probe(permissions: Set<PluginPermission>): String {
        val source = shim + """

            export default class Plugin extends OrknuxPlugin {
              id() { return 'probe'; }
              apiVersion() { return 1; }
              functions() {
                return [new OrknuxFunction({
                  name: 'decode',
                  returnType: 'string',
                  run: () => {
                    const bytes = Uint8Array.from([72, 233, 108, 108, 111]);
                    return JSON.stringify({
                      latin1: new TextDecoder('latin1').decode(bytes),
                      utf8: new TextDecoder('utf-8').decode(Uint8Array.from([104, 105])),
                      encoded: Array.from(new TextEncoder().encode('hé')),
                    });
                  },
                })];
              }
            }
        """.trimIndent()

        val answer = PluginRunner(PluginProperties()).call(source, "decode", emptyList(), permissions = permissions)
        return when (answer) {
            is ScriptResult.Returned -> answer.json ?: "null"
            is ScriptResult.Failed -> "FAILED: ${answer.reason}"
            else -> "?"
        }
    }

    @Test
    fun `latin1 is decoded whether or not the engine offers a decoder`() {
        for (permissions in listOf(emptySet(), setOf(PluginPermission.TEXT_ENCODING))) {
            val said = probe(permissions)
            println("=== ${permissions.map { it.name }} -> $said")
            // 0xE9 is e-acute in latin1, one byte, one character.
            assertThat(said).contains("H\u00e9llo", "hi")
            // And utf-8 still encodes e-acute as its two bytes.
            assertThat(said).contains("[104,195,169]")
        }
    }
}
