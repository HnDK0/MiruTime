package dev.mirutime.feature.tts

import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.ReaderBlock
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.app.features.reader.document.SentenceSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsChunkerTest {
    private fun document(vararg blocks: ReaderBlock) = ReaderDocument(
        bookUrl = "local://book",
        chapterUrl = "local://book#chapter=1",
        chapterIndex = 1,
        title = "Chapter",
        blocks = blocks.toList(),
    )

    private fun paragraph(index: Int, text: String, spans: List<SentenceSpan> = emptyList()) =
        ReaderBlock(BlockId("local://book#chapter=1", index), BlockKind.Paragraph, text, sentences = spans)

    @Test
    fun `sentences become chunks and images are skipped`() {
        val doc = document(
            paragraph(0, "One. Two.", listOf(SentenceSpan(0, 4), SentenceSpan(5, 9))),
            ReaderBlock(BlockId("local://book#chapter=1", 1), BlockKind.Image, "cover.jpg", imageRef = "cover.jpg"),
        )

        val chunks = TtsChunker.chunks(doc)

        assertEquals(listOf("One.", "Two."), chunks.map { it.text })
        assertEquals(listOf(0, 1), chunks.map { it.sentenceIndex })
        assertTrue(chunks.all { !it.translated })
    }

    @Test
    fun `ready translation wins over original`() {
        val doc = document(paragraph(0, "Hello world."))

        val chunks = TtsChunker.chunks(doc, mapOf(0 to "Привет мир."))

        assertEquals("Привет мир.", chunks.single().text)
        assertTrue(chunks.single().translated)
    }

    @Test
    fun `missing translation falls back to original`() {
        val doc = document(paragraph(0, "Original."))

        val chunks = TtsChunker.chunks(doc, mapOf(9 to "Other."))

        assertEquals("Original.", chunks.single().text)
        assertFalse(chunks.single().translated)
    }

    @Test
    fun `long text is split into bounded chunks`() {
        val text = (1..1200).joinToString(" ") { "word$it" }
        val doc = document(paragraph(0, text))

        val chunks = TtsChunker.chunks(doc)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= 3800 })
        assertEquals(text.split(' ').size, chunks.sumOf { it.text.split(' ').size })
    }

    @Test
    fun `status panels logs and dividers are not spoken`() {
        val doc = document(
            paragraph(0, "***"),
            paragraph(1, "Name: Kaito\nLevel: 12\nHP: 120/150"),
            paragraph(2, "[12:34] Alice: hi\n[12:35] Bob: yo"),
            paragraph(3, "Real prose."),
        )

        val chunks = TtsChunker.chunks(doc)

        assertEquals(listOf("Real prose."), chunks.map { it.text })
        assertEquals(listOf(3), chunks.map { it.blockId.index })
    }

    @Test
    fun `translated noise is not spoken either`() {
        val doc = document(paragraph(0, "Original prose."))

        val chunks = TtsChunker.chunks(doc, mapOf(0 to "HP: 120 / MP: 30"))

        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `decorated text keeps offsets on real characters`() {
        val text = "*** Hello. ***"
        val doc = document(paragraph(0, text, listOf(SentenceSpan(4, 10))))

        val chunk = TtsChunker.chunks(doc).single()

        assertEquals("Hello.", chunk.text)
        assertEquals(4, chunk.startOffset)
        assertEquals(10, chunk.endOffset)
        assertEquals("Hello.", text.substring(chunk.startOffset, chunk.endOffset))
    }

    @Test
    fun `newlines inside a chunk become spaces without shifting offsets`() {
        val text = "One.\nTwo."
        val doc = document(paragraph(0, text, listOf(SentenceSpan(0, 9))))

        val chunk = TtsChunker.chunks(doc).single()

        assertEquals("One. Two.", chunk.text)
        assertEquals(0, chunk.startOffset)
        assertEquals(9, chunk.endOffset)
    }
}
