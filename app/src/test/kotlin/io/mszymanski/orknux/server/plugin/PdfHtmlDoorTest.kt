package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.workflow.script.PluginCapability
import io.mszymanski.orknux.workflow.script.PluginProperties
import io.mszymanski.orknux.workflow.script.PluginRunner
import io.mszymanski.orknux.workflow.script.ScriptResult
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * `orknux.render.htmlFromPdf`, through the door rather than on either side of
 * it.
 *
 * The two halves are written in two languages and agree only by convention:
 * the sandbox helper composes the call and the host reads it back. `pngFromPdf`
 * sends a positional array and this sends an object saying `op: "html"`,
 * because the two share one capability - they share the thing the grant is
 * about, which is handing an untrusted document to PDFBox - and something has
 * to tell them apart. That "something" is exactly the kind of agreement that is
 * right until somebody edits one side, so it is pinned here.
 *
 * [PdfRendererTest] covers what the reading itself does; this covers the seam.
 */
class PdfHtmlDoorTest {

    private val mapper = ObjectMapper()
    private val renderer = PdfRenderer()

    /** What reached the door, so the shape the helper sent can be read back. */
    private var asked: String? = null

    /**
     * The server's own dispatch, spelled the way `SlackPluginHost` spells it.
     *
     * Written out rather than reaching for the Spring bean: this test is about
     * the shape that crosses, and the host drags a Slack client, a workspace
     * and an installation's settings in with it.
     */
    private val host = io.mszymanski.orknux.workflow.script.PluginHost { capability, argument, _ ->
        if (capability != PluginCapability.RENDER_PDF) {
            mapper.writeValueAsString(mapOf("error" to "not this test's business"))
        } else {
            asked = argument
            val given = mapper.readTree(argument)
            if (given.isObject && given.path("op").asString() == "html") {
                val pdf = Base64.getDecoder().decode(given.path("pdf").asString())
                val from = given.path("from").takeIf { it.isNumber }?.asInt()
                val to = given.path("to").takeIf { it.isNumber }?.asInt()
                when (val read = renderer.html(pdf, from, to)) {
                    is PdfRenderer.Reading.Refused -> mapper.writeValueAsString(mapOf("error" to read.reason))
                    is PdfRenderer.Reading.Read -> mapper.writeValueAsString(
                        mapOf(
                            "html" to read.html,
                            "pages" to read.pages,
                            "from" to read.from,
                            "to" to read.to,
                            "characters" to read.characters,
                        ),
                    )
                }
            } else {
                mapper.writeValueAsString(mapOf("error" to "that call takes a pdf"))
            }
        }
    }

    /** A plugin whose whole job is to make the call this is about. */
    private val plugin = """
        export default class Reader extends OrknuxPlugin {
          id() { return 'reader'; }
          apiVersion() { return 1; }

          functions() {
            return [
              new OrknuxFunction({
                name: 'read',
                description: 'Reads a pdf back as html.',
                params: [
                  { name: 'pdf', type: 'string' },
                  { name: 'from', type: 'number' },
                  { name: 'to', type: 'number' },
                ],
                returnType: 'object',
                run: (pdf, from, to) => orknux.render.htmlFromPdf(pdf, from, to),
              }),
            ];
          }
        }
    """.trimIndent()

    private fun read(pdf: ByteArray, from: Int? = null, to: Int? = null): Map<String, Any?> {
        val runner = PluginRunner(PluginProperties(), host)
        val answer = runner.call(
            source = plugin,
            functionName = "read",
            arguments = listOf(
                mapper.writeValueAsString(Base64.getEncoder().encodeToString(pdf)),
                mapper.writeValueAsString(from),
                mapper.writeValueAsString(to),
            ),
            permissions = emptySet(),
            capabilities = setOf(PluginCapability.RENDER_PDF),
        )
        assertThat(answer).isInstanceOf(ScriptResult.Returned::class.java)
        @Suppress("UNCHECKED_CAST")
        return mapper.readValue(
            requireNotNull((answer as ScriptResult.Returned).json),
            Map::class.java,
        ) as Map<String, Any?>
    }

    @Test
    fun `a plugin reads a document it holds as base64`() {
        val answered = read(document(pages = 2))

        assertThat(answered["error"]).isNull()
        assertThat(answered["pages"]).isEqualTo(2)
        assertThat(answered["from"]).isEqualTo(1)
        assertThat(answered["to"]).isEqualTo(2)
        assertThat(answered["html"].toString()).contains("Page 1").contains("Page 2")
    }

    @Test
    fun `the call says which one it is, so the other one still works`() {
        read(document(pages = 1))

        val sent = mapper.readTree(requireNotNull(asked))
        assertThat(sent.isObject).isTrue()
        assertThat(sent.path("op").asString()).isEqualTo("html")
        assertThat(sent.path("pdf").isTextual).isTrue()
    }

    @Test
    fun `a range crosses as it was written`() {
        val answered = read(document(pages = 3), from = 2, to = 3)

        assertThat(answered["from"]).isEqualTo(2)
        assertThat(answered["to"]).isEqualTo(3)
        // The document's own count, whatever was read.
        assertThat(answered["pages"]).isEqualTo(3)
    }

    /** A refusal is data on this side too: the plugin reads it, nothing throws. */
    @Test
    fun `what the server refuses arrives as a sentence`() {
        val answered = read("not a pdf".toByteArray())

        assertThat(answered["html"]).isNull()
        assertThat(answered["error"].toString()).contains("not a pdf")
    }

    private fun document(pages: Int): ByteArray {
        PDDocument().use { document ->
            repeat(pages) { at ->
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                PDPageContentStream(document, page).use { ink ->
                    ink.beginText()
                    ink.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 24f)
                    ink.newLineAtOffset(72f, 700f)
                    ink.showText("Page ${at + 1}")
                    ink.endText()
                }
            }
            val out = ByteArrayOutputStream()
            document.save(out)
            return out.toByteArray()
        }
    }
}
