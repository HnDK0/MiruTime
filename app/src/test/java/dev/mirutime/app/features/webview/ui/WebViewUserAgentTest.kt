package dev.mirutime.app.features.webview.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebViewUserAgentTest {

    @Test
    fun `host resolution is applied to the page load`() {
        val ua = webViewUserAgent("https://pic.desu.xxx/c.jpg") { "native-ua" }
        assertEquals("native-ua", ua)
    }

    @Test
    fun `an unresolved host leaves the webview default`() {
        assertNull(webViewUserAgent("https://example.test/page") { null })
        assertNull(webViewUserAgent("https://example.test/page") { "  " })
    }

    @Test
    fun `a blank or missing url never reaches the resolver`() {
        var calls = 0
        assertNull(webViewUserAgent(null) { calls++; "native-ua" })
        assertNull(webViewUserAgent("   ") { calls++; "native-ua" })
        assertEquals(0, calls)
    }

    @Test
    fun `a history step targets the neighbouring entry`() {
        assertEquals(1, historyTargetIndex(currentIndex = 2, size = 4, step = -1))
        assertEquals(3, historyTargetIndex(currentIndex = 2, size = 4, step = 1))
    }

    @Test
    fun `a history step past either end has no target`() {
        assertNull(historyTargetIndex(currentIndex = 0, size = 4, step = -1))
        assertNull(historyTargetIndex(currentIndex = 3, size = 4, step = 1))
        assertNull(historyTargetIndex(currentIndex = 0, size = 0, step = 1))
    }
}
