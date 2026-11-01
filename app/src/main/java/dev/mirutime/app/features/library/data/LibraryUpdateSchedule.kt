package dev.mirutime.app.features.library.data

import androidx.work.NetworkType
import dev.mirutime.app.features.settings.data.PersistedSettings

/**
 * Periodic-work shape derived from the persisted library-update settings.
 * `0` interval hours means "on launch": one one-time request instead of a
 * periodic one.
 */
data class LibraryUpdateScheduleSpec(
    val intervalMinutes: Long,
    val flexMinutes: Long,
    val oneTime: Boolean,
    val networkType: NetworkType,
    val requiresCharging: Boolean,
    val requiresBatteryNotLow: Boolean,
)

object LibraryUpdateSchedule {
    const val UniquePeriodicWork = "mirutime-library-update"
    const val UniqueLaunchWork = "mirutime-library-update-launch"
    const val LaunchIntervalHours = 0
    const val MinIntervalHours = 12
    const val FlexMinutes = 15L

    fun spec(settings: PersistedSettings): LibraryUpdateScheduleSpec {
        val hours = settings.libraryIntervalHours.coerceAtLeast(0)
        val launch = hours == LaunchIntervalHours
        val intervalHours = if (launch) LaunchIntervalHours else hours.coerceAtLeast(MinIntervalHours)
        return LibraryUpdateScheduleSpec(
            intervalMinutes = if (launch) 0L else intervalHours * 60L,
            flexMinutes = if (launch) 0L else FlexMinutes,
            oneTime = launch,
            networkType = if (settings.updateWifiOnly) {
                NetworkType.UNMETERED
            } else {
                // A library check is useless without a connection, so "any
                // connection" still means connected, not NOT_REQUIRED.
                NetworkType.CONNECTED
            },
            requiresCharging = settings.updateWhileCharging,
            requiresBatteryNotLow = true,
        )
    }
}
