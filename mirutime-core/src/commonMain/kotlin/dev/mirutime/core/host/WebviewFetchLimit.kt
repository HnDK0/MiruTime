package dev.mirutime.core.host

// Per-source ceiling for the hidden WebView lane: one page at a time and at
// most four launches per minute. Pure decision so the caller owns the state
// and the clock; AndroidWebviewFetcher keeps the per-source in-flight count
// and launch timestamps. Over the limit the fetch returns nil before a
// WebView is created.
object WebviewFetchLimit {
    const val MAX_CONCURRENT = 1
    const val MAX_PER_WINDOW = 4
    const val WINDOW_MS = 60_000L

    fun allows(inFlight: Int, recentStarts: List<Long>, nowMs: Long): Boolean =
        inFlight < MAX_CONCURRENT &&
            recentStarts.count { nowMs - it < WINDOW_MS } < MAX_PER_WINDOW
}
