package io.mszymanski.orknux.server.plugin

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * Drawing a page of a PDF, and refusing what should not be drawn.
 *
 * What this is for is an agent looking at its own work: a model that can see
 * reads pictures, and a document it just wrote is bytes - so without this an
 * agent reports "the report is ready" because that is what it did rather than
 * because that is what came out.
 *
 * The bounds matter as much as the drawing. A PDF is an untrusted document
 * format with an embedded-file model and an encryption model of its own, and
 * what a page declares about its own size decides how large a picture comes
 * out of it.
 */
class PdfRendererTest {

    private val renderer = PdfRenderer()

    @Test
    fun `it draws a page as a png`() {
        val drawn = renderer.png(document(pages = 1), page = 1, width = null)

        assertThat(drawn).isInstanceOf(PdfRenderer.Drawing.Drawn::class.java)
        val made = drawn as PdfRenderer.Drawing.Drawn
        // The PNG signature, so this is a picture rather than a hopeful array.
        assertThat(made.png.take(8)).containsExactly(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertThat(made.pages).isEqualTo(1)
    }

    /**
     * A width is what a caller can reason about - how much of the page a model
     * will be able to read - rather than dots per inch, which is a fact about
     * paper.
     */
    @Test
    fun `a width is honoured`() {
        val wide = renderer.png(document(pages = 1), page = 1, width = 900) as PdfRenderer.Drawing.Drawn
        val narrow = renderer.png(document(pages = 1), page = 1, width = 300) as PdfRenderer.Drawing.Drawn

        assertThat(wide.width).isBetween(880, 920)
        assertThat(narrow.width).isBetween(290, 310)
        assertThat(wide.png.size).isGreaterThan(narrow.png.size)
    }

    /** Pages are counted the way a person counts them, and an agent will say it. */
    @Test
    fun `pages are counted from one, and the count comes back`() {
        val three = document(pages = 3)

        val second = renderer.png(three, page = 2, width = 400) as PdfRenderer.Drawing.Drawn
        assertThat(second.pages).isEqualTo(3)

        assertThat(renderer.png(three, page = 0, width = null))
            .isInstanceOf(PdfRenderer.Drawing.Refused::class.java)

        val past = renderer.png(three, page = 4, width = null) as PdfRenderer.Drawing.Refused
        assertThat(past.reason)
            .describedAs("the refusal says how many there are, so the caller can ask again correctly")
            .contains("3 page")
    }

    /** What is not a PDF is refused in a sentence rather than as a parser's stack. */
    @Test
    fun `something that is not a pdf is refused`() {
        val refused = renderer.png("not a pdf at all".toByteArray(), page = 1, width = null)

        assertThat(refused).isInstanceOf(PdfRenderer.Drawing.Refused::class.java)
        assertThat((refused as PdfRenderer.Drawing.Refused).reason)
            .describedAs("said as what it is rather than in the parser's words")
            .contains("not a pdf")
    }

    @Test
    fun `an empty document, and an impossible width, are refused by name`() {
        assertThat((renderer.png(ByteArray(0), 1, null) as PdfRenderer.Drawing.Refused).reason)
            .contains("nothing to draw")
        assertThat((renderer.png(document(pages = 1), 1, 40) as PdfRenderer.Drawing.Refused).reason)
            .contains("width")
    }

    /** A document of however many pages, with something on each of them. */
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
