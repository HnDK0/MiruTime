package dev.mirutime.app.features.finder.data

import dev.mirutime.app.core.AppCacheConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `clear empties the global cache`() {
        val key = searchCacheKey("dune", null)
        FinderSearchCache.putGlobal(globalSnapshot(storedAt = now))

        FinderSearchCache.clear()

        assertNull(FinderSearchCache.global(key, now))
    }
}
