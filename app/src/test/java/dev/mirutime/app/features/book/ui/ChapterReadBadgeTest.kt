package dev.mirutime.app.features.book.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterReadBadgeTest {

    @Test
    fun `unread chapter never shows the read badge`() {
        assertFalse(shouldShowReadBadge(isRead = false, isLastRead = false, isLast = false))
        assertFalse(shouldShowReadBadge(isRead = false, isLastRead = true, isLast = true))
    }

    @Test
    fun `plain read chapter shows the read badge`() {
        assertTrue(shouldShowReadBadge(isRead = true, isLastRead = false, isLast = false))
    }

    @Test
    fun `last read or last chapter already conveys read state`() {
        assertFalse(shouldShowReadBadge(isRead = true, isLastRead = true, isLast = false))
        assertFalse(shouldShowReadBadge(isRead = true, isLastRead = false, isLast = true))
        assertFalse(shouldShowReadBadge(isRead = true, isLastRead = true, isLast = true))
    }
}
