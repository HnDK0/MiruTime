package dev.mirutime.app.features.settings.backup

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mirutime.app.MiruApp
import dev.mirutime.app.features.settings.data.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression: toggling auto backup used to save a value loaded from
 * the store, which rolled back edits still inside the 300 ms debounce window.
 */
@RunWith(AndroidJUnit4::class)
class AutoBackupSchedulerInstrumentedTest {
    @Test
    fun disablingAutoBackupKeepsUnpersistedEdits() {
        val app = ApplicationProvider.getApplicationContext<MiruApp>()
        val previous = AppSettings.persistedSnapshot()
        try {
            AppSettings.applyPersisted(
                previous.copy(autoBackup = true, backupIncludeImages = false),
            )
            app.settingsStore.save(AppSettings.persistedSnapshot())

            // Simulates a settings toggle that has not reached the store yet.
            AppSettings.backupIncludeImages = true

            AutoBackupScheduler.disable(app)

            assertTrue(AppSettings.backupIncludeImages)
            assertTrue(app.settingsStore.load().backupIncludeImages)
            assertFalse(app.settingsStore.load().autoBackup)
        } finally {
            app.settingsStore.save(previous)
            AppSettings.applyPersisted(previous)
        }
    }
}
