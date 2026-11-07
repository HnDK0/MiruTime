package dev.mirutime.app.features.library.data

import dev.mirutime.app.features.dashboard.data.RoomDashboardDataProvider
import dev.mirutime.data.library.BookCoverEntity
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.BookReadStatsRow
import dev.mirutime.data.library.BookTagNameRow
import dev.mirutime.data.library.LibraryDao
import dev.mirutime.data.library.LibraryRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Counting DAO stand-in: the provider may only call the batch methods once,
// and any per-book readChapterIndexes/tagsForBook attempt fails the test.
class RoomBatchReadsTest {

    private class CountingDao(
        val books: List<BookEntity>,
        val stats: List<BookReadStatsRow>,
        val tags: List<BookTagNameRow>,
        val covers: List<BookCoverEntity>,
    ) {
        var libraryBooksCalls = 0
        var historyCalls = 0
        var searchCalls = 0
        var statsCalls = 0
        var tagsCalls = 0
        var coverCalls = 0
        var unfinishedCalls = 0
        var perBookCalls = 0
        var fullTableCalls = 0
        val statsIds = mutableListOf<List<Long>>()
        val tagsIds = mutableListOf<List<Long>>()
        val coverIds = mutableListOf<List<Long>>()

        val proxy: LibraryDao = Proxy.newProxyInstance(
            LibraryDao::class.java.classLoader,
            arrayOf(LibraryDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "libraryBooks" -> {
                    libraryBooksCalls++
                    books
                }
                "history" -> {
                    historyCalls++
                    books.take(10)
                }
                "searchBooks" -> {
                    searchCalls++
                    books.take(10)
                }
                "readChapterStats" -> {
                    fullTableCalls++
                    stats
                }
                "readChapterStatsFor" -> {
                    statsCalls++
                    val ids = (args!!.first() as List<*>).filterIsInstance<Long>()
                    statsIds += listOf(ids)
                    stats.filter { it.bookId in ids }
                }
                "tagsByBook" -> {
                    fullTableCalls++
                    tags
                }
                "tagsByBookFor" -> {
                    tagsCalls++
                    val ids = (args!!.first() as List<*>).filterIsInstance<Long>()
                    tagsIds += listOf(ids)
                    tags.filter { it.bookId in ids }
                }
                "bookCovers" -> {
                    fullTableCalls++
                    covers
                }
                "bookCoversFor" -> {
                    coverCalls++
                    val ids = (args!!.first() as List<*>).filterIsInstance<Long>()
                    coverIds += listOf(ids)
                    covers.filter { it.bookId in ids }
                }
                "unfinishedLibraryBooks" -> {
                    unfinishedCalls++
                    books.take(4)
                }
                "readChapterIndexes", "tagsForBook" ->
                    throw AssertionError("Per-book query used: ${method.name}")
                else -> throw AssertionError("Unexpected DAO call: ${method.name}")
            }
        } as LibraryDao
    }

    private fun books() = (1..120).map { index ->
        BookEntity(
            bookId = index.toLong(),
            installId = "plugin",
            url = "https://site/book/$index",
            title = "Book $index",
            author = "Author",
            coverUrl = "legacy-$index",
            contentType = "Novel",
            rating = 0.0,
            shelfId = "reading",
            totalChapters = 10,
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
    }

    private fun stats(books: List<BookEntity>) = books.map { book ->
        BookReadStatsRow(
            bookId = book.bookId,
            readCount = (book.bookId % 10).toInt(),
            maxReadIndex = (book.bookId % 10).toInt(),
        )
    }

    private fun tags(books: List<BookEntity>) = books.map { book ->
        BookTagNameRow(bookId = book.bookId, name = "tag-${book.bookId}")
    }

    @Test
    fun `library provider maps 120 books with one batch call each`() = runTest {
        val rows = books()
        val dao = CountingDao(
            books = rows,
            stats = stats(rows),
            tags = tags(rows),
            covers = listOf(
                BookCoverEntity(bookId = 1, remoteUrl = "https://cdn.test/one.jpg"),
            ),
        )
        val provider = RoomLibraryDataProvider(
            library = LibraryRepository(dao.proxy),
            now = { 1_000L },
        )

        val mapped = provider.books()

        assertEquals(120, mapped.size)
        assertEquals(1, dao.libraryBooksCalls)
        assertEquals(1, dao.statsCalls)
        assertEquals(1, dao.tagsCalls)
        assertEquals(1, dao.coverCalls)
        assertEquals(0, dao.perBookCalls)
        assertEquals(0, dao.fullTableCalls)
        assertEquals(1, mapped.first().readChapters)
        assertEquals(listOf("tag-2"), mapped[1].genres)
        assertEquals("legacy-3", mapped[2].coverUrl)

        val last = provider.lastRead()
        assertEquals("https://site/book/1", last?.bookUrl)
        assertEquals(2, dao.statsCalls)
        assertEquals(2, dao.tagsCalls)
        assertEquals(2, dao.coverCalls)
        assertEquals(0, dao.perBookCalls)
        assertEquals(0, dao.fullTableCalls)
        // lastRead asks for one book, not the whole library.
        assertEquals(listOf(1L), dao.statsIds.last())
        assertEquals(listOf(1L), dao.tagsIds.last())
        assertEquals(listOf(1L), dao.coverIds.last())

        provider.searchBooks("book", limit = 10)
        assertEquals(1, dao.searchCalls)
        assertEquals(3, dao.statsCalls)
        assertEquals(3, dao.tagsCalls)
        assertEquals(3, dao.coverCalls)
        assertEquals(0, dao.perBookCalls)
        assertEquals(0, dao.fullTableCalls)
        assertEquals((1L..10L).toList(), dao.statsIds.last())
        assertEquals((1L..10L).toList(), dao.tagsIds.last())
        assertEquals((1L..10L).toList(), dao.coverIds.last())
    }

    @Test
    fun `library and search keep one url from two installs separate`() = runTest {
        val rows = listOf(
            books().first().copy(bookId = 1, installId = "install-a"),
            books().first().copy(bookId = 2, installId = "install-b"),
            books()[1].copy(bookId = 3, installId = "install-a"),
        )
        val dao = CountingDao(books = rows, stats = emptyList(), tags = emptyList(), covers = emptyList())
        val provider = RoomLibraryDataProvider(
            library = LibraryRepository(dao.proxy),
            now = { 1_000L },
        )

        assertEquals(
            listOf("https://site/book/1", "https://site/book/1", "https://site/book/2"),
            provider.books().map { it.bookUrl },
        )
        assertEquals(
            listOf("https://site/book/1", "https://site/book/1", "https://site/book/2"),
            provider.searchBooks("book", limit = 10).map { it.bookUrl },
        )
    }

    @Test
    fun `dashboard next up maps four candidates with batch stats and covers`() = runTest {
        val rows = books()
        val dao = CountingDao(
            books = rows,
            stats = stats(rows),
            tags = tags(rows),
            covers = listOf(
                BookCoverEntity(bookId = 1, remoteUrl = "https://cdn.test/one.jpg"),
            ),
        )
        val provider = RoomDashboardDataProvider(
            library = LibraryRepository(dao.proxy),
            now = { 1_000L },
        )

        val nextUp = provider.nextUp()

        assertEquals(4, nextUp.size)
        assertEquals(1, dao.unfinishedCalls)
        assertEquals(1, dao.statsCalls)
        assertEquals(1, dao.coverCalls)
        assertEquals(0, dao.perBookCalls)
        // Dashboard uses the same per-page cover query as the library.
        assertEquals(0, dao.fullTableCalls)
        assertEquals(0, dao.fullTableCalls)
        assertEquals(listOf(1, 2, 3, 4), nextUp.map { it.readChapters })
        assertTrue(nextUp.all { it.coverUrl?.startsWith("legacy-") == true })
    }
}
