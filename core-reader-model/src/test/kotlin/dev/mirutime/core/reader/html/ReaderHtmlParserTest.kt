package dev.mirutime.core.reader.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `literal angle prose survives and becomes typographic`() {
        assertEquals(
            listOf(ParsedBlock.Paragraph("He said ⟨cosmic announcement⟩ now.")),
            ReaderHtmlParser.parse("<p>He said <cosmic announcement> now.</p>"),
        )
        assertEquals(
            listOf(ParsedBlock.Paragraph("⟨Alice⟩ Hello")),
            ReaderHtmlParser.parse("<p>&lt;Alice&gt; Hello</p>"),
        )
        assertEquals(
            listOf(ParsedBlock.Paragraph("⟨Alice⟩ Hi")),
            ReaderHtmlParser.parse("<p><Alice> Hi</p>"),
        )
    }

    @Test
    fun `real tags with boolean and spaced attributes are removed`() {
        assertEquals(
            listOf(ParsedBlock.Paragraph("text")),
            ReaderHtmlParser.parse("<div hidden>text</div>"),
        )
        assertEquals(
            listOf(ParsedBlock.Paragraph("ST")),
            ReaderHtmlParser.parse("<details open><summary>S</summary>T</details>"),
        )
        assertEquals(
            listOf(ParsedBlock.Paragraph("link")),
            ReaderHtmlParser.parse("<p><a href = \"x\">link</a></p>"),
        )
        assertEquals(
            listOf(ParsedBlock.Paragraph("t")),
            ReaderHtmlParser.parse("<p><custom data-x=\"1\">t</custom></p>"),
        )
    }

    @Test
    fun `math and attribute-like prose is not treated as a tag`() {
        assertEquals(
            listOf(ParsedBlock.Paragraph("⟨x = y⟩")),
            ReaderHtmlParser.parse("<p><x = y></p>"),
        )
        assertEquals(
            listOf(ParsedBlock.Paragraph("a ⟨ b ⟩ c")),
            ReaderHtmlParser.parse("<p>a < b > c</p>"),
        )
    }

    @Test
    fun `declarations and comments are removed`() {
        assertEquals(
            listOf(ParsedBlock.Paragraph("Text")),
            ReaderHtmlParser.parse("<!DOCTYPE html><p>Text</p>"),
        )
        assertEquals(
            listOf(ParsedBlock.Paragraph("Text")),
            ReaderHtmlParser.parse("<!-- note --><p>Text</p>"),
        )
    }

    @Test
    fun `escaped markup names become literal typographic brackets`() {
        assertEquals(
            listOf(ParsedBlock.Paragraph("⟨b⟩bold⟨/b⟩")),
            ReaderHtmlParser.parse("<p>&lt;b&gt;bold&lt;/b&gt;</p>"),
        )
    }

    @Test
    fun `parsed text never keeps ascii angle brackets`() {
        val blocks = ReaderHtmlParser.parse(
            "<p>a < b > c &lt;System&gt; <b>x</b> <custom data-y=\"2\">z</custom></p>",
        )
        assertTrue(blocks.isNotEmpty())
        val text = blocks.joinToString("") { (it as ParsedBlock.Paragraph).text }
        assertFalse(text.contains('<'))
        assertFalse(text.contains('>'))
    }
}
