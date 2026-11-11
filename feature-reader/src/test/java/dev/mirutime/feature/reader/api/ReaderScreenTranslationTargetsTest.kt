package dev.mirutime.feature.reader.api

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderScreenTranslationTargetsTest {
    @Test
    fun `loaded previous chapters are ensured after the active and next`() {
        assertEquals(
            listOf(5, 6, 4, 3),
            translationEnsureTargets(
                activeChapterIndex = 5,
                loadedChapterIndices = setOf(3, 4, 5, 6),
            ),
        )
    }

    @Test
    fun `loaded chapters beyond the prefetch window are not translated`() {
        assertEquals(
            listOf(5, 6, 4),
            translationEnsureTargets(5, setOf(4, 5, 6, 8)),
        )
    }

    @Test
    fun `targets are unique when active and loaded overlap`() {
        assertEquals(
            listOf(1, 2),
            translationEnsureTargets(1, setOf(1, 2)),
        )
    }

    @Test
    fun `a chapter loaded ahead of the active one is ensured`() {
        assertEquals(
            listOf(1000, 1001, 999),
            translationEnsureTargets(
                activeChapterIndex = 1000,
                loadedChapterIndices = setOf(999, 1000, 1001),
                nextChapterIndex = 1001,
            ),
        )
    }
}
