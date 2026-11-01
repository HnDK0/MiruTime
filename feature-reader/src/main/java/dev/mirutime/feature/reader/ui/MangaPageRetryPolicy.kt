package dev.mirutime.feature.reader.ui

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

// Manga page images: an image that fails once is usually a transient decode or
// CDN hiccup, so each URL gets two automatic retries one second apart. When a
// page still fails, the chapter's page list itself is probably stale (rotated
// CDN tokens), so the page list is refreshed ONCE per list — not once per URL
// and never in a loop. The manual Retry button stays the user's own path.
internal class MangaPageRetryPolicy(
    private val maxAttempts: Int = 2,
    val delayMs: Long = 1_000L,
) {
    private val failures = ConcurrentHashMap<String, Int>()
    private val refreshed = AtomicBoolean(false)

    /** Registers one failed load and returns what the caller does next. */
    fun onError(url: String): MangaPageRetry {
        val attempt = failures.merge(url, 1, Int::plus) ?: 1
        return when {
            attempt <= maxAttempts -> MangaPageRetry.Retry(delayMs)
            shouldRefreshPages(attempt, url) -> MangaPageRetry.RefreshPages
            else -> MangaPageRetry.Stop
        }
    }

    /**
     * True once per page list after a URL exhausted its automatic retries. The
     * whole chapter shares one refresh: a dead CDN would fail every page the
     * same way, and the refreshed list is the only way to get new URLs.
     */
    fun shouldRefreshPages(attempt: Int, url: String): Boolean =
        attempt > maxAttempts &&
            failures[url] == attempt &&
            refreshed.compareAndSet(false, true)
}

internal sealed interface MangaPageRetry {
    data class Retry(val delayMs: Long) : MangaPageRetry
    data object RefreshPages : MangaPageRetry
    data object Stop : MangaPageRetry
}
