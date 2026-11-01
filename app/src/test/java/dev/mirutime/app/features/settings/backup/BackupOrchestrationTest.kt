package dev.mirutime.app.features.settings.backup

import dev.mirutime.app.R
import dev.mirutime.app.features.settings.data.PersistedSettings
import dev.mirutime.app.features.settings.data.RestoreProgress
import dev.mirutime.app.features.settings.data.RestoreStage
import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupOrchestrationTest {
    @Test
    fun `backup names are app scoped and timestamped`() {
        val millis = LocalDateTime.of(2026, 10, 23, 19, 45, 7)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val manual = BackupRotation.fileName(millis, auto = false)
        val auto = BackupRotation.fileName(millis, auto = true)

        assertEquals("mirutime-backup-20261023-194507.zip", manual)
        assertEquals("mirutime-auto-20261023-194507.zip", auto)
        assertTrue(BackupRotation.isAppBackup(manual))
        assertTrue(BackupRotation.isAppBackup(auto))
        assertFalse(BackupRotation.isAppBackup("holiday.zip"))
        assertFalse(BackupRotation.isAppBackup("mirutime-backup-20261023-194507.zip.bak"))
    }

    @Test
    fun `rotation deletes only oldest app backups beyond the limit`() {
        val files = listOf(
            BackupFileInfo("1", "mirutime-backup-20260101-000000.zip", 100),
            BackupFileInfo("2", "mirutime-auto-20260102-000000.zip", 200),
            BackupFileInfo("3", "mirutime-backup-20260103-000000.zip", 300),
            BackupFileInfo("4", "mirutime-auto-20260104-000000.zip", 400),
            BackupFileInfo("5", "holiday.zip", 1),
        )

        val stale = BackupRotation.selectForDeletion(files, maxCount = 2)

        assertEquals(
            listOf("mirutime-backup-20260101-000000.zip", "mirutime-auto-20260102-000000.zip"),
            stale.map { it.displayName },
        )
        assertTrue(BackupRotation.selectForDeletion(files, maxCount = 5).isEmpty())
    }

    @Test
    fun `schedule enforces the 60 minute floor and a bounded flex`() {
        val short = BackupSchedule.spec(PersistedSettings(backupIntervalMinutes = 5))
        assertEquals(60L, short.intervalMinutes)
        assertEquals(15L, short.flexMinutes)
        assertTrue(short.requiresBatteryNotLow)
        assertFalse(short.requiresNetwork)

        val daily = BackupSchedule.spec(PersistedSettings(backupIntervalMinutes = 1440))
        assertEquals(1440L, daily.intervalMinutes)
        assertEquals(15L, daily.flexMinutes)
    }

    @Test
    fun `periodic WorkManager request carries constraints interval and flex`() {
        val request = AutoBackupScheduler.buildPeriodicRequest(
            BackupSchedule.spec(PersistedSettings(backupIntervalMinutes = 60)),
        )

        val spec = request.workSpec
        assertEquals(60L * 60_000L, spec.intervalDuration)
        assertEquals(15L * 60_000L, spec.flexDuration)
        assertTrue(spec.constraints.requiresBatteryNotLow())
        assertFalse(spec.constraints.requiresCharging())
        assertEquals(NetworkType.NOT_REQUIRED, spec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(BackupSchedule.BackoffMinutes * 60_000L, spec.backoffDelayDuration)
    }

    @Test
    fun `policy disables auto backup when the SAF grant is missing`() {
        assertEquals(
            AutoBackupAction.Cancel,
            AutoBackupPolicy.decide(
                autoBackup = false,
                isTreeUri = true,
                permissionGranted = true,
            ),
        )
        assertEquals(
            AutoBackupAction.DisablePermissionLost,
            AutoBackupPolicy.decide(
                autoBackup = true,
                isTreeUri = false,
                permissionGranted = true,
            ),
        )
        assertEquals(
            AutoBackupAction.DisablePermissionLost,
            AutoBackupPolicy.decide(
                autoBackup = true,
                isTreeUri = true,
                permissionGranted = false,
            ),
        )
        assertEquals(
            AutoBackupAction.Schedule,
            AutoBackupPolicy.decide(
                autoBackup = true,
                isTreeUri = true,
                permissionGranted = true,
            ),
        )
    }

    @Test
    fun `notification content maps restore stages to monotonic percentages`() {
        val validating = BackupNotificationContentFactory.restore(
            RestoreProgress(RestoreStage.VALIDATING, 1, 1),
        )
        val extracting = BackupNotificationContentFactory.restore(
            RestoreProgress(RestoreStage.EXTRACTING, 1, 2),
        )
        val database = BackupNotificationContentFactory.restore(
            RestoreProgress(RestoreStage.DATABASE, 1, 1),
        )
        val files = BackupNotificationContentFactory.restore(
            RestoreProgress(RestoreStage.FILES, 1, 1),
        )
        val settings = BackupNotificationContentFactory.restore(
            RestoreProgress(RestoreStage.SETTINGS, 1, 1),
        )
        val cache = BackupNotificationContentFactory.restore(
            RestoreProgress(RestoreStage.CACHE, 1, 1),
        )

        assertEquals(5, validating.percent)
        assertEquals(20, extracting.percent)
        assertEquals(70, database.percent)
        assertEquals(90, files.percent)
        assertEquals(97, settings.percent)
        assertEquals(100, cache.percent)
        assertEquals(R.string.backup_stage_database, database.textRes)
        assertTrue(validating.percent <= extracting.percent)
        assertTrue(extracting.percent <= database.percent)
        assertTrue(database.percent <= files.percent)
        assertTrue(files.percent <= settings.percent)
        assertTrue(settings.percent <= cache.percent)
        assertEquals(100, BackupNotificationContentFactory.backup(150).percent)
        assertEquals(0, BackupNotificationContentFactory.backup(-1).percent)
    }
}
