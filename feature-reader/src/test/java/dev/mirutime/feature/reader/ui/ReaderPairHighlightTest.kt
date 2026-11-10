package dev.mirutime.feature.reader.ui

import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.feature.tts.api.TtsHighlight
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parallel pair needs the same tint predicate as a single copy: the cursor
 * is compared in the string space of the copy that is actually spoken.
 */
class ReaderPairHighlightTest {
    private val blockId = BlockId("chapter", 3)
    private val otherBlock = BlockId("chapter", 4)

    // translated "Hello world." has 12 chars; original "Привет мир." has 11.
    private val copy = FeedPairCopy(
        translated = "Hello world.",
        translatedStart = 100,
        original = "Привет мир.",
        originalStart = 200,
    )

    private fun highlight(
        start: Int,
        translated: Boolean,
        block: BlockId = blockId,
    ) = TtsHighlight(
        blockId = block,
        start = start,
        end = start + 1,
        colorArgb = 0xFF00FF00,
        translated = translated,
    )

    @Test
    fun `no highlight never tints a pair`() {
        assertFalse(pairHighlightActive(blockId, copy, null))
    }

    @Test
    fun `a cursor in another block never tints this pair`() {
        assertFalse(pairHighlightActive(blockId, copy, highlight(100, translated = true, block = otherBlock)))
        assertFalse(pairHighlightActive(blockId, copy, highlight(200, translated = false, block = otherBlock)))
    }

    @Test
    fun `the translated copy tints only in the translated string space`() {
        assertTrue(pairHighlightActive(blockId, copy, highlight(105, translated = true)))
        // The same number lies in the original string's space; it must not tint.
        assertFalse(pairHighlightActive(blockId, copy, highlight(205, translated = true)))
    }

    @Test
    fun `the original copy tints only in the original string space`() {
        assertTrue(pairHighlightActive(blockId, copy, highlight(205, translated = false)))
        assertFalse(pairHighlightActive(blockId, copy, highlight(105, translated = false)))
    }

    @Test
    fun `the cursor range is half-open`() {
        val firstTranslated = copy.translatedStart
        val endTranslated = copy.translatedStart + copy.translated.length
        assertTrue(pairHighlightActive(blockId, copy, highlight(firstTranslated, translated = true)))
        assertFalse(pairHighlightActive(blockId, copy, highlight(endTranslated, translated = true)))
    }

    @Test
    fun `a blank copy is never tinted`() {
        val blankTranslated = copy.copy(translated = "", translatedStart = 0)
        val blankOriginal = copy.copy(original = "", originalStart = 0)

        assertFalse(pairHighlightActive(blockId, blankTranslated, highlight(0, translated = true)))
        assertFalse(pairHighlightActive(blockId, blankOriginal, highlight(0, translated = false)))
    }
}
