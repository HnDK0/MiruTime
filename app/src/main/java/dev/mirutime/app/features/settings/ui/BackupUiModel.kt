package dev.mirutime.app.features.settings.ui

import androidx.annotation.StringRes
import dev.mirutime.app.R
import dev.mirutime.app.features.settings.backup.BackupOperationKind
import dev.mirutime.app.features.settings.data.RestoreStage

enum class AutoBackupAccess { Off, NeedsFolder, PermissionLost, Scheduled }

/**
 * Pure backup-screen mapping. Kept out of the composable so interval choices,
 * status decisions and stage labels can be unit-tested without Compose.
 */
object BackupUiModel {
    // Ordered shortest to longest. The backend floor is 60 minutes; the UI
    // never offers anything shorter.
    val intervalOptions: List<Int> = listOf(60, 360, 720, 1440, 10080)

    fun accessStatus(
        autoBackup: Boolean,
        folderSelected: Boolean,
        permissionGranted: Boolean,
    ): AutoBackupAccess = when {
        !autoBackup -> AutoBackupAccess.Off
        !folderSelected -> AutoBackupAccess.NeedsFolder
        !permissionGranted -> AutoBackupAccess.PermissionLost
        else -> AutoBackupAccess.Scheduled
    }

    @StringRes
    fun stageLabelRes(kind: BackupOperationKind, stage: RestoreStage?): Int = when (kind) {
        BackupOperationKind.Backup -> R.string.backup_stage_backing_up
        BackupOperationKind.Restore -> when (stage) {
            RestoreStage.VALIDATING, null -> R.string.backup_stage_validating
            RestoreStage.EXTRACTING -> R.string.backup_stage_extracting
            RestoreStage.DATABASE -> R.string.backup_stage_database
            RestoreStage.FILES -> R.string.backup_stage_files
            RestoreStage.SETTINGS -> R.string.backup_stage_settings
            RestoreStage.CACHE -> R.string.backup_stage_cache
        }
    }
}
