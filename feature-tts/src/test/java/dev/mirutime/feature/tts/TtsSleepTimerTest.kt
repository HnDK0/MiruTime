package dev.mirutime.feature.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TtsSleepTimerTest {
    @Test
    fun `notification key ignores countdown ticks`() {
        val timer = TtsSleepTimerState(
            mode = TtsSleepTimerMode.Minutes,
            remainingSeconds = 60,
            totalSeconds = 60,
            endsAtEpochMillis = 1_000L,
        )

        assertEquals(
            ttsSleepTimerNotificationKey(timer),
            ttsSleepTimerNotificationKey(timer.copy(remainingSeconds = 59)),
        )
    }

    @Test
    fun `notification key changes with the boundary and off state`() {
        val minutes = TtsSleepTimerState(
            mode = TtsSleepTimerMode.Minutes,
            endsAtEpochMillis = 1_000L,
        )
        val chapters = TtsSleepTimerState(
            mode = TtsSleepTimerMode.Chapters,
            chaptersRemaining = 2,
        )

        assertNotEquals(ttsSleepTimerNotificationKey(minutes), "off")
        assertNotEquals(
            ttsSleepTimerNotificationKey(minutes),
            ttsSleepTimerNotificationKey(minutes.copy(endsAtEpochMillis = 2_000L)),
        )
        assertNotEquals(
            ttsSleepTimerNotificationKey(chapters),
            ttsSleepTimerNotificationKey(chapters.copy(chaptersRemaining = 1)),
        )
        assertEquals("off", ttsSleepTimerNotificationKey(null))
    }

    @Test
    fun `presets clamp to the supported range`() {
        assertEquals(1, clampSleepTimerMinutes(0))
        assertEquals(180, clampSleepTimerMinutes(999))
        assertEquals(1, clampSleepTimerChapters(0))
        assertEquals(99, clampSleepTimerChapters(999))
    }
}
