package dev.mirutime.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

class ParagraphHtmlTest {

    @Test
    fun `newlines split paragraphs and text is escaped`() {
        assertEquals(
            "<p>A &amp; B</p><p>C &lt;tag&gt;</p>",
            textToParagraphHtml("A & B\n\n  C <tag>  \n\n"),
        )
    }

    @Test
    fun `blank text yields empty html`() {
        assertEquals("", textToParagraphHtml("   \n  \n"))
    }

    @Test
    fun `block html is preserved`() {
        val html = "<p>One</p><p><img src=\"images/1.jpg\"/></p>"
        assertEquals(html, chapterHtml(html))
    }

    @Test
    fun `plain text with a literal less-than is escaped`() {
        assertEquals("<p>Tom &amp; Jerry &lt;tag&gt;</p>", chapterHtml("Tom & Jerry <tag>"))
    }
}
