package io.mszymanski.orknux.server.attachment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream

/**
 * What a file is served as, and what the browser is told it may do with it.
 *
 * The headers are the whole of the security here, so they are the thing under
 * test: a picture and a report are both handed back inline, and the reason
 * that is safe is `sandbox` rather than anything about the bytes. Everything
 * else is a download, which is the answer for a format nobody here has thought
 * about.
 */
class AttachmentDownloadsTest {

    private val store = mock(AttachmentStore::class.java)
    private val downloads = AttachmentDownloads(store)

    private fun served(contentType: String, filename: String = "thing", reading: Boolean = false) = run {
        `when`(store.open("where")).thenReturn(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        downloads.serve(
            filename = filename,
            contentType = contentType,
            sizeBytes = 3,
            location = "where",
            reading = reading,
        )
    }

    private fun headerOf(contentType: String, name: String, reading: Boolean = false): String =
        served(contentType, reading = reading).headers.getFirst(name).orEmpty()

    @Test
    fun `a picture is shown either way, because a page draws it in an img`() {
        assertThat(headerOf("image/png", "Content-Disposition")).startsWith("inline")
        assertThat(served("image/png").headers.contentType.toString()).isEqualTo("image/png")
    }

    /**
     * The one this exists for: an agent's report is a page somebody opens, not
     * a file they download and then open from their own machine - which is the
     * same HTML with more trust around it, not less.
     *
     * Only where the caller asked to read it. That ask is its own address -
     * see `SavedArtifactAPI.preview` - so an artifact's own link stays a
     * download whatever is inside it, and somebody sending that link to a
     * colleague is sending a file rather than a page.
     */
    @Test
    fun `a report an agent wrote is shown where somebody asked to read it`() {
        assertThat(headerOf("text/html", "Content-Disposition", reading = true)).startsWith("inline")
        assertThat(served("text/html", reading = true).headers.contentType.toString()).startsWith("text/html")
    }

    @Test
    fun `and is handed over as a file where nobody did`() {
        assertThat(headerOf("text/html", "Content-Disposition")).startsWith("attachment")
        assertThat(served("text/html").headers.contentType.toString()).isEqualTo("application/octet-stream")
    }

    @Test
    fun `and a document carries its charset without becoming a download`() {
        // The type arrives from whatever wrote the file, and a charset on it is
        // ordinary: `text/html; charset=utf-8` is the same kind as `text/html`.
        assertThat(headerOf("text/html; charset=utf-8", "Content-Disposition", reading = true))
            .startsWith("inline")
    }

    /**
     * Nothing runs and nothing is fetched, whatever the bytes are. This is the
     * assertion the inline answer rests on: without `sandbox` a page served
     * from our host is a page with our cookies.
     */
    @Test
    fun `a document lands in a sandbox with no script and no network`() {
        val policy = headerOf("text/html", "Content-Security-Policy", reading = true)

        assertThat(policy).contains("sandbox")
        assertThat(policy).contains("default-src 'none'")
        // What it may do is local and nothing else: its own inline styles, and
        // pictures it carried as data.
        assertThat(policy).contains("img-src data:")
        assertThat(policy).contains("style-src 'unsafe-inline'")
        assertThat(policy).doesNotContain("script-src")
        assertThat(policy).doesNotContain("https:")
    }

    @Test
    fun `a picture's policy stays as narrow as it was`() {
        val policy = headerOf("image/png", "Content-Security-Policy")

        assertThat(policy).isEqualTo("default-src 'none'; img-src 'self'; frame-ancestors 'self'; sandbox")
    }

    /**
     * And it may be framed by us, because that is how it is read.
     *
     * Spring Security writes `X-Frame-Options: DENY` on everything that does
     * not already carry the header, which is right for a page and wrong for
     * the one response whose whole purpose is to be opened inside a frame:
     * the reader got Chrome's "refused to connect" where the report should
     * have been. Both headers say it, because the old one is what an older
     * browser reads.
     */
    @Test
    fun `a document may be framed by this application, and by nobody else`() {
        assertThat(headerOf("text/html", "Content-Security-Policy", reading = true))
            .contains("frame-ancestors 'self'")
        assertThat(headerOf("text/html", "X-Frame-Options", reading = true)).isEqualTo("SAMEORIGIN")
        assertThat(headerOf("image/png", "X-Frame-Options")).isEqualTo("SAMEORIGIN")
    }

    /**
     * SVG is markup that a browser treats as an image where this header does
     * not reach - an `<img src>` on a page of ours, most of all - so it is
     * handed over as a file.
     */
    @Test
    fun `svg is a download, however image-like it looks`() {
        assertThat(headerOf("image/svg+xml", "Content-Disposition")).startsWith("attachment")
        assertThat(served("image/svg+xml").headers.contentType.toString())
            .isEqualTo("application/octet-stream")
    }

    @Test
    fun `anything nobody has thought about is a download`() {
        assertThat(headerOf("application/zip", "Content-Disposition")).startsWith("attachment")
        assertThat(headerOf("application/x-msdownload", "Content-Disposition")).startsWith("attachment")
    }

    @Test
    fun `a filename with a space and an accent survives the trip`() {
        val said = headerOf("image/png", "Content-Disposition").let { it }
        assertThat(said).contains("filename*=UTF-8''")

        val awkward = downloads.serve("zażółć gęślą.png", "image/png", 3, "where")
            .headers.getFirst("Content-Disposition").orEmpty()
        // Percent-encoded, and a space as %20 rather than the `+` a form uses.
        assertThat(awkward).contains("%20")
        assertThat(awkward).doesNotContain("+")
    }

    @Test
    fun `nosniff, so a document is read as what it says it is`() {
        assertThat(headerOf("text/html", "X-Content-Type-Options", reading = true)).isEqualTo("nosniff")
        assertThat(headerOf("application/zip", "X-Content-Type-Options")).isEqualTo("nosniff")
    }
}
