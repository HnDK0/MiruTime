package dev.mirutime.app.features.library.data

import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.LibraryDao
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.ShelfEntity
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryUpdateServiceShelfTest {

    private fun book(id: Long, shelfId: String) = BookEntity(
        bookId = id,
        installId = "plugin",
        url = "https://site/book/$id",
        title = "Book $id",
        author = "Author",
        coverUrl = null,
        contentType = "Novel",
        rating = 0.0,
        shelfId = shelfId,
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

    private fun shelves() = listOf(
        ShelfEntity(id = "reading", label = "Reading", position = 0),
        ShelfEntity(
            id = "archive",
            label = "Archive",
            excludeFromUpdates = true,
            position = 1,
        ),
    )

    private fun libraryDao(
        books: List<BookEntity>,
        recorded: MutableList<Pair<Long, Int>> = mutableListOf(),
    ): LibraryDao =
        Proxy.newProxyInstance(
            LibraryDao::class.java.classLoader,
            arrayOf(LibraryDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "libraryBooks" -> books
                "shelves" -> shelves()
                "bookByUrl" -> books.firstOrNull { it.url == (args?.get(0) as? String) }
                "recordUpdateCheck" -> {
                    recorded += (args?.get(0) as Long) to (args?.get(1) as Int)
                    Unit
                }
                else -> throw AssertionError("Unexpected DAO call: ${method.name}")
            }
        } as LibraryDao

    private class StubProvider(
        private val result: Int? = 1,
        private val failure: Exception? = null,
    ) : LibraryBookRefreshPort {
        val refreshed = mutableListOf<String>()

        override suspend fun refreshChapterCount(bookUrl: String): Int? {
            refreshed += bookUrl
            failure?.let { throw it }
            return result
        }
    }

    @Test
    fun `books on an excluded shelf are skipped by checkAll and checkBook`() = runTest {
        val books = listOf(book(1, "reading"), book(2, "archive"))
        val dao = libraryDao(books)
        val provider = StubProvider()
        val service = LibraryUpdateService(
            provider = provider,
            library = LibraryRepository(dao),
            now = { 1_000L },
        )

        val summary = service.checkAll()

        assertEquals(1, summary.checked)
        assertEquals(listOf("https://site/book/1"), provider.refreshed)

        assertEquals(0, service.checkBook("https://site/book/2"))
        assertEquals(listOf("https://site/book/1"), provider.refreshed)
    }

    @Test
    fun `new chapters are recorded and reported`() = runTest {
        val books = listOf(book(1, "reading"))
        val recorded = mutableListOf<Pair<Long, Int>>()
        val service = LibraryUpdateService(
            provider = StubProvider(result = 3),
            library = LibraryRepository(libraryDao(books, recorded)),
            now = { 1_000L },
        )

        val summary = service.checkAll()

        assertEquals(UpdateCheckSummary(checked = 1, updated = 1, failed = 0), summary)
        assertEquals(listOf(1L to 3), recorded)
    }

    @Test
    fun `empty refresh result is a failure and is not recorded`() = runTest {
        val books = listOf(book(1, "reading"))
        val recorded = mutableListOf<Pair<Long, Int>>()
        val service = LibraryUpdateService(
            provider = StubProvider(result = null),
            library = LibraryRepository(libraryDao(books, recorded)),
            now = { 1_000L },
        )

        val summary = service.checkAll()

        assertEquals(UpdateCheckSummary(checked = 0, updated = 0, failed = 1), summary)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `throwing refresh is a failure and is not recorded`() = runTest {
        val books = listOf(book(1, "reading"))
        val recorded = mutableListOf<Pair<Long, Int>>()
        val service = LibraryUpdateService(
            provider = StubProvider(failure = IllegalStateException("boom")),
            library = LibraryRepository(libraryDao(books, recorded)),
            now = { 1_000L },
        )

        val summary = service.checkAll()

        assertEquals(UpdateCheckSummary(checked = 0, updated = 0, failed = 1), summary)
        assertTrue(recorded.isEmpty())
    }
}
