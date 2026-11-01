package dev.mirutime.translator

import dev.mirutime.translator.internal.chunkUnitGroups
import dev.mirutime.translator.internal.chunkUnits
import dev.mirutime.translator.internal.llmMaxChars
import dev.mirutime.translator.internal.splitToBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChunkingTest {
    @Test
    fun llmBudgetUsesTargetLanguage() {
        assertEquals(
            307,
            llmMaxChars(null, 1_000, 1_000, LanguageCode.parse("zh")),
        )
        assertEquals(
            2153,
            llmMaxChars(null, 1_000, 1_000, LanguageCode.parse("en")),
        )
        assertEquals(
            1230,
            llmMaxChars(null, 1_000, 1_000, null),
        )
        assertEquals(
            321,
            llmMaxChars(321, 1_000, 1_000, LanguageCode.parse("zh")),
        )
        assertEquals(
            307,
            llmMaxChars(null, 1_000, 1_000, LanguageCode.parse("zh-Hans")),
        )
    }

    @Test
    fun unitWithinBudgetIsNotSplit() {
        val unit = TranslationUnit("p", "Hello")
        val plan = splitToBudget(listOf(unit), 10, ProviderId.GOOGLE_PA)

        assertEquals(listOf(unit), plan.units)
        assertEquals(mapOf("p" to " Hola "), plan.restore(mapOf("p" to " Hola ")))
    }

    @Test
    fun paragraphBoundaryIsPreferredAndPreserved() {
        val plan = splitToBudget(
            listOf(TranslationUnit("p", "aaaaaa\n\nbbbbbb")),
            10,
            ProviderId.GOOGLE_PA,
        )

        assertEquals(listOf("aaaaaa", "bbbbbb"), plan.units.map { it.text })
        assertEquals(listOf("p", "p#2"), plan.units.map { it.id })
        assertEquals(
            mapOf("p" to "A\n\nB"),
            plan.restore(mapOf("p" to "A", "p#2" to "B")),
        )
    }

    @Test
    fun lineBoundaryIsUsedBeforeSentenceAndWhitespace() {
        val plan = splitToBudget(
            listOf(TranslationUnit("p", "aaaaaa\nbbbbbb")),
            10,
            ProviderId.GOOGLE_PA,
        )

        assertEquals(listOf("aaaaaa", "bbbbbb"), plan.units.map { it.text })
        assertEquals(
            mapOf("p" to "A\nB"),
            plan.restore(mapOf("p" to "A", "p#2" to "B")),
        )
    }

    @Test
    fun sentenceBoundaryKeepsTrailingSpace() {
        val plan = splitToBudget(
            listOf(TranslationUnit("p", "Hello. World.")),
            10,
            ProviderId.GOOGLE_PA,
        )

        assertEquals(listOf("Hello.", "World."), plan.units.map { it.text })
        assertEquals(
            mapOf("p" to "Hola. Mundo."),
            plan.restore(mapOf("p" to "Hola.", "p#2" to "Mundo.")),
        )
    }

    @Test
    fun sentenceBoundaryIncludesClosingQuote() {
        val plan = splitToBudget(
            listOf(TranslationUnit("p", "Hello.\" World.")),
            10,
            ProviderId.GOOGLE_PA,
        )

        assertEquals(listOf("Hello.\"", "World."), plan.units.map { it.text })
        assertEquals(
            mapOf("p" to "Hola.\" Mundo."),
            plan.restore(mapOf("p" to "Hola.\"", "p#2" to "Mundo.")),
        )
    }

    @Test
    fun whitespaceBoundaryIsUsedAsFallback() {
        val plan = splitToBudget(
            listOf(TranslationUnit("p", "Hello big world")),
            10,
            ProviderId.GOOGLE_PA,
        )

        assertEquals(listOf("Hello big", "world"), plan.units.map { it.text })
        assertEquals(
            mapOf("p" to "Hola grande mundo"),
            plan.restore(mapOf("p" to "Hola grande", "p#2" to "mundo")),
        )
    }

    @Test
    fun hardSplitIsUsedWithoutNaturalBoundary() {
        val plan = splitToBudget(
            listOf(TranslationUnit("p", "abcdefghij")),
            5,
            ProviderId.GOOGLE_PA,
        )

        assertEquals(listOf("abcde", "fghij"), plan.units.map { it.text })
        assertEquals(
            mapOf("p" to "ABCDEFGHIJ"),
            plan.restore(mapOf("p" to "ABCDE", "p#2" to "FGHIJ")),
        )
    }

    @Test
    fun hardSplitDoesNotBreakSurrogatePair() {
        val text = "aaaa\uD83D\uDE00bbbb"
        val plan = splitToBudget(
            listOf(TranslationUnit("p", text)),
            5,
            ProviderId.GOOGLE_PA,
        )

        assertEquals(text, plan.units.joinToString("") { it.text })
        for (part in plan.units) {
            assertFalse(part.text.first().isLowSurrogate(), "part starts with low surrogate")
            assertFalse(part.text.last().isHighSurrogate(), "part ends with high surrogate")
        }
        assertEquals(
            mapOf("p" to "XYZ"),
            plan.restore(
                mapOf(
                    "p" to "X",
                    "p#2" to "Y",
                    "p#3" to "Z",
                ),
            ),
        )
    }

    @Test
    fun crlfParagraphRunRoundTripsExactly() {
        val plan = splitToBudget(
            listOf(TranslationUnit("p", "aaaa\r\n\r\nbbbb")),
            10,
            ProviderId.GOOGLE_PA,
        )

        assertEquals(listOf("aaaa", "bbbb"), plan.units.map { it.text })
        assertEquals(
            mapOf("p" to "A\r\n\r\nB"),
            plan.restore(mapOf("p" to "A", "p#2" to "B")),
        )
    }

    @Test
    fun newlineRunOfThreeIsOneParagraphSeparator() {
        val plan = splitToBudget(
            listOf(TranslationUnit("p", "aaaa\n\n\nbbbb")),
            10,
            ProviderId.GOOGLE_PA,
        )

        assertEquals(listOf("aaaa", "bbbb"), plan.units.map { it.text })
        assertEquals(
            mapOf("p" to "A\n\n\nB"),
            plan.restore(mapOf("p" to "A", "p#2" to "B")),
        )
    }

    @Test
    fun leadingNewlineRunWithTinyBudgetHasNoEmptyParts() {
        val text = "\n\naaaa"
        val plan = splitToBudget(
            listOf(TranslationUnit("p", text)),
            3,
            ProviderId.GOOGLE_PA,
        )

        assertTrue(plan.units.all { it.text.isNotEmpty() }, "parts: ${plan.units.map { it.text }}")
        val translated = plan.units.associate { it.id to it.text }
        assertEquals(text, plan.restore(translated)["p"])
    }

    @Test
    fun hardSplitAtEmojiBoundaryNeverReturnsEmptyPart() {
        val text = "\uD83D\uDE00\uD83D\uDE00ab"
        val plan = splitToBudget(
            listOf(TranslationUnit("p", text)),
            1,
            ProviderId.GOOGLE_PA,
        )

        assertTrue(plan.units.all { it.text.isNotEmpty() }, "parts: ${plan.units.map { it.text }}")
        val translated = plan.units.associate { it.id to it.text }
        assertEquals(text, plan.restore(translated)["p"])
    }

    @Test
    fun syntheticIdsAreCollisionFree() {
        val plan = splitToBudget(
            listOf(
                TranslationUnit("p", "abcdefghij"),
                TranslationUnit("p#2", "ok"),
            ),
            5,
            ProviderId.GOOGLE_PA,
        )

        val ids = plan.units.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue("p#2#1" in ids)
        assertFalse(plan.units.any { it.id == "p#2" && it.text == "fghij" })
    }

    @Test
    fun missingSplitPartIsTruncated() {
        val plan = splitToBudget(
            listOf(TranslationUnit("p", "abcdefghij")),
            5,
            ProviderId.GOOGLE_PA,
        )

        val failure = assertFailsWith<TranslationFailure> {
            plan.restore(mapOf("p" to "ABCDE"))
        }

        val error = failure.error
        assertIs<TranslationError.Truncated>(error)
        assertEquals(2, error.expected)
        assertEquals(1, error.actual)
    }

    @Test
    fun chunkUnitsCountsSeparatorOnlyBetweenUnits() {
        val chunks = chunkUnits(
            listOf(TranslationUnit("a", "12345"), TranslationUnit("b", "6789")),
            10,
            "\n",
        )

        assertEquals(1, chunks.size)
        assertEquals("12345\n6789", chunks.single().text)
    }

    @Test
    fun chunkUnitGroupsReservesPerUnitOverhead() {
        val units = listOf(
            TranslationUnit("a", "x".repeat(10)),
            TranslationUnit("b", "y".repeat(10)),
        )

        val groups = chunkUnitGroups(units, 50, overheadPerUnit = 32)

        assertEquals(2, groups.size)
    }
}
