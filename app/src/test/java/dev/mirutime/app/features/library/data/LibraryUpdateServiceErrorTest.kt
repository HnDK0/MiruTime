package dev.mirutime.app.features.library.data

import dev.mirutime.core.api.PluginError
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.LibraryDao
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.ShelfEntity
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// A library update that failed inside the plugin must surface the typed error
// instead of a bare "failed" count with no reason.
class LibraryUpdateServiceErrorTest {

    private val networkError = PluginError.Network(
        cause = "network",
        status = 503,
        reason = "Service Unavailable",
        host = "site.example",
        path = "/book/1",
    )

    private fun book(id: Long) = BookEntity(
        bookId = id,
        installId = "freewebnovel",
        url = "https://site.example/book/$id",
        title = "Book $id",
        author = "Author",
        coverUrl = null,
        contentType = "Novel",
        rating = 0.0,
        shelfId = "reading",
        totalChapters = 1,
        inLibrary = true,
        addedAt = 1L,
        updatedAt = 1L,
        lastReadAt = null,
        lastChapterId = null,
        lastChapterIndex = null,
        chapterListHash = null,
        lastUpdateAt = null,
        genresJson = "[]",
    )

    private fun libraryDao(books: List<BookEntity>): LibraryDao =
        Proxy.newProxyInstance(
            LibraryDao::class.java.classLoader,
            arrayOf(LibraryDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "libraryBooks" -> books
                "shelves" -> listOf(ShelfEntity(id = "reading", label = "Reading", position = 0))
                "bookByUrl" -> books.firstOrNull { it.url == (args?.get(0) as? String) }
                "recordUpdateCheck" -> Unit
                else -> throw AssertionError("Unexpected DAO call: ${method.name}")
            }
        } as LibraryDao

    private class ErrorProvider(private val error: PluginError) : LibraryBookRefreshPort {
        override suspend fun refreshChapterCount(bookUrl: String): Int? = 0

        override suspend fun refreshChapterCountResult(bookUrl: String): RefreshResult =
            RefreshResult(count = null, error = error)
    }

    @Test
    fun `typed refresh failure becomes firstError`() = runTest {
        val service = LibraryUpdateService(
            provider = ErrorProvider(networkError),
            library = LibraryRepository(libraryDao(listOf(book(1)))),
            now = { 1_000L },
        )

        val summary = service.checkAll()

        assertEquals(1, summary.failed)
        assertEquals(networkError, summary.firstError)
        assertEquals("freewebnovel", summary.firstErrorSource)
    }

    @Test
    fun `successful check clears firstError`() = runTest {
        val service = LibraryUpdateService(
            provider = object : LibraryBookRefreshPort {
                override suspend fun refreshChapterCount(bookUrl: String): Int? = 2
            },
            library = LibraryRepository(libraryDao(listOf(book(1)))),
            now = { 1_000L },
        )

        val summary = service.checkAll()

        assertEquals(UpdateCheckSummary(checked = 1, updated = 1, failed = 0), summary)
        assertNull(summary.firstError)
    }
}
