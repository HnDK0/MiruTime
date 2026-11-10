package dev.mirutime.app.features.settings.data

import android.content.Context
import android.content.SharedPreferences
import dev.mirutime.app.design.THEME_BACKUP_KEYS
import dev.mirutime.app.design.THEME_PREFS
import dev.mirutime.app.design.notifyThemePrefsChanged
import dev.mirutime.app.features.reader.data.PrefsReaderConfigStore
import dev.mirutime.feature.tts.PrefsTtsSessionStore

/**
 * Android snapshot/apply for reader preferences. Only the user-owned keys are
 * copied: downloaded preset-background caches stay out of the archive.
 */
class PrefsReaderBackupStore(
    context: Context,
    private val readerConfig: PrefsReaderConfigStore,
    private val onThemeRestored: () -> Unit = ::notifyThemePrefsChanged,
    private val onRestored: () -> Unit = {},
) : ReaderBackupStore {
    private val appContext = context.applicationContext

    override fun snapshot(): ReaderBackupPrefs = ReaderBackupPrefs(
        theme = dump(prefs(THEME_PREFS), THEME_BACKUP_KEYS),
        readerConfig = dump(
            prefs(PrefsReaderConfigStore.PREFS_NAME),
            PrefsReaderConfigStore.BackupKeys,
        ),
        tts = dump(
            prefs(PrefsTtsSessionStore.PREFS_NAME),
            PrefsTtsSessionStore.BackupKeys,
        ),
    )

    override fun restore(prefs: ReaderBackupPrefs) {
        val config = write(
            prefs(PrefsReaderConfigStore.PREFS_NAME),
            prefs.readerConfig,
            PrefsReaderConfigStore.BackupKeys,
        )
        val tts = write(
            prefs(PrefsTtsSessionStore.PREFS_NAME),
            prefs.tts,
            PrefsTtsSessionStore.BackupKeys,
        )
        val theme = write(prefs(THEME_PREFS), prefs.theme, THEME_BACKUP_KEYS)
        if (!config || !tts || !theme) error("Could not save reader preferences")
        readerConfig.reload()
        onThemeRestored()
        onRestored()
    }

    private fun prefs(name: String): SharedPreferences =
        appContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun dump(prefs: SharedPreferences, keys: Set<String>): Map<String, String> =
        keys.mapNotNull { key -> prefs.getString(key, null)?.let { key to it } }.toMap()

    // commit() keeps the restored values durable before the DB tail finishes.
    private fun write(
        prefs: SharedPreferences,
        values: Map<String, String>,
        keys: Set<String>,
    ): Boolean {
        val editor = prefs.edit()
        keys.forEach { editor.remove(it) }
        values.forEach { (key, value) -> editor.putString(key, value) }
        return editor.commit()
    }
}
