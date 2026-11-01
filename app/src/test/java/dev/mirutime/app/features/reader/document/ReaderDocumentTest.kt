package dev.mirutime.app.features.reader.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ReaderDocumentTest {
    private fun block(index: Int, kind: BlockKind, original: String) = ReaderBlock(
        id = BlockId("chapter", index),
        kind = kind,
        original = original,
    )

    private fun document(
        title: String,
        blocks: List<ReaderBlock>,
    ) = ReaderDocument(
        bookUrl = "book",
        chapterUrl = "chapter",
        chapterIndex = 0,
        title = title,
        blocks = blocks,
    )

    @Test
    fun `title block is the leading heading equal to title`() {
        val doc = document(
            title = "Falling Sideways",
            blocks = listOf(
                block(0, BlockKind.Heading, "Falling Sideways"),
                block(1, BlockKind.Paragraph, "Text"),
            ),
        )

        assertEquals(doc.blocks[0], doc.titleBlock())
    }

    @Test
    fun `title block ignores surrounding whitespace`() {
        val doc = document(
            title = "Falling Sideways",
            blocks = listOf(
                block(0, BlockKind.Heading, "  Falling Sideways  "),
                block(1, BlockKind.Paragraph, "Text"),
            ),
        )

        assertEquals(doc.blocks[0], doc.titleBlock())
    }

    @Test
    fun `title block is null when heading differs or is not leading`() {
        val different = document(
            title = "Falling Sideways",
            blocks = listOf(
                block(0, BlockKind.Heading, "Chapter 126"),
                block(1, BlockKind.Paragraph, "Text"),
            ),
        )
        val notLeading = document(
            title = "Falling Sideways",
            blocks = listOf(
                block(0, BlockKind.Paragraph, "Intro"),
                block(1, BlockKind.Heading, "Falling Sideways"),
            ),
        )
        val paragraph = document(
            title = "Falling Sideways",
            blocks = listOf(block(0, BlockKind.Paragraph, "Falling Sideways")),
        )

        assertNull(different.titleBlock())
        assertNull(notLeading.titleBlock())
        assertNull(paragraph.titleBlock())
    }

    @Test
    fun `body blocks drop only the duplicated title block`() {
        val title = block(0, BlockKind.Heading, "Falling Sideways")
        val body = listOf(title, block(1, BlockKind.Paragraph, "One"), block(2, BlockKind.Paragraph, "Two"))
        val doc = document(title = "Falling Sideways", blocks = body)

        assertEquals(listOf(body[1], body[2]), doc.bodyBlocks())
    }

    @Test
    fun `body blocks keep original block ids so anchors stay valid`() {
        val blocks = listOf(
            block(0, BlockKind.Heading, "Falling Sideways"),
            block(1, BlockKind.Paragraph, "One"),
        )
        val doc = document(title = "Falling Sideways", blocks = blocks)

        assertEquals(listOf(1), doc.bodyBlocks().map { it.id.index })
        assertSame(blocks[1], doc.blockAt(blocks[1].id))
        assertEquals(ReadingAnchor("chapter", blocks[0].id, 0), doc.firstAnchor())
    }

    @Test
    fun `body blocks are all blocks when there is no title block`() {
        val blocks = listOf(block(0, BlockKind.Paragraph, "One"))
        val doc = document(title = "Falling Sideways", blocks = blocks)

        assertEquals(blocks, doc.bodyBlocks())
    }
}
