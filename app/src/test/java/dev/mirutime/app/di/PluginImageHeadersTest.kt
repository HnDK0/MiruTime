package dev.mirutime.app.di

import dev.mirutime.core.network.HostOptionsRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginImageHeadersTest {
    private val options = HostOptionsRegistry()

    @Test
    fun `unregistered hosts get the browser user agent and no referer`() {
        val headers = imageHeadersFor("https://img.example/a.jpg", options)

        assertEquals(setOf("User-Agent"), headers.keys)
        assertTrue(headers.getValue("User-Agent").startsWith("Mozilla/5.0"))
        assertFalse(headers.getValue("User-Agent").startsWith("okhttp/"))
        assertTrue(imageHeadersFor("not a url", options).isEmpty())
    }

    @Test
    fun `registered referer and user agent are returned`() {
        options.setReferer("img.example", "source", "https://site.example/chapter/1")
        options.setUserAgent("img.example", "source", "MiruTime-Test")

        assertEquals(
            mapOf(
                "Referer" to "https://site.example/chapter/1",
                "User-Agent" to "MiruTime-Test",
            ),
            imageHeadersFor("https://img.example/a.jpg", options),
        )
    }

    @Test
    fun `host lookup ignores scheme, www and port`() {
        options.setReferer("img.example", "source", "https://site.example/")

        val headers = imageHeadersFor("http://www.IMG.example:8080/a.jpg", options)

        assertEquals("https://site.example/", headers["Referer"])
        assertTrue(headers.getValue("User-Agent").startsWith("Mozilla/5.0"))
    }

    @Test
    fun `blank user agent falls back to the browser default`() {
        options.setUserAgent("img.example", "source", "  ")

        assertTrue(imageHeadersFor("https://img.example/a.jpg", options).getValue("User-Agent").startsWith("Mozilla/5.0"))
    }

    @Test
    fun `unparseable url gets no user agent`() {
        assertNull(imageHeadersFor("not a url", options)["User-Agent"])
    }

    @Test
    fun `the image header port never builds a cookie header`() {
        options.setReferer("img.example", "source", "https://site.example/chapter/1")
        options.setUserAgent("img.example", "source", "MiruTime-Test")

        val headers = imageHeadersFor("https://img.example/a.jpg", options)

        assertEquals(setOf("Referer", "User-Agent"), headers.keys)
    }
}
