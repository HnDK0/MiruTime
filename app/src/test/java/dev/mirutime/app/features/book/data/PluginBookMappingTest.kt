package dev.mirutime.app.features.book.data

import dev.mirutime.core.text.chapterHtml
import dev.mirutime.core.text.textToParagraphHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Pure mapping helpers of the plugin book provider — no Android imports.
class PluginBookMappingTest {

    @Test
    fun `blank lines split paragraphs and wrap in p tags`() {
        assertEquals(
            "<p>First para.</p><p>Second para.</p>",
            textToParagraphHtml("First para.\n\nSecond para."),
        )
    }

    @Test
    fun `single newlines also split paragraphs`() {
        assertEquals(
            "<p>Line one</p><p>Line two</p><p>Line three</p>",
            textToParagraphHtml("Line one\nLine two\nLine three"),
        )
    }

    @Test
    fun `special characters are escaped`() {
        assertEquals("<p>Tom &amp; Jerry &lt;tag&gt;</p>", textToParagraphHtml("Tom & Jerry <tag>"))
    }

    @Test
    fun `carriage returns are normalised and blank runs collapsed`() {
        assertEquals("<p>A</p><p>B</p>", textToParagraphHtml("A\r\n\r\n  B  \n\n\n"))
    }

    @Test
    fun `blank chapter text yields empty html`() {
        assertEquals("", textToParagraphHtml("   \n  \n"))
    }

    @Test
    fun `html chapters preserve img tags and relative sources`() {
        val html = "<p>One</p><p><img src=\"images/1.jpg\"/></p>"
        assertEquals(html, chapterHtml(html))
    }

    @Test
    fun `plain text with a literal less-than is escaped`() {
        assertEquals(
            "<p>Tom &amp; Jerry &lt;tag&gt;</p>",
            chapterHtml("Tom & Jerry <tag>"),
        )
    }

    @Test
    fun `rating parses bare numbers and rejects garbage`() {
        assertEquals(4.5f, parsePluginRating("4.5"), 0.0001f)
        assertEquals(0f, parsePluginRating(null), 0.0001f)
        assertEquals(0f, parsePluginRating(""), 0.0001f)
        assertEquals(0f, parsePluginRating("abc"), 0.0001f)
    }

    @Test
    fun `rating scales slash-max onto the 0-5 scale`() {
        assertEquals(4.75f, parsePluginRating("9.5/10"), 0.0001f)
    }

    @Test
    fun `rating clamps out-of-range and non-finite values`() {
        assertEquals(5f, parsePluginRating("7"), 0.0001f)
        assertEquals(0f, parsePluginRating("-2"), 0.0001f)
        assertEquals(0f, parsePluginRating("NaN"), 0.0001f)
    }

    @Test
    fun `status maps to ongoing`() {
        assertTrue(pluginStatusOngoing("Ongoing"))
        assertTrue(pluginStatusOngoing("Releasing (ongoing)"))
        assertFalse(pluginStatusOngoing("Completed"))
        assertFalse(pluginStatusOngoing("Finished"))
        assertFalse(pluginStatusOngoing(null))
        assertFalse(pluginStatusOngoing(""))
    }
}