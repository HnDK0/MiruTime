package dev.mirutime.uikit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitProgressTest {
    @Test
    fun `known counts produce a fraction and two labels`() {
        val state = splitProgressState(24, 120)
        assertEquals(0.2f, state.fraction, 0.0001f)
        assertEquals("24", state.left)
        assertEquals("120", state.right)
        assertFalse(state.unknown)
    }

    @Test
    fun `full progress reaches one`() {
        assertEquals(1f, splitProgressState(120, 120).fraction, 0.0001f)
    }

    @Test
    fun `unknown counts are honest`() {
        assertTrue(splitProgressState(0, 0).unknown)
        assertTrue(splitProgressState(null, null).unknown)
        assertEquals("—", splitProgressState(null, null).left)
        assertEquals("—", splitProgressState(null, null).right)
    }

    @Test
    fun `divider respects both number widths`() {
        assertEquals(18f, splitDividerX(56f, 0.02f, 18f, 26f), 0.001f)
        assertEquals(30f, splitDividerX(56f, 0.9f, 18f, 26f), 0.001f)
        assertEquals(28f, splitDividerX(56f, 0.5f, 18f, 26f), 0.001f)
    }
}
