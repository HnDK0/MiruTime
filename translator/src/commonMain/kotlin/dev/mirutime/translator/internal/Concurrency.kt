package dev.mirutime.translator.internal

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * Upper bound for [boundedMap] even if a config value is accidentally huge.
 * It keeps a bad setting from opening an unbounded number of requests.
 */
internal const val MAX_BOUNDED_CONCURRENCY = 32

/**
 * Maps [items] with at most [concurrency] calls in flight and preserves input
 * order in the result. Values outside 1..[MAX_BOUNDED_CONCURRENCY] are coerced.
 * A failing block cancels the remaining work and propagates normally.
 */
internal suspend fun <T, R> boundedMap(
    items: List<T>,
    concurrency: Int,
    block: suspend (T) -> R,
): List<R> {
    val limit = concurrency.coerceIn(1, MAX_BOUNDED_CONCURRENCY)
    return coroutineScope {
        val results = ArrayList<R>(items.size)
        for (window in items.chunked(limit)) {
            results += window.map { item -> async { block(item) } }.awaitAll()
        }
        results
    }
}

/**
 * Sequential spacing that also covers recovery requests. A bisected chunk
 * sends its children through the same lambda, so keeping the gap here (not in
 * the chunk loop) stops a damaged batch from firing a burst at a rate-limited
 * endpoint. Concurrent mode is unchanged: no spacing.
 */
internal fun throttledTranslator(
    concurrency: Int,
    gapMs: Long,
    translate: suspend (TextChunk) -> String,
): suspend (TextChunk) -> String {
    if (concurrency != 1) return translate
    var sent = 0
    return { chunk ->
        if (sent > 0) delay(gapMs)
        sent++
        translate(chunk)
    }
}
