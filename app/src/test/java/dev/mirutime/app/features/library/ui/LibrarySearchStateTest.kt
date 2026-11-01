package dev.mirutime.app.features.library.ui

import dev.mirutime.app.features.library.data.LibraryPageCursor
import dev.mirutime.app.features.library.data.LibraryPageSlice
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.library.domain.LibraryBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Query changes now go through the pager: the previous FTS page stays visible
// while the new page loads, then the first page replaces it rather than
// appending.
class LibrarySearchStateTest {

    @Test
    fun `new first page replaces the previous search rows`() {
        val search = LibraryPagerState()
            .beginFirst()
            .let { it.applyFirst(it.generation, page("alpha")) }
        val next = search.beginFirst()

        assertEquals(listOf("alpha"), next.books.map { it.bookUrl })
        assertTrue(next.refreshing)

        val applied = next.applyFirst(next.generation, page("beta"))
        assertEquals(listOf("beta"), applied.books.map { it.bookUrl })
        assertEquals(1, applied.pagesLoaded)
    }

    @Test
    fun `stale search page cannot overwrite the current query`() {
        val old = LibraryPagerState().beginFirst()
        val current = old.beginFirst()

        val applied = current.applyFirst(
            old.generation,
            page("old", cursor = LibraryPageCursor.Search(1), hasMore = true),
        )

        assertEquals(emptyList<String>(), applied.books.map { it.bookUrl })
        assertTrue(applied.loadingFirst)
        assertFalse(applied.hasMore)
    }

    private fun page(
        vararg urls: String,
        cursor: LibraryPageCursor? = null,
        hasMore: Boolean = false,
    ) = LibraryPageSlice(
        books = urls.map { book(it) },
        nextCursor = cursor,
        hasMore = hasMore,
    )

    private fun book(url: String) = LibraryBook(
        bookUrl = url,
        title = url,
        source = "Source",
        author = "Author",
        rating = 0f,
        contentType = ContentType.Novel,
        totalChapters = 10,
        readChapters = 0,
        hasUpdate = false,
        shelfId = "reading",
        genres = emptyList(),
        lastReadRank = 0,
        lastUpdateRank = 0,
        addedRank = 0,
        lastReadLabel = "",
        continueLabel = "",
    )
}
