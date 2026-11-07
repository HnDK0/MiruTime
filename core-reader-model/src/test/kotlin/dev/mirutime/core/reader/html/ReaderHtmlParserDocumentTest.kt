package dev.mirutime.core.reader.html

import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.SentenceSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderHtmlParserDocumentTest {
    @Test
    fun `document blocks keep stable ids and sentence spans`() {
        val blocks = ReaderHtmlParser.parseDocument(
            html = "<h2>Title</h2><p>One. Two!</p>",
            chapterUrl = "b#chapter=1",
        )

        assertEquals(2, blocks.size)
        assertEquals(BlockKind.Heading, blocks[0].kind)
        assertEquals(BlockId("b#chapter=1", 0), blocks[0].id)
        assertEquals(BlockId("b#chapter=1", 1), blocks[1].id)
        assertEquals(
            listOf(SentenceSpan(0, 4), SentenceSpan(5, 9)),
            blocks[1].sentences,
        )
    }

    @Test
    fun `long paragraph gets sentence spans from the shared splitter`() {
        val text = "For a few minutes after that, Sunny was in a dark mood. But then he pulled himself out of it and inhaled deeply, trying to enjoy the fresh air. Indeed, air like that was hard to come by in the real world: micro dust and other pollutants made it rough and unpleasant."
        val blocks = ReaderHtmlParser.parseDocument("<p>$text</p>", "c")

        assertEquals(3, blocks.single().sentences.size)
        assertEquals(text, blocks.single().sentences.joinToString(" ") { text.substring(it.start, it.end) })
        assertEquals(text.length, blocks.single().sentences.last().end)
    }

    @Test
    fun `inline images become image blocks with resolved urls`() {
        val blocks = ReaderHtmlParser.parseDocument(
            html = "<p>before<img src=\"/img/a.png\" alt=\"A\"/>after</p>" +
                "<p><img src=\"https://cdn.site/b.jpg\"/></p>",
            chapterUrl = "https://site/book/chapter/1",
        )

        assertEquals(
            listOf(BlockKind.Paragraph, BlockKind.Image, BlockKind.Paragraph, BlockKind.Image),
            blocks.map { it.kind },
        )
        assertEquals("before", blocks[0].original)
        assertEquals("https://site/img/a.png", blocks[1].imageRef)
        assertEquals("A", blocks[1].original)
        assertEquals("after", blocks[2].original)
        assertEquals("https://cdn.site/b.jpg", blocks[3].imageRef)
    }

    @Test
    fun `image alt text is angle-normalized`() {
        val blocks = ReaderHtmlParser.parseDocument(
            html = "<p><img src=\"a.png\" alt=\"Power < 10\"/></p>",
            chapterUrl = "c",
        )

        assertEquals("Power ⟨ 10", blocks.single { it.kind == BlockKind.Image }.original)
    }

    @Test
    fun `oversized paragraph is split into bounded blocks`() {
        val longText = (1..2_000).joinToString(" ") { "word" }
        val blocks = ReaderHtmlParser.parseDocument("<p>$longText</p>", "c")

        assertTrue(blocks.size > 1)
        assertTrue(blocks.all { it.original.length <= 4_000 })
        assertEquals(longText, blocks.joinToString(" ") { it.original })
    }

    @Test
    fun `plain text becomes one paragraph`() {
        val blocks = ReaderHtmlParser.parseDocument("One line", "c")
        assertEquals(1, blocks.size)
        assertEquals(BlockKind.Paragraph, blocks[0].kind)
        assertEquals("One line", blocks[0].original)
    }
}
