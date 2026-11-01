package dev.mirutime.app.features.settings.data

import java.io.File
import kotlinx.serialization.Serializable

/**
 * Reader-side preferences that live outside [PersistedSettings]: app theme,
 * `reader_config` (mode/typography/manga) and the `reader_tts` session plus
 * voice/engine/rate/pitch settings.
 * Values are the raw SharedPreferences strings so new reader keys survive a
 * backup without another schema change.
 */
internal const val ReaderBackupPrefsVersion = 1

@Serializable
data class ReaderBackupPrefs(
    val version: Int = ReaderBackupPrefsVersion,
    val theme: Map<String, String> = emptyMap(),
    val readerConfig: Map<String, String> = emptyMap(),
    val tts: Map<String, String> = emptyMap(),
)

/** Snapshot/apply seam so backup/restore tests need no Android context. */
interface ReaderBackupStore {
    fun snapshot(): ReaderBackupPrefs

    fun restore(prefs: ReaderBackupPrefs)
}

object NoopReaderBackupStore : ReaderBackupStore {
    override fun snapshot(): ReaderBackupPrefs = ReaderBackupPrefs()

    override fun restore(prefs: ReaderBackupPrefs) = Unit
}

internal const val READER_ENTRY = "reader.json"
internal const val READER_ASSETS_ENTRY = "reader/assets"

// Directories that hold user-owned reader art. The cache dir (downloaded
// preset previews) is never part of this list.
internal val READER_ASSET_DIRS = listOf(
    "reader/fonts" to "fonts",
    "reader/backgrounds" to "reader-backgrounds",
    "backgrounds" to "theme-backgrounds",
)
internal val READER_ASSET_BUCKETS = READER_ASSET_DIRS.map { it.second }.toSet()

/**
 * Maps an archive entry back into filesDir. Only the three flat, user-owned
 * buckets are accepted; [READER_ASSETS_ENTRY] is validated by the caller.
 */
internal fun readerAssetTarget(filesDir: File, entryPath: String): File {
    val rest = entryPath.removePrefix("$READER_ASSETS_ENTRY/")
    val bucket = rest.substringBefore('/')
    val name = rest.substringAfter('/')
    val dir = when (bucket) {
        "fonts" -> "reader/fonts"
        "reader-backgrounds" -> "reader/backgrounds"
        "theme-backgrounds" -> "backgrounds"
        else -> error("Backup contains an invalid reader asset: $entryPath")
    }
    return File(filesDir, "$dir/$name")
}
