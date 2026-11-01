package dev.mirutime.app.core

import dev.mirutime.core.network.RedirectLimitException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// The repo lane owns its redirect walk and its error strings: a final 3xx is
// "too many redirects for <original>", every other non-2xx is
// "HTTP <code> for <current>", and core's RedirectLimitException never leaks.
class RepoFetchTest {

    private val url = "https://example.com/plugins/index.yaml"

    private fun hop(
        status: Int,
        location: String? = null,
        body: ByteArray = ByteArray(0),
        finalUrl: String = url,
    ) = RemoteBytes(status, body, emptyMap(), finalUrl, location)

    @Test
    fun `2xx returns the body`() = runBlocking {
        val body = "sources: []".encodeToByteArray()
        val result = RepoFetch({ hop(200, body = body) }).fetch(url)

        assertArrayEquals(body, result)
    }

    @Test
    fun `404 and 500 keep the HTTP status message`() = runBlocking {
        for (code in listOf(404, 500)) {
            try {
                RepoFetch({ hop(code) }).fetch(url)
                fail("expected HTTP $code failure")
            } catch (e: IllegalStateException) {
                assertEquals("HTTP $code for $url", e.message)
            }
        }
    }

    @Test
    fun `relative location resolves against the current hop`() = runBlocking {
        val sent = mutableListOf<String>()
        val body = "sources: []".encodeToByteArray()
        val result = RepoFetch(
            send = { current ->
                sent += current
                if (current.endsWith("/plugins/index.yaml")) {
                    hop(302, "../mirror/index.yaml", finalUrl = current)
                } else {
                    hop(200, body = body, finalUrl = current)
                }
            },
        ).fetch(url)

        assertEquals(
            listOf(url, "https://example.com/mirror/index.yaml"),
            sent,
        )
        assertArrayEquals(body, result)
    }

    @Test
    fun `a redirect chain that never lands fails with the repo message`() = runBlocking {
        var sent = 0
        try {
            RepoFetch(
                { current ->
                    sent++
                    hop(302, "/loop", finalUrl = current)
                },
            ).fetch(url)
            fail("expected redirect overflow")
        } catch (e: IllegalStateException) {
            assertEquals("too many redirects for $url", e.message)
            assertFalse("must not surface core's redirect exception", e is RedirectLimitException)
        }
        // initial request + 10 followed hops; the 11th 3xx exceeds the cap.
        assertEquals(11, sent)
    }

    @Test
    fun `3xx without location is an HTTP status failure`() = runBlocking {
        try {
            RepoFetch({ hop(302) }).fetch(url)
            fail("expected HTTP 302 failure")
        } catch (e: IllegalStateException) {
            assertEquals("HTTP 302 for $url", e.message)
        }
    }

    @Test
    fun `redirect limit fails closed without sending the next hop`() = runBlocking {
        val sent = mutableListOf<String>()
        try {
            RepoFetch(
                send = { current ->
                    sent += current
                    hop(302, "/next", finalUrl = current)
                },
                maxRedirects = 2,
            ).fetch(url)
            fail("expected redirect limit failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("too many redirects for $url"))
        }
        assertEquals(3, sent.size)
    }
}
