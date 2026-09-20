package io.mszymanski.orknux.server.plugin

import org.apache.batik.transcoder.SVGAbstractTranscoder
import org.apache.batik.transcoder.TranscoderException
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.PNGTranscoder
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.io.StringReader

/**
 * An SVG, drawn as a PNG.
 *
 * The server does this because the sandbox cannot: GraalJS here has no
 * WebAssembly at all — `typeof WebAssembly` is `undefined` and only the `js`
 * language is installed, which was checked rather than assumed — so the usual
 * answer of bundling a WASM rasteriser is not available to a plugin. Nor is
 * there a pure-JavaScript one worth having: drawing SVG means a full 2D engine,
 * path filling and font rendering.
 *
 * ## What is switched off, and why it is switched off here
 *
 * SVG is not a picture format. It is a document with a scripting model, an
 * external-reference model and an entity model, and a renderer that honours all
 * three is a program that fetches what the document tells it to fetch and runs
 * what the document tells it to run. Everything below is set explicitly rather
 * than left to the library's defaults, because those have moved between
 * versions and the one thing this must not do is change behaviour when the
 * dependency is bumped.
 *
 * What a plugin hands over is a diagram it drew a moment ago, which needs none
 * of it. The guards are for the day something hands over a document from
 * somewhere else.
 */
@Component
class SvgRenderer {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @param svg the document, as text.
     * @param width how wide the picture should be in pixels, or null for the
     *   size the document itself declares.
     */
    fun png(svg: String, width: Int?): Drawing {
        if (svg.isBlank()) return Drawing.Refused("there is nothing to draw: the svg is empty")
        if (svg.length > MOST_SOURCE) {
            return Drawing.Refused("that svg is ${svg.length} characters, and $MOST_SOURCE is the most this draws")
        }

        /*
         * A shape is not proof, and it is not meant to be. What refuses a
         * document that is not an SVG is the parser; this is here so the
         * common mistake - handing over base64, or a whole HTML page - is
         * answered in a sentence rather than as a parse error forty lines long.
         */
        if (!svg.trimStart().let { it.startsWith("<svg") || it.startsWith("<?xml") || it.startsWith("<!") }) {
            return Drawing.Refused("that does not look like svg: it has to be the markup itself, not base64 or a url")
        }

        if (width != null && (width < 1 || width > MOST_WIDTH)) {
            return Drawing.Refused("a width has to be between 1 and $MOST_WIDTH pixels")
        }

        val out = ByteArrayOutputStream()
        val transcoder = PNGTranscoder().apply {
            /*
             * No scripts, and nothing fetched.
             *
             * Between them these are the whole of what makes rendering somebody
             * else's SVG dangerous: a document that runs code, and a document
             * that makes this server issue requests of its choosing - to an
             * intranet address, or to a file:// url that reads the disk. Off,
             * by name, both of them.
             */
            addTranscodingHint(SVGAbstractTranscoder.KEY_ALLOW_EXTERNAL_RESOURCES, false)
            addTranscodingHint(SVGAbstractTranscoder.KEY_CONSTRAIN_SCRIPT_ORIGIN, true)
            addTranscodingHint(SVGAbstractTranscoder.KEY_EXECUTE_ONLOAD, false)

            // A ceiling on what comes out, not only on what goes in: a small
            // document can declare an enormous canvas, and the bytes for it are
            // this server's memory.
            if (width != null) {
                addTranscodingHint(SVGAbstractTranscoder.KEY_WIDTH, width.toFloat())
            }
            addTranscodingHint(SVGAbstractTranscoder.KEY_MAX_WIDTH, MOST_WIDTH.toFloat())
            addTranscodingHint(SVGAbstractTranscoder.KEY_MAX_HEIGHT, MOST_HEIGHT.toFloat())
        }

        return try {
            transcoder.transcode(TranscoderInput(StringReader(svg)), TranscoderOutput(out))
            val bytes = out.toByteArray()
            if (bytes.isEmpty()) {
                Drawing.Refused("the renderer produced nothing; the svg may declare no size")
            } else {
                Drawing.Drawn(bytes)
            }
        } catch (failure: TranscoderException) {
            /*
             * The library's own sentence, and its cause where it has one.
             *
             * A TranscoderException often carries no message of its own - what
             * went wrong is on the exception it wraps - so reporting only the
             * top of the chain says "could not draw that svg: null", which
             * tells nobody anything. The cause is where the element and the
             * attribute are named.
             */
            val said = failure.message
                ?: failure.exception?.let { "${it.javaClass.simpleName}: ${it.message}" }
                ?: failure.cause?.let { "${it.javaClass.simpleName}: ${it.message}" }
                ?: "the document could not be read"
            log.warn("an svg could not be drawn: {}", said, failure)
            Drawing.Refused("could not draw that svg: $said")
        } catch (failure: OutOfMemoryError) {
            // A canvas past what the ceilings caught. Reported rather than
            // allowed to take a request thread down with it.
            log.warn("an svg asked for more memory than this server would give it", failure)
            Drawing.Refused("that svg asks for a picture too large to draw")
        } catch (failure: Exception) {
            log.warn("an svg could not be drawn", failure)
            Drawing.Refused("could not draw that svg: ${failure.message ?: failure.javaClass.simpleName}")
        }
    }

    sealed interface Drawing {
        data class Drawn(val png: ByteArray) : Drawing

        /** Said back to the caller as it stands, so it knows what to do differently. */
        data class Refused(val reason: String) : Drawing
    }

    private companion object {
        /** As long a document as this draws. A diagram, not a map. */
        const val MOST_SOURCE = 2 * 1024 * 1024

        const val MOST_WIDTH = 4096
        const val MOST_HEIGHT = 4096
    }
}
