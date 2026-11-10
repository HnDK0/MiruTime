package dev.mirutime.feature.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The width slider resizes the surface it lives on, so its range must never be
 * derived from that surface. These are the pure guards for that rule.
 */
class FloatingWidthRangeTest {
    @Test
    fun `the range comes from the screen and not from the panel`() {
        val range = floatingWidthRange(screenWidthDp = 400f, marginDp = 16f)
        // Same screen, any panel width: identical range.
        val a = clampFloatingPanelWidth(240f, range)
        val b = clampFloatingPanelWidth(360f, range)
        assertEquals(240f, a)
        assertEquals(360f, b)
        assertEquals(range, floatingWidthRange(screenWidthDp = 400f, marginDp = 16f))
    }

    @Test
    fun `the range can grow past the default width`() {
        val range = floatingWidthRange(screenWidthDp = 400f, marginDp = 16f)
        assertTrue("max must exceed the default", range.endInclusive > TtsFloatingDefaultWidth)
    }

    @Test
    fun `a narrow screen still yields a non-empty range`() {
        val range = floatingWidthRange(screenWidthDp = 180f, marginDp = 16f)
        assertTrue(range.endInclusive > range.start)
    }

    @Test
    fun `clamping is idempotent`() {
        val range = floatingWidthRange(screenWidthDp = 400f, marginDp = 16f)
        val once = clampFloatingPanelWidth(999f, range)
        assertEquals(once, clampFloatingPanelWidth(once, range))
    }

    @Test
    fun `a corrupt width falls back to the default`() {
        val range = floatingWidthRange(screenWidthDp = 400f, marginDp = 16f)
        assertEquals(TtsFloatingDefaultWidth, clampFloatingPanelWidth(Float.NaN, range))
    }

    @Test
    fun `a pinch can grow the panel from the stored width`() {
        val range = floatingWidthRange(screenWidthDp = 500f, marginDp = 16f)
        // 300 dp at a 100 px span, fingers moving to 150 px: 1.5x, still inside
        // the range (max is 500 - 32 = 468).
        assertEquals(450f, pinchPanelWidth(300f, previousSpan = 100f, span = 150f, range = range))
    }

    @Test
    fun `a pinch cannot exceed the range`() {
        val range = floatingWidthRange(screenWidthDp = 400f, marginDp = 16f)
        val next = pinchPanelWidth(300f, previousSpan = 10f, span = 1_000f, range = range)
        assertEquals(range.endInclusive, next)
    }

    @Test
    fun `a pinch with a zero span keeps the current width`() {
        val range = floatingWidthRange(screenWidthDp = 400f, marginDp = 16f)
        assertEquals(300f, pinchPanelWidth(300f, previousSpan = 0f, span = 120f, range = range))
        assertEquals(300f, pinchPanelWidth(300f, previousSpan = 100f, span = 0f, range = range))
    }
}
