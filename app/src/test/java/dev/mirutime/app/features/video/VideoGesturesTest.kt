package dev.mirutime.app.features.video

import dev.mirutime.app.features.video.domain.DoubleTapAccumulator
import dev.mirutime.app.features.video.domain.GestureAction
import dev.mirutime.app.features.video.domain.brightnessFraction
import dev.mirutime.app.features.video.domain.clampSeekMs
import dev.mirutime.app.features.video.domain.dragSeekTargetMs
import dev.mirutime.app.features.video.domain.isBrightnessSide
import dev.mirutime.app.features.video.domain.isTap
import dev.mirutime.app.features.video.domain.longPressSpeed
import dev.mirutime.app.features.video.domain.volumeFractionDelta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoGesturesTest {
    @Test
    fun `tap is a short release`() {
        assertTrue(isTap(0))
        assertTrue(isTap(299))
        assertFalse(isTap(300))
        assertFalse(isTap(-1))
    }

    @Test
    fun `double tap window accumulates and expires`() {
        var now = 1_000L
        val accumulator = DoubleTapAccumulator(windowMs = 300, nowMs = { now })

        assertEquals(1, accumulator.onTap())
        now += 250
        assertEquals(2, accumulator.onTap())
        now += 250
        assertEquals(3, accumulator.onTap())
        now += 301
        assertTrue(accumulator.isExpired())
        assertEquals(1, accumulator.onTap())
        assertFalse(accumulator.isExpired())
    }

    @Test
    fun `seek clamps to known duration`() {
        assertEquals(0L, clampSeekMs(1_000, -5_000, 60_000))
        assertEquals(60_000L, clampSeekMs(59_000, 5_000, 60_000))
        assertEquals(15_000L, clampSeekMs(10_000, 5_000, 60_000))
        assertNull(clampSeekMs(10_000, 5_000, 0))
    }

    @Test
    fun `horizontal drag maps width to duration`() {
        assertEquals(70_000L, dragSeekTargetMs(50f, 100f, 20_000, 100_000))
        assertEquals(0L, dragSeekTargetMs(-500f, 100f, 20_000, 100_000))
        assertEquals(100_000L, dragSeekTargetMs(500f, 100f, 20_000, 100_000))
        assertNull(dragSeekTargetMs(50f, 100f, 20_000, 0))
        assertNull(dragSeekTargetMs(50f, 0f, 20_000, 100_000))
    }

    @Test
    fun `left half is brightness unless swapped`() {
        assertTrue(isBrightnessSide(0.2f, swappedSides = false))
        assertFalse(isBrightnessSide(0.8f, swappedSides = false))
        assertFalse(isBrightnessSide(0.2f, swappedSides = true))
        assertTrue(isBrightnessSide(0.8f, swappedSides = true))
    }

    @Test
    fun `upward swipe raises brightness and volume`() {
        assertEquals(0.6f, brightnessFraction(0.5f, -100f, 1_000f), 0.0001f)
        assertEquals(0.4f, brightnessFraction(0.5f, 100f, 1_000f), 0.0001f)
        assertEquals(1f, brightnessFraction(0.5f, -1_000f, 1_000f), 0.0001f)
        assertEquals(0f, brightnessFraction(0.5f, 1_000f, 1_000f), 0.0001f)
        assertEquals(0.1f, volumeFractionDelta(-100f, 1_000f), 0.0001f)
        assertEquals(-0.1f, volumeFractionDelta(100f, 1_000f), 0.0001f)
    }

    @Test
    fun `long press doubles and restores the speed`() {
        assertEquals(
            GestureAction.Speed(2f),
            longPressSpeed(pressed = true, originalSpeed = 1.25f),
        )
        assertEquals(
            GestureAction.Speed(1.25f),
            longPressSpeed(pressed = false, originalSpeed = 1.25f),
        )
    }
}
