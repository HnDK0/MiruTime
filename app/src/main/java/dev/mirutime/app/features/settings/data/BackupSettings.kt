package dev.mirutime.app.features.settings.data

import kotlinx.serialization.Serializable

/** Raw `mirutime_kv` contents, keys already in "namespace:key" form. */
@Serializable
data class PluginKvBackup(val entries: Map<String, String> = emptyMap())

/** SAF folders and the short-id registry for local books/manga. */
@Serializable
data class LocalSourcesBackup(
    val books: List<String> = emptyList(),
    val manga: List<String> = emptyList(),
    val registry: Map<String, String> = emptyMap(),
)

interface PluginKvBackupStore {
    fun snapshot(): PluginKvBackup

    /** Merge: archive values win, settings added after the backup survive. */
    fun restore(backup: PluginKvBackup)
}

interface LocalSourcesBackupStore {
    fun snapshot(): LocalSourcesBackup

    /** Merge: archive values win, folders/ids added after the backup survive. */
    fun restore(backup: LocalSourcesBackup)
}

object NoopPluginKvBackupStore : PluginKvBackupStore {
    override fun snapshot(): PluginKvBackup = PluginKvBackup()

    override fun restore(backup: PluginKvBackup) = Unit
}

object NoopLocalSourcesBackupStore : LocalSourcesBackupStore {
    override fun snapshot(): LocalSourcesBackup = LocalSourcesBackup()

    override fun restore(backup: LocalSourcesBackup) = Unit
}

internal fun mergePluginKv(
    existing: Map<String, String>,
    backup: Map<String, String>,
): Map<String, String> = existing + backup

internal fun mergeFolders(
    existing: Collection<String>,
    backup: Collection<String>,
): Set<String> = (existing + backup).toSet()
