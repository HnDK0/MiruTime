package dev.mirutime.app.di.host

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// CookieManager-backed store contract: the platform owns domain/path/expiry,
// the store owns parsing, the response URL and the batch flush.
class WebViewCookieStoreTest {

    private val url = "https://example.com/book/1"

    @Test
    fun `get parses the manager header into name value pairs`() = runTest {
        val storage = FakeWebCookieStorage().apply {
            cookieHeaders[url] = "cf_clearance=abc; session=1; empty=; malformed"
        }

        val cookies = WebViewCookieStore(storage).get(url)

        assertEquals(
            listOf("cf_clearance" to "abc", "session" to "1", "empty" to ""),
            cookies.map { it.name to it.value },
        )
    }

    @Test
    fun `get keeps the manager order when a name appears on two paths`() = runTest {
        val storage = FakeWebCookieStorage().apply {
            cookieHeaders[url] = "sid=specific; theme=dark; sid=root"
        }

        val cookies = WebViewCookieStore(storage).get(url)

        assertEquals(
            listOf("sid" to "specific", "theme" to "dark", "sid" to "root"),
            cookies.map { it.name to it.value },
        )
    }

    @Test
    fun `setFromResponse forwards the raw header against the response url`() = runTest {
        val storage = FakeWebCookieStorage()

        WebViewCookieStore(storage).setFromResponse(
            "https://cdn.example.com/img/1.jpg",
            listOf("sid=1; Domain=.example.org; Path=/", "theme=dark; Path=/"),
        )

        assertEquals(
            listOf(
                "https://cdn.example.com/img/1.jpg" to "sid=1; Domain=.example.org; Path=/",
                "https://cdn.example.com/img/1.jpg" to "theme=dark; Path=/",
            ),
            storage.setCalls,
        )
        assertEquals("one flush per batch", 1, storage.flushes)
    }

    @Test
    fun `clearCookies expires names on both schemes and bare and dotted domains`() = runTest {
        val storage = FakeWebCookieStorage()

        WebViewCookieStore(storage).clearCookies("Example.COM", listOf("cf_clearance"))

        assertEquals(6, storage.setCalls.size)
        assertTrue(
            storage.setCalls.all { (_, header) -> "cf_clearance=; Max-Age=0; Path=/" in header },
        )
        assertEquals(
            setOf("https://example.com/", "http://example.com/"),
            storage.setCalls.map { it.first }.toSet(),
        )
        val headers = storage.setCalls.map { it.second }
        assertTrue(headers.any { "Domain=" !in it })
        assertTrue(headers.any { "Domain=example.com" in it })
        assertTrue(headers.any { "Domain=.example.com" in it })
        assertEquals("clearCookies ends with one flush", 1, storage.flushes)
    }

    @Test
    fun `clear expires every name the host currently holds`() = runTest {
        val storage = FakeWebCookieStorage().apply {
            cookieHeaders["https://example.com/"] = "a=1; b=2"
            cookieHeaders["http://example.com/"] = "c=3"
        }

        WebViewCookieStore(storage).clear("example.com")

        assertEquals(
            setOf("a", "b", "c"),
            storage.setCalls.map { it.second.substringBefore('=') }.toSet(),
        )
    }

    @Test
    fun `an unavailable manager degrades to empty and never throws`() = runTest {
        val storage = FakeWebCookieStorage().apply { unavailable = true }
        val store = WebViewCookieStore(storage)

        assertTrue(store.get(url).isEmpty())
        store.setFromResponse(url, listOf("sid=1; Path=/"))
        store.clearCookies("example.com", listOf("sid"))
        store.clear("example.com")
    }
}
