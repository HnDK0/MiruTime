package dev.mirutime.core.local

import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CbzExporterTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 3)

    private fun entryNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names += entry.name
                entry = zip.nextEntry
            }
        }
        return names
    }

    @Test
    fun `writes pages in order and reads back through the parser`() {
        val output = ByteArrayOutputStream()
        writeCbz(
            output = output,
            pages = listOf(CbzPage("001.jpg", jpeg), CbzPage("002.png", png)),
            comicInfo = ComicInfoMeta(title = "Book", summary = "Summary"),
            number = 2,
            pageCount = 2,
        )
        val bytes = output.toByteArray()

        assertEquals(listOf("ComicInfo.xml", "001.jpg", "002.png"), entryNames(bytes))
        val book = parseCbz(bytes)
        assertEquals("Book", book.title)
        assertEquals("Summary", book.description)
        assertEquals(2, book.chapters.size)
        assertEquals("001", book.chapters.first().title)
    }

    @Test
    fun `comic info carries series volume number and page count`() {
        val xml = buildComicInfoXml(
            meta = ComicInfoMeta(
                title = "Chapter 1",
                series = "Series",
                volume = "2",
                summary = "Summary",
                author = "Writer",
                artist = "Artist",
                genres = listOf("Action", "Fantasy"),
            ),
            pageCount = 12,
            number = 1,
        ).decodeToString()

        assertTrue(xml.contains("<Title>Chapter 1</Title>"))
        assertTrue(xml.contains("<Series>Series</Series>"))
        assertTrue(xml.contains("<Volume>2</Volume>"))
        assertTrue(xml.contains("<Number>1</Number>"))
        assertTrue(xml.contains("<PageCount>12</PageCount>"))
        assertTrue(xml.contains("<Writer>Writer</Writer>"))
        assertTrue(xml.contains("<Penciller>Artist</Penciller>"))
        assertTrue(xml.contains("<Genre>Action, Fantasy</Genre>"))
        assertTrue(xml.contains("<Manga>Yes</Manga>"))
    }

    @Test
    fun `empty pages are skipped`() {
        val output = ByteArrayOutputStream()
        writeCbz(
            output = output,
            pages = listOf(CbzPage("001.jpg", jpeg), CbzPage("002.jpg", ByteArray(0))),
            comicInfo = null,
        )

        assertEquals(listOf("001.jpg"), entryNames(output.toByteArray()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty page list is rejected`() {
        writeCbz(ByteArrayOutputStream(), emptyList(), comicInfo = null)
    }

    @Test
    fun `xml special characters are escaped`() {
        val xml = buildComicInfoXml(
            meta = ComicInfoMeta(series = "A & B <C>"),
            pageCount = 1,
        ).decodeToString()

        assertTrue(xml.contains("<Series>A &amp; B &lt;C&gt;</Series>"))
    }
}
