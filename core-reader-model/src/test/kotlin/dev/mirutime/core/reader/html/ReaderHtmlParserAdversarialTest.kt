package dev.mirutime.core.reader.html

import dev.mirutime.app.features.reader.document.BlockKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the tag regexes against catastrophic backtracking and text loss on
 * malformed input: chapters are user-visible, so a hang here freezes the reader.
 */
class ReaderHtmlParserAdversarialTest {

    @Test(timeout = 5_000)
    fun `attribute-like noise does not hang`() {
        val html = "<p>" + "<a " + "x=".repeat(2_000) + "</p>"

        val blocks = ReaderHtmlParser.parse(html)

        assertTrue(blocks.isNotEmpty())
    }

    @Test(timeout = 5_000)
    fun `many unclosed angle brackets do not hang`() {
        val html = "<p>" + "<".repeat(2_000) + "</p>"

        val blocks = ReaderHtmlParser.parse(html)

        assertTrue(blocks.isNotEmpty())
    }

    @Test(timeout = 5_000)
    fun `long unclosed tag keeps its text`() {
        val tail = "a".repeat(3_000)
        val input = "start <div $tail"

        val blocks = ReaderHtmlParser.parse(input)

        assertEquals(listOf(ParsedBlock.Paragraph("start ⟨div $tail")), blocks)
    }

    @Test(timeout = 5_000)
    fun `large chapter parses every block`() {
        val paragraph = "<p>" + "Sentence one. Sentence two. ".repeat(20) + "</p>"
        val html = paragraph.repeat(300)

        val blocks = ReaderHtmlParser.parseDocument(html, "chapter")

        assertEquals(300, blocks.size)
        assertTrue(blocks.all { it.kind == BlockKind.Paragraph })
        assertTrue(blocks.all { it.original.isNotBlank() })
    }

    @Test(timeout = 5_000)
    fun `transform runs on every text block`() {
        val blocks = ReaderHtmlParser.parseDocument("<p>a</p><p>b</p>", "chapter") {
            it.uppercase()
        }

        assertEquals(listOf("A", "B"), blocks.map { it.original })
    }
}
