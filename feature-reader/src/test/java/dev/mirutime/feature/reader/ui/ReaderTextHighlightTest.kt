package dev.mirutime.feature.reader.ui

import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.ReaderBlock
import dev.mirutime.feature.tts.api.TtsHighlight
import dev.mirutime.feature.tts.ui.highlightedRange
import org.junit.Assert.assertEquals
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
        val text = highlightedSentence("fghij", sentenceStart = 5, block, highlight(6, 9))

        val style = text.spanStyles.single()
        assertEquals(1, style.start)
        assertEquals(4, style.end)
    }

    @Test
    fun clipsRangeAtSentenceEdges() {
        val text = highlightedSentence("fghij", sentenceStart = 5, block, highlight(3, 20))

        val style = text.spanStyles.single()
        assertEquals(0, style.start)
        assertEquals(5, style.end)
    }

    @Test
    fun ignoresRangeThatBelongsToAnotherSentence() {
        val text = highlightedSentence("fghij", sentenceStart = 0, block, highlight(10, 15))

        assertTrue(text.spanStyles.isEmpty())
    }

    @Test
    fun ignoresTranslatedOrDisabledRange() {
        assertTrue(
            highlightedSentence("fghij", 5, block, highlight(6, 9, translated = true)).spanStyles.isEmpty(),
        )
        assertTrue(
            highlightedSentence("fghij", 5, block, highlight(6, 9, rangeEnabled = false)).spanStyles.isEmpty(),
        )
    }

    @Test
    fun ignoresRangeForAnotherBlock() {
        val text = highlightedSentence(
            "fghij",
            5,
            block,
            highlight(6, 9, blockId = BlockId("another", 0)),
        )

        assertTrue(text.spanStyles.isEmpty())
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
