package dev.mirutime.app.features.library.ui

import dev.mirutime.app.features.library.data.LibraryPageCursor
import dev.mirutime.app.features.library.data.LibraryPageSlice
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.library.domain.LibraryBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPagerStateTest {

    @Test
    fun `first page replaces rows and records the next cursor`() {
        val start = LibraryPagerState()
        val loading = start.beginFirst()
        assertTrue(loading.loadingFirst)
        assertEquals(1, loading.generation)

        val page = page("a", "b", cursor = LibraryPageCursor.Search(2), hasMore = true, total = 4)
        val applied = loading.applyFirst(loading.generation, page)

        assertEquals(listOf("a", "b"), applied.books.map { it.bookUrl })
        assertEquals(LibraryPageCursor.Search(2), applied.cursor)
        assertTrue(applied.hasMore)
        assertEquals(4, applied.total)
        assertFalse(applied.loadingFirst)
        assertEquals(1, applied.pagesLoaded)
    }

    @Test
    fun `more pages append and dedupe the same book url`() {
        val first = LibraryPagerState()
            .beginFirst()
            .let { it.applyFirst(it.generation, page("a", "b", cursor = LibraryPageCursor.Search(2), hasMore = true)) }
        val loading = first.beginMore()
        assertTrue(loading.loadingMore)
        val next = loading.applyMore(
            loading.generation,
            page("b", "c", cursor = null, hasMore = false, total = 3),
        )

        assertEquals(listOf("a", "b", "c"), next.books.map { it.bookUrl })
        assertFalse(next.hasMore)
        assertNull(next.cursor)
        assertEquals(2, next.pagesLoaded)
    }

    @Test
    fun `stale generation cannot apply first or more pages`() {
        val first = LibraryPagerState().beginFirst()
        val stale = first.generation
        val second = first.beginFirst()

        assertSame(second, second.applyFirst(stale, page("stale")))
        val more = second
            .applyFirst(second.generation, page("a", cursor = LibraryPageCursor.Search(1), hasMore = true))
            .beginMore()
        assertSame(more, more.applyMore(stale, page("stale")))
        assertEquals(listOf("a"), more.books.map { it.bookUrl })
    }

    @Test
    fun `load more waits for hasMore and is not re-entrant`() {
        val empty = LibraryPagerState()
        assertSame(empty, empty.beginMore())

        val first = LibraryPagerState()
            .beginFirst()
            .let { it.applyFirst(it.generation, page("a", cursor = LibraryPageCursor.Search(1), hasMore = true)) }
        val loading = first.beginMore()
        assertSame(loading, loading.beginMore())
        assertTrue(loading.loadingMore)
    }

    @Test
    fun `first and more failures expose retry and clear on a new load`() {
        val first = LibraryPagerState().beginFirst()
        val failed = first.failFirst(first.generation)
        assertTrue(failed.firstError)
        assertFalse(failed.loadingFirst)

        val retried = failed.beginFirst()
        assertFalse(retried.firstError)
        assertTrue(retried.loadingFirst)

        val loaded = retried.applyFirst(
            retried.generation,
            page("a", cursor = LibraryPageCursor.Search(1), hasMore = true),
        )
        val moreFail = loaded.beginMore().failMore(loaded.generation)
        assertTrue(moreFail.moreError)
        assertFalse(moreFail.loadingMore)

        val clearMore = moreFail.retryMore()
        assertFalse(clearMore.moreError)
        assertTrue(clearMore.beginMore().loadingMore)
    }

    @Test
    fun `keeping a page during refresh is exposed as refreshing`() {
        val first = LibraryPagerState()
            .beginFirst()
            .let { it.applyFirst(it.generation, page("a")) }
        val refreshing = first.beginFirst()

        assertEquals(listOf("a"), refreshing.books.map { it.bookUrl })
        assertTrue(refreshing.refreshing)
        assertTrue(refreshing.loadingFirst)
    }

    @Test
    fun `load more triggers only near the end and not while blocked`() {
        assertFalse(shouldLoadMore(lastVisible = 5, total = 20, hasMore = true, loading = false))
        assertFalse(shouldLoadMore(lastVisible = 15, total = 20, hasMore = true, loading = false))
        assertTrue(shouldLoadMore(lastVisible = 16, total = 20, hasMore = true, loading = false))
        assertTrue(shouldLoadMore(lastVisible = 19, total = 20, hasMore = true, loading = false))
        // A second scroll tick while the page is in flight must not re-trigger.
        assertFalse(shouldLoadMore(lastVisible = 19, total = 20, hasMore = true, loading = true))
        assertFalse(shouldLoadMore(lastVisible = 19, total = 20, hasMore = false, loading = false))
        assertFalse(shouldLoadMore(lastVisible = 4, total = 0, hasMore = true, loading = false))
        assertTrue(
            shouldLoadMore(
                lastVisible = 19,
                total = 20,
                hasMore = true,
                loading = false,
                threshold = 1,
            ),
        )
        assertFalse(
            shouldLoadMore(
                lastVisible = 18,
                total = 20,
                hasMore = true,
                loading = false,
                threshold = 1,
            ),
        )
    }

    @Test
    fun `load more footer covers more loading and failed retry`() {
        assertTrue(showsLoadMoreFooter(hasMore = true, loading = false, error = false))
        assertTrue(showsLoadMoreFooter(hasMore = false, loading = true, error = false))
        assertTrue(showsLoadMoreFooter(hasMore = false, loading = false, error = true))
        assertFalse(showsLoadMoreFooter(hasMore = false, loading = false, error = false))

        val loaded = LibraryPagerState()
            .beginFirst()
            .let { it.applyFirst(it.generation, page("a", cursor = LibraryPageCursor.Search(1), hasMore = true)) }
        assertFalse(loaded.loadMoreBlocked)
        val loading = loaded.beginMore()
        assertTrue(loading.loadMoreBlocked)
        assertTrue(showsLoadMoreFooter(loading.hasMore, loading.loadingMore, loading.moreError))

        val failed = loading.failMore(loading.generation)
        assertTrue(failed.loadMoreBlocked)
        assertTrue(showsLoadMoreFooter(failed.hasMore, failed.loadingMore, failed.moreError))

        val retried = failed.retryMore()
        assertFalse(retried.loadMoreBlocked)
        assertTrue(retried.beginMore().loadingMore)
    }

    private fun page(
        vararg urls: String,
        cursor: LibraryPageCursor? = null,
        hasMore: Boolean = false,
        total: Int? = null,
    ) = LibraryPageSlice(
        books = urls.map { book(it) },
        nextCursor = cursor,
        hasMore = hasMore,
        total = total,
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
