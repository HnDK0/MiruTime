package dev.mirutime.app.features.book.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterDeleteOutcomeTest {

    @Test
    fun `a thrown delete is a failure, never a silent success`() {
        assertEquals(ChapterDeleteOutcome.Failed, chapterDeleteOutcome(null))
    }

    @Test
    fun `zero removed rows is a no-op that keeps the selection`() {
        assertEquals(ChapterDeleteOutcome.Nothing, chapterDeleteOutcome(0, failed = 0))
    }

    @Test
    fun `a positive count is the only success that clears the selection`() {
        assertEquals(ChapterDeleteOutcome.Success, chapterDeleteOutcome(1, failed = 0))
        assertEquals(ChapterDeleteOutcome.Success, chapterDeleteOutcome(12, failed = 0))
    }

    @Test
    fun `one deleted and one failed is a partial failure that keeps the selection`() {
        assertEquals(ChapterDeleteOutcome.Failed, chapterDeleteOutcome(1, failed = 1))
        assertEquals(ChapterDeleteOutcome.Failed, chapterDeleteOutcome(0, failed = 1))
    }

    @Test
    fun `badges may wrap to two lines at 200 percent font scale`() {
        // A single line clips "ПОСЛЕДНЯЯ ПРОЧИТАННАЯ" on a narrow column.
        assertTrue(ChapterBadgeMaxLines >= 2)
    }
}
