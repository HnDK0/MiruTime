package dev.mirutime.app.features.settings.backup

import dev.mirutime.app.features.settings.data.RestoreProgress
import dev.mirutime.app.features.settings.data.RestoreStage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BackupOperationMonitorTest {
    @Before
    fun setUp() {
        BackupOperationMonitor.clear()
    }

    @After
    fun tearDown() {
        BackupOperationMonitor.clear()
    }

    @Test
    fun `backup progress is clamped and completion carries counts`() {
        BackupOperationMonitor.begin(BackupOperationKind.Backup)
        assertEquals(BackupOperationKind.Backup, BackupOperationMonitor.state.value.kind)
        assertTrue(BackupOperationMonitor.state.value.running)

        BackupOperationMonitor.backupProgress(0.42f)
        assertEquals(42, BackupOperationMonitor.state.value.percent)

        BackupOperationMonitor.backupProgress(1.7f)
        assertEquals(100, BackupOperationMonitor.state.value.percent)

        BackupOperationMonitor.finish(
            kind = BackupOperationKind.Backup,
            success = true,
            message = "Books: 2, files: 4, plugins: 1",
        )
        val done = BackupOperationMonitor.state.value
        assertFalse(done.running)
        assertTrue(done.finished)
        assertEquals(true, done.success)
        assertEquals(100, done.percent)
        assertTrue(done.finishedAt > 0L)
        assertEquals("Books: 2, files: 4, plugins: 1", done.message)
    }

    @Test
    fun `restore progress exposes stage and counters`() {
        BackupOperationMonitor.begin(BackupOperationKind.Restore)
        BackupOperationMonitor.restoreProgress(
            RestoreProgress(RestoreStage.FILES, 3, 12),
        )

        val progress = BackupOperationMonitor.state.value
        assertEquals(BackupOperationKind.Restore, progress.kind)
        assertEquals(RestoreStage.FILES, progress.stage)
        assertEquals(3, progress.current)
        assertEquals(12, progress.total)
        assertEquals(75, progress.percent)
    }

    @Test
    fun `failure keeps the last progress and marks the operation finished`() {
        BackupOperationMonitor.begin(BackupOperationKind.Restore)
        BackupOperationMonitor.restoreProgress(RestoreProgress(RestoreStage.DATABASE, 1, 2))
        BackupOperationMonitor.finish(
            kind = BackupOperationKind.Restore,
            success = false,
            message = "Backup is missing an entry",
        )

        val failed = BackupOperationMonitor.state.value
        assertTrue(failed.finished)
        assertEquals(false, failed.success)
        assertEquals(53, failed.percent)
        assertEquals("Backup is missing an entry", failed.message)
    }
}
