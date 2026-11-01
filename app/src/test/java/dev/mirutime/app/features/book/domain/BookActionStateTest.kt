package dev.mirutime.app.features.book.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookActionStateTest {

    @Test
    fun `chapters loading disables the action and never names a chapter`() {
        val state = bookActionState(
            chaptersLoading = true,
            totalChapters = 0,
            readCount = 0,
            chapterIndex = null,
            chapterTitle = null,
        )

        assertEquals(BookActionKind.LoadingChapters, state.kind)
        assertFalse(state.enabled)
        assertNull(state.chapterIndex)
    }

    @Test
    fun `zero or unknown total is not read again and is disabled`() {
        val zeroTotal = bookActionState(
            chaptersLoading = false,
            totalChapters = 0,
            readCount = 0,
            chapterIndex = null,
            chapterTitle = null,
        )
        val zeroTotalWithRead = bookActionState(
            chaptersLoading = false,
            totalChapters = 0,
            readCount = 5,
            chapterIndex = null,
            chapterTitle = null,
        )

        assertEquals(BookActionKind.NoChapters, zeroTotal.kind)
        assertFalse(zeroTotal.enabled)
        assertEquals(BookActionKind.NoChapters, zeroTotalWithRead.kind)
        assertFalse(zeroTotalWithRead.enabled)
    }

    @Test
    fun `read again needs a positive total and every chapter read`() {
        val finished = bookActionState(
            chaptersLoading = false,
            totalChapters = 10,
            readCount = 10,
            chapterIndex = 10,
            chapterTitle = "Finale",
        )
        val overCount = bookActionState(
            chaptersLoading = false,
            totalChapters = 10,
            readCount = 12,
            chapterIndex = 10,
            chapterTitle = "Finale",
        )

        assertEquals(BookActionKind.ReadAgain, finished.kind)
        assertEquals("Finale", finished.chapterTitle)
        assertTrue(finished.enabled)
        assertEquals(BookActionKind.ReadAgain, overCount.kind)
    }

    @Test
    fun `unread book starts and a mid book continues at the real index`() {
        val start = bookActionState(
            chaptersLoading = false,
            totalChapters = 10,
            readCount = 0,
            chapterIndex = 1,
            chapterTitle = "Prologue",
        )
        val mid = bookActionState(
            chaptersLoading = false,
            totalChapters = 10,
            readCount = 4,
            chapterIndex = 5,
            chapterTitle = "Chapter Five",
        )

        assertEquals(BookActionKind.Start, start.kind)
        assertEquals(1, start.chapterIndex)
        assertEquals(BookActionKind.Continue, mid.kind)
        assertEquals(5, mid.chapterIndex)
        assertEquals("Chapter Five", mid.chapterTitle)
        assertTrue(mid.enabled)
    }

    @Test
    fun `saved progress continues even with zero marked chapters`() {
        val saved = bookActionState(
            chaptersLoading = false,
            totalChapters = 10,
            readCount = 0,
            chapterIndex = 3,
            chapterTitle = "Saved chapter",
            hasSavedProgress = true,
        )

        assertEquals(BookActionKind.Continue, saved.kind)
        assertEquals(3, saved.chapterIndex)
        assertEquals("Saved chapter", saved.chapterTitle)
    }

    @Test
    fun `stats stay unknown while chapters load or the total is zero`() {
        assertFalse(bookChaptersCountKnown(chaptersLoading = true, totalChapters = 10))
        assertFalse(bookChaptersCountKnown(chaptersLoading = false, totalChapters = 0))
        assertTrue(bookChaptersCountKnown(chaptersLoading = false, totalChapters = 10))
    }
}
