package dev.mirutime.app.core

// One place for the cache bounds. The global search snapshot is process memory
// only, but it is time-bounded: it carries catalogue links and cover URLs, so
// serving it forever would let those rot.
object AppCacheConfig {
    const val SEARCH_CACHE_TTL_MS = 15L * 60 * 1000
    const val REPO_CACHE_TTL_MS = 15L * 60 * 1000

    fun isFresh(storedAt: Long, ttlMs: Long, now: Long = System.currentTimeMillis()): Boolean =
        now >= storedAt && now - storedAt < ttlMs
}
