package dev.mirutime.core.host

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Per-source hidden-WebView ceiling: one fetch in flight, four per minute.
class WebviewFetchLimitTest {

    @Test
    fun fourLaunchesPerWindowAreAllowed() {
        val starts = listOf(0L, 1_000L, 2_000L, 3_000L)
        assertTrue(WebviewFetchLimit.allows(0, starts.take(3), 3_000L))
        assertFalse(WebviewFetchLimit.allows(0, starts, 3_000L))
    }

    @Test
    fun windowSlides() {
        val starts = listOf(0L, 1_000L, 2_000L, 3_000L)
        assertFalse(WebviewFetchLimit.allows(0, starts, 59_999L))
        assertTrue(WebviewFetchLimit.allows(0, starts, 60_000L))
    }

    @Test
    fun staleStartsDoNotCount() {
        val old = listOf(0L, 1L, 2L, 3L)
        assertTrue(WebviewFetchLimit.allows(0, old, 120_000L))
    }

    @Test
    fun oneConcurrentFetchOnly() {
        assertTrue(WebviewFetchLimit.allows(0, emptyList(), 0L))
        assertFalse(WebviewFetchLimit.allows(1, emptyList(), 0L))
    }
}
