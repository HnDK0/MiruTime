package dev.mirutime.core.network

import dev.mirutime.core.host.CfChallenge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The challenge navigation target is decided by ONE predicate. Exactly four
// markers mean "no page JS context" (XHR, Accept: json, /admin-ajax.php,
// /api/); a plain non-GET is a browser navigation (form POST) and must load
// its own URL, never the Referer. Every case carries a Referer so a fallback
// to the request URL can only come from the predicate, not from a missing one.
class CfNavigateUrlTest {

    private fun challenge(method: String, url: String, headers: Map<String, String>): CfChallenge {
        val xhr = isXhrRequest(method, url, headers)
        return CfChallenge(
            host = "example.com",
            url = url,
            status = 403,
            markers = emptyList(),
            referer = "https://example.com/browse",
            isXhr = xhr,
        )
    }

    @Test
    fun getWithoutHeaders() {
        val challenge = challenge("GET", "https://example.com/search?q=x", emptyMap())
        assertFalse(challenge.isXhr)
        assertEquals("https://example.com/search?q=x", challenge.navigateUrl)
    }

    @Test
    fun formPostUsesExactUrl() {
        val challenge = challenge(
            "POST",
            "https://example.com/search",
            mapOf("Content-Type" to "application/x-www-form-urlencoded"),
        )
        assertFalse(challenge.isXhr, "a form POST is a navigation, not XHR")
        assertEquals("https://example.com/search", challenge.navigateUrl)
    }

    @Test
    fun postWithOriginUsesExactUrl() {
        val challenge = challenge(
            "POST",
            "https://example.com/search",
            mapOf("Origin" to "https://example.com"),
        )
        assertFalse(challenge.isXhr, "Origin alone does not mean XHR")
        assertEquals("https://example.com/search", challenge.navigateUrl)
    }

    @Test
    fun postWithRequestedWithUsesReferer() {
        val challenge = challenge(
            "POST",
            "https://example.com/wp-admin/admin-ajax.php",
            mapOf("X-Requested-With" to "XMLHttpRequest"),
        )
        assertTrue(challenge.isXhr)
        assertEquals("https://example.com/browse", challenge.navigateUrl)
    }

    @Test
    fun adminAjaxGetUsesReferer() {
        val challenge = challenge("GET", "https://example.com/wp-admin/admin-ajax.php?action=x", emptyMap())
        assertTrue(challenge.isXhr)
        assertEquals("https://example.com/browse", challenge.navigateUrl)
    }

    @Test
    fun apiPathUsesReferer() {
        val challenge = challenge("GET", "https://example.com/api/__api_party/search", emptyMap())
        assertTrue(challenge.isXhr)
        assertEquals("https://example.com/browse", challenge.navigateUrl)
    }

    @Test
    fun jsonAcceptUsesReferer() {
        val challenge = challenge(
            "GET",
            "https://example.com/search",
            mapOf("Accept" to "application/json"),
        )
        assertTrue(challenge.isXhr)
        assertEquals("https://example.com/browse", challenge.navigateUrl)
    }

    @Test
    fun postWithRefererUsesExactUrl() {
        val challenge = challenge(
            "POST",
            "https://example.com/search",
            mapOf("Referer" to "https://example.com/browse"),
        )
        assertFalse(challenge.isXhr)
        assertEquals("https://example.com/search", challenge.navigateUrl)
    }
}
