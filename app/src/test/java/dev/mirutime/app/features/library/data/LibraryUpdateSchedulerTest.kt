package dev.mirutime.app.features.library.data

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import dev.mirutime.app.features.settings.data.PersistedSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryUpdateSchedulerTest {
    @Test
    fun `default settings keep the 24 hour automatic schedule`() {
        val spec = LibraryUpdateSchedule.spec(PersistedSettings())

        assertFalse(spec.oneTime)
        assertEquals(24L * 60L, spec.intervalMinutes)
    }

    @Test
    fun `launch interval becomes one one-time request`() {
        val spec = LibraryUpdateSchedule.spec(
            PersistedSettings(libraryIntervalHours = 0),
        )

        assertTrue(spec.oneTime)
        assertEquals(0L, spec.intervalMinutes)
        assertEquals(0L, spec.flexMinutes)
        assertEquals(NetworkType.CONNECTED, spec.networkType)
    }

    @Test
    fun `periodic interval and constraints come from settings`() {
        val spec = LibraryUpdateSchedule.spec(
            PersistedSettings(
                libraryIntervalHours = 24,
                updateWifiOnly = true,
                updateWhileCharging = false,
            ),
        )

        assertFalse(spec.oneTime)
        assertEquals(24L * 60L, spec.intervalMinutes)
        assertEquals(LibraryUpdateSchedule.FlexMinutes, spec.flexMinutes)
        assertEquals(NetworkType.UNMETERED, spec.networkType)
        assertFalse(spec.requiresCharging)
        assertTrue(spec.requiresBatteryNotLow)
    }

    @Test
    fun `periodic WorkManager request carries interval flex and constraints`() {
        val spec = LibraryUpdateSchedule.spec(
            PersistedSettings(
                libraryIntervalHours = 12,
                updateWifiOnly = false,
                updateWhileCharging = true,
            ),
        )
        val request = LibraryUpdateScheduler.buildPeriodicRequest(spec)

        val work = request.workSpec
        assertEquals(12L * 60L * 60_000L, work.intervalDuration)
        assertEquals(LibraryUpdateSchedule.FlexMinutes * 60_000L, work.flexDuration)
        assertEquals(12L * 60L * 60_000L, work.initialDelay)
        assertTrue(work.constraints.requiresCharging())
        assertTrue(work.constraints.requiresBatteryNotLow())
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, work.backoffPolicy)
    }

    @Test
    fun `one-time WorkManager request keeps the same constraints`() {
        val spec = LibraryUpdateSchedule.spec(
            PersistedSettings(libraryIntervalHours = 0, updateWifiOnly = true),
        )
        val request = LibraryUpdateScheduler.buildOneTimeRequest(spec)

        val work = request.workSpec
        assertTrue(work.constraints.requiresCharging())
        assertTrue(work.constraints.requiresBatteryNotLow())
        assertEquals(NetworkType.UNMETERED, work.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, work.backoffPolicy)
    }

    @Test
    fun `log host drops the rest of a transport url`() {
        assertEquals(
            "api.example.com",
            updateErrorHost("failed for https://api.example.com/repos/x?token=secret"),
        )
        assertEquals("unknown", updateErrorHost("offline"))
        assertEquals("unknown", updateErrorHost(null))
    }
}
