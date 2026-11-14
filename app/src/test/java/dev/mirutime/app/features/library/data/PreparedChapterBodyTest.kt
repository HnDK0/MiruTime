package dev.mirutime.app.features.library.data

import dev.mirutime.core.host.FileSystem
import dev.mirutime.core.local.LocalChapter
import dev.mirutime.core.local.LocalImage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// Import-time body persistence for EPUB/FB2, exercised with a fake FileSystem.
class PreparedChapterBodyTest {

    private class MapFileSystem : FileSystem {
        val files = linkedMapOf<String, ByteArray>()
        override fun read(path: String): ByteArray? = files[path]
        override fun write(path: String, data: ByteArray) {
            files[path] = data
        }
        override fun exists(path: String): Boolean = path in files
        override fun delete(path: String) {
            files.remove(path)
        }
        override fun rename(from: String, to: String) {
            files[to] = files.remove(from) ?: error("missing $from")
        }
        override fun list(dir: String): List<String> =
            files.keys.filter { it.startsWith("$dir/") }.sorted()
    }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)

    @Test
    fun `stream copy deletes its temp on failure and cancellation`() {
        val failed = File.createTempFile("mirutime-fail-", ".tmp").apply { deleteOnExit() }
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("boom")
        }
        try {
            runBlocking { streamToTemp(broken, failed, 1024) }
            fail("expected failure")
        } catch (_: IOException) {
            // expected
        }
        assertFalse(failed.exists())

        val cancelled = File.createTempFile("mirutime-cancel-", ".tmp").apply { deleteOnExit() }
        try {
            runBlocking {
                streamToTemp(ByteArrayInputStream(ByteArray(10)), cancelled, 1024) {
                    throw CancellationException("cancelled")
                }
            }
            fail("expected cancellation")
        } catch (_: CancellationException) {
            // expected
        }
        assertFalse(cancelled.exists())
    }

    @Test
    fun `stream copy over the cap throws with limit and actual size`() {
        val temp = File.createTempFile("mirutime-limit-", ".tmp").apply { deleteOnExit() }
        try {
            runBlocking { streamToTemp(ByteArrayInputStream(ByteArray(2048)), temp, 1024) }
            fail("expected size limit")
        } catch (e: ImportLimitException) {
            assertEquals(1024L, e.limitBytes)
            assertEquals(2048L, e.actualBytes)
        }
        assertFalse(temp.exists())
    }

    @Test
    fun `body html and inline image are written under the book directory`() {
        val fs = MapFileSystem()
        val chapter = LocalChapter(
            url = "fb2_0",
            title = "Chapter",
            body = "First paragraph.\n\nSecond paragraph.",
            images = listOf(LocalImage(position = 18, bytes = png, mediaType = "image/png")),
        )

        val prepared = prepareChapterBody(
            chapter = chapter,
            bodyKey = "7",
            chapterIndex = 1,
            bookDir = "local_books/local-books/sha",
            baseDir = File("/files"),
            fileSystem = fs,
        )

        assertEquals("local_books/local-books/sha/chapters/7.html", prepared.path)
        assertArrayEquals(png, fs.read("local_books/local-books/sha/images/1/1.png"))
        val html = fs.read(prepared.path)!!.decodeToString()
        assertTrue(html.contains("<p>First paragraph.</p>"))
        assertTrue(html.contains("<p><img src=\"file:/files/local_books/local-books/sha/images/1/1.png\"/></p>"))
        assertTrue(html.contains("<p>Second paragraph.</p>"))
        assertEquals(html.encodeToByteArray().size.toLong(), prepared.size)
        assertEquals(sha256Hex(html.encodeToByteArray()), prepared.sha256)
    }

    @Test
    fun `text-only body keeps the plain paragraph mapping and writes no images`() {
        val fs = MapFileSystem()
        val prepared = prepareChapterBody(
            chapter = LocalChapter("epub#ch1", "One", "One\n\nTwo"),
            bodyKey = "8",
            chapterIndex = 2,
            bookDir = "local_books/local-books/sha",
            baseDir = File("/files"),
            fileSystem = fs,
        )

        assertEquals("<p>One</p><p>Two</p>", fs.read(prepared.path)!!.decodeToString())
        assertFalse(fs.files.keys.any { "/images/" in it })
    }
}
