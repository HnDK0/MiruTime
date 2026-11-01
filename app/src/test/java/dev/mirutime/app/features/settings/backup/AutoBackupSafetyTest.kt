package dev.mirutime.app.features.settings.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBackupSafetyTest {
    @Test
    fun `transient retries are bounded and end with a terminal result`() {
        assertEquals(AutoBackupAttemptDecision.Retry, AutoBackupRetryPolicy.decide(0))
        assertEquals(AutoBackupAttemptDecision.Retry, AutoBackupRetryPolicy.decide(1))
        assertEquals(AutoBackupAttemptDecision.GiveUp, AutoBackupRetryPolicy.decide(2))
        assertEquals(AutoBackupAttemptDecision.GiveUp, AutoBackupRetryPolicy.decide(99))
    }

    @Test
    fun `part naming never looks like a completed backup`() {
        val finalName = "mirutime-auto-20261024-120000.zip"
        val partName = BackupRotation.partName(finalName)

        assertEquals("mirutime-auto-20261024-120000.zip.part", partName)
        assertTrue(BackupRotation.isAppPart(partName))
        assertFalse(BackupRotation.isAppBackup(partName))
        assertFalse(BackupRotation.isAppPart(finalName))
        assertTrue(BackupRotation.isAppBackup(finalName))
    }

    @Test
    fun `rotation skips parts protected and recent archives`() {
        val now = 10_000_000L
        val old = now - 10 * 60_000
        val files = listOf(
            BackupFileInfo("1", "mirutime-auto-20260101-000000.zip", old),
            BackupFileInfo("2", "mirutime-auto-20260102-000000.zip", old + 1),
            BackupFileInfo("3", "mirutime-auto-20260103-000000.zip", now - 1_000),
            BackupFileInfo("4", "mirutime-auto-20260104-000000.zip.part", old),
            BackupFileInfo("5", "holiday.zip", 1),
            BackupFileInfo("6", "mirutime-backup-20251231-000000.zip", old - 1),
        )
        val justCreated = files[1].displayName

        // #2 is protected, #3 is too recent, #4 is a part, #5 is not ours.
        assertTrue(
            BackupRotation.selectForDeletion(
                files,
                maxCount = 2,
                excludedNames = setOf(justCreated),
                now = now,
            ).isEmpty(),
        )

        val stale = BackupRotation.selectForDeletion(
            files,
            maxCount = 1,
            excludedNames = setOf(justCreated),
            now = now,
        )
        assertEquals(
            listOf("mirutime-backup-20251231-000000.zip"),
            stale.map { it.displayName },
        )
    }

    @Test
    fun `stale part detection uses the part age`() {
        val now = 10_000_000L
        val fresh = BackupFileInfo(
            "1",
            "mirutime-auto-20261024-120000.zip.part",
            now - BackupRotation.StalePartMillis + 1,
        )
        val stale = fresh.copy(lastModified = now - BackupRotation.StalePartMillis)

        assertFalse(BackupRotation.isStalePart(fresh, now))
        assertTrue(BackupRotation.isStalePart(stale, now))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `run gate serializes periodic and run-now work`() = runTest {
        val order = mutableListOf<String>()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val first = launch {
            AutoBackupRunGate.withLock {
                order += "first-start"
                firstEntered.complete(Unit)
                releaseFirst.await()
                order += "first-end"
            }
        }
        firstEntered.await()
        val second = launch {
            AutoBackupRunGate.withLock { order += "second" }
        }

        advanceUntilIdle()
        assertEquals(listOf("first-start"), order)

        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertEquals(listOf("first-start", "first-end", "second"), order)
    }
}
