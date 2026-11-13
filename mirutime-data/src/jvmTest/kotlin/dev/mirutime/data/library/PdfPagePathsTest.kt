package dev.mirutime.data.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// PDF chapter pages are `pdfpage://<bookId>/<index>` references. A restore
// remaps book ids, so the embedded id must follow while the page order stays.
class PdfPagePathsTest {

    @Test
    fun `only a pdfpage reference counts as a reference`() {
        assertTrue(isPdfPageReference("pdfpage://7/0"))
        assertFalse(isPdfPageReference("local_books/local-books/sha/chapters/1/00001.jpg"))
        assertFalse(isPdfPageReference("file:///books/1.jpg"))
        assertFalse(isPdfPageReference(""))
    }

    @Test
    fun `a remap rewrites the book id and keeps every index`() {
        val escaped = """["pdfpage:\/\/22\/0","pdfpage:\/\/22\/1","pdfpage:\/\/22\/2"]"""

        val remapped = remapPdfPagePaths(escaped, oldBookId = 22, newBookId = 5)

        assertEquals(
            """["pdfpage:\/\/5\/0","pdfpage:\/\/5\/1","pdfpage:\/\/5\/2"]""",
            remapped,
        )
    }

    @Test
    fun `an already unescaped array is rewritten too`() {
        val plain = """["pdfpage://22/0","pdfpage://22/11"]"""

        assertEquals(
            """["pdfpage://5/0","pdfpage://5/11"]""",
            remapPdfPagePaths(plain, oldBookId = 22, newBookId = 5),
        )
    }

    @Test
    fun `a similar book id is not touched`() {
        val plain = """["pdfpage://222/0"]"""

        assertEquals(plain, remapPdfPagePaths(plain, oldBookId = 22, newBookId = 5))
    }

    @Test
    fun `image rows and an unchanged id are returned as-is`() {
        val images = """["chapters/1/00001.jpg","chapters/1/00002.jpg"]"""

        assertEquals(images, remapPdfPagePaths(images, oldBookId = 1, newBookId = 2))
        assertEquals(
            """["pdfpage://22/0"]""",
            remapPdfPagePaths("""["pdfpage://22/0"]""", oldBookId = 22, newBookId = 22),
        )
    }
}
