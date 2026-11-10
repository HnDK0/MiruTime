package dev.mirutime.feature.tts

import android.content.SharedPreferences
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsBackupKeysTest {

    @Test
    fun `every key written by the TTS settings store is backed up`() {
        val prefs = RecordingPrefs()
        PrefsTtsSettingsStore(prefs).save(
            TtsSettings(
                voiceId = "voice-a",
                engineId = "engine-a",
                translatedVoiceId = "voice-b",
                translatedEngineId = "engine-b",
                savedVoices = listOf(
                    TtsSavedVoice(
                        name = "preset",
                        voiceId = "voice-a",
                        speed = 1.2f,
                        pitch = 0.9f,
                        engineId = "engine-a",
                    ),
                ),
            ),
        )

        assertTrue(
            "keys not in BackupKeys: ${prefs.putKeys - PrefsTtsSessionStore.BackupKeys}",
            PrefsTtsSessionStore.BackupKeys.containsAll(prefs.putKeys),
        )
    }

    private class RecordingPrefs : SharedPreferences {
        val putKeys = mutableSetOf<String>()

        override fun getAll(): Map<String, *> = emptyMap<String, Any>()

        override fun getString(key: String?, defValue: String?): String? = defValue

        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = defValues

        override fun getInt(key: String?, defValue: Int): Int = defValue

        override fun getLong(key: String?, defValue: Long): Long = defValue

        override fun getFloat(key: String?, defValue: Float): Float = defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue

        override fun contains(key: String?): Boolean = false

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) putKeys += key
                return this
            }

            override fun putStringSet(
                key: String?,
                values: Set<String>?,
            ): SharedPreferences.Editor = this

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this

            override fun remove(key: String?): SharedPreferences.Editor = this

            override fun clear(): SharedPreferences.Editor = this

            override fun commit(): Boolean = true

            override fun apply() = Unit
        }
    }
}
