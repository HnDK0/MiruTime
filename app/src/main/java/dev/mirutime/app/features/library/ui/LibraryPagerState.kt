package dev.mirutime.app.features.library.ui

import dev.mirutime.app.features.library.data.LibraryPageCursor
import dev.mirutime.app.features.library.data.LibraryPageSlice
import dev.mirutime.app.features.library.domain.LibraryBook

/**
 * Pure pager state for the library list. [generation] invalidates every
 * in-flight load after a filter/query change; pages append with URL dedupe.
 */
data class LibraryPagerState(
    val generation: Int = 0,
    val books: List<LibraryBook> = emptyList(),
    val cursor: LibraryPageCursor? = null,
    val hasMore: Boolean = false,
    val total: Int? = null,
    val loadingFirst: Boolean = true,
    val loadingMore: Boolean = false,
    val firstError: Boolean = false,
    val moreError: Boolean = false,
    val pagesLoaded: Int = 0,
) {
    val refreshing: Boolean get() = loadingFirst && books.isNotEmpty()

    /** True while an automatic load-more must wait (first page, in flight, or failed). */
    val loadMoreBlocked: Boolean get() = loadingFirst || loadingMore || moreError

    /** Starts a new first-page load; keeps the current rows for a soft refresh. */
    fun beginFirst(): LibraryPagerState = copy(
        generation = generation + 1,
        cursor = null,
        hasMore = false,
        total = null,
        loadingFirst = true,
        loadingMore = false,
        firstError = false,
        moreError = false,
        pagesLoaded = 0,
    )

    fun applyFirst(generation: Int, page: LibraryPageSlice): LibraryPagerState =
        if (generation != this.generation || !loadingFirst) {
            this
        } else {
            copy(
                books = page.books.distinctBy { it.bookUrl },
                cursor = page.nextCursor,
                hasMore = page.hasMore,
                total = page.total,
                loadingFirst = false,
                loadingMore = false,
                firstError = false,
                moreError = false,
                pagesLoaded = 1,
            )
        }

    fun beginMore(): LibraryPagerState =
        if (!hasMore || cursor == null || loadingFirst || loadingMore || moreError) {
            this
        } else {
            copy(loadingMore = true, moreError = false)
        }

    fun applyMore(generation: Int, page: LibraryPageSlice): LibraryPagerState =
        if (generation != this.generation || !loadingMore) {
            this
        } else {
            copy(
                books = appendUnique(books, page.books),
                cursor = page.nextCursor,
                hasMore = page.hasMore,
                total = page.total ?: total,
                loadingMore = false,
                moreError = false,
                pagesLoaded = pagesLoaded + 1,
            )
        }

    fun failFirst(generation: Int): LibraryPagerState =
        if (generation != this.generation) {
            this
        } else {
            copy(loadingFirst = false, loadingMore = false, firstError = true)
        }

    fun failMore(generation: Int): LibraryPagerState =
        if (generation != this.generation) {
            this
        } else {
            copy(loadingMore = false, moreError = true)
        }

    fun retryMore(): LibraryPagerState =
        if (moreError) copy(moreError = false) else this

    private fun appendUnique(
        existing: List<LibraryBook>,
        incoming: List<LibraryBook>,
    ): List<LibraryBook> {
        val seen = existing.mapTo(HashSet()) { it.bookUrl }
        return existing + incoming.filter { seen.add(it.bookUrl) }
    }
}

/** Rows from the end that start the next-page prefetch. */
internal const val LoadMoreThreshold = 4

/**
 * Pure near-end predicate: load when the last laid-out row is within
 * [threshold] rows of the end, there is another page, and no load is already
 * running or failed. [total] is LazyList total items, headers included.
 */
internal fun shouldLoadMore(
    lastVisible: Int,
    total: Int,
    hasMore: Boolean,
    loading: Boolean,
    threshold: Int = LoadMoreThreshold,
): Boolean = hasMore && !loading && total > 0 && lastVisible >= total - threshold

/** Footer stays visible while more pages exist, a page loads, or it failed. */
internal fun showsLoadMoreFooter(
    hasMore: Boolean,
    loading: Boolean,
    error: Boolean,
): Boolean = hasMore || loading || error
