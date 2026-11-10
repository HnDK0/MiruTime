package dev.mirutime.app.features.book.data

import dev.mirutime.app.features.book.domain.BookChapter
import dev.mirutime.core.local.CbzFileReader
import dev.mirutime.core.log.MiruFileLogger
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookCbzExporterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 7)

    private class FakeTarget : CbzExportTarget {
        var folder: String? = null
        var metadata: ByteArray? = null
        val chapters = linkedMapOf<String, ByteArray>()

        override suspend fun beginFolder(suggestedName: String): String {
            folder = suggestedName
            return suggestedName
        }

        override suspend fun writeMetadata(folderName: String, xml: ByteArray) {
            metadata = xml
        }

        override suspend fun writeChapter(folderName: String, fileName: String, file: File) {
            chapters[fileName] = file.readBytes()
        }
    }

    private fun page(name: String): String {
        val file = temp.newFile(name)
        file.writeBytes(jpeg)
        return file.absolutePath
    }

    private fun chapter(index: Int, title: String, downloaded: Boolean = true) = BookChapter(
        index = index,
        title = title,
        isRead = false,
        downloaded = downloaded,
        translated = false,
        downloading = false,
    )

    private fun exporter(
        chapters: List<BookChapter>,
        pages: Map<Int, List<String>>,
        local: Boolean = false,
    ) = BookCbzExporter(
        isLocalBook = { local },
        bookInfo = { CbzBookInfo(title = "Моё перерождение", author = "Ann", genres = listOf("Action")) },
        chapters = { chapters },
        pagePaths = { _, index -> pages[index].orEmpty() },
        tempDir = temp.root,
    )

    @Test
    fun `downloaded chapters become one cbz each with metadata inside and out`() = runTest {
        val target = FakeTarget()
        val result = exporter(
            chapters = listOf(chapter(1, "Глава 1"), chapter(2, "Глава 2")),
            pages = mapOf(
                1 to listOf(page("1-1.jpg"), page("1-2.jpg")),
                2 to listOf(page("2-1.jpg")),
            ),
        ).export("book", target)

        assertEquals(CbzExportResult.Success(2, 0), result)
        assertEquals("Моё перерождение", target.folder)
        assertEquals(
            listOf("001 - Глава 1.cbz", "002 - Глава 2.cbz"),
            target.chapters.keys.toList(),
        )
        val first = CbzFileReader(
            temp.newFile("read-1.cbz").apply {
                writeBytes(target.chapters.getValue("001 - Глава 1.cbz"))
            },
        )
        first.use {
            assertEquals("Глава 1", it.title)
            assertEquals(2, it.imageNames.size)
        }
        val metadata = requireNotNull(target.metadata).decodeToString()
        assertTrue(metadata.contains("<Series>Моё перерождение</Series>"))
        assertTrue(metadata.contains("<Genre>Action</Genre>"))
    }

    @Test
    fun `chapters without local pages are skipped`() = runTest {
        val target = FakeTarget()
        val result = exporter(
            chapters = listOf(chapter(1, "Глава 1", downloaded = false), chapter(2, "Глава 2")),
            pages = mapOf(2 to listOf(page("2-1.jpg"))),
        ).export("book", target)

        assertEquals(CbzExportResult.Success(1, 1), result)
        assertEquals(listOf("002 - Глава 2.cbz"), target.chapters.keys.toList())
    }

    @Test
    fun `a chapter with a missing page is skipped whole`() = runTest {
        val target = FakeTarget()
        val result = exporter(
            chapters = listOf(chapter(1, "Глава 1"), chapter(2, "Глава 2")),
            pages = mapOf(
                1 to listOf(page("ok.jpg"), "/missing/page.jpg"),
                2 to listOf(page("2.jpg")),
            ),
        ).export("book", target)

        assertEquals(CbzExportResult.Success(1, 1), result)
        assertEquals(listOf("002 - Глава 2.cbz"), target.chapters.keys.toList())
    }

    @Test
    fun `a write failure is reported as Failure, not NoChapters`() = runTest {
        val target = object : CbzExportTarget {
            override suspend fun beginFolder(suggestedName: String) = suggestedName
            override suspend fun writeMetadata(folderName: String, xml: ByteArray) = Unit
            override suspend fun writeChapter(folderName: String, fileName: String, file: File) {
                error("SAF refused the document")
            }
        }

        val result = exporter(
            chapters = listOf(chapter(1, "Глава 1")),
            pages = mapOf(1 to listOf(page("1.jpg"))),
        ).export("book", target)

        assertTrue(result is CbzExportResult.Failure)
    }

    @Test
    fun `a skipped chapter is logged with its index and error type`() = runTest {
        val logDir = temp.newFolder("logs")
        MiruFileLogger.install(logDir)
        val target = object : CbzExportTarget {
            override suspend fun beginFolder(suggestedName: String) = suggestedName
            override suspend fun writeMetadata(folderName: String, xml: ByteArray) = Unit
            override suspend fun writeChapter(folderName: String, fileName: String, file: File) {
                if (fileName.startsWith("001")) error("SAF refused the document")
            }
        }

        val result = exporter(
            chapters = listOf(chapter(1, "Глава 1"), chapter(2, "Глава 2")),
            pages = mapOf(1 to listOf(page("1.jpg")), 2 to listOf(page("2.jpg"))),
        ).export("book", target)
        MiruFileLogger.flush()

        assertEquals(CbzExportResult.Success(1, 1), result)
        val log = MiruFileLogger.logFile(logDir).readText()
        assertTrue("skip reason must name the chapter: $log", log.contains("chapter=1"))
        assertTrue("skip reason must carry the cause: $log", log.contains("SAF refused the document"))
    }

    @Test
    fun `cbz export is enabled only for downloaded remote manga`() {
        assertTrue(cbzExportEnabled(isManga = true, isLocal = false, exportableCount = 1))
        assertFalse(cbzExportEnabled(isManga = true, isLocal = true, exportableCount = 1))
        assertFalse(cbzExportEnabled(isManga = false, isLocal = false, exportableCount = 1))
        assertFalse(cbzExportEnabled(isManga = true, isLocal = false, exportableCount = 0))
    }

    @Test
    fun `local books and empty sources export nothing`() = runTest {
        assertEquals(
            CbzExportResult.NoChapters,
            exporter(emptyList(), emptyMap(), local = true).export("book", FakeTarget()),
        )
        assertEquals(
            CbzExportResult.NoChapters,
            exporter(listOf(chapter(1, "Глава 1")), emptyMap()).export("book", FakeTarget()),
        )
    }
}
