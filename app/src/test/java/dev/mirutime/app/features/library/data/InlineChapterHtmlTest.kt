package dev.mirutime.app.features.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Pure interleaving used by LocalBookProvider for EPUB/FB2 inline images.
class InlineChapterHtmlTest {

    @Test
    fun `image position splits text paragraphs without losing either side`() {
        val body = "First paragraph.\n\nSecond paragraph."
        val html = interleaveChapterText(
            body,
            listOf(InlinePage(position = 18, url = "file:///books/7/images/1/1.png")),
        )
        assertEquals(
            "<p>First paragraph.</p>" +
                "<p><img src=\"file:///books/7/images/1/1.png\"/></p>" +
                "<p>Second paragraph.</p>",
            html,
        )
    }

    @Test
    fun `text-only chapters keep the existing paragraph mapping`() {
        assertEquals(
            "<p>One</p><p>Two</p>",
            interleaveChapterText("One\n\nTwo", emptyList()),
        )
    }

    @Test
    fun `image urls are attribute-escaped and extension follows magic bytes`() {        val html = interleaveChapterText(
            "",
            listOf(InlinePage(0, "file:///a%20b&c.png")),
        )
        assertTrue(html.contains("a%20b&amp;c.png"))

        assertEquals("png", imageExtension(null, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
        assertEquals("jpg", imageExtension(null, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertEquals("webp", imageExtension("image/webp", ByteArray(0)))
    }

    @Test
    fun `only a pdf page reference skips the book-directory resolution`() {
        val html = pageImagesHtml(
            java.io.File("/data/books"),
            listOf("pdfpage://7/0", "chapters/1/00002.jpg"),
        )
        assertEquals(
            "<p><img src=\"pdfpage://7/0\"/></p>" +
                "<p><img src=\"file:/data/books/chapters/1/00002.jpg\"/></p>",
            html,
        )
    }
}
