package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.workflow.script.PluginCapability
import io.mszymanski.orknux.workflow.script.PluginInspection
import io.mszymanski.orknux.workflow.script.PluginPermission
import io.mszymanski.orknux.workflow.script.PluginProperties
import io.mszymanski.orknux.workflow.script.PluginRunner
import io.mszymanski.orknux.workflow.script.ScriptResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * The whole way through: mermaid source in, a picture out.
 *
 * The plugin builds the diagram and the server draws it, because the sandbox
 * has no rasteriser - so this is the one path that cannot be checked by looking
 * at either half on its own.
 */
class MermaidPngTest {

    private val mapper = ObjectMapper()
    private val renderer = SvgRenderer()

    private val bundle: String =
        Files.readString(Path.of("C:/Users/micha/Projects/orknux-extension/plugins/mermaid/mermaid.js"))

    /** Only the capability the plugin asked for, answered the way the server answers it. */
    private val host = io.mszymanski.orknux.workflow.script.PluginHost { capability, argument, _ ->
        if (capability != PluginCapability.RENDER_PNG) {
            mapper.writeValueAsString(mapOf("error" to "not this test's business"))
        } else {
            val given = mapper.readTree(argument)
            when (val drawn = renderer.png(given.get(0).asString(), null)) {
                is SvgRenderer.Drawing.Refused -> mapper.writeValueAsString(mapOf("error" to drawn.reason))
                is SvgRenderer.Drawing.Drawn -> mapper.writeValueAsString(
                    mapOf(
                        "base64" to Base64.getEncoder().encodeToString(drawn.png),
                        "bytes" to drawn.png.size,
                    ),
                )
            }
        }
    }

    /*
     * A sequence diagram, and the choice is worth recording.
     *
     * A flowchart does not render in this sandbox at all, and did not before
     * any of this: elkjs, the layout engine mermaid uses for `graph`, is
     * GWT-compiled and reaches for a global holder that resolves to nothing
     * here, so it dies on `A.Math.max`. That is a separate defect in a
     * vendored bundle, and pinning this test to it would mean a test that
     * fails for a reason it is not about.
     */
    private val source =
        "sequenceDiagram\n  U->>S: Ask\n  S-->>U: Answer"

    private fun render(format: String): String {
        val runner = PluginRunner(PluginProperties(), host)
        assertThat(runner.inspect(bundle)).isInstanceOf(PluginInspection.Read::class.java)

        val answer = runner.call(
            source = bundle,
            functionName = "render",
            arguments = listOf(
                mapper.writeValueAsString(source),
                mapper.writeValueAsString(""),
                mapper.writeValueAsString(format),
            ),
            permissions = setOf(PluginPermission.TEXT_ENCODING),
            capabilities = setOf(PluginCapability.RENDER_PNG),
        )
        assertThat(answer).isInstanceOf(ScriptResult.Returned::class.java)
        return requireNotNull((answer as ScriptResult.Returned).json)
    }

    @Test
    fun `png is what it answers, and it is a real picture`() {
        val answered = mapper.readTree(render("png"))

        assertThat(answered.get("svg").asString()).isEmpty()
        val png = Base64.getDecoder().decode(answered.get("png").asString())
        assertThat(png.take(8)).containsExactly(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertThat(answered.get("bytes").asInt()).isEqualTo(png.size)
    }

    @Test
    fun `svg is still there for a caller that wants the markup`() {
        val answered = mapper.readTree(render("svg"))

        assertThat(answered.get("png").asString()).isEmpty()
        assertThat(answered.get("svg").asString()).startsWith("<svg")
    }
}
