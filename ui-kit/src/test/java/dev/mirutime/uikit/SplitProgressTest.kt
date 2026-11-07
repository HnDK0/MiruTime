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

    @Test
    fun `measured four digit segments stay inside the pill`() {
        assertEquals(30f, splitDividerX(56f, 0.5f, 30f, 30f), 0.001f)
        assertEquals(30f, splitDividerX(56f, 0.9f, 30f, 30f), 0.001f)
    }

    @Test
    fun `adaptive gap keeps the divider away from the digits`() {
        // 8 / 1432: four gaps share the free width.
        assertEquals(5.25f, splitTextGap(56f, 6f, 23f, 3f, 8f), 0.001f)
        // 1234 / 1234 at 8sp: still a real gap, just tighter.
        assertEquals(3.5f, splitTextGap(56f, 18f, 18f, 3f, 8f), 0.001f)
        // At 10sp both four-digit pairs are tight, so the caller shrinks the font.
        assertEquals(1.5f, splitTextGap(56f, 22f, 22f, 3f, 8f), 0.001f)
    }
}
