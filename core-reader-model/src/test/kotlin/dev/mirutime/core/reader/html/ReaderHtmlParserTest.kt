package dev.mirutime.core.reader.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderHtmlParserTest {
    @Test
    fun `parses headings and paragraphs`() {
        val blocks = ReaderHtmlParser.parse(
            "<h2>Chapter 1</h2><p>First.</p><p>Second.</p>",
        )
        assertEquals(ParsedBlock.Heading("Chapter 1"), blocks[0])
        assertEquals(ParsedBlock.Paragraph("First."), blocks[1])
        assertEquals(ParsedBlock.Paragraph("Second."), blocks[2])
    }

    @Test
    fun `strips nested tags and turns br into newline`() {
        val blocks = ReaderHtmlParser.parse("<p>Hello <b>bold</b><br/>world</p>")
        assertEquals(listOf(ParsedBlock.Paragraph("Hello bold\nworld")), blocks)
    }

    @Test
    fun `decodes named and numeric entities`() {
        val blocks = ReaderHtmlParser.parse("<p>A&amp;B&nbsp;&#8212;&#x41;</p>")
        assertEquals(listOf(ParsedBlock.Paragraph("A&B —A")), blocks)
    }

    @Test
    fun `drops script and style`() {
        val blocks = ReaderHtmlParser.parse(
            "<style>p{}</style><script>alert(1)</script><p>Text</p>",
        )
        assertEquals(listOf(ParsedBlock.Paragraph("Text")), blocks)
    }

    @Test
    fun `plain text fallback and empty input`() {
        assertTrue(ReaderHtmlParser.parse("").isEmpty())
        assertEquals(
            listOf(ParsedBlock.Paragraph("Just text")),
            ReaderHtmlParser.parse("<div>Just text</div>"),
        )
    }
}
