package dev.mirutime.feature.reader.ui

import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.ReaderBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedPairPlanTest {

    private val original =
        "The first sentence of the paragraph is long enough. " +
            "The second sentence is also long enough to split. " +
            "The third sentence closes the paragraph."
    private val translated =
        "De eerste zin van de paragraaf is lang genoeg. " +
            "De tweede zin is ook lang genoeg om te splitsen. " +
            "De derde zin sluit de paragraaf af."

    private val block = ReaderBlock(
        id = BlockId("chapter", 0),
        kind = BlockKind.Paragraph,
        original = original,
    )

    @Test
    fun pairsEverySentenceInOrderWithoutLoss() {
        val plan = feedPairPlan(block, translated, splitSentences = true)

        assertEquals(3, plan.size)
        assertEquals(
            listOf(
                "The first sentence of the paragraph is long enough.",
                "The second sentence is also long enough to split.",
                "The third sentence closes the paragraph.",
            ),
            plan.map { it.original },
        )
        assertEquals(
            listOf(
                "De eerste zin van de paragraaf is lang genoeg.",
                "De tweede zin is ook lang genoeg om te splitsen.",
                "De derde zin sluit de paragraaf af.",
            ),
            plan.map { it.translated },
        )
        plan.forEach { copy ->
            assertEquals(
                copy.original,
                original.substring(copy.originalStart, copy.originalStart + copy.original.length),
            )
            assertEquals(
                copy.translated,
                translated.substring(copy.translatedStart, copy.translatedStart + copy.translated.length),
            )
        }
        assertEquals(plan.sortedBy { it.originalStart }, plan)
    }

    @Test
    fun splitOffKeepsOneWholeBlockPair() {
        val plan = feedPairPlan(block, translated, splitSentences = false)

        assertEquals(1, plan.size)
        assertEquals(original, plan.single().original)
        assertEquals(translated, plan.single().translated)
    }

    @Test
    fun blankTranslationKeepsOriginalOnly() {
        val plan = feedPairPlan(block, "", splitSentences = true)

        assertEquals(1, plan.size)
        assertTrue(plan.single().translated.isBlank())
        assertEquals(original, plan.single().original)
    }
}
