package io.mszymanski.orknux.server.plugin

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * One page of a PDF, drawn as a PNG.
 *
 * ## Why a server does this
 *
 * An agent that writes a document cannot look at it. A PDF is bytes; a model
 * that can see reads pictures, and the two are different things in a request -
 * so an agent handed its own output in base64 knows only that it exists. It
 * says "the report is ready" because that is what it did, not because that is
 * what came out, and a document with its diagram off the page or its table cut
 * in half is reported as finished.
 *
 * This is the other half of [SvgRenderer], and it is there for the same reason:
 * the sandbox cannot do it. Rasterising a PDF means a font stack, a colour
 * model and a path renderer; there is no such thing in pure JavaScript worth
 * having, and GraalJS here has no WebAssembly to borrow one through.
 *
 * ## What it will draw
 *
 * One page at a time, of a document small enough to hold in memory, at a width
 * a screen would show. A PDF is an untrusted document format with an
 * embedded-file model and an encryption model, and PDFBox is a parser like any
 * other - so the bounds are here rather than assumed, and what arrives is
 * always something this installation made a moment ago.
 */
@Component
class PdfRenderer {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @param pdf the document itself.
     * @param page which page, counting from one - the way a person counts them,
     *   and the way an agent will say it.
     * @param width how wide the picture should be in pixels, or null for
     *   something a screen can read.
     */
    fun png(pdf: ByteArray, page: Int, width: Int?): Drawing {
        if (pdf.isEmpty()) return Drawing.Refused("there is nothing to draw: the pdf is empty")
        if (pdf.size > MOST_BYTES) {
            // The cap in megabytes, as the contract's table asks: the caller is
            // usually a model, and a number it can act on beats "too large".
            return Drawing.Refused("that pdf is larger than ${MOST_BYTES / (1024 * 1024)} MB, which is the most this draws")
        }
        if (page < 1) return Drawing.Refused("pages are counted from 1")
        if (width != null && (width < MOST_NARROW || width > MOST_WIDE)) {
            return Drawing.Refused("a width has to be between $MOST_NARROW and $MOST_WIDE pixels")
        }

        return try {
            Loader.loadPDF(pdf).use { document ->
                /*
                 * An encrypted document is refused rather than opened.
                 *
                 * PDFBox will decrypt one with an empty password, which is how
                 * most "protected" PDFs are made - and doing that quietly would
                 * mean this server stepping around a restriction somebody set.
                 * What reaches here is a document this installation made, so
                 * the honest answer is to say what it is.
                 */
                if (document.isEncrypted) {
                    return Drawing.Refused("that pdf is encrypted, and this does not open one")
                }
                if (page > document.numberOfPages) {
                    return Drawing.Refused(
                        "that pdf has ${document.numberOfPages} page(s), and page $page was asked for",
                    )
                }

                /*
                 * Sized by width rather than by dpi, because a width is what
                 * the caller can reason about: a model is shown a picture and
                 * the question is how much of the page it can read, not how
                 * many dots an inch of it has.
                 */
                val scale = if (width == null) {
                    // 96 dpi against the PDF's own 72, which is what
                    // RENDERING.md names for a call that asks for no width.
                    SCREEN_DPI / PDF_DPI
                } else {
                    val box = document.getPage(page - 1).cropBox
                    (width.toFloat() / box.width.coerceAtLeast(1f)).coerceIn(MOST_SHRUNK, MOST_BLOWN_UP)
                }

                val image = PDFRenderer(document).renderImage(page - 1, scale, ImageType.RGB)
                val out = ByteArrayOutputStream()
                ImageIO.write(image, "png", out)
                val bytes = out.toByteArray()

                if (bytes.isEmpty()) {
                    Drawing.Refused("the renderer produced nothing for page $page")
                } else {
                    Drawing.Drawn(bytes, image.width, image.height, document.numberOfPages)
                }
            }
        } catch (failure: OutOfMemoryError) {
            // A page past what the bounds caught. Reported rather than allowed
            // to take a request thread down with it.
            log.warn("a pdf page asked for more memory than this server would give it", failure)
            Drawing.Refused("that page asks for a picture too large to draw")
        } catch (failure: java.io.IOException) {
            /*
             * Said as what it is rather than in the parser's words, which the
             * contract's table asks for: "End-of-File, expected line at offset
             * 16" is PDFBox talking to itself, and the caller is usually a
             * model deciding what to do next.
             */
            log.warn("a pdf could not be read", failure)
            Drawing.Refused("that is not a pdf, or it is damaged: the document could not be read")
        } catch (failure: Exception) {
            log.warn("a pdf page could not be drawn", failure)
            Drawing.Refused("could not draw that pdf: ${failure.message ?: failure.javaClass.simpleName}")
        }
    }

    sealed interface Drawing {

        data class Drawn(val png: ByteArray, val width: Int, val height: Int, val pages: Int) : Drawing

        /** Said back to the caller as it stands, so it knows what to do differently. */
        data class Refused(val reason: String) : Drawing
    }

    private companion object {
        /**
         * As large a document as this holds in memory.
         *
         * The number `http.upload` already caps at, which RENDERING.md picks
         * on purpose: one bound to remember rather than two.
         */
        const val MOST_BYTES = 10 * 1024 * 1024

        /** What a page is drawn at when nobody asked for a width: 96 dpi. */
        const val SCREEN_DPI = 96f
        const val PDF_DPI = 72f

        const val MOST_NARROW = 64
        const val MOST_WIDE = 4096

        /**
         * And what the scale is held to whatever the page says it is.
         *
         * A PDF page can declare a crop box of almost any size, so a width in
         * pixels can ask for a scale of five hundred. These are the bounds on
         * the picture that comes out rather than on the number that went in.
         */
        const val MOST_SHRUNK = 0.05f
        const val MOST_BLOWN_UP = 8f
    }
}
