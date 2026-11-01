package dev.mirutime.app.features.settings.backup

import dev.mirutime.app.features.settings.data.PersistedSettings

/** Periodic-work shape derived from persisted auto-backup settings. */
data class BackupScheduleSpec(
    val intervalMinutes: Long,
    val flexMinutes: Long,
    val requiresBatteryNotLow: Boolean = true,
    val requiresNetwork: Boolean = false,
)

object BackupSchedule {
    const val MinIntervalMinutes = 60L
    const val MinFlexMinutes = 5L
    const val MaxFlexMinutes = 15L
    const val BackoffMinutes = 10L

    fun spec(settings: PersistedSettings): BackupScheduleSpec {
        val interval = settings.backupIntervalMinutes.toLong().coerceAtLeast(MinIntervalMinutes)
        val flex = (interval / 4).coerceIn(MinFlexMinutes, MaxFlexMinutes)
        return BackupScheduleSpec(
            intervalMinutes = interval,
            flexMinutes = flex,
            requiresBatteryNotLow = true,
            requiresNetwork = false,
        )
    }
}

enum class AutoBackupAction {
    Cancel,
    DisablePermissionLost,
    Schedule,
}

/**
 * Pure decision used by the scheduler and Worker. A missing/lost SAF grant is
 * not a retryable error: the app disables auto backup and reports it instead
 * of failing every cycle.
 */
object AutoBackupPolicy {
    fun decide(
        autoBackup: Boolean,
        isTreeUri: Boolean,
        permissionGranted: Boolean,
    ): AutoBackupAction = when {
        !autoBackup -> AutoBackupAction.Cancel
        !isTreeUri || !permissionGranted -> AutoBackupAction.DisablePermissionLost
        else -> AutoBackupAction.Schedule
    }
}
