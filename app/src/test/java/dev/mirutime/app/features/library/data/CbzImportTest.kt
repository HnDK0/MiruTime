package dev.mirutime.app.features.library.data

import dev.mirutime.app.features.local.data.naturalSortKey
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.app.features.reader.document.mangaPages
import dev.mirutime.core.reader.html.ReaderHtmlParser
import dev.mirutime.core.host.FileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CbzImportTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class MapFileSystem : FileSystem {
        val files = mutableMapOf<String, ByteArray>()

        override fun read(path: String): ByteArray? = files[path]
        override fun write(path: String, data: ByteArray) {
            files[path] = data
        }
        override fun exists(path: String): Boolean = path in files
        override fun delete(path: String) {
            files.remove(path)
        }
        override fun rename(from: String, to: String) {
            files.remove(from)?.let { files[to] = it }
        }
        override fun list(dir: String): List<String> = emptyList()
    }

    @Test
    fun `three images stage as one natural ordered chapter`() {
        val fileSystem = MapFileSystem()
        val names = listOf("010.jpg", "002.jpg", "001.png")
            .sortedWith(compareBy(::naturalSortKey))
        val bytes = mapOf(
            "001.png" to byteArrayOf(1),
            "002.jpg" to byteArrayOf(2, 2),
            "010.jpg" to byteArrayOf(3, 3, 3),
        )

        val stage = stageCbzPages(
            bookDir = "local_books/local-manga/sha",
            imageNames = names,
            readPage = { bytes[it] },
            fileSystem = fileSystem,
        )
        val seed = cbzChapterSeed("local://book", "My Comic")

        assertEquals(listOf("001.png", "002.jpg", "010.jpg"), names)
        assertEquals(1, seed.index)
        assertEquals("local://book#chapter=1", seed.url)
        assertEquals("My Comic", seed.title)
        assertEquals(
            listOf(
                "local_books/local-manga/sha/chapters/1/00001.png",
                "local_books/local-manga/sha/chapters/1/00002.jpg",
                "local_books/local-manga/sha/chapters/1/00003.jpg",
            ),
            stage.paths,
        )
        assertEquals(6L, stage.totalBytes)
        assertTrue(stage.coverBytes!!.contentEquals(byteArrayOf(1)))
    }

    @Test
    fun `missing image fails and removes already staged pages`() {
        val fileSystem = MapFileSystem()
        var failed = false
        try {
            stageCbzPages(
                bookDir = "book",
                imageNames = listOf("1.jpg", "2.jpg", "3.jpg"),
                readPage = { if (it == "2.jpg") null else byteArrayOf(1) },
                fileSystem = fileSystem,
            )
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertTrue(failed)
        assertTrue(fileSystem.files.isEmpty())
    }

    @Test
    fun `empty cbz fails before writing anything`() {
        val fileSystem = MapFileSystem()
        var failed = false
        try {
            stageCbzPages("book", emptyList(), { byteArrayOf(1) }, fileSystem)
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)
        assertTrue(fileSystem.files.isEmpty())
    }

    @Test
    fun `three page html parses into three manga pages in order`() {
        val paths = listOf("chapters/1/00001.jpg", "chapters/1/00002.jpg", "chapters/1/00003.jpg")
        val html = pageImagesHtml(temp.root, paths)
        val chapterUrl = "local://book#chapter=1"
        val document = ReaderDocument(
            bookUrl = "local://book",
            chapterUrl = chapterUrl,
            chapterIndex = 1,
            title = "My Comic",
            blocks = ReaderHtmlParser.parseDocument(html, chapterUrl),
        )

        val pages = document.mangaPages()
        assertEquals(3, pages.size)
        assertEquals(listOf("00001.jpg", "00002.jpg", "00003.jpg"), pages.map { it.imageRef!!.substringAfterLast('/') })
    }
}
