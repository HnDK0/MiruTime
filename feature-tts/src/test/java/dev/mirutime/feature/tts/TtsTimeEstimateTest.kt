package dev.mirutime.feature.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsTimeEstimateTest {

    @Test
    fun durationUsesTheSameClockShapeAsNoveLA() {
        assertEquals("0:00", formatTtsDuration(0))
        assertEquals("0:59", formatTtsDuration(59))
        assertEquals("1:00", formatTtsDuration(60))
        assertEquals("59:59", formatTtsDuration(3_599))
        assertEquals("1:00:00", formatTtsDuration(3_600))
    }

    @Test
    fun negativeDurationNeverRendersNegativeTime() {
        assertEquals("0:00", formatTtsDuration(-1))
    }

    @Test
    fun estimateScalesCharactersByRateAndSpeed() {
        assertEquals(100, ttsEstimatedSeconds(1_300, 1f))
        assertEquals(50, ttsEstimatedSeconds(1_300, 2f))
        assertEquals(1, ttsEstimatedSeconds(1, 1f))
        assertEquals(0, ttsEstimatedSeconds(0, 1f))
    }

    @Test
    fun estimateFallsBackToNormalSpeedForInvalidRates() {
        assertEquals(100, ttsEstimatedSeconds(1_300, Float.NaN))
        assertEquals(200, ttsEstimatedSeconds(1_300, 0.5f))
    }
}
