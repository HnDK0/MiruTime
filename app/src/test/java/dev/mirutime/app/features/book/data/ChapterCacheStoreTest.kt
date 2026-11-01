package dev.mirutime.app.features.book.data

import dev.mirutime.app.features.downloads.data.LocalDownloadFileStore
import dev.mirutime.data.library.ChapterBodyEntity
import dev.mirutime.data.library.ChapterEntity
import dev.mirutime.data.library.ChapterPageEntity
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChapterCacheStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val bookUrl = "https://site/book"
    private val chapter = ChapterEntity(
        chapterId = 11,
        bookId = 7,
        idx = 1,
        url = "$bookUrl/1",
        title = "Chapter 1",
        updatedAt = 1,
    )

    private class FakeChapterCacheRepository(val chapter: ChapterEntity) : ChapterCacheRepository {
        val bodies = HashMap<Long, ChapterBodyEntity>()
        val pages = HashMap<Long, ChapterPageEntity>()
        var bodySaves = 0
        var pageSaves = 0

        override suspend fun chapter(bookUrl: String, chapterIndex: Int): ChapterEntity? =
            chapter.takeIf { it.idx == chapterIndex }

        override suspend fun chapterBody(chapterId: Long): ChapterBodyEntity? = bodies[chapterId]

        override suspend fun chapterPage(chapterId: Long): ChapterPageEntity? = pages[chapterId]

        override suspend fun saveChapterBody(body: ChapterBodyEntity) {
            bodySaves++
            bodies[body.chapterId] = body
        }

        override suspend fun saveChapterPage(page: ChapterPageEntity) {
            pageSaves++
            pages[page.chapterId] = page
        }
    }

    @Test
    fun `opening a chapter saves the body once and a reopen does not write again`() = runTest {
        val repo = FakeChapterCacheRepository(chapter)
        val files = LocalDownloadFileStore(temp.newFolder("downloads"))
        val cache = ChapterCacheStore(repo, files, now = { 123L })

        cache.saveText(bookUrl, 1, "<p>hello</p>")
        cache.saveText(bookUrl, 1, "<p>hello</p>")

        assertEquals(1, repo.bodySaves)
        assertNotNull(repo.bodies[11])
        val body = repo.bodies.getValue(11)
        assertTrue(File(body.path).isFile)
        assertEquals(123L, body.fetchedAt)
        assertEquals(File(body.path).length(), body.bytes)
    }

    @Test
    fun `an existing download body is never overwritten on open`() = runTest {
        val repo = FakeChapterCacheRepository(chapter)
        val files = LocalDownloadFileStore(temp.newFolder("downloads"))
        val stored = files.writeText(chapter.bookId, chapter.chapterId, "<p>from download</p>")
        repo.bodies[11] = ChapterBodyEntity(
            chapterId = 11,
            format = "html",
            path = stored.path,
            bytes = stored.bytes,
            sha256 = stored.sha256,
            fetchedAt = 5,
        )

        ChapterCacheStore(repo, files).saveText(bookUrl, 1, "<p>from reader</p>")

        assertEquals(0, repo.bodySaves)
        assertEquals("<p>from download</p>", File(stored.path).readText())
    }

    @Test
    fun `page cache downloads once and sums page bytes`() = runTest {
        val repo = FakeChapterCacheRepository(chapter)
        val files = LocalDownloadFileStore(temp.newFolder("downloads"))
        val cache = ChapterCacheStore(repo, files)
        val urls = listOf("$bookUrl/1.jpg", "$bookUrl/2.png")
        var fetches = 0

        val first = cache.savePages(bookUrl, 1, urls) {
            fetches++
            byteArrayOf(1, 2, 3)
        }
        val second = cache.savePages(bookUrl, 1, urls) {
            fetches++
            byteArrayOf(1, 2, 3)
        }

        assertTrue(first)
        assertTrue(second)
        assertEquals(2, fetches)
        assertEquals(1, repo.pageSaves)
        assertNotNull(repo.pages[11])
        assertEquals(6L, repo.pages.getValue(11).totalBytes)
    }

    @Test
    fun `a missing chapter row is ignored instead of failing the read`() = runTest {
        val repo = FakeChapterCacheRepository(chapter.copy(idx = 2))
        val files = LocalDownloadFileStore(temp.newFolder("downloads"))

        ChapterCacheStore(repo, files).saveText(bookUrl, 1, "<p>hello</p>")

        assertEquals(0, repo.bodySaves)
        assertNull(repo.bodies[11])
    }
}
