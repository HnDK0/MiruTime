package dev.mirutime.app.features.reader.data

import dev.mirutime.data.library.LocalBookFileEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfPageFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val pdfBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46)
    private val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)

    @Test
    fun `a page reference parses into a book and page index`() {
        assertEquals(PdfPageRef(42, 0), parsePdfPageUrl("pdfpage://42/0"))
        assertEquals(PdfPageRef(7, 12), parsePdfPageUrl("pdfpage://7/12"))
    }

    @Test
    fun `malformed references are rejected`() {
        assertNull(parsePdfPageUrl(""))
        assertNull(parsePdfPageUrl("pdfpage://"))
        assertNull(parsePdfPageUrl("pdfpage://0/1"))
        assertNull(parsePdfPageUrl("pdfpage://7/-1"))
        assertNull(parsePdfPageUrl("pdfpage://7/1/2"))
        assertNull(parsePdfPageUrl("pdfpage://seven/1"))
        assertNull(parsePdfPageUrl("file:///books/1.jpg"))
        assertNull(parsePdfPageUrl("https://c.test/page.jpg"))
    }

    @Test
    fun `the generated reference round-trips`() {
        assertEquals(PdfPageRef(9, 3), parsePdfPageUrl(pdfPageUrl(9, 3)))
    }

    @Test
    fun `page refs are ordered and capped`() {
        assertEquals(
            listOf("pdfpage://5/0", "pdfpage://5/1", "pdfpage://5/2"),
            pdfPageRefs(5, 3),
        )
        assertEquals(MaxPdfPages, pdfPageRefs(5, MaxPdfPages + 10).size)
        assertEquals(emptyList<String>(), pdfPageRefs(5, 0))
        assertEquals(emptyList<String>(), pdfPageRefs(5, -1))
    }

    @Test
    fun `a cached page is served without rendering`() = runBlocking {
        val files = fixture()
        val cached = File(files.root, "7/0.jpg").apply {
            parentFile.mkdirs()
            writeBytes(jpegBytes)
        }
        var renders = 0
        val cache = cache(files, render = { _, _, _, _ -> renders++; true })

        assertEquals(cached, cache.file("pdfpage://7/0"))
        assertEquals(0, renders)
    }

    @Test
    fun `a missing page renders into the cache and is returned`() = runBlocking {
        val files = fixture()
        var width = 0
        val cache = cache(files, render = { pdf, index, widthPx, out ->
            assertEquals(files.pdf, pdf)
            assertEquals(2, index)
            width = widthPx
            out.writeBytes(jpegBytes)
            true
        })

        val page = cache.file("pdfpage://7/2")

        assertEquals(File(files.root, "7/2.jpg"), page)
        assertEquals(jpegBytes.size.toLong(), page?.length())
        assertArrayEquals(jpegBytes, page?.readBytes())
        assertEquals(1600, width)
        assertFalse("a temp file must be renamed away", File(files.root, "7/2.jpg.tmp").exists())
    }

    @Test
    fun `a failed render returns null and leaves no file behind`() = runBlocking {
        val files = fixture()
        val cache = cache(files, render = { _, _, _, out ->
            // A renderer that wrote a partial file must not leave it visible.
            out.writeBytes(jpegBytes)
            false
        })

        assertNull(cache.file("pdfpage://7/1"))
        assertFalse(File(files.root, "7/1.jpg").exists())
        assertFalse(File(files.root, "7/1.jpg.tmp").exists())
    }

    @Test
    fun `a render that produces no jpeg is not cached`() = runBlocking {
        val files = fixture()
        val cache = cache(files, render = { _, _, _, out ->
            out.writeBytes(byteArrayOf(0, 0, 0))
            true
        })

        assertNull(cache.file("pdfpage://7/1"))
        assertFalse(File(files.root, "7/1.jpg").exists())
    }

    @Test
    fun `a non-pdf local row is never rendered`() = runBlocking {
        val files = fixture(format = "EPUB")
        var renders = 0
        val cache = cache(files, render = { _, _, _, _ -> renders++; true })

        assertNull(cache.file("pdfpage://7/0"))
        assertEquals(0, renders)
    }

    @Test
    fun `an unknown book is not rendered`() = runBlocking {
        val files = fixture(row = null)
        var renders = 0
        val cache = cache(files, render = { _, _, _, _ -> renders++; true })

        assertNull(cache.file("pdfpage://7/0"))
        assertEquals(0, renders)
    }

    @Test
    fun `eviction drops the least recently used pages above the budget`() = runBlocking {
        val files = fixture()
        val old = File(files.root, "7/0.jpg").apply {
            parentFile.mkdirs()
            writeBytes(jpegBytes)
            setLastModified(1_000L)
        }
        File(files.root, "7/1.jpg").apply {
            writeBytes(jpegBytes)
            setLastModified(2_000L)
        }
        // Budget fits one page: the older cached page goes first.
        val cache = cache(files, render = { _, _, _, out -> out.writeBytes(jpegBytes); true }, maxBytes = 4)

        assertEquals(File(files.root, "7/2.jpg"), cache.file("pdfpage://7/2"))
        assertFalse(old.exists())
    }

    private class Fixture(val row: LocalBookFileEntity?, val pdf: File, val root: File)

    private fun fixture(
        row: LocalBookFileEntity? = bookRow(),
        format: String = "PDF",
    ): Fixture {
        val pdf = temp.newFile("book.pdf").apply { writeBytes(pdfBytes) }
        return Fixture(row?.copy(format = format, copyPath = pdf.name), pdf, File(temp.root, "pdf_pages"))
    }

    private fun cache(
        files: Fixture,
        render: suspend (File, Int, Int, File) -> Boolean,
        maxBytes: Long = 256L * 1024 * 1024,
    ) = PdfPageFiles(
        localBookFile = { files.row },
        baseDir = temp.root,
        root = files.root,
        render = render,
        maxBytes = maxBytes,
    )

    private companion object {
        fun bookRow() = LocalBookFileEntity(
            bookId = 7,
            uri = "file:///book.pdf",
            displayName = "book.pdf",
            format = "PDF",
            mime = "application/pdf",
            sizeBytes = 4,
            sha256 = "sha",
            copyPath = "book.pdf",
            importedAt = 1,
        )
    }
}
