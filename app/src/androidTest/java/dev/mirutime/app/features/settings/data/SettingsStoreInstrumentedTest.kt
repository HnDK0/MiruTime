package dev.mirutime.app.features.settings.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsStoreInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("instrumented_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun persistedKeysAndBackupSettingsSurviveNewStoreInstance() {
        val prefs = context.getSharedPreferences("instrumented_settings", Context.MODE_PRIVATE)
        val store = SettingsStore(SharedPreferencesKeyValueStore(prefs))
        val expected = PersistedSettings(
            provider = "DEEPL",
            deeplKeys = "secret-key",
            globalTarget = "ru",
            autoBackup = true,
            backupIncludeImages = true,
            backupIncludeSettings = true,
            pinnedSources = listOf("local-books", "chikari"),
        )

        store.save(expected)
        val reloaded = SettingsStore(SharedPreferencesKeyValueStore(prefs)).load()

        assertEquals("DEEPL", reloaded.provider)
        assertEquals("secret-key", reloaded.deeplKeys)
        assertEquals("ru", reloaded.globalTarget)
        assertTrue(reloaded.autoBackup)
        assertTrue(reloaded.backupIncludeImages)
        assertEquals(listOf("local-books", "chikari"), reloaded.pinnedSources)
    }

    @Test
    fun toStringNeverContainsApiKey() {
        val value = PersistedSettings(googlePaKeys = "super-secret", geminiKeys = "other-secret")
        assertTrue(!value.toString().contains("super-secret"))
        assertTrue(!value.toString().contains("other-secret"))
    }
}
