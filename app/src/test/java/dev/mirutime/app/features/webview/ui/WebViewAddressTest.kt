package dev.mirutime.app.features.webview.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebViewAddressTest {
    @Test
    fun addsHttpsWhenSchemeIsMissing() {
        assertEquals("https://example.com/chapter/2", normalizeAddress("example.com/chapter/2"))
        // A bare host with a port must stay a host, not become a scheme.
        assertEquals("https://example.com:8080/x", normalizeAddress("example.com:8080/x"))
        assertEquals("https://example.com", normalizeAddress("  example.com  "))
    }

    @Test
    fun keepsHttpAndKnownAppSchemes() {
        assertEquals("https://example.com", normalizeAddress("https://example.com"))
        assertEquals("http://example.com", normalizeAddress("http://example.com"))
        assertEquals("mailto:hi@example.com", normalizeAddress("mailto:hi@example.com"))
        assertEquals("intent://scan/#Intent;scheme=zxing;end", normalizeAddress("intent://scan/#Intent;scheme=zxing;end"))
        assertEquals("ftp://example.com/file", normalizeAddress("ftp://example.com/file"))
    }

    @Test
    fun rejectsBlankInput() {
        assertNull(normalizeAddress(""))
        assertNull(normalizeAddress("   "))
    }
}
