package dev.mirutime.app.features.reader.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSplitTest {

    private fun block(
        kind: BlockKind,
        text: String,
        sentences: List<SentenceSpan> = emptyList(),
    ) = ReaderBlock(BlockId("chapter", 0), kind, text, sentences = sentences)

    @Test
    fun `split is enabled only for paragraphs`() {
        assertTrue(ReaderSplit.shouldSplit(block(BlockKind.Paragraph, "text"), enabled = true))
        assertFalse(ReaderSplit.shouldSplit(block(BlockKind.Heading, "text"), enabled = true))
        assertFalse(ReaderSplit.shouldSplit(block(BlockKind.Divider, "text"), enabled = true))
        assertFalse(ReaderSplit.shouldSplit(block(BlockKind.Paragraph, "text"), enabled = false))
    }

    @Test
    fun `original spans use parse time split`() {
        val sentences = listOf(SentenceSpan(0, 5), SentenceSpan(6, 11))
        val block = block(BlockKind.Paragraph, "Hello. World.", sentences)

        assertEquals(sentences, ReaderSplit.originalSpans(block))
    }

    @Test
    fun `original spans fall back to the splitter`() {
        val text = "Hello. World."
        val block = block(BlockKind.Paragraph, text)

        assertEquals(SentenceSplitter.spans(text), ReaderSplit.originalSpans(block))
    }
}
