package dev.mirutime.app.features.book.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterFilterTest {

    @Test
    fun `all keeps both read and unread`() {
        assertTrue(ChapterFilter.All.matches(isRead = true))
        assertTrue(ChapterFilter.All.matches(isRead = false))
    }

    @Test
    fun `read keeps only read chapters`() {
        assertTrue(ChapterFilter.Read.matches(isRead = true))
        assertFalse(ChapterFilter.Read.matches(isRead = false))
    }

    @Test
    fun `unread keeps only unread chapters`() {
        assertFalse(ChapterFilter.Unread.matches(isRead = true))
        assertTrue(ChapterFilter.Unread.matches(isRead = false))
    }
}
