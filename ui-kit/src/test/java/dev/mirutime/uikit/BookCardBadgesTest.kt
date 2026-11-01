package dev.mirutime.uikit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookCardBadgesTest {
    @Test
    fun `same source is filled and count is at least one`() {
        assertEquals(MiruMembershipBadge(true, 1), memberBadge(true, 0))
        assertEquals(MiruMembershipBadge(false, 3), memberBadge(false, 3))
    }

    @Test
    fun `rating text is one decimal with no trailing zero`() {
        assertEquals("4.5", ratingText(4.5f))
        assertEquals("4", ratingText(4.0f))
        assertNull(ratingText(0f))
        assertNull(ratingText(5.5f))
    }
}
