package dev.mirutime.feature.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Manga page images: two automatic 1s retries per URL, then ONE chapter-pages
// refresh per page list (rotated CDN tokens), never a loop.
class MangaPageRetryPolicyTest {

    @Test
    fun `two automatic retries one second apart`() {
        val policy = MangaPageRetryPolicy()
        val page = "https://cdn.test/1.jpg"

        assertEquals(MangaPageRetry.Retry(1_000L), policy.onError(page))
        assertEquals(MangaPageRetry.Retry(1_000L), policy.onError(page))
    }

    @Test
    fun `the third failure asks for one chapter-pages refresh`() {
        val policy = MangaPageRetryPolicy(maxAttempts = 2, delayMs = 1_000L)
        val page = "https://cdn.test/1.jpg"

        repeat(2) { policy.onError(page) }

        assertEquals(MangaPageRetry.RefreshPages, policy.onError(page))
    }

    @Test
    fun `the refresh happens once per page list`() {
        val policy = MangaPageRetryPolicy()
        val first = "https://cdn.test/1.jpg"
        val second = "https://cdn.test/2.jpg"
        repeat(2) { policy.onError(first) }

        assertEquals(MangaPageRetry.RefreshPages, policy.onError(first))
        // Another dead page in the same chapter must not refresh again.
        repeat(2) { policy.onError(second) }
        assertEquals(MangaPageRetry.Stop, policy.onError(second))
        // Nor must the first page keep requesting one.
        assertEquals(MangaPageRetry.Stop, policy.onError(first))
    }

    @Test
    fun `urls keep independent retry budgets`() {
        val policy = MangaPageRetryPolicy()
        val a = "https://cdn.test/a.jpg"
        val b = "https://cdn.test/b.jpg"

        policy.onError(a)

        assertEquals(MangaPageRetry.Retry(1_000L), policy.onError(b))
    }

    @Test
    fun `shouldRefreshPages only after the retry budget`() {
        val policy = MangaPageRetryPolicy()
        val page = "https://cdn.test/1.jpg"

        assertFalse(policy.shouldRefreshPages(1, page))
        assertFalse(policy.shouldRefreshPages(2, page))
        // No recorded failures at that attempt: not a real exhausted URL.
        assertFalse(policy.shouldRefreshPages(3, page))
    }
}
