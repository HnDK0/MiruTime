package dev.mirutime.feature.reader.ui

import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.ReaderBlock
import dev.mirutime.app.features.reader.document.SentenceSpan
import dev.mirutime.feature.tts.api.TtsHighlight
import dev.mirutime.feature.tts.ui.highlightedRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTextHighlightTest {

    private val block = ReaderBlock(
        id = BlockId("chapter", 0),
        kind = BlockKind.Paragraph,
        original = "abcdefghijklmnopqrst",
    )

    private fun highlight(
        start: Int,
        end: Int,
        translated: Boolean = false,
        rangeEnabled: Boolean = true,
        blockId: BlockId = block.id,
    ) = TtsHighlight(
        blockId = blockId,
        start = start,
        end = end,
        colorArgb = 0xFFFF0000,
        translated = translated,
        rangeEnabled = rangeEnabled,
    )

    @Test
    fun mapsBlockRangeOntoOneSentence() {
        val range = ttsHighlightRange("fghij".length, block.id, highlight(6, 9), isTranslated = false, baseOffset = 5)

        assertEquals(1 until 4, range)
    }

    @Test
    fun clipsRangeAtSentenceEdges() {
        val range = ttsHighlightRange("fghij".length, block.id, highlight(3, 20), isTranslated = false, baseOffset = 5)

        assertEquals(0 until 5, range)
    }

    @Test
    fun ignoresRangeThatBelongsToAnotherSentence() {
        val range = ttsHighlightRange("fghij".length, block.id, highlight(10, 15), isTranslated = false, baseOffset = 0)

        assertNull(range)
    }

    @Test
    fun ignoresTranslatedOrDisabledRange() {
        assertNull(ttsHighlightRange("fghij".length, block.id, highlight(6, 9, translated = true), isTranslated = false, baseOffset = 5))
        assertNull(ttsHighlightRange("fghij".length, block.id, highlight(6, 9, rangeEnabled = false), isTranslated = false, baseOffset = 5))
    }

    @Test
    fun ignoresRangeForAnotherBlock() {
        val range = ttsHighlightRange(
            "fghij".length,
            block.id,
            highlight(6, 9, blockId = BlockId("another", 0)),
            isTranslated = false,
            baseOffset = 5,
        )

        assertNull(range)
    }

    @Test
    fun activeSentenceRangePicksSpanContainingCursor() {
        val spans = listOf(SentenceSpan(0, 5), SentenceSpan(6, 11))

        assertEquals(6 until 11, activeSentenceRange(spans, block.id, highlight(7, 9), isTranslated = false))
        assertEquals(0 until 5, activeSentenceRange(spans, block.id, highlight(2, 3), isTranslated = false))
    }

    @Test
    fun activeSentenceRangeIgnoresOtherCopyBlockOrMissingHighlight() {
        val spans = listOf(SentenceSpan(0, 5), SentenceSpan(6, 11))

        assertNull(activeSentenceRange(spans, block.id, highlight(7, 9, translated = true), isTranslated = false))
        assertNull(activeSentenceRange(spans, block.id, highlight(7, 9, blockId = BlockId("another", 0)), isTranslated = false))
        assertNull(activeSentenceRange(spans, block.id, null, isTranslated = false))
    }

    @Test
    fun activeSentenceRangeIgnoresCursorOutsideSpans() {
        val spans = listOf(SentenceSpan(0, 5), SentenceSpan(6, 11))

        assertNull(activeSentenceRange(spans, block.id, highlight(5, 6), isTranslated = false))
    }

    @Test
    fun mapsRelativeRangeOntoDisplayedText() {
        val text = highlightedRange("Spoken words here", 7, 12, 0xFFFF0000)

        val style = text.spanStyles.single()
        assertEquals(7, style.start)
        assertEquals(12, style.end)
    }

    @Test
    fun clipsRelativeRangeToText() {
        val text = highlightedRange("abc", -5, 99, 0xFFFF0000)

        val style = text.spanStyles.single()
        assertEquals(0, style.start)
        assertEquals(3, style.end)
    }

    @Test
    fun emptyRelativeRangeAddsNoHighlight() {
        assertTrue(highlightedRange("abc", 2, 2, 0xFFFF0000).spanStyles.isEmpty())
        assertTrue(highlightedRange("abc", 10, 20, 0xFFFF0000).spanStyles.isEmpty())
    }
}
