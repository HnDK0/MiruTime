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
import org.junit.Test

// The SQL list matches tags OR genresJson, so the in-memory FTS filter must
// use the same union. A tags-only display list would drop a book whose selected
// genre lives only in genresJson.
class LibraryGenreFilterTest {

    @Test
    fun `json-only genre matches list search and select all while tags stay visible`() = runTest {
        val book = BookEntity(
            bookId = 1L,
            installId = "plugin",
            url = "https://site/book/1",
            title = "Book 1",
            author = "Author",
            coverUrl = null,
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
            genresJson = """["json-only"]""",
        )
        val provider = RoomLibraryDataProvider(
            library = LibraryRepository(dao(book)),
            translations = null,
            now = { 1_000L },
        )
        val filters = LibraryPageFilters(genres = setOf("json-only"))

        val list = provider.libraryPage(
            query = "",
            cursor = null,
            limit = 60,
            filters = filters,
            sort = LibrarySort.LastRead,
            direction = SortDirection.Desc,
        )
        val search = provider.libraryPage(
            query = "Book",
            cursor = null,
            limit = 60,
            filters = filters,
            sort = LibrarySort.LastRead,
            direction = SortDirection.Desc,
        )
        val urls = provider.matchingLibraryUrls(query = "Book", filters = filters)

        assertEquals(listOf(book.url), list.books.map { it.bookUrl })
        assertEquals(1, list.total)
        assertEquals(list.books.size, list.total)
        assertEquals(listOf(book.url), search.books.map { it.bookUrl })
        assertEquals(listOf(book.url), urls)

        // Display keeps the old "tags win" labels; only the filter set unions.
        val mapped = list.books.single()
        assertEquals(listOf("visible-tag"), mapped.genres)
        assertEquals(listOf("visible-tag", "json-only"), mapped.filterGenres)
    }

    private fun dao(book: BookEntity): LibraryDao =
        Proxy.newProxyInstance(
            LibraryDao::class.java.classLoader,
            arrayOf(LibraryDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "libraryPageCount" -> 1
                "libraryPageRows" -> listOf(LibraryPageRow(book = book, unreadCount = 0))
                "libraryPageUrls" -> listOf(book.url)
                "searchBooks" -> if ((args!![3] as Int) == 0) listOf(book) else emptyList()
                "readChapterStatsFor" -> emptyList<BookReadStatsRow>()
                "tagsByBookFor" -> listOf(BookTagNameRow(bookId = book.bookId, name = "visible-tag"))
                "bookCoversFor" -> emptyList<BookCoverEntity>()
                else -> throw AssertionError("Unexpected DAO call: ${method.name}")
            }
        } as LibraryDao
}
