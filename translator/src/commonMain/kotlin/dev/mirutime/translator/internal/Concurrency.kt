package dev.mirutime.translator.internal

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
