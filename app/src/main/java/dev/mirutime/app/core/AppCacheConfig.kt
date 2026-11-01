package dev.mirutime.app.core

// One place for the cache bounds. Search and catalog snapshots are process
// memory only, but they are time-bounded: a snapshot carries catalogue links
// and cover URLs, so serving it forever would let those rot. Cover URLs are
// part of the catalog snapshot, so they expire with the same 15-minute bound.
object AppCacheConfig {
    const val SEARCH_CACHE_TTL_MS = 15L * 60 * 1000
    const val CATALOG_CACHE_TTL_MS = 15L * 60 * 1000
    const val REPO_CACHE_TTL_MS = 15L * 60 * 1000
    const val CATALOG_CACHE_MAX_ENTRIES = 20

    fun isFresh(storedAt: Long, ttlMs: Long, now: Long = System.currentTimeMillis()): Boolean =
        now >= storedAt && now - storedAt < ttlMs
}
