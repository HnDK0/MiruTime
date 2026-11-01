package dev.mirutime.app.features.library.data

import dev.mirutime.app.features.library.domain.LibrarySort
import dev.mirutime.app.features.library.domain.SortDirection
import dev.mirutime.data.library.BookCoverEntity
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.BookReadStatsRow
import dev.mirutime.data.library.BookTagNameRow
import dev.mirutime.data.library.LibraryDao
import dev.mirutime.data.library.LibraryPageRow
import dev.mirutime.data.library.LibraryRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomLibraryPageTest {

    private class PageDao(
        val rows: List<BookEntity>,
        val total: Int,
    ) {
        var booksCalls = 0
        var pageCalls = 0
        var statsCalls = 0
        var tagsCalls = 0
        var coverCalls = 0
        var fullTableCalls = 0
        val statsIds = mutableListOf<List<Long>>()
        val tagsIds = mutableListOf<List<Long>>()
        val coverIds = mutableListOf<List<Long>>()

        val proxy: LibraryDao = Proxy.newProxyInstance(
            LibraryDao::class.java.classLoader,
            arrayOf(LibraryDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "libraryPageCount" -> total
                "libraryPageRows" -> {
                    pageCalls++
                    // repository asks for limit + 1; give one extra row.
                    rows.take(61).map { LibraryPageRow(book = it, unreadCount = 3) }
                }
                "searchBooks" -> {
                    val limit = args!![2] as Int
                    val offset = args[3] as Int
                    rows.drop(offset).take(limit)
                }
                "readChapterStats" -> {
                    fullTableCalls++
                    emptyList<BookReadStatsRow>()
                }
                "readChapterStatsFor" -> {
                    statsCalls++
                    val ids = (args!!.first() as List<*>).filterIsInstance<Long>()
                    statsIds += listOf(ids)
                    ids.map { BookReadStatsRow(bookId = it, readCount = 2, maxReadIndex = 2) }
                }
                "tagsByBook" -> {
                    fullTableCalls++
                    emptyList<BookTagNameRow>()
                }
                "tagsByBookFor" -> {
                    tagsCalls++
                    val ids = (args!!.first() as List<*>).filterIsInstance<Long>()
                    tagsIds += listOf(ids)
                    emptyList<BookTagNameRow>()
                }
                "bookCovers" -> {
                    fullTableCalls++
                    emptyList<BookCoverEntity>()
                }
                "bookCoversFor" -> {
                    coverCalls++
                    val ids = (args!!.first() as List<*>).filterIsInstance<Long>()
                    coverIds += listOf(ids)
                    emptyList<BookCoverEntity>()
                }
                "libraryBooks" -> {
                    booksCalls++
                    rows
                }
                else -> throw AssertionError("Unexpected DAO call: ${method.name}")
            }
        } as LibraryDao
    }

    @Test
    fun `page maps only the returned rows and batches stats tags covers`() = runTest {
        val rows = (1..61).map { book(it.toLong()) }
        val dao = PageDao(rows = rows, total = 120)
        val provider = RoomLibraryDataProvider(
            library = LibraryRepository(dao.proxy),
            translations = null,
            now = { 1_000L },
        )

        val page = provider.libraryPage(
            query = "",
            cursor = null,
            limit = 60,
            filters = LibraryPageFilters(),
            sort = LibrarySort.LastRead,
            direction = SortDirection.Desc,
        )

        assertEquals(60, page.books.size)
        assertTrue(page.hasMore)
        assertEquals(120, page.total)
        assertNotNull(page.nextCursor)
        assertEquals((1L..60L).toList(), page.books.map { it.bookUrl.substringAfterLast('/').toLong() })
        assertEquals(1, dao.pageCalls)
        assertEquals(1, dao.statsCalls)
        assertEquals(1, dao.tagsCalls)
        assertEquals(1, dao.coverCalls)
        assertEquals((1L..60L).toList(), dao.statsIds.single())
        assertEquals((1L..60L).toList(), dao.tagsIds.single())
        assertEquals((1L..60L).toList(), dao.coverIds.single())
        // The provider must not fall back to the full-library read.
        assertEquals(0, dao.booksCalls)
        assertEquals(0, dao.fullTableCalls)
        assertEquals(2, page.books.first().readChapters)
    }

    @Test
    fun `search page keeps the offset across filtered rows`() = runTest {
        val rows = (1..30).map { id ->
            book(id.toLong(), shelfId = if (id <= 5) "completed" else "reading")
        }
        val dao = PageDao(rows = rows, total = rows.size)
        val provider = RoomLibraryDataProvider(
            library = LibraryRepository(dao.proxy),
            translations = null,
            now = { 1_000L },
        )

        val page = provider.libraryPage(
            query = "book",
            cursor = null,
            limit = 5,
            filters = LibraryPageFilters(shelfId = "reading"),
            sort = LibrarySort.LastRead,
            direction = SortDirection.Desc,
        )

        assertEquals((6L..10L).toList(), page.books.map { it.bookUrl.substringAfterLast('/').toLong() })
        assertTrue(page.hasMore)
        assertEquals(LibraryPageCursor.Search(10), page.nextCursor)
        assertEquals(null, page.total)
        assertTrue(page.books.all { it.shelfId == "reading" })
        assertEquals(listOf((1L..5L).toList(), (6L..10L).toList()), dao.statsIds)
        assertEquals(0, dao.booksCalls)
    }

    @Test
    fun `search page ignores sort and direction`() = runTest {
        val rows = (1..3).map { book(it.toLong()) }
        val dao = PageDao(rows = rows, total = rows.size)
        val provider = RoomLibraryDataProvider(
            library = LibraryRepository(dao.proxy),
            translations = null,
            now = { 1_000L },
        )

        val lastRead = provider.libraryPage(
            query = "book",
            cursor = null,
            limit = 60,
            filters = LibraryPageFilters(),
            sort = LibrarySort.LastRead,
            direction = SortDirection.Desc,
        )
        val title = provider.libraryPage(
            query = "book",
            cursor = null,
            limit = 60,
            filters = LibraryPageFilters(),
            sort = LibrarySort.Title,
            direction = SortDirection.Asc,
        )

        assertEquals(
            lastRead.books.map { it.bookUrl },
            title.books.map { it.bookUrl },
        )
    }

    private fun book(id: Long, shelfId: String = "reading") = BookEntity(
        bookId = id,
        installId = "plugin",
        url = "https://site/book/$id",
        title = "Book $id",
        author = "Author",
        coverUrl = "legacy-$id",
        contentType = "Novel",
        rating = 0.0,
        shelfId = shelfId,
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
