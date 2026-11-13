package dev.mirutime.app.di

import dev.mirutime.app.core.AppCacheConfig
import dev.mirutime.core.host.FileSystem
import dev.mirutime.core.installer.IndexEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Relative to PluginGraph's root (filesDir/mirutime_plugins).
internal const val REPO_CATALOG_FILE = "repo_catalog.json"

// Last successful repository catalog, kept on disk so Manage extensions can
// paint rows before the network answers. Error is not persisted: a cached
// list is only the entries, never a stale failure message.
internal data class CachedRepoCatalog(
    val repoUrl: String,
    val storedAt: Long,
    val catalog: RepoCatalog,
)

// Atomic read/write over the plugin file system (AndroidFileSystem writes a
// temp file then renames). A missing, corrupt or foreign-URL file is a miss,
// never an exception.
internal class RepoCatalogStore(
    private val fileSystem: FileSystem,
    private val path: String = REPO_CATALOG_FILE,
) {
    fun read(repoUrl: String): CachedRepoCatalog? =
        readSnapshot()?.takeIf { it.repoUrl == repoUrl.trim() }

    fun readSnapshot(): CachedRepoCatalog? {
        val bytes = fileSystem.read(path) ?: return null
        val raw = runCatching { bytes.decodeToString() }.getOrNull() ?: return null
        return runCatching { RepoCatalogCodec.decode(raw) }.getOrNull()
    }

    fun write(snapshot: CachedRepoCatalog) {
        // Partial success is worth keeping; an empty catalog is not.
        if (snapshot.repoUrl.isBlank() ||
            (snapshot.catalog.entries.isEmpty() && snapshot.catalog.libraries.isEmpty())
        ) {
            return
        }
        runCatching {
            fileSystem.write(path, RepoCatalogCodec.encode(snapshot).encodeToByteArray())
        }
    }
}

// A cached snapshot of any age may be painted; only repoCatalog() applies the
// TTL before deciding to hit the network.
internal fun isRepoCatalogFresh(
    cached: CachedRepoCatalog?,
    ttlMs: Long,
    now: Long = System.currentTimeMillis(),
): Boolean = cached != null && AppCacheConfig.isFresh(cached.storedAt, ttlMs, now)

private object RepoCatalogCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(snapshot: CachedRepoCatalog): String = json.encodeToString(
        RepoCatalogDiskModel(
            repoUrl = snapshot.repoUrl,
            storedAt = snapshot.storedAt,
            entries = snapshot.catalog.entries.map { it.toDiskModel() },
            libraries = snapshot.catalog.libraries.map { it.toDiskModel() },
        ),
    )

    fun decode(raw: String): CachedRepoCatalog? {
        val model = json.decodeFromString<RepoCatalogDiskModel>(raw)
        if (model.repoUrl.isBlank() || model.storedAt <= 0) return null
        val entries = model.entries.mapNotNull { it.toIndexEntry() }
        val libraries = model.libraries.mapNotNull { it.toIndexEntry() }
        if (entries.isEmpty() && libraries.isEmpty()) return null
        return CachedRepoCatalog(
            repoUrl = model.repoUrl.trim(),
            storedAt = model.storedAt,
            catalog = RepoCatalog(entries = entries, libraries = libraries),
        )
    }
}

@Serializable
private data class RepoCatalogDiskModel(
    val repoUrl: String,
    val storedAt: Long,
    val entries: List<RepoCatalogEntryModel> = emptyList(),
    // Legacy snapshots predate this field: the default keeps them readable.
    val libraries: List<RepoCatalogEntryModel> = emptyList(),
)

@Serializable
private data class RepoCatalogEntryModel(
    val id: String,
    val name: String? = null,
    val version: String? = null,
    val url: String,
    val sha256: String? = null,
    val api: Int? = null,
    val contentType: String? = null,
    val language: String? = null,
    val icon: String? = null,
    // Declared shared-library deps: dropping them here would silently disable
    // the install gate for every catalog read from this cache.
    val requires: List<String> = emptyList(),
)

private fun IndexEntry.toDiskModel(): RepoCatalogEntryModel = RepoCatalogEntryModel(
    id = id,
    name = name,
    version = version,
    url = url,
    sha256 = sha256,
    api = api,
    contentType = contentType,
    language = language,
    icon = icon,
    requires = requires,
)

private fun RepoCatalogEntryModel.toIndexEntry(): IndexEntry? {
    val cleanId = id.trim()
    val cleanUrl = url.trim()
    if (cleanId.isEmpty() || cleanUrl.isEmpty()) return null
    return IndexEntry(
        id = cleanId,
        name = name,
        version = version,
        url = cleanUrl,
        sha256 = sha256,
        api = api,
        contentType = contentType,
        language = language,
        icon = icon,
        requires = requires.trimmed().distinct(),
    )
}

private fun List<String>.trimmed(): List<String> =
    mapNotNull { it.trim().takeIf(String::isNotEmpty) }
