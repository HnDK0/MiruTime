package dev.mirutime.core.reader.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HtmlEntitiesTest {
    @Test
    fun `common typography decodes`() {
        assertEquals("—", HtmlEntities.decode("mdash"))
        assertEquals("–", HtmlEntities.decode("ndash"))
        assertEquals("…", HtmlEntities.decode("hellip"))
        assertEquals("«", HtmlEntities.decode("laquo"))
        assertEquals("»", HtmlEntities.decode("raquo"))
        assertEquals("“", HtmlEntities.decode("ldquo"))
        assertEquals("”", HtmlEntities.decode("rdquo"))
        assertEquals("‘", HtmlEntities.decode("lsquo"))
        assertEquals("’", HtmlEntities.decode("rsquo"))
        assertEquals("•", HtmlEntities.decode("bull"))
        assertEquals("€", HtmlEntities.decode("euro"))
    }

    @Test
    fun `case sensitivity is respected`() {
        assertEquals("Α", HtmlEntities.decode("Alpha"))
        assertEquals("α", HtmlEntities.decode("alpha"))
        assertEquals("Ä", HtmlEntities.decode("Auml"))
        assertEquals("ä", HtmlEntities.decode("auml"))
    }

    @Test
    fun `basic and escaped markup names decode`() {
        assertEquals("&", HtmlEntities.decode("amp"))
        assertEquals("<", HtmlEntities.decode("lt"))
        assertEquals(">", HtmlEntities.decode("gt"))
        assertEquals("\"", HtmlEntities.decode("quot"))
        assertEquals("'", HtmlEntities.decode("apos"))
    }

    @Test
    fun `unknown names return null`() {
        assertNull(HtmlEntities.decode("definitelyNotAnEntity"))
        assertNull(HtmlEntities.decode("NBSP"))
    }

    @Test
    fun `html4 uppercase variants decode`() {
        assertEquals("&", HtmlEntities.decode("AMP"))
        assertEquals("<", HtmlEntities.decode("LT"))
        assertEquals("©", HtmlEntities.decode("COPY"))
        assertEquals("®", HtmlEntities.decode("REG"))
    }

    @Test
    fun `parser decodes named numeric and supplementary references`() {
        val blocks = ReaderHtmlParser.parse(
            "<p>&mdash; &hellip; &laquo;text&raquo; &#65; &#x1F600; &unknown;</p>",
        )
        assertEquals("— … «text» A 😀", (blocks.single() as ParsedBlock.Paragraph).text)
    }

    @Test
    fun `invalid numeric references are dropped rather than spoken`() {
        val blocks = ReaderHtmlParser.parse("<p>a &#xD800; b &#0; c &#x110000; end</p>")
        assertEquals("a b c end", (blocks.single() as ParsedBlock.Paragraph).text)
    }
}
