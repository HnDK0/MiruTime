package dev.mirutime.app.features.reader.data

import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaPageFileCacheTest {
    private fun tempDir(): File = kotlin.io.path.createTempDirectory("manga-pages").toFile()

    @Test
    fun `a page is fetched once and then served from disk`() = runTest {
        var calls = 0
        val cache = MangaPageFileCache(
            directory = tempDir(),
            fetchRemote = { _, _ ->
                calls++
                byteArrayOf(1, 2, 3)
            },
            headersFor = { emptyMap() },
        )

        val first = cache.file(PAGE)
        val second = cache.file(PAGE)

        assertNotNull(first)
        assertEquals(first, second)
        assertEquals(1, calls)
        assertTrue(first!!.isFile)
    }

    @Test
    fun `concurrent requests for one page share a single fetch`() = runTest {
        var calls = 0
        val cache = MangaPageFileCache(
            directory = tempDir(),
            fetchRemote = { _, _ ->
                calls++
                byteArrayOf(9, 9)
            },
            headersFor = { emptyMap() },
        )

        val results = (1..4).map { async { cache.file(PAGE) } }.awaitAll()

        assertTrue(results.all { it != null })
        assertEquals(1, calls)
    }

    @Test
    fun `different urls never share a file`() = runTest {
        val cache = MangaPageFileCache(
            directory = tempDir(),
            fetchRemote = { _, _ -> byteArrayOf(5) },
            headersFor = { emptyMap() },
        )

        val first = cache.file("https://cdn.test/a/page.jpg")
        val second = cache.file("https://cdn.test/b/page.jpg")

        assertNotNull(first)
        assertNotNull(second)
        assertTrue(first != second)
    }

    @Test
    fun `a failed fetch leaves no file behind`() = runTest {
        val directory = tempDir()
        val cache = MangaPageFileCache(
            directory = directory,
            fetchRemote = { _, _ -> null },
            headersFor = { emptyMap() },
        )

        assertNull(cache.file(PAGE))
        assertEquals(0, directory.listFiles().orEmpty().size)
    }

    @Test
    fun `the byte budget evicts the oldest page`() = runTest {
        val directory = tempDir()
        val cache = MangaPageFileCache(
            directory = directory,
            fetchRemote = { _, _ -> ByteArray(10) },
            headersFor = { emptyMap() },
            maxBytes = 15,
        )

        val oldest = cache.file("https://cdn.test/old.jpg")
        Thread.sleep(5)
        val newest = cache.file("https://cdn.test/new.jpg")

        assertNotNull(oldest)
        assertNotNull(newest)
        assertFalse(oldest!!.exists())
        assertTrue(newest!!.exists())
    }

    @Test
    fun `a local page is served from disk and never copied into the cache`() = runTest {
        val directory = tempDir()
        val root = tempDir()
        val page = File(root, "local_books/local-manga/x/pages/1/001.png").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val cache = MangaPageFileCache(
            directory = directory,
            fetchRemote = { _, _ -> null },
            headersFor = { emptyMap() },
            localRoots = listOf(File(root, "local_books")),
        )

        val file = cache.file(page.toURI().toString())

        assertNotNull(file)
        assertEquals(page.canonicalFile, file!!.canonicalFile)
        assertEquals(0, directory.listFiles().orEmpty().size)
        // A re-downloaded chapter must show its new page, not the old copy.
        page.writeBytes(byteArrayOf(4, 5))
        assertArrayEquals(byteArrayOf(4, 5), cache.file(page.toURI().toString())!!.readBytes())
    }

    @Test
    fun `a local file outside the allowed roots is not served`() = runTest {
        val directory = tempDir()
        val root = tempDir()
        val outside = File(root, "elsewhere/001.png").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val cache = MangaPageFileCache(
            directory = directory,
            fetchRemote = { _, _ -> byteArrayOf(9) },
            headersFor = { emptyMap() },
            localRoots = listOf(File(root, "local_books")),
        )

        assertNull(cache.file(outside.toURI().toString()))
        assertEquals(0, directory.listFiles().orEmpty().size)
    }

    private companion object {
        const val PAGE = "https://cdn.test/page.jpg"
    }
}
