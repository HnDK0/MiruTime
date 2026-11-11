package dev.mirutime.app.features.dashboard.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesNextRunTest {
    private val now = 1_000_000L

    @Test
    fun `overdue run waits for charging only when required and unplugged`() {
        assertTrue(waiting(charging = false, requiresCharging = true))
    }

    @Test
    fun `charging device shows the normal next-run state`() {
        assertFalse(waiting(charging = true, requiresCharging = true))
    }

    @Test
    fun `run that is not overdue is never waiting`() {
        assertFalse(waiting(charging = false, requiresCharging = true, nextAt = now + 1))
    }

    @Test
    fun `auto update off or launch mode never waits`() {
        assertFalse(waiting(charging = false, requiresCharging = true, autoUpdate = false))
        assertFalse(waiting(charging = false, requiresCharging = true, intervalHours = 0))
    }

    private fun waiting(
        charging: Boolean,
        requiresCharging: Boolean,
        autoUpdate: Boolean = true,
        intervalHours: Int = 12,
        nextAt: Long = now,
    ): Boolean = showWaitingForCharging(
        autoUpdate = autoUpdate,
        intervalHours = intervalHours,
        nextAt = nextAt,
        now = now,
        requiresCharging = requiresCharging,
        charging = charging,
    )
}
