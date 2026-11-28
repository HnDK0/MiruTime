package dev.mirutime.core.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private fun xml(text: String): XmlElement = assertNotNull(parseXmlDocument(text.toByteArray(Charsets.UTF_8)))

private fun fb2(body: String) = """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
<description><title-info><book-title>T</book-title><author><first-name>A</first-name><last-name>B</last-name></author></title-info></description>
<body>$body</body></FictionBook>"""

class XmlTextOrderTest {
    @Test
    fun textContentFollowsDocumentOrder() {
        assertEquals("one two three", xml("<a>one <b>two</b> three</a>").textContent)
        assertEquals("x123y", xml("<a>x<b>1<c>2</c>3</b>y</a>").textContent)
        assertEquals("xyz", xml("<a>x<![CDATA[y]]>z</a>").textContent)
    }

    @Test
    fun textStillMergesTheDirectTextNodes() {
        assertEquals("one  three", xml("<a>one <b>two</b> three</a>").text)
        assertEquals("only", xml("<a>only</a>").text)
    }

    @Test
    fun inlineMarkupInAFb2ParagraphKeepsItsPlace() {
        val book = parseFb2(fb2("<section><p>This is <emphasis>important</emphasis> text.</p></section>").toByteArray())
        assertEquals("This is important text.", book.chapters.single().body.trim())
    }
}
