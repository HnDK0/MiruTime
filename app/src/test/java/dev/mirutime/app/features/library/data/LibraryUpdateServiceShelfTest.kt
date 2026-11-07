package dev.mirutime.app.features.library.data

import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.LibraryDao
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.ShelfEntity
import dev.mirutime.data.library.bookKeyUrl
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryUpdateServiceShelfTest {

    private fun book(id: Long, shelfId: String, total: Int = 1) = BookEntity(
        bookId = id,
        installId = "plugin",
        url = "https://site/book/$id",
        title = "Book $id",
        author = "Author",
        coverUrl = null,
        contentType = "Novel",
        rating = 0.0,
        shelfId = shelfId,
        totalChapters = total,
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
        assertEquals(
            listOf("https://site/book/1"),
            provider.refreshed.map(::bookKeyUrl),
        )

        assertEquals(0, service.checkBook("https://site/book/2"))
        assertEquals(
            listOf("https://site/book/1"),
            provider.refreshed.map(::bookKeyUrl),
        )
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

    @Test
    fun `overlapping runs record the new chapters once`() = runTest {
        // The badge accumulates deltas, so two overlapping runs must not both
        // count the same new chapters against the same old total.
        val books = mutableMapOf(1L to book(1, "reading", total = 100))
        val badges = mutableMapOf<Long, Int>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var refreshes = 0
        val dao = Proxy.newProxyInstance(
            LibraryDao::class.java.classLoader,
            arrayOf(LibraryDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "libraryBooks" -> books.values.toList()
                "shelves" -> shelves()
                "recordUpdateCheck" -> {
                    val id = args!![0] as Long
                    val total = args[1] as Int
                    val delta = args[2] as Int
                    badges[id] = (badges[id] ?: 0) + delta
                    books[id] = books.getValue(id).copy(totalChapters = total)
                    Unit
                }
                else -> throw AssertionError("Unexpected DAO call: ${method.name}")
            }
        } as LibraryDao
        val provider = object : LibraryBookRefreshPort {
            override suspend fun refreshChapterCount(bookUrl: String): Int? {
                refreshes++
                entered.complete(Unit)
                release.await()
                return 110
            }
        }
        val service = LibraryUpdateService(
            provider = provider,
            library = LibraryRepository(dao),
            now = { 1_000L },
        )

        val first = async { service.checkAll() }
        entered.await()
        val second = async { service.checkAll() }
        // Let the second run reach the gate while the first still holds it.
        yield()
        release.complete(Unit)
        first.await()
        second.await()

        // Without the gate, both runs read 100 and add 10: the badge would be 20.
        assertEquals(10, badges[1])
        assertEquals(2, refreshes)
    }
}
