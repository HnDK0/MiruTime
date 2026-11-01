package dev.mirutime.app.features.settings.ui

import dev.mirutime.app.R
import dev.mirutime.app.features.settings.backup.BackupOperationKind
import dev.mirutime.app.features.settings.data.RestoreStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupUiModelTest {
    @Test
    fun `interval choices never go below the backend floor`() {
        assertTrue(BackupUiModel.intervalOptions.all { it >= 60 })
        assertEquals(BackupUiModel.intervalOptions.sorted(), BackupUiModel.intervalOptions)
        assertTrue(60 in BackupUiModel.intervalOptions)
        assertTrue(1440 in BackupUiModel.intervalOptions)
    }

    @Test
    fun `auto backup access follows folder and permission state`() {
        assertEquals(
            AutoBackupAccess.Off,
            BackupUiModel.accessStatus(false, folderSelected = true, permissionGranted = true),
        )
        assertEquals(
            AutoBackupAccess.NeedsFolder,
            BackupUiModel.accessStatus(true, folderSelected = false, permissionGranted = true),
        )
        assertEquals(
            AutoBackupAccess.PermissionLost,
            BackupUiModel.accessStatus(true, folderSelected = true, permissionGranted = false),
        )
        assertEquals(
            AutoBackupAccess.Scheduled,
            BackupUiModel.accessStatus(true, folderSelected = true, permissionGranted = true),
        )
    }

    @Test
    fun `stage labels cover every restore stage and the backup lane`() {
        assertEquals(
            R.string.backup_stage_backing_up,
            BackupUiModel.stageLabelRes(BackupOperationKind.Backup, RestoreStage.FILES),
        )
        assertEquals(
            R.string.backup_stage_validating,
            BackupUiModel.stageLabelRes(BackupOperationKind.Restore, null),
        )
        assertEquals(
            R.string.backup_stage_extracting,
            BackupUiModel.stageLabelRes(BackupOperationKind.Restore, RestoreStage.EXTRACTING),
        )
        assertEquals(
            R.string.backup_stage_database,
            BackupUiModel.stageLabelRes(BackupOperationKind.Restore, RestoreStage.DATABASE),
        )
        assertEquals(
            R.string.backup_stage_files,
            BackupUiModel.stageLabelRes(BackupOperationKind.Restore, RestoreStage.FILES),
        )
        assertEquals(
            R.string.backup_stage_settings,
            BackupUiModel.stageLabelRes(BackupOperationKind.Restore, RestoreStage.SETTINGS),
        )
        assertEquals(
            R.string.backup_stage_cache,
            BackupUiModel.stageLabelRes(BackupOperationKind.Restore, RestoreStage.CACHE),
        )
    }
}
