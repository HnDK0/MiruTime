package dev.mirutime.feature.reader.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuDoubleTapTest {
    private val timeout = 300L

    @Test
    fun `first center tap is not a double tap`() {
        assertFalse(isMenuDoubleTap(now = 1_000L, lastTapAt = 0L, timeout = timeout))
    }

    @Test
    fun `second tap within the timeout is a double tap`() {
        assertTrue(isMenuDoubleTap(now = 1_200L, lastTapAt = 1_000L, timeout = timeout))
    }

    @Test
    fun `second tap after the timeout is not a double tap`() {
        assertFalse(isMenuDoubleTap(now = 1_301L, lastTapAt = 1_000L, timeout = timeout))
    }
}
