package dev.mirutime.app.features.finder.data

import dev.mirutime.app.core.AppCacheConfig
import dev.mirutime.app.features.finder.domain.CatalogBook
import dev.mirutime.app.features.finder.domain.CatalogFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// TTL behaviour of the process-level finder cache. Times are passed in
// explicitly so the tests never depend on the wall clock.
class FinderCacheTtlTest {

    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        FinderSearchCache.clear()
    }

    private fun globalSnapshot(storedAt: Long) = FinderSearchCache.GlobalSnapshot(
        key = searchCacheKey("dune", null),
        results = emptyList(),
        total = 0,
        done = 0,
        scrollIndex = 0,
        scrollOffset = 0,
        storedAt = storedAt,
    )

    private fun catalogKey() = CatalogCacheKey("alpha", CatalogFilters(query = "q"))

    private fun catalogSnapshot(storedAt: Long) = FinderSearchCache.CatalogSnapshot(
        books = emptyList(),
        pagesLoaded = 1,
        hasMore = false,
        scrollIndex = 0,
        scrollOffset = 0,
        storedAt = storedAt,
    )

    private fun catalogMapSize(): Int {
        val field = FinderSearchCache::class.java.getDeclaredField("catalog")
        field.isAccessible = true
        return (field.get(FinderSearchCache) as Map<*, *>).size
    }

    @Test
    fun `fresh global snapshot is returned`() {
        val snapshot = globalSnapshot(storedAt = now)
        FinderSearchCache.putGlobal(snapshot)

        assertEquals(snapshot, FinderSearchCache.global(snapshot.key, now))
    }

    @Test
    fun `expired global snapshot is not returned`() {
        val key = searchCacheKey("dune", null)
        FinderSearchCache.putGlobal(
            globalSnapshot(storedAt = now - AppCacheConfig.SEARCH_CACHE_TTL_MS - 1),
        )

        assertNull(FinderSearchCache.global(key, now))
    }

    @Test
    fun `global snapshot at the exact ttl boundary is expired`() {
        val key = searchCacheKey("dune", null)
        FinderSearchCache.putGlobal(
            globalSnapshot(storedAt = now - AppCacheConfig.SEARCH_CACHE_TTL_MS),
        )

        assertNull(FinderSearchCache.global(key, now))
    }

    @Test
    fun `snapshot stored in the future is not fresh`() {
        val key = searchCacheKey("dune", null)
        FinderSearchCache.putGlobal(globalSnapshot(storedAt = now + 1))

        assertNull(FinderSearchCache.global(key, now))
    }

    @Test
    fun `fresh catalog snapshot is returned`() {
        val key = catalogKey()
        val snapshot = catalogSnapshot(storedAt = now)
        FinderSearchCache.putCatalog(key, snapshot)

        assertEquals(snapshot, FinderSearchCache.catalog(key, now))
    }

    @Test
    fun `expired catalog snapshot is not returned and is evicted`() {
        val key = catalogKey()
        FinderSearchCache.putCatalog(
            key,
            catalogSnapshot(storedAt = now - AppCacheConfig.CATALOG_CACHE_TTL_MS - 1),
        )

        assertNull(FinderSearchCache.catalog(key, now))
        assertEquals(0, catalogMapSize())
    }

    @Test
    fun `clear empties both caches`() {
        val key = searchCacheKey("dune", null)
        FinderSearchCache.putGlobal(globalSnapshot(storedAt = now))
        FinderSearchCache.putCatalog(catalogKey(), catalogSnapshot(storedAt = now))

        FinderSearchCache.clear()

        assertNull(FinderSearchCache.global(key, now))
        assertNull(FinderSearchCache.catalog(catalogKey(), now))
        assertTrue(catalogMapSize() == 0)
    }

    // A catalogue long-press writes membership and then invalidates; replaying
    // either snapshot would show the pre-toggle inLibrary badge.
    @Test
    fun `membership invalidation drops snapshots carrying stale badges`() {
        val url = "https://site/book"
        val staleBook = CatalogBook(
            bookUrl = url,
            title = "Book",
            rating = 0f,
            chapters = 0,
            genres = emptyList(),
            ongoing = false,
            inLibrary = false,
        )
        FinderSearchCache.putCatalog(
            catalogKey(),
            FinderSearchCache.CatalogSnapshot(
                books = listOf(staleBook),
                pagesLoaded = 1,
                hasMore = false,
                scrollIndex = 0,
                scrollOffset = 0,
                storedAt = now,
            ),
        )

        FinderSearchCache.clear()

        assertNull(FinderSearchCache.catalog(catalogKey(), now))
    }
}
