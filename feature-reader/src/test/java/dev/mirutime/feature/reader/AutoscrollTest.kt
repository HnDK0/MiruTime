package dev.mirutime.feature.reader

import dev.mirutime.feature.reader.Autoscroll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoscrollTest {
    @Test
    fun `distance is speed dp per second times density`() {
        assertEquals(40f, Autoscroll.distancePx(speed = 40, elapsedMs = 500, density = 2f), 0.001f)
        assertEquals(80f, Autoscroll.distancePx(speed = 40, elapsedMs = 1000, density = 2f), 0.001f)
    }

    @Test
    fun `speed is clamped`() {
        assertEquals(5, Autoscroll.sanitizeSpeed(1))
        assertEquals(200, Autoscroll.sanitizeSpeed(999))
        assertEquals(40, Autoscroll.sanitizeSpeed(40))
    }

    @Test
    fun `frame distance is capped after a long stall`() {
        val thirtyFrames = Autoscroll.frameDistancePx(
            speed = 100,
            elapsedMs = 33,
            density = 2f,
        )
        assertEquals(6.6f, thirtyFrames, 0.001f)
        // A 2 s stall must not jump; the cap keeps it at one 100 ms step.
        assertEquals(
            Autoscroll.distancePx(100, 100, 2f),
            Autoscroll.frameDistancePx(100, 2_000, 2f),
            0.001f,
        )
        assertEquals(0f, Autoscroll.frameDistancePx(100, -5, 2f), 0.001f)
    }

    @Test
    fun `end is reached when the list stops moving`() {
        assertTrue(Autoscroll.reachedEnd(requestedPx = 4f, consumedPx = 4f, canScrollForward = false))
        assertTrue(Autoscroll.reachedEnd(requestedPx = 4f, consumedPx = 1f, canScrollForward = true))
        assertFalse(Autoscroll.reachedEnd(requestedPx = 4f, consumedPx = 4f, canScrollForward = true))
    }
}
