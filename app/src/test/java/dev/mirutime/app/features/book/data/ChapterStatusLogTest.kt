package dev.mirutime.app.features.book.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterStatusLogTest {
    private val counters = ChapterStatusCounters(
        bookId = 14,
        count = 3241,
        read = 0,
        downloaded = 2,
        translated = 2,
        last = 3241,
    )

    @Test
    fun `an identical snapshot inside the window is not logged`() {
        assertFalse(
            shouldLogChapterStatus(
                previous = counters,
                current = counters,
                now = 1_000L,
                lastLoggedAt = 500L,
            ),
        )
    }

    @Test
    fun `a changed counter is logged immediately`() {
        assertTrue(
            shouldLogChapterStatus(
                previous = counters,
                current = counters.copy(downloaded = 3),
                now = 1_000L,
                lastLoggedAt = 500L,
            ),
        )
    }

    @Test
    fun `an unchanged snapshot is logged once per window`() {
        assertTrue(
            shouldLogChapterStatus(
                previous = counters,
                current = counters,
                now = 31_000L,
                lastLoggedAt = 500L,
            ),
        )
    }

    @Test
    fun `the first snapshot is always logged`() {
        assertTrue(
            shouldLogChapterStatus(
                previous = null,
                current = counters,
                now = 1L,
                lastLoggedAt = 0L,
            ),
        )
    }
}
