package dev.mirutime.feature.tts

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsTtsSettingsStoreTest {
    @Test
    fun `save and load keep auto-next off and mini-player on`() {
        val prefs = FakeSharedPreferences()
        val store = PrefsTtsSettingsStore(prefs)

        store.save(TtsSettings(autoNextChapter = false, miniPlayerAlways = true))

        val loaded = store.load()
        assertFalse(loaded.autoNextChapter)
        assertTrue(loaded.miniPlayerAlways)
        assertEquals("0", prefs.values[PrefsTtsSessionStore.KEY_AUTO_NEXT])
        assertEquals("1", prefs.values[PrefsTtsSessionStore.KEY_MINI_PLAYER_ALWAYS])
    }

    @Test
    fun `absent auto-next defaults to on`() {
        val loaded = PrefsTtsSettingsStore(FakeSharedPreferences()).load()

        assertTrue(loaded.autoNextChapter)
    }
}

private class FakeSharedPreferences : SharedPreferences {
    val values = mutableMapOf<String, Any?>()

    override fun getAll(): Map<String, *> = values.toMap()

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
        values[key] as? Set<String> ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor(values)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private class FakeEditor(
        private val values: MutableMap<String, Any?>,
    ) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor =
            put(key, value)

        override fun putStringSet(
            key: String?,
            values: Set<String>?,
        ): SharedPreferences.Editor = put(key, values)

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
            put(key, value)

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) removed += key
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }

        override fun commit(): Boolean {
            if (clear) values.clear()
            values.keys.removeAll(removed)
            values.putAll(pending)
            pending.clear()
            removed.clear()
            clear = false
            return true
        }

        override fun apply() {
            commit()
        }

        private fun put(key: String?, value: Any?): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
    }
}
