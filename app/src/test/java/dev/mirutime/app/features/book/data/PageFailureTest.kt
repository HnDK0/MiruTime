package dev.mirutime.app.features.book.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageFailureTest {
    @Test
    fun `success has no failure`() {
        assertNull(pageFailureFor(200))
        assertNull(pageFailureFor(206))
    }

    @Test
    fun `auth and cloudflare responses pause the source`() {
        assertEquals(ChapterFailureKind.AUTH_REQUIRED, pageFailureFor(401)?.kind)
        assertEquals(ChapterFailureKind.AUTH_REQUIRED, pageFailureFor(403)?.kind)
        assertEquals(ChapterFailureKind.RATE_LIMITED, pageFailureFor(429)?.kind)
    }

    @Test
    fun `not found is terminal`() {
        assertEquals(ChapterFailureKind.NOT_FOUND, pageFailureFor(404)?.kind)
        assertEquals(false, pageFailureFor(404)?.retryable)
    }

    @Test
    fun `transport and server responses are retryable`() {
        assertTrue(pageFailureFor(0)!!.retryable)
        assertTrue(pageFailureFor(503)!!.retryable)
        assertEquals(ChapterFailureKind.NETWORK, pageFailureFor(503)?.kind)
    }

    @Test
    fun `an empty successful body is retried, never stored`() {
        assertEquals(
            ChapterFailureKind.NETWORK,
            runCatching { pageBytesFor(200, ByteArray(0)) }.exceptionOrNull()
                ?.let { (it as ChapterDownloadException).kind },
        )
        assertEquals(
            ChapterFailureKind.NETWORK,
            runCatching { pageBytesFor(204, null) }.exceptionOrNull()
                ?.let { (it as ChapterDownloadException).kind },
        )
        assertEquals(3, pageBytesFor(200, byteArrayOf(1, 2, 3)).size)
    }
}
