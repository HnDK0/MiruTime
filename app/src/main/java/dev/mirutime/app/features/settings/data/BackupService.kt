package dev.mirutime.app.features.settings.data

import android.content.Context
import dev.mirutime.core.log.MiruLog
import dev.mirutime.app.features.library.data.LocalMangaInstallId
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.LibrarySchemaVersion
import dev.mirutime.data.library.LocalBackupBook
import dev.mirutime.data.library.LocalBackupCover
import dev.mirutime.data.library.createDatabaseSnapshot
import dev.mirutime.data.library.pruneHistoryOnlyBooks
import dev.mirutime.data.library.pruneLocalMangaBooks
import dev.mirutime.data.library.rewriteCachedBodyPaths
import dev.mirutime.data.library.snapshotBookCounts
import dev.mirutime.data.library.stableBackupBodyPath
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Backup archive format. v2 added optional settings/images/plugins; v3 added
// the reader payload (theme/reader/TTS prefs + user assets); v4 adds plugin KV
// and SAF local sources. Restore still reads v1-v3 archives. Per-entry paths
// stay stable (`manifest.json`, `settings.json`, `reader.json`,
// `settings/plugin-kv.json`, `settings/local-sources.json`,
// `database/mirutime.db`, `files/...`, `plugins/...`, `reader/assets/...`).
const val BackupFormatVersion = 4
val SupportedBackupFormats = 1..BackupFormatVersion

@Serializable
data class BackupEntry(
    val name: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class BackupFileEntry(
    val path: String,
    val bookId: Long,
    val kind: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class BackupPluginEntry(
    val path: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class BackupReaderAssetEntry(
    val path: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class BackupCounts(
    // Books the archive selected files for: every in-library book except
    // local-manga. Log-only (`stagingBookCount`); restore does not read it.
    val localBooks: Int,
    // Every in-library book in the snapshot.
    val libraryBooks: Int = 0,
    // Every book row in the DB snapshot, including history-only books.
    val totalBooks: Int = 0,
    val localFiles: Int,
    val pluginFiles: Int = 0,
    val readerFiles: Int = 0,
    val pluginKvEntries: Int = 0,
    val localSourceFolders: Int = 0,
)

@Serializable
data class BackupManifest(
    val formatVersion: Int = BackupFormatVersion,
    val appVersion: String,
    val schemaVersion: Int = LibrarySchemaVersion,
    val createdAt: Long,
    val settingsIncluded: Boolean,
    val imagesIncluded: Boolean = true,
    val pluginsIncluded: Boolean = false,
    // Reader prefs/assets follow includeSettings: they are preferences, not
    // library content. Absent on v1/v2 archives and when settings are off.
    val readerIncluded: Boolean = false,
    val database: BackupEntry,
    val settings: BackupEntry? = null,
    val reader: BackupEntry? = null,
    val pluginKv: BackupEntry? = null,
    val localSources: BackupEntry? = null,
    val files: List<BackupFileEntry> = emptyList(),
    val plugins: List<BackupPluginEntry> = emptyList(),
    val readerAssets: List<BackupReaderAssetEntry> = emptyList(),
    val counts: BackupCounts,
)

/** What a manual/automatic backup includes. */
data class BackupOptions(
    val includeSettings: Boolean = true,
    val includeImages: Boolean = true,
    val includePlugins: Boolean = false,
    // Default is library-only: history-only rows are not user library content.
    val includeHistoryOnly: Boolean = false,
) {
    companion object {
        fun fromSettings(): BackupOptions = BackupOptions(
            includeSettings = AppSettings.backupIncludeSettings,
            includeImages = AppSettings.backupIncludeImages,
            includePlugins = AppSettings.backupIncludePlugins,
            includeHistoryOnly = AppSettings.backupIncludeHistoryOnly,
        )
    }
}

data class DatabaseSnapshot(
    val file: File,
    val size: Long,
    val sha256: String,
    val schemaVersion: Int = LibrarySchemaVersion,
    // Counted inside the pruned snapshot, so the manifest never claims books
    // the archive dropped (local-manga rows, history-only rows).
    val totalBooks: Int = 0,
    val libraryBooks: Int = 0,
)

data class BackupResult(
    val success: Boolean,
    val bytes: Long = 0,
    val entryCount: Int = 0,
    val bookCount: Int = 0,
    val libraryBookCount: Int = 0,
    val localBookCount: Int = 0,
    val localFileCount: Int = 0,
    val pluginFileCount: Int = 0,
    val readerFileCount: Int = 0,
    val error: String? = null,
)

// The one Json instance used for both manifest and settings. Defaults are
// written out on purpose: restore must see every field, including empty keys.
internal val BackupJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * Streams one backup archive into [createBackup]'s output.
 *
 * The archive carries a consistent DB snapshot (VACUUM INTO), an optional
 * settings snapshot (including API keys), every file the in-library books need
 * to stay readable offline (plugin chapter bodies and covers, plus local-book
 * copies; optionally without covers), and optionally the Lua plugin trees.
 * Files never load into RAM; each entry is copied in fixed-size chunks with a
 * per-file cap, then the finished ZIP is reopened and verified.
 */
class BackupService(
    private val databasePath: File,
    private val filesDir: File,
    private val cacheDir: File,
    private val appVersion: String,
    private val localBooks: suspend () -> List<LocalBackupBook>,
    private val localCovers: suspend () -> List<LocalBackupCover> = { emptyList() },
    private val settingsSnapshot: () -> PersistedSettings = { AppSettings.persistedSnapshot() },
    private val readerSnapshot: () -> ReaderBackupPrefs = { ReaderBackupPrefs() },
    private val pluginKvSnapshot: () -> PluginKvBackup,
    private val localSourcesSnapshot: () -> LocalSourcesBackup,
    private val snapshotDatabase: suspend (File, Boolean) -> DatabaseSnapshot =
        { target, includeHistoryOnly ->
            createSnapshot(databasePath, target, includeHistoryOnly, filesDir)
        },
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    constructor(
        context: Context,
        databasePath: File,
        library: LibraryRepository,
        readerSnapshot: () -> ReaderBackupPrefs = { ReaderBackupPrefs() },
        pluginKvSnapshot: () -> PluginKvBackup,
        localSourcesSnapshot: () -> LocalSourcesBackup,
    ) : this(
        databasePath = databasePath,
        filesDir = context.applicationContext.filesDir,
        cacheDir = context.applicationContext.cacheDir,
        appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0.1",
        localBooks = { library.localBackupBooks() },
        localCovers = { library.localBackupCovers() },
        readerSnapshot = readerSnapshot,
        pluginKvSnapshot = pluginKvSnapshot,
        localSourcesSnapshot = localSourcesSnapshot,
    )

    /**
     * Writes the ZIP and leaves [outputStream] open (the caller owns it). The
     * returned result reports failure instead of throwing so UI can show a
     * toast; cancellation always propagates after temp cleanup.
     */
    suspend fun createBackup(
        outputStream: OutputStream,
        options: BackupOptions = BackupOptions(),
        onProgress: (Float) -> Unit = {},
    ): BackupResult {
        if (!BackupOperationGate.tryEnter()) {
            return BackupResult(
                success = false,
                error = "Another backup or restore is already running",
            )
        }
        val temp = File(cacheDir, "$BACKUP_TEMP_PREFIX${newId()}.tmp")
        return try {
            cleanupStaleTemps()
            val result = writeArchiveTo(temp, options, onProgress)
            if (result.success) {
                temp.inputStream().use { input ->
                    input.copyTo(outputStream, CHUNK_BYTES)
                }
                outputStream.flush()
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BackupResult(success = false, error = e.message ?: "Backup failed")
        } finally {
            temp.delete()
            BackupOperationGate.exit()
        }
    }

    /**
     * Writes and validates the archive directly into [target]. The caller owns
     * [target] and can copy it to SAF atomically afterwards.
     */
    suspend fun createBackupTo(
        target: File,
        options: BackupOptions = BackupOptions(),
        onProgress: (Float) -> Unit = {},
    ): BackupResult {
        if (!BackupOperationGate.tryEnter()) {
            return BackupResult(
                success = false,
                error = "Another backup or restore is already running",
            )
        }
        return try {
            cleanupStaleTemps(keep = target)
            writeArchiveTo(target, options, onProgress)
        } finally {
            BackupOperationGate.exit()
        }
    }

    private suspend fun writeArchiveTo(
        target: File,
        options: BackupOptions,
        onProgress: (Float) -> Unit,
    ): BackupResult {
        var snapshot: DatabaseSnapshot? = null
        var snapshotTarget: File? = null
        try {
            onProgress(0.02f)
            // Selection already filters in Room; keep the archive boundary
            // strict so a future caller cannot leak a history-only copy.
            val books = localBooks().filter { it.inLibrary }
            onProgress(0.10f)
            val fileCandidates = books.flatMap(::collectBookFiles)
            // Plugin books contribute their cached bodies and covers (a user
            // override is always user data). A user override is always packed;
            // other covers follow includeImages. Local-manga books are pruned
            // from the snapshot (their pages are never packed), so their covers
            // would be dead weight in the archive.
            val coverCandidates = localCovers()
                .filter { it.installId != LocalMangaInstallId }
                .mapNotNull { cover ->
                val file = resolve(cover.coverLocalPath)
                if (!file.isFile) {
                    if (cover.isUserOverride) {
                        MiruLog.event("backup", "cover-missing", "book" to cover.bookId)
                    }
                    return@mapNotNull null
                }
                FileCandidate(
                    entryPath = entryPath(file, cover.bookId, KIND_COVER),
                    bookId = cover.bookId,
                    kind = KIND_COVER,
                    file = file,
                    isUserOverride = cover.isUserOverride,
                )
            }
            // Covers follow includeImages (user overrides always ride along).
            // Manga pages and video files are never candidates.
            val candidates = (fileCandidates + coverCandidates)
                .filter {
                    options.includeImages ||
                        it.kind != KIND_COVER ||
                        it.isUserOverride
                }
                .distinctBy { it.entryPath }
                .sortedBy { it.entryPath }
            val plugins = if (options.includePlugins) collectPluginFiles() else emptyList()
            val readerAssets = if (options.includeSettings) collectReaderAssets() else emptyList()
            onProgress(0.15f)

            val targetDb = File(cacheDir, "mirutime-db-${newId()}.db")
            snapshotTarget = targetDb
            if (targetDb.exists()) targetDb.delete()
            val db = snapshotDatabase(targetDb, options.includeHistoryOnly)
            snapshot = db
            onProgress(0.30f)

            val settingsBytes = if (options.includeSettings) {
                BackupJson
                    .encodeToString(
                        PersistedSettings.serializer(),
                        settingsSnapshot().withDeviceLocalPathsFrom(current = null),
                    )
                    .encodeToByteArray()
            } else {
                null
            }
            val readerBytes = if (options.includeSettings) {
                BackupJson
                    .encodeToString(ReaderBackupPrefs.serializer(), readerSnapshot())
                    .encodeToByteArray()
            } else {
                null
            }
            val pluginKvBackup = if (options.includeSettings || options.includePlugins) {
                pluginKvSnapshot()
            } else {
                null
            }
            val localSourcesBackup = if (options.includeSettings) {
                localSourcesSnapshot()
            } else {
                null
            }
            val pluginKvBytes = pluginKvBackup?.let {
                BackupJson.encodeToString(PluginKvBackup.serializer(), it).encodeToByteArray()
            }
            val localSourcesBytes = localSourcesBackup?.let {
                BackupJson.encodeToString(LocalSourcesBackup.serializer(), it).encodeToByteArray()
            }
            if (pluginKvBytes != null &&
                pluginKvBytes.size.toLong() > MAX_ARCHIVE_PLUGIN_KV_BYTES
            ) {
                error("Plugin settings are too large for a backup")
            }
            if (localSourcesBytes != null &&
                localSourcesBytes.size.toLong() > MAX_ARCHIVE_LOCAL_SOURCES_BYTES
            ) {
                error("Local sources are too large for a backup")
            }
            val fileEntries = candidates.map { candidate ->
                val size = candidate.file.length()
                if (size > MAX_ENTRY_BYTES) {
                    error("File too large for backup: ${candidate.entryPath}")
                }
                BackupFileEntry(
                    path = candidate.entryPath,
                    bookId = candidate.bookId,
                    kind = candidate.kind,
                    size = size,
                    sha256 = sha256File(candidate.file),
                )
            }
            val pluginEntries = plugins.map { candidate ->
                val size = candidate.file.length()
                if (size > MAX_ENTRY_BYTES) {
                    error("Plugin file too large for backup: ${candidate.entryPath}")
                }
                BackupPluginEntry(
                    path = candidate.entryPath,
                    size = size,
                    sha256 = sha256File(candidate.file),
                )
            }
            val readerAssetEntries = readerAssets.map { candidate ->
                val size = candidate.file.length()
                if (size > MAX_READER_ASSET_BYTES) {
                    error("Reader asset too large for backup: ${candidate.entryPath}")
                }
                BackupReaderAssetEntry(
                    path = candidate.entryPath,
                    size = size,
                    sha256 = sha256File(candidate.file),
                )
            }
            val estimated = db.size +
                fileEntries.sumOf { it.size } +
                pluginEntries.sumOf { it.size } +
                readerAssetEntries.sumOf { it.size } +
                (settingsBytes?.size?.toLong() ?: 0L) +
                (readerBytes?.size?.toLong() ?: 0L) +
                (pluginKvBytes?.size?.toLong() ?: 0L) +
                (localSourcesBytes?.size?.toLong() ?: 0L)
            requireFreeSpace(target.parentFile ?: cacheDir, estimated, "backup archive")

            val manifest = BackupManifest(
                appVersion = appVersion,
                createdAt = clock(),
                settingsIncluded = options.includeSettings,
                imagesIncluded = options.includeImages,
                pluginsIncluded = options.includePlugins,
                readerIncluded = options.includeSettings,
                database = BackupEntry(
                    name = DATABASE_ENTRY,
                    size = db.size,
                    sha256 = db.sha256,
                ),
                settings = settingsBytes?.let {
                    BackupEntry(
                        name = SETTINGS_ENTRY,
                        size = it.size.toLong(),
                        sha256 = sha256Hex(it),
                    )
                },
                reader = readerBytes?.let {
                    BackupEntry(
                        name = READER_ENTRY,
                        size = it.size.toLong(),
                        sha256 = sha256Hex(it),
                    )
                },
                pluginKv = pluginKvBytes?.let {
                    BackupEntry(
                        name = PLUGIN_KV_ENTRY,
                        size = it.size.toLong(),
                        sha256 = sha256Hex(it),
                    )
                },
                localSources = localSourcesBytes?.let {
                    BackupEntry(
                        name = LOCAL_SOURCES_ENTRY,
                        size = it.size.toLong(),
                        sha256 = sha256Hex(it),
                    )
                },
                files = fileEntries,
                plugins = pluginEntries,
                readerAssets = readerAssetEntries,
                counts = BackupCounts(
                    localBooks = books.size,
                    libraryBooks = db.libraryBooks,
                    // Without history-only rows the snapshot holds just the
                    // in-library books; both numbers come from the snapshot.
                    totalBooks = if (options.includeHistoryOnly) {
                        db.totalBooks
                    } else {
                        db.libraryBooks
                    },
                    localFiles = fileEntries.size,
                    pluginFiles = pluginEntries.size,
                    readerFiles = readerAssetEntries.size,
                    pluginKvEntries = pluginKvBackup?.entries?.size ?: 0,
                    localSourceFolders = localSourcesBackup?.let {
                        it.books.size + it.manga.size
                    } ?: 0,
                ),
            )
            val manifestBytes = BackupJson
                .encodeToString(BackupManifest.serializer(), manifest)
                .encodeToByteArray()

            var bytes = 0L
            var entries = 0

            target.parentFile?.mkdirs()
            FileOutputStream(target).use { fileOut ->
                val zip = ZipOutputStream(BufferedOutputStream(fileOut))
                try {
                    zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                    zip.write(manifestBytes)
                    zip.closeEntry()
                    bytes += manifestBytes.size
                    entries++

                    if (settingsBytes != null) {
                        zip.putNextEntry(ZipEntry(SETTINGS_ENTRY))
                        zip.write(settingsBytes)
                        zip.closeEntry()
                        bytes += settingsBytes.size
                        entries++
                    }

                    if (readerBytes != null) {
                        zip.putNextEntry(ZipEntry(READER_ENTRY))
                        zip.write(readerBytes)
                        zip.closeEntry()
                        bytes += readerBytes.size
                        entries++
                    }

                    if (pluginKvBytes != null) {
                        zip.putNextEntry(ZipEntry(PLUGIN_KV_ENTRY))
                        zip.write(pluginKvBytes)
                        zip.closeEntry()
                        bytes += pluginKvBytes.size
                        entries++
                    }

                    if (localSourcesBytes != null) {
                        zip.putNextEntry(ZipEntry(LOCAL_SOURCES_ENTRY))
                        zip.write(localSourcesBytes)
                        zip.closeEntry()
                        bytes += localSourcesBytes.size
                        entries++
                    }

                    bytes += putFile(zip, DATABASE_ENTRY, db.file)
                    entries++
                    onProgress(0.40f)

                    val payload = candidates.size + plugins.size
                    candidates.forEachIndexed { index, candidate ->
                        bytes += putFile(zip, candidate.entryPath, candidate.file)
                        entries++
                        val fraction = if (payload == 0) 1f else (index + 1f) / payload
                        onProgress((0.40f + 0.55f * fraction).coerceAtMost(0.95f))
                    }
                    plugins.forEachIndexed { index, candidate ->
                        bytes += putFile(zip, candidate.entryPath, candidate.file)
                        entries++
                        val fraction = (candidates.size + index + 1f) / payload.coerceAtLeast(1)
                        onProgress((0.40f + 0.55f * fraction).coerceAtMost(0.95f))
                    }
                    readerAssets.forEach { candidate ->
                        bytes += putFile(zip, candidate.entryPath, candidate.file)
                        entries++
                    }
                    zip.finish()
                    zip.flush()
                    fileOut.fd.sync()
                } finally {
                    zip.close()
                }
            }

            // Reopen the finished archive: EOCD/central directory, exact entry
            // set, sizes and hashes. Only then may the caller copy it to SAF.
            validateBackupArchive(target)
            onProgress(1f)
            return BackupResult(
                success = true,
                bytes = target.length(),
                entryCount = entries,
                bookCount = if (options.includeHistoryOnly) db.totalBooks else db.libraryBooks,
                libraryBookCount = db.libraryBooks,
                localBookCount = books.size,
                localFileCount = fileEntries.size,
                pluginFileCount = pluginEntries.size,
                readerFileCount = readerAssetEntries.size,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            target.delete()
            return BackupResult(
                success = false,
                error = e.message ?: e::class.simpleName ?: "Backup failed",
            )
        } finally {
            snapshot?.file?.delete()
            if (snapshotTarget != snapshot?.file) snapshotTarget?.delete()
        }
    }

    /**
     * Removes temp archives and old database snapshots left by a killed
     * process. Fresh DB snapshots are kept (another live operation may own
     * them); [keep] protects the caller's current target.
     */
    fun cleanupStaleTemps(
        keep: File? = null,
        now: Long = System.currentTimeMillis(),
    ) {
        val cutoff = now - StaleDbTempAgeMs
        cacheDir.listFiles()?.forEach { file ->
            val name = file.name
            if (keep != null &&
                (file.absolutePath == keep.absolutePath || name.startsWith(keep.name))
            ) {
                return@forEach
            }
            when {
                name.startsWith(BACKUP_TEMP_PREFIX) && name.endsWith(".tmp") -> file.delete()
                name.startsWith(DB_TEMP_PREFIX) && name.hasDbTempSuffix() -> {
                    val modified = file.lastModified()
                    if (modified > 0 && modified < cutoff) file.delete()
                }
            }
        }
    }

    private fun String.hasDbTempSuffix(): Boolean =
        endsWith(".db") || endsWith(".db-wal") || endsWith(".db-shm") || endsWith(".db-journal")

    private data class FileCandidate(
        val entryPath: String,
        val bookId: Long,
        val kind: String,
        val file: File,
        val isUserOverride: Boolean = false,
    )

    // copyPath (file or folder), cover and every body path of one book.
    // Directories recurse; duplicates are written once, first kind wins.
    private fun collectBookFiles(book: LocalBackupBook): List<FileCandidate> {
        // Manga books are pruned from the snapshot and never own packed files.
        if (book.installId == LocalMangaInstallId) return emptyList()
        val seen = LinkedHashMap<String, FileCandidate>()
        // A local book owns its copy and bodies: losing them breaks the book.
        // Plugin bodies are a cache the reader can refill, so a missing file is
        // skipped instead of failing the whole backup.
        val required = book.installId in LOCAL_INSTALL_IDS

        fun missing(kind: String, path: String): Nothing = error(
            "Backup is missing required $kind for book ${book.bookId} " +
                "(${book.title}): $path",
        )

        fun addFile(
            file: File,
            kind: String,
            required: Boolean,
            path: String,
            override: String? = null,
        ) {
            if (!file.isFile) {
                if (required) missing(kind, path)
                return
            }
            val entry = override ?: entryPath(file, book.bookId, kind)
            if (seen.containsKey(entry)) return
            seen[entry] = FileCandidate(entry, book.bookId, kind, file)
        }

        fun addPath(
            path: String?,
            kind: String,
            required: Boolean,
            override: (File) -> String? = { null },
        ) {
            if (path.isNullOrBlank()) {
                if (required) missing(kind, "<empty>")
                return
            }
            val file = resolve(path)
            when {
                file.isFile -> addFile(file, kind, required, path, override(file))
                file.isDirectory -> {
                    val children = file.walkTopDown().filter { it.isFile }.toList()
                    if (children.isEmpty() && required) missing(kind, path)
                    children.forEach { child ->
                        addFile(child, kind, required, child.absolutePath, override(child))
                    }
                }
                else -> if (required) missing(kind, path)
            }
        }

        // A plugin body gets an id-free path so a restore into another library
        // cannot collide with a different book's cache file; the snapshot
        // rewrites the same path (see rewriteCachedBodyPaths). Local books keep
        // their meaningful slug-based paths.
        fun bodyEntry(file: File): String? {
            if (book.installId in LOCAL_INSTALL_IDS) return null
            val relative = file.relativeToOrNull(filesDir)
                ?.takeIf { !it.path.startsWith("..") }
                ?.invariantSeparatorsPath
                ?: return null
            return "files/" + stableBackupBodyPath(relative) { sha256Hex(it.encodeToByteArray()) }
        }

        addPath(book.copyPath, KIND_LOCAL_COPY, required)
        addPath(book.coverLocalPath, KIND_COVER, false)
        book.bodyPaths.forEach { addPath(it, KIND_CHAPTER_BODY, required, ::bodyEntry) }
        return seen.values.toList()
    }

    private data class ReaderAssetCandidate(
        val entryPath: String,
        val file: File,
    )

    // Only user-owned, flat asset folders. Ready-made preset pictures live in
    // filesDir/preset_backgrounds and are deliberately absent.
    private fun collectReaderAssets(): List<ReaderAssetCandidate> {
        val assets = READER_ASSET_DIRS.flatMap { (dirName, bucket) ->
            val dir = File(filesDir, dirName)
            if (!dir.isDirectory) {
                emptyList()
            } else {
                dir.listFiles().orEmpty()
                    .filter { it.isFile && !it.name.endsWith(".tmp") }
                    .map { file ->
                        ReaderAssetCandidate("$READER_ASSETS_ENTRY/$bucket/${file.name}", file)
                    }
            }
        }.distinctBy { it.entryPath }.sortedBy { it.entryPath }
        if (assets.size > MAX_READER_ASSETS) {
            error("Too many reader assets for backup: ${assets.size}")
        }
        val total = assets.sumOf { it.file.length() }
        if (total > MAX_READER_ASSETS_BYTES) {
            error("Reader assets are too large for backup")
        }
        return assets
    }

    private data class PluginCandidate(
        val entryPath: String,
        val file: File,
    )

    // Lua plugins live under filesDir/mirutime_plugins/{lua_plugins,lua_libs}.
    // Both trees are included so a plugin's shared modules restore with it.
    private fun collectPluginFiles(): List<PluginCandidate> {
        val root = File(filesDir, PLUGIN_ROOT)
        return PLUGIN_DIRS.flatMap { dirName ->
            val dir = File(root, dirName)
            if (!dir.isDirectory) {
                emptyList()
            } else {
                dir.walkTopDown()
                    .filter { it.isFile }
                    .map { file ->
                        val relative = file.relativeTo(root).invariantSeparatorsPath
                        PluginCandidate("$PLUGINS_ENTRY/$relative", file)
                    }
                    .toList()
            }
        }.distinctBy { it.entryPath }.sortedBy { it.entryPath }
    }

    private fun resolve(path: String): File {
        val direct = File(path)
        return if (direct.isAbsolute) direct else File(filesDir, path)
    }

    // Keep the app-relative layout under files/ so restore can put every file
    // back where the DB expects it. Paths outside filesDir use a book-scoped
    // fallback instead of leaking arbitrary absolute paths into the archive.
    private fun entryPath(file: File, bookId: Long, kind: String): String {
        val relative = file.relativeToOrNull(filesDir)
        val safe = relative?.takeIf { !it.path.startsWith("..") }?.invariantSeparatorsPath
        return if (safe != null) {
            "files/$safe"
        } else {
            "files/_external/$bookId/$kind/${file.name}"
        }
    }

    private suspend fun putFile(zip: ZipOutputStream, entryPath: String, file: File): Long {
        zip.putNextEntry(ZipEntry(entryPath))
        var written = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                zip.write(buffer, 0, read)
                written += read
            }
        }
        zip.closeEntry()
        return written
    }
}

/**
 * SAF tree URIs (auto-backup folder, export folder) are device-local: a tree
 * picked on another device does not resolve here. The archive never carries
 * them, and restore keeps the target device's own picks instead of blanking
 * them with the archive value.
 */
internal fun PersistedSettings.withDeviceLocalPathsFrom(
    current: PersistedSettings?,
): PersistedSettings = copy(
    backupDirectory = current?.backupDirectory.orEmpty(),
    exportDirectory = current?.exportDirectory.orEmpty(),
)

private suspend fun createSnapshot(
    databasePath: File,
    target: File,
    includeHistoryOnly: Boolean,
    filesRoot: File,
): DatabaseSnapshot =
    withContext(Dispatchers.IO) {
        createDatabaseSnapshot(
            databasePath = databasePath.absolutePath,
            targetPath = target.absolutePath,
        )
        if (!includeHistoryOnly) {
            pruneHistoryOnlyBooks(target.absolutePath)
        }
        // Manga page files are never packed, so their DB rows would restore a
        // book with no readable content.
        pruneLocalMangaBooks(target.absolutePath)
        // Plugin bodies are packed under id-free paths: restoring into a
        // library that already uses the source ids must not overwrite another
        // book's cache file. Rows follow the same paths the archive uses.
        rewriteCachedBodyPaths(
            snapshotPath = target.absolutePath,
            filesRoot = filesRoot.absolutePath,
            hash = { sha256Hex(it.encodeToByteArray()) },
        )
        val (totalBooks, libraryBooks) = snapshotBookCounts(target.absolutePath)
        DatabaseSnapshot(
            file = target,
            size = target.length(),
            sha256 = sha256File(target),
            totalBooks = totalBooks,
            libraryBooks = libraryBooks,
        )
    }

internal fun sha256File(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(CHUNK_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

internal fun ByteArray.toHex(): String {
    val chars = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        out.append(chars[value ushr 4]).append(chars[value and 0x0F])
    }
    return out.toString()
}

internal const val MANIFEST_ENTRY = "manifest.json"
internal const val SETTINGS_ENTRY = "settings.json"
internal const val PLUGIN_KV_ENTRY = "settings/plugin-kv.json"
internal const val LOCAL_SOURCES_ENTRY = "settings/local-sources.json"
internal const val DATABASE_ENTRY = "database/mirutime.db"
private const val KIND_LOCAL_COPY = "local-copy"
internal const val KIND_COVER = "cover"
internal const val KIND_CHAPTER_BODY = "chapter-body"
private const val KIND_CHAPTER_PAGE = "chapter-page"
private const val CHUNK_BYTES = 64 * 1024

// One pathological cover/page must not turn a backup into an OOM or a partial
// archive. Imported books themselves are capped by the importer.
private const val MAX_ENTRY_BYTES = 2L * 1024 * 1024 * 1024

internal const val PLUGIN_ROOT = "mirutime_plugins"
internal const val PLUGINS_ENTRY = "plugins"
internal const val BACKUP_TEMP_PREFIX = "mirutime-backup-"
internal const val DB_TEMP_PREFIX = "mirutime-db-"
internal const val StaleDbTempAgeMs = 6L * 60 * 60 * 1000
internal val LOCAL_INSTALL_IDS = setOf("local-books")
private val PLUGIN_DIRS = listOf("lua_plugins", "lua_libs")
private const val FREE_SPACE_HEADROOM = 16L * 1024 * 1024

/** Process-wide one-at-a-time gate shared by backup and restore. */
internal object BackupOperationGate {
    private val active = java.util.concurrent.atomic.AtomicBoolean(false)

    fun tryEnter(): Boolean = active.compareAndSet(false, true)

    fun exit() {
        active.set(false)
    }
}

internal data class ManifestPayload(
    val path: String,
    val size: Long,
    val sha256: String,
    val maxBytes: Long,
)

/** Every payload the manifest promises, in archive order. */
internal fun BackupManifest.payloadEntries(
    maxEntryBytes: Long,
    maxSettingsBytes: Long,
    maxReaderBytes: Long = MAX_ARCHIVE_READER_BYTES,
    maxReaderAssetBytes: Long = MAX_READER_ASSET_BYTES,
    maxPluginKvBytes: Long = MAX_ARCHIVE_PLUGIN_KV_BYTES,
    maxLocalSourcesBytes: Long = MAX_ARCHIVE_LOCAL_SOURCES_BYTES,
): List<ManifestPayload> = buildList {
    add(ManifestPayload(database.name, database.size, database.sha256, maxEntryBytes))
    settings?.let { add(ManifestPayload(it.name, it.size, it.sha256, maxSettingsBytes)) }
    reader?.let { add(ManifestPayload(it.name, it.size, it.sha256, maxReaderBytes)) }
    pluginKv?.let { add(ManifestPayload(it.name, it.size, it.sha256, maxPluginKvBytes)) }
    localSources?.let { add(ManifestPayload(it.name, it.size, it.sha256, maxLocalSourcesBytes)) }
    files.forEach { add(ManifestPayload(it.path, it.size, it.sha256, maxEntryBytes)) }
    plugins.forEach { add(ManifestPayload(it.path, it.size, it.sha256, maxEntryBytes)) }
    readerAssets.forEach { add(ManifestPayload(it.path, it.size, it.sha256, maxReaderAssetBytes)) }
}

internal fun requireFreeSpace(dir: File, required: Long, label: String) {
    if (required <= 0) return
    val usable = dir.usableSpace
    // usableSpace can report 0 when the OS withholds it; never block on that.
    if (usable > 0 && usable < required + FREE_SPACE_HEADROOM) {
        error("Not enough free space for $label")
    }
}

/**
 * Reopens a finished archive and proves it is complete: central directory
 * readable, manifest first, no missing/duplicate/extra entries, and every
 * declared size/hash matches the stored bytes.
 */
internal fun validateBackupArchive(file: File) {
    if (!file.isFile || file.length() == 0L) error("Backup archive is empty")
    try {
        java.util.zip.ZipFile(file).use { zip ->
            val names = zip.entries().toList().map { it.name }.filterNot { it.endsWith("/") }
            if (names.isEmpty() || names.first() != MANIFEST_ENTRY) {
                error("Backup archive has no leading manifest.json")
            }
            if (names.toSet().size != names.size) error("Backup archive has duplicate entries")
            val manifestEntry = zip.getEntry(MANIFEST_ENTRY)
                ?: error("Backup archive has no manifest.json")
            val manifestBytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
            if (manifestBytes.size > MAX_ARCHIVE_MANIFEST_BYTES) {
                error("manifest.json is too large")
            }
            val manifest = try {
                BackupJson.decodeFromString(
                    BackupManifest.serializer(),
                    manifestBytes.decodeToString(),
                )
            } catch (_: Exception) {
                error("manifest.json is not a valid MiruTime backup manifest")
            }
            if (manifest.formatVersion !in SupportedBackupFormats) {
                error("Unsupported backup format ${manifest.formatVersion}")
            }
            if (manifest.pluginKv != null &&
                !manifest.settingsIncluded && !manifest.pluginsIncluded
            ) {
                error("Backup plugin settings flag does not match its payload")
            }
            if (manifest.localSources != null && !manifest.settingsIncluded) {
                error("Backup local sources flag does not match its payload")
            }
            val expected = manifest.payloadEntries(
                maxEntryBytes = MAX_ENTRY_BYTES,
                maxSettingsBytes = MAX_ARCHIVE_SETTINGS_BYTES,
                maxReaderBytes = MAX_ARCHIVE_READER_BYTES,
                maxPluginKvBytes = MAX_ARCHIVE_PLUGIN_KV_BYTES,
                maxLocalSourcesBytes = MAX_ARCHIVE_LOCAL_SOURCES_BYTES,
            ).associateBy { it.path }
            val expectedNames = expected.keys + MANIFEST_ENTRY
            if (names.toSet() != expectedNames) {
                error("Backup archive entries do not match the manifest")
            }
            expected.values.forEach { entry ->
                if (entry.size < 0 || entry.size > entry.maxBytes) {
                    error("Backup entry is too large: ${entry.path}")
                }
                val zipEntry = zip.getEntry(entry.path)
                    ?: error("Backup archive is missing ${entry.path}")
                if (zipEntry.size != entry.size) {
                    error("Backup size does not match the manifest: ${entry.path}")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                zip.getInputStream(zipEntry).use { input ->
                    val buffer = ByteArray(CHUNK_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                if (digest.digest().toHex() != entry.sha256) {
                    error("Backup checksum does not match the manifest: ${entry.path}")
                }
            }
        }
    } catch (e: java.util.zip.ZipException) {
        error("Backup archive is truncated or corrupt: ${e.message ?: "bad ZIP"}")
    }
}

// A large novel library with covers can exceed the old 1 MB cap; the service
// rejected its own valid archive. Manga pages are not backed up, so this is
// headroom for many small novel/cover entries.
private const val MAX_ARCHIVE_MANIFEST_BYTES = 16 * 1024 * 1024
private const val MAX_ARCHIVE_SETTINGS_BYTES = 16L * 1024 * 1024
internal const val MAX_ARCHIVE_READER_BYTES = 4L * 1024 * 1024
internal const val MAX_ARCHIVE_PLUGIN_KV_BYTES = 4L * 1024 * 1024
internal const val MAX_ARCHIVE_LOCAL_SOURCES_BYTES = 8L * 1024 * 1024
internal const val MAX_READER_ASSET_BYTES = 64L * 1024 * 1024
private const val MAX_READER_ASSETS = 500
private const val MAX_READER_ASSETS_BYTES = 512L * 1024 * 1024
