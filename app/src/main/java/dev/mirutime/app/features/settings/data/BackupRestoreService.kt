package dev.mirutime.app.features.settings.data

import android.content.Context
import dev.mirutime.core.log.MiruLog
import dev.mirutime.data.library.LibraryDao
import dev.mirutime.data.library.restoreBookIdMap as computeRestoreBookIdMap
import dev.mirutime.data.library.LibraryDatabase
import dev.mirutime.data.library.LibraryImportReport
import dev.mirutime.data.library.LibrarySchemaVersion
import dev.mirutime.data.library.MinimumRestorableBackupSchemaVersion
import dev.mirutime.data.library.RestoreJournalStatus
import dev.mirutime.data.library.importLibraryBackup
import dev.mirutime.data.library.isPdfPageReference
import dev.mirutime.data.library.inspectBackupDatabase
import dev.mirutime.data.library.libraryDatabaseAt
import dev.mirutime.data.library.migrateBackupDatabase
import dev.mirutime.data.library.verifyDatabaseSnapshot
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Restores a backup archive into the live app without a restart.
 *
 * The archive is streamed into [STAGING_DIR] and fully validated (manifest,
 * hashes, schema/user_version, integrity check, settings). A v6 archive is
 * migrated to the current schema in the staging database before it is
 * verified and imported. The validated backup database is then imported into
 * the live Room database in one write transaction with id remapping, while the
 * staged local files are copied under [ROLLBACK_DIR] protection. Settings are
 * applied in memory as the last step, so every screen sees the restored state
 * immediately.
 *
 * If the process dies mid-restore, [recoverInterruptedRestore] runs on the next
 * start: it restores files/settings when the DB transaction did not commit, or
 * only finishes applying settings when it did. No manual restart is required in
 * the normal path.
 */
// Manga pages and local copies are never packed. After restore, drop any body
// row whose file is absent: a restored row must not claim a download it cannot
// serve. This covers plugin books whose bodies older archives did not carry.
// Rows whose file exists (same-device cache) survive.
internal suspend fun LibraryDao.pruneMissingRestoredContent(
    exists: (String) -> Boolean,
) {
    restorePageRows().forEach { row ->
        val paths = pagePaths(row.pagePathsJson)
        // A PDF page row is a reference (`pdfpage://`) resolved through the
        // book's own copy, not a file: testing it as a path would drop it.
        if (paths.isEmpty() || paths.any { !isPdfPageReference(it) && !exists(it) }) {
            deleteChapterPages(listOf(row.chapterId))
        }
    }
    restoreBodyRows().forEach { row ->
        if (!exists(row.path)) deleteChapterBody(row.chapterId)
    }
    localMangaFileRows().forEach { row ->
        if (row.copyPath.isBlank() || !exists(row.copyPath)) {
            deleteLocalBookFile(row.bookId)
        }
    }
    // A backup may legitimately contain only the DB (files=0). The restored
    // book_covers rows still point at paths that do not exist on this device;
    // clear them so the library uses remoteUrl and the cover backfill can
    // download the files again.
    var clearedCovers = 0
    bookCovers().forEach { row ->
        val path = row.localPath
        if (!path.isNullOrBlank() && !exists(path)) {
            upsertBookCover(
                row.copy(
                    localPath = null,
                    sha256 = null,
                    bytes = 0,
                    fetchedAt = null,
                    checkedAt = null,
                    etag = null,
                    lastModified = null,
                    lastError = null,
                    retryAt = null,
                ),
            )
            clearedCovers++
        }
    }
    if (clearedCovers > 0) {
        MiruLog.event("backup", "covers-cleared", "count" to clearedCovers)
    }
    // Deleting the pages/bodies above may leave a chapter that still claims to
    // be offline. The denormalized flag is only valid while a body/page row
    // exists, so drop the ones this restore just orphaned.
    resetOrphanedDownloadedFlags()
}

class BackupRestoreService(
    private val filesDir: File,
    private val databasePath: File,
    private val settingsStore: SettingsStore,
    private val readerStore: ReaderBackupStore = NoopReaderBackupStore,
    private val pluginKvStore: PluginKvBackupStore,
    private val localSourcesStore: LocalSourcesBackupStore,
    private val appDataDir: File = filesDir.parentFile ?: filesDir,
    private val verifyDatabase: (File) -> Unit = { verifyDatabaseSnapshot(it.absolutePath) },
    private val inspectDatabase: (File) -> Int = { inspectBackupDatabase(it.absolutePath) },
    private val migrateDatabase: (File, Int) -> Unit = { file, fromVersion ->
        migrateBackupDatabase(file.absolutePath, fromVersion, LibrarySchemaVersion)
    },
    private val importDatabase: ImportDatabase = { _, _, _, _, _ ->
        error("Live database import is not configured")
    },
    private val stageNovelaBackup: StageNovelaBackup? = null,
    private val journalExists: suspend (Long) -> Boolean = { false },
    private val deleteJournal: suspend (Long) -> Unit = {},
    private val deleteAllJournals: suspend () -> Unit = {},
    private val reloadPlugins: suspend () -> Unit = {},
    private val clearImageCaches: () -> Unit = {},
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val newOperationId: () -> Long = {
        (UUID.randomUUID().leastSignificantBits and Long.MAX_VALUE).takeIf { it != 0L } ?: 1L
    },
    private val deletePath: (File) -> Boolean = { it.delete() },
    private val movePath: ((File, File) -> Unit)? = null,
    private val writeAtomicFile: (File, String) -> Unit = ::writeTextAtomic,
    // Old->new book ids recovered by identity; only the journalless recovery
    // needs it, but the app passes the real one so covers can be placed.
    private val restoreBookIdMap: (staged: File, live: File) -> Map<Long, Long> = { _, _ -> emptyMap() },
    private val maxEntryBytes: Long = MAX_RESTORE_ENTRY_BYTES,
    private val maxTotalBytes: Long = MAX_RESTORE_TOTAL_BYTES,
    private val maxEntries: Int = MAX_RESTORE_ENTRIES,
    private val pruneMissingRestoredContent: suspend () -> Unit = {},
) {
    constructor(
        context: Context,
        databasePath: File,
        settingsStore: SettingsStore,
        database: LibraryDatabase,
        reloadPlugins: suspend () -> Unit = {},
        clearImageCaches: () -> Unit = {},
        readerStore: ReaderBackupStore = NoopReaderBackupStore,
        pluginKvStore: PluginKvBackupStore,
        localSourcesStore: LocalSourcesBackupStore,
        repair: suspend () -> Unit = {},
        onBookKeyRewritten: suspend (oldUrl: String, newUrl: String) -> Unit = { _, _ -> },
        novelaStager: NovelaBackupStager? = null,
    ) : this(
        filesDir = context.applicationContext.filesDir,
        databasePath = databasePath,
        settingsStore = settingsStore,
        readerStore = readerStore,
        pluginKvStore = pluginKvStore,
        localSourcesStore = localSourcesStore,
        appDataDir = context.applicationContext.filesDir.parentFile
            ?: context.applicationContext.filesDir,
        importDatabase = { sourceFile, coverPathFor, journalId, journalNow, onCommitted ->
            val source = libraryDatabaseAt(context, sourceFile)
            try {
                database.importLibraryBackup(
                    source = source.libraryDao(),
                    coverPathFor = coverPathFor,
                    journalId = journalId,
                    journalNow = journalNow,
                    onBookKeyRewritten = onBookKeyRewritten,
                    repair = repair,
                    onCommitted = onCommitted,
                    // Cached bodies are importable user data (plugin books keep
                    // their downloaded chapters offline), so both MiruTime
                    // archives and converted NoveLA archives carry them.
                    importCachedBodies = true,
                )
            } finally {
                source.close()
            }
        },
        stageNovelaBackup = novelaStager?.let { stager ->
            { input, staging, onProgress, onStage ->
                stager.stage(input, staging, onProgress, onStage)
            }
        },
        restoreBookIdMap = { staged, live -> computeRestoreBookIdMap(staged.absolutePath, live.absolutePath) },
        journalExists = { id ->
            database.libraryDao().restoreJournalCount(
                id = id,
                batchId = id.toString(),
                status = RestoreJournalStatus,
            ) > 0
        },
        deleteJournal = { id ->
            database.libraryDao().deleteRestoreJournal(id, RestoreJournalStatus)
        },
        deleteAllJournals = {
            database.libraryDao().deleteRestoreJournals(RestoreJournalStatus)
        },
        reloadPlugins = reloadPlugins,
        clearImageCaches = clearImageCaches,
        pruneMissingRestoredContent = {
            val root = context.applicationContext.filesDir.normalize()
            database.libraryDao().pruneMissingRestoredContent { path ->
                if (path.isBlank()) {
                    false
                } else {
                    val file = (if (File(path).isAbsolute) File(path) else File(root, path)).normalize()
                    file.startsWith(root) && file.exists()
                }
            }
        },
    )

    private val stagingRoot = File(filesDir, STAGING_DIR)
    private val rollbackRoot = File(appDataDir, ROLLBACK_DIR)

    /**
     * Validates [input] (never closing it; the caller owns the SAF stream) and
     * restores it live. Returns as soon as the data is visible to the app.
     */
    suspend fun restore(
        input: InputStream,
        onProgress: (Float) -> Unit = {},
    ): RestoreApplyResult = restoreInternal(input, onProgress, null)

    /** Same restore with per-stage counters for a future progress UI. */
    suspend fun restoreWithStages(
        input: InputStream,
        onProgress: (Float) -> Unit = {},
        onStage: (RestoreProgress) -> Unit,
    ): RestoreApplyResult = restoreInternal(input, onProgress, onStage)

    private suspend fun restoreInternal(
        input: InputStream,
        onProgress: (Float) -> Unit,
        onStage: ((RestoreProgress) -> Unit)?,
    ): RestoreApplyResult = withContext(Dispatchers.IO) {
        if (!BackupOperationGate.tryEnter()) {
            return@withContext RestoreApplyResult(
                applied = false,
                error = "Another backup or restore is already running",
            )
        }
        try {
            onStage?.invoke(RestoreProgress(RestoreStage.VALIDATING, 0, 1))
            val staged = stageInternal(
                input = input,
                onProgress = { value -> onProgress(value * STAGING_PROGRESS) },
                onStage = onStage,
            )
            if (!staged.success) {
                return@withContext RestoreApplyResult(
                    applied = false,
                    error = staged.error,
                )
            }
            onStage?.invoke(RestoreProgress(RestoreStage.VALIDATING, 1, 1))
            applyStagedLive(onProgress, onStage)
        } finally {
            BackupOperationGate.exit()
        }
    }

    /**
     * Streams [input] into a temporary staging directory. Nothing is published
     * and no current data is touched unless the whole archive validates.
     */
    suspend fun stage(
        input: InputStream,
        onProgress: (Float) -> Unit = {},
        onStage: ((RestoreProgress) -> Unit)? = null,
    ): RestoreStageResult {
        if (!BackupOperationGate.tryEnter()) {
            return RestoreStageResult(
                success = false,
                error = "Another backup or restore is already running",
            )
        }
        return try {
            stageInternal(input, onProgress, onStage)
        } finally {
            BackupOperationGate.exit()
        }
    }

    private suspend fun stageInternal(
        input: InputStream,
        onProgress: (Float) -> Unit,
        onStage: ((RestoreProgress) -> Unit)?,
    ): RestoreStageResult = withContext(Dispatchers.IO) {
        if (stagingRoot.exists()) {
            // A staging directory on disk is not an active operation: the
            // process-wide gate already serializes backups/restores. Finish an
            // interrupted restore if possible, otherwise drop the unusable
            // leftover; otherwise it would block every later restore.
            try {
                recoverInterruptedRestore()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MiruLog.event(
                    "backup",
                    "stale-stage-recovery-failed",
                    "error" to e::class.simpleName,
                )
            }
            if (stagingRoot.exists()) {
                stagingRoot.deleteRecursively()
                MiruLog.event("backup", "stale-stage-cleared")
            }
            if (stagingRoot.exists()) {
                return@withContext RestoreStageResult(
                    success = false,
                    error = "Cannot clear a stale restore directory.",
                )
            }
        }
        val temp = File(filesDir, "$STAGING_DIR.tmp-${newId()}")
        try {
            cleanupStaleStages()
            temp.mkdirs()
            onProgress(0.01f)
            val (firstEntry, stream) = peekZipFirstEntry(input)
            if (firstEntry == NOVELA_DB_ENTRY) {
                val stage = stageNovelaBackup
                    ?: error("NoveLA backup restore is not configured")
                stage(
                    stream,
                    temp,
                    { value -> onProgress(NOVELA_PROGRESS_SHARE * value) },
                    onStage,
                )
            } else {
                extract(stream, temp, onProgress, onStage)
            }
            if (!temp.renameTo(stagingRoot)) {
                error("Cannot publish the staged restore")
            }
            onProgress(1f)
            val stagedBooks = stagingBookCount(stagingRoot)
            MiruLog.event("backup", "stage", "ok" to true, "books" to stagedBooks)
            RestoreStageResult(
                success = true,
                localBookCount = stagedBooks,
            )
        } catch (e: CancellationException) {
            temp.deleteRecursively()
            throw e
        } catch (e: Exception) {
            temp.deleteRecursively()
            RestoreStageResult(
                success = false,
                error = e.message ?: "Restore validation failed",
            )
        }
    }

    /**
     * Crash-recovery fallback. Runs on the next start before providers open and
     * only finishes an interrupted restore; a normal restore never leaves work
     * for the next launch. Returns settings that should be applied in memory.
     */
    suspend fun recoverInterruptedRestore(): RestoreApplyResult {
        cleanupStaleStages()
        val markerFile = File(rollbackRoot, PROGRESS_MARKER)
        if (!markerFile.isFile) {
            // A completed restore may have left a journal row if its cleanup
            // died after the marker was removed. There is no marker to protect,
            // so these rows are safe to prune.
            runCatching { deleteAllJournals() }
            rollbackRoot.deleteRecursively()
            stagingRoot.deleteRecursively()
            return RestoreApplyResult(applied = false)
        }
        return try {
            val marker = readMarker(markerFile)
            // A marker written just before the transaction can be missing its
            // commit line; the journal row is the authoritative commit proof.
            val fileJournal = File(rollbackRoot, FILE_JOURNAL).isFile
            val needsFileResume = marker.phase == PHASE_DB_FILES || fileJournal
            val dbCommitted = marker.phase == PHASE_DB_FILES ||
                marker.phase == PHASE_DB_COMMITTED ||
                (marker.operationId?.let { journalExists(it) } ?: false)
            val result = if (dbCommitted) {
                if (!needsFileResume && stagingRoot.isDirectory) {
                    // DB committed but no file mapping was ever journaled (crash
                    // or a full disk between the commit and the journal write).
                    val staged = try {
                        readStagedManifest()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // A finished restore whose staging survived: nothing to
                        // materialize, and the plugin reload stays skipped.
                        val replay = replayRestoredSnapshots(reload = false)
                        if (replay.ok && marker.phase == PHASE_DB_COMMITTED) {
                            stagingRoot.deleteRecursively()
                            rollbackRoot.deleteRecursively()
                        }
                        return RestoreApplyResult(
                            applied = true,
                            settings = replay.settings,
                            error = replay.error,
                        )
                    }
                    // The file mapping was never journaled, but books can be
                    // matched to the live rows by identity, so covers land on
                    // their new ids too. Without this the library would list
                    // books whose files never left the staging tree.
                    val materializeError = try {
                        val ids = runCatching {
                            restoreBookIdMap(
                                resolveInside(stagingRoot, staged.database.name),
                                databasePath,
                            )
                        }.getOrDefault(emptyMap())
                        copyStagedFiles(
                            manifest = staged.copy(
                                // A cover already on disk is a live user
                                // override or a finished copy: never overwrite it.
                                files = staged.files.filter { entry ->
                                    if (entry.kind != KIND_COVER) return@filter true
                                    val newId = ids[entry.bookId] ?: return@filter false
                                    !File(
                                        File(filesDir, "covers/$newId"),
                                        entry.path.substringAfterLast('/'),
                                    ).exists()
                                },
                            ),
                            bookIds = ids,
                            preservedCovers = emptySet(),
                            rollbacks = mutableListOf(),
                            created = mutableListOf(),
                            onStage = null,
                            resume = true,
                        )
                        pruneMissingRestoredContent()
                        writeMarker(PHASE_DB_COMMITTED, marker.operationId ?: 0L)
                        marker.operationId?.let { deleteJournal(it) }
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        MiruLog.event(
                            "backup",
                            "journalless-file-recovery-failed",
                            "error" to e::class.simpleName,
                        )
                        e.message ?: "Restored files could not be copied"
                    }
                    val replay = replayRestoredSnapshots(reload = true)
                    if (materializeError == null && replay.ok) {
                        stagingRoot.deleteRecursively()
                        rollbackRoot.deleteRecursively()
                    }
                    return RestoreApplyResult(
                        applied = true,
                        settings = replay.settings,
                        error = replay.error ?: materializeError,
                    )
                }
                if (needsFileResume) {
                    val resumed = runCatching { resumeFilePhase() }.getOrDefault(false)
                    if (resumed) runCatching { pruneMissingRestoredContent() }
                    if (!resumed) {
                        // DB is committed; keep staging + mapping for another
                        // attempt instead of dropping the only local files.
                        val replay = replayRestoredSnapshots(reload = false)
                        return RestoreApplyResult(
                            applied = true,
                            settings = replay.settings,
                            error = replay.error,
                        )
                    }
                    writeMarker(PHASE_DB_COMMITTED, marker.operationId ?: 0L)
                    File(rollbackRoot, FILE_JOURNAL).delete()
                }
                val replay = replayRestoredSnapshots(reload = true)
                val journalOk = runCatching {
                    marker.operationId?.let { deleteJournal(it) }
                }.isSuccess
                if (!replay.ok || !journalOk) {
                    // The DB is committed; keep the marker/rollback for retry
                    // but never report a rollback.
                    return RestoreApplyResult(
                        applied = true,
                        settings = replay.settings,
                        error = replay.error ?: if (!journalOk) {
                            "Restore journal could not be cleared"
                        } else {
                            null
                        },
                    )
                }
                RestoreApplyResult(applied = true, settings = replay.settings)
            } else {
                val filesRestored = rollbackStagedFiles()
                val previous = File(rollbackRoot, PREVIOUS_SETTINGS_ENTRY)
                val settings = if (previous.isFile) parseSettings(previous) else null
                val previousReader = File(rollbackRoot, PREVIOUS_READER_ENTRY)
                val reader = if (previousReader.isFile) parseReaderPrefs(previousReader) else null
                val settingsRestored = runCatching {
                    settings?.let {
                        if (!settingsStore.save(it)) error("settings were not persisted")
                    }
                    reader?.let { readerStore.restore(it) }
                }.isSuccess
                if (!filesRestored || !settingsRestored) {
                    // Keep the marker, staging and rollback for the next start.
                    return RestoreApplyResult(applied = false)
                }
                marker.operationId?.let { deleteJournal(it) }
                RestoreApplyResult(applied = false, settings = settings)
            }
            // Only drop the rollback once recovery actually completed; a failed
            // attempt keeps it for the next start instead of losing data.
            stagingRoot.deleteRecursively()
            rollbackRoot.deleteRecursively()
            result
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            RestoreApplyResult(applied = false)
        }
    }

    private data class ReplayResult(
        val settings: PersistedSettings?,
        val ok: Boolean,
        val error: String?,
    )

    /**
     * Applies every durable RESTORED_* snapshot. Idempotent: KV/local sources
     * merge, settings/reader overwrite. One failed step does not skip the rest.
     */
    private suspend fun replayRestoredSnapshots(
        reload: Boolean,
    ): ReplayResult {
        val settingsFile = File(rollbackRoot, RESTORED_SETTINGS_ENTRY)
        val readerFile = File(rollbackRoot, RESTORED_READER_ENTRY)
        val pluginKvFile = File(rollbackRoot, RESTORED_PLUGIN_KV_ENTRY)
        val localSourcesFile = File(rollbackRoot, RESTORED_LOCAL_SOURCES_ENTRY)
        var ok = true
        var firstError: String? = null

        fun rememberFailure(label: String, e: Throwable) {
            ok = false
            if (firstError == null) firstError = "$label: ${e.message ?: "failed"}"
        }

        val settings = runCatching {
            if (settingsFile.isFile) parseSettings(settingsFile) else null
        }.getOrElse {
            rememberFailure("settings", it)
            null
        }
        val reader = runCatching {
            if (readerFile.isFile) parseReaderPrefs(readerFile) else null
        }.getOrElse {
            rememberFailure("reader", it)
            null
        }
        val pluginKv = runCatching {
            if (pluginKvFile.isFile) parsePluginKv(pluginKvFile) else null
        }.getOrElse {
            rememberFailure("plugin-kv", it)
            null
        }
        val localSources = runCatching {
            if (localSourcesFile.isFile) parseLocalSources(localSourcesFile) else null
        }.getOrElse {
            rememberFailure("local-sources", it)
            null
        }

        suspend fun runStep(label: String, block: suspend () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                rememberFailure(label, e)
            }
        }

        runStep("settings") {
            settings?.let { if (!settingsStore.save(it)) error("settings were not persisted") }
        }
        runStep("reader") { reader?.let { readerStore.restore(it) } }
        runStep("plugin-kv") { pluginKv?.let { pluginKvStore.restore(it) } }
        runStep("local-sources") { localSources?.let { localSourcesStore.restore(it) } }
        if (reload && (pluginKv != null || localSources != null)) {
            runStep("plugins") { reloadPlugins() }
        }
        return ReplayResult(settings, ok, firstError)
    }

    private fun writeMarker(phase: String, operationId: Long) {
        writeAtomicFile(File(rollbackRoot, PROGRESS_MARKER), "$phase\n$operationId")
    }

    private fun writeFileJournal(bookIds: Map<Long, Long>, preservedCovers: Set<Long>) {
        val lines = ArrayList<String>(bookIds.size + preservedCovers.size + 1)
        lines += FILE_JOURNAL_VERSION
        bookIds.forEach { (oldId, newId) -> lines += "b $oldId $newId" }
        preservedCovers.forEach { lines += "p $it" }
        writeAtomicFile(File(rollbackRoot, FILE_JOURNAL), lines.joinToString("\n"))
    }

    private fun readFileJournal(): Pair<Map<Long, Long>, Set<Long>>? {
        val file = File(rollbackRoot, FILE_JOURNAL)
        if (!file.isFile) return null
        val lines = file.readLines()
        if (lines.firstOrNull() != FILE_JOURNAL_VERSION) return null
        val ids = LinkedHashMap<Long, Long>()
        val preserved = LinkedHashSet<Long>()
        for (line in lines.drop(1)) {
            val parts = line.split(' ')
            when {
                parts.size == 3 && parts[0] == "b" -> {
                    val oldId = parts[1].toLongOrNull() ?: return null
                    val newId = parts[2].toLongOrNull() ?: return null
                    ids[oldId] = newId
                }
                parts.size == 2 && parts[0] == "p" -> {
                    preserved += parts[1].toLongOrNull() ?: return null
                }
                line.isBlank() -> Unit
                else -> return null
            }
        }
        return ids to preserved
    }

    private fun resumeFilePhase(): Boolean {
        if (!stagingRoot.isDirectory) return false
        val manifest = readStagedManifest()
        val (ids, preserved) = readFileJournal() ?: return false
        copyStagedFiles(
            manifest = manifest,
            bookIds = ids,
            preservedCovers = preserved,
            rollbacks = mutableListOf(),
            created = mutableListOf(),
            onStage = null,
            resume = true,
        )
        return true
    }

    private data class RestoreMarker(
        val phase: String,
        val operationId: Long?,
    )

    private fun readMarker(file: File): RestoreMarker {
        val lines = file.readText().lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        return RestoreMarker(
            phase = lines.firstOrNull().orEmpty(),
            operationId = lines.getOrNull(1)?.toLongOrNull(),
        )
    }

    private suspend fun applyStagedLive(
        onProgress: (Float) -> Unit,
        onStage: ((RestoreProgress) -> Unit)?,
    ): RestoreApplyResult {
        if (!stagingRoot.isDirectory) {
            return RestoreApplyResult(applied = false, error = "No staged restore to apply")
        }
        var phase = PHASE_FILES_COPIED
        var journalWarning: String? = null
        var previousSettings: PersistedSettings? = null
        var previousReader: ReaderBackupPrefs? = null
        val operationId = newOperationId()
        val rollbacks = mutableListOf<FileRollback>()
        val created = mutableListOf<File>()
        try {
            val manifest = readStagedManifest()
            validateManifest(manifest)
            val stagedDb = resolveInside(stagingRoot, manifest.database.name)
            requireEntryFile(stagedDb, manifest.database, "database")
            if (sha256File(stagedDb) != manifest.database.sha256) {
                error("Database checksum does not match the manifest")
            }
            val stagedSchema = inspectDatabase(stagedDb)
            if (stagedSchema < LibrarySchemaVersion) {
                migrateDatabase(stagedDb, stagedSchema)
            }
            verifyDatabase(stagedDb)

            val archivedSettings = manifest.settings?.let { entry ->
                val stagedSettingsFile = resolveInside(stagingRoot, entry.name)
                requireEntryFile(stagedSettingsFile, entry, "settings")
                if (sha256File(stagedSettingsFile) != entry.sha256) {
                    error("Settings checksum does not match the manifest")
                }
                parseSettings(stagedSettingsFile)
            }
            val reader = manifest.reader?.let { entry ->
                val stagedReaderFile = resolveInside(stagingRoot, entry.name)
                requireEntryFile(stagedReaderFile, entry, "reader")
                if (sha256File(stagedReaderFile) != entry.sha256) {
                    error("Reader checksum does not match the manifest")
                }
                parseReaderPrefs(stagedReaderFile)
            }
            val pluginKv = manifest.pluginKv?.let { entry ->
                val stagedKvFile = resolveInside(stagingRoot, entry.name)
                requireEntryFile(stagedKvFile, entry, "plugin settings")
                if (sha256File(stagedKvFile) != entry.sha256) {
                    error("Plugin settings checksum does not match the manifest")
                }
                parsePluginKv(stagedKvFile)
            }
            val localSources = manifest.localSources?.let { entry ->
                val stagedSourcesFile = resolveInside(stagingRoot, entry.name)
                requireEntryFile(stagedSourcesFile, entry, "local sources")
                if (sha256File(stagedSourcesFile) != entry.sha256) {
                    error("Local sources checksum does not match the manifest")
                }
                parseLocalSources(stagedSourcesFile)
            }

            previousSettings = runCatching { settingsStore.load() }.getOrNull()
            previousReader = runCatching { readerStore.snapshot() }.getOrNull()
            // SAF folder picks belong to this device, not to the archive.
            val settings = archivedSettings?.withDeviceLocalPathsFrom(previousSettings)
            prepareRollback(
                previousSettings,
                settings,
                previousReader,
                reader,
                pluginKv,
                localSources,
                operationId,
            )
            onProgress(STAGING_PROGRESS * 0.99f)

            onStage?.invoke(RestoreProgress(RestoreStage.DATABASE, 0, 1))
            // DB phase: rows + the reserved journal commit here. File copying
            // must never run inside this transaction (long WAL, no resume).
            val report = importDatabase(
                stagedDb,
                { oldBookId, newBookId, oldPath ->
                    coverPathFor(filesDir, oldBookId, newBookId, oldPath)
                },
                operationId,
                System.currentTimeMillis(),
            ) { committed ->
                // The DB transaction is durable: journal the file mapping and
                // mark the phase before repair/FTS can be cancelled. A journal
                // failure must not turn a committed restore into a failure.
                phase = PHASE_DB_FILES
                try {
                    writeFileJournal(committed.bookIdMap, committed.preservedCoverBookIds)
                    writeMarker(PHASE_DB_FILES, operationId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    journalWarning = e.message ?: "Restore journal could not be written"
                    MiruLog.event(
                        "backup",
                        "journal-write-failed",
                        "error" to e::class.simpleName,
                    )
                }
            }
            onStage?.invoke(RestoreProgress(RestoreStage.DATABASE, 1, 1))
            phase = PHASE_DB_FILES
            // From here the DB is durable. Cancellation must never roll it
            // back; keep the marker + mapping so the file phase can resume.
            return withContext(NonCancellable) {
                try {
                    // Journal first: a kill before the marker must still be
                    // recoverable via the journal row + files.journal. A full
                    // disk here must not skip the file copy itself: the copy
                    // works from the in-memory mapping.
                    try {
                        writeFileJournal(report.bookIdMap, report.preservedCoverBookIds)
                        writeMarker(PHASE_DB_FILES, operationId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        journalWarning = journalWarning
                            ?: (e.message ?: "Restore journal could not be written")
                        MiruLog.event(
                            "backup",
                            "journal-write-failed",
                            "error" to e::class.simpleName,
                        )
                    }
                    copyStagedFiles(
                        manifest = manifest,
                        bookIds = report.bookIdMap,
                        preservedCovers = report.preservedCoverBookIds,
                        rollbacks = mutableListOf(),
                        created = mutableListOf(),
                        onStage = onStage,
                    )
                    pruneMissingRestoredContent()
                    phase = PHASE_DB_COMMITTED
                    writeMarker(PHASE_DB_COMMITTED, operationId)
                    File(rollbackRoot, FILE_JOURNAL).delete()
                    onProgress(0.99f)

                    if (settings != null) {
                        onStage?.invoke(RestoreProgress(RestoreStage.SETTINGS, 0, 1))
                        if (!settingsStore.save(settings)) {
                            error("Restored settings could not be saved")
                        }
                        AppSettings.applyPersisted(settings)
                        onStage?.invoke(RestoreProgress(RestoreStage.SETTINGS, 1, 1))
                    }
                    if (reader != null) {
                        readerStore.restore(reader)
                    }
                    pluginKv?.let { pluginKvStore.restore(it) }
                    localSources?.let { localSourcesStore.restore(it) }

                    // Derived caches/plugins are rebuilt only after the import commits.
                    onStage?.invoke(RestoreProgress(RestoreStage.CACHE, 0, 1))
                    runCatching { reloadPlugins() }
                    runCatching { clearImageCaches() }
                    onStage?.invoke(RestoreProgress(RestoreStage.CACHE, 1, 1))
                    onProgress(1f)
                    // Marker is already durable; deleting the journal before
                    // the rollback directory is what makes the restore final.
                    deleteJournal(operationId)
                    stagingRoot.deleteRecursively()
                    rollbackRoot.deleteRecursively()
                    RestoreApplyResult(
                        applied = true,
                        settings = settings,
                        pluginsRestored = manifest.plugins.size,
                        error = journalWarning,
                    )
                } catch (e: Exception) {
                    // DB and files are committed; settings may be partial.
                    // Staging is no longer needed and must not block a later
                    // restore; keep only the rollback snapshots for recovery.
                    if (phase == PHASE_DB_COMMITTED) stagingRoot.deleteRecursively()
                    RestoreApplyResult(
                        applied = true,
                        settings = settings,
                        pluginsRestored = manifest.plugins.size,
                        error = e.message ?: journalWarning,
                    )
                }
            }
        } catch (e: CancellationException) {
            rollbackAfterFailure(
                phase,
                rollbacks,
                created,
                previousSettings,
                previousReader,
                operationId,
            )
            throw e
        } catch (e: Exception) {
            rollbackAfterFailure(
                phase,
                rollbacks,
                created,
                previousSettings,
                previousReader,
                operationId,
            )
            return RestoreApplyResult(
                applied = phase == PHASE_DB_COMMITTED,
                error = e.message ?: "Restore could not be applied",
            )
        }
    }

    private suspend fun rollbackAfterFailure(
        phase: String,
        rollbacks: List<FileRollback>,
        created: List<File>,
        previousSettings: PersistedSettings?,
        previousReader: ReaderBackupPrefs?,
        operationId: Long,
    ) {
        if (phase == PHASE_DB_FILES) {
            // DB committed but files are not materialized yet. Keep the marker,
            // staging and mapping for the next-start file-phase retry.
            return
        }
        if (phase == PHASE_DB_COMMITTED) {
            // The database transaction is already durable; replay the durable
            // RESTORED_* snapshots. Keep the marker when anything fails.
            val replay = replayRestoredSnapshots(reload = true)
            replay.settings?.let { AppSettings.applyPersisted(it) }
            if (replay.ok && runCatching { deleteJournal(operationId) }.isSuccess) {
                rollbackRoot.deleteRecursively()
            }
            stagingRoot.deleteRecursively()
        } else {
            val filesRolledBack = rollbackFiles(rollbacks, created)
            val settingsRolledBack = previousSettings?.let {
                runCatching {
                    if (!settingsStore.save(it)) error("settings were not persisted")
                    AppSettings.applyPersisted(it)
                }.isSuccess
            } ?: true
            val readerRolledBack = previousReader?.let {
                runCatching { readerStore.restore(it) }.isSuccess
            } ?: true
            // Keep both the staging archive and the rollback directory when
            // any file/settings rollback failed, so the next start can retry.
            if (filesRolledBack && settingsRolledBack && readerRolledBack) {
                stagingRoot.deleteRecursively()
                rollbackRoot.deleteRecursively()
            }
        }
    }

    private fun prepareRollback(
        previous: PersistedSettings?,
        restored: PersistedSettings?,
        previousReader: ReaderBackupPrefs?,
        restoredReader: ReaderBackupPrefs?,
        restoredPluginKv: PluginKvBackup?,
        restoredLocalSources: LocalSourcesBackup?,
        operationId: Long,
    ) {
        rollbackRoot.deleteRecursively()
        rollbackRoot.mkdirs()
        previous?.let {
            File(rollbackRoot, PREVIOUS_SETTINGS_ENTRY).writeText(
                BackupJson.encodeToString(PersistedSettings.serializer(), it),
            )
        }
        restored?.let {
            File(rollbackRoot, RESTORED_SETTINGS_ENTRY).writeText(
                BackupJson.encodeToString(PersistedSettings.serializer(), it),
            )
        }
        previousReader?.let {
            File(rollbackRoot, PREVIOUS_READER_ENTRY).writeText(
                BackupJson.encodeToString(ReaderBackupPrefs.serializer(), it),
            )
        }
        restoredReader?.let {
            File(rollbackRoot, RESTORED_READER_ENTRY).writeText(
                BackupJson.encodeToString(ReaderBackupPrefs.serializer(), it),
            )
        }
        restoredPluginKv?.let {
            File(rollbackRoot, RESTORED_PLUGIN_KV_ENTRY).writeText(
                BackupJson.encodeToString(PluginKvBackup.serializer(), it),
            )
        }
        restoredLocalSources?.let {
            File(rollbackRoot, RESTORED_LOCAL_SOURCES_ENTRY).writeText(
                BackupJson.encodeToString(LocalSourcesBackup.serializer(), it),
            )
        }
        File(rollbackRoot, PROGRESS_MARKER).writeText("$PHASE_FILES_COPIED\n$operationId")
    }

    private fun copyStagedFiles(
        manifest: BackupManifest,
        bookIds: Map<Long, Long>,
        preservedCovers: Set<Long>,
        rollbacks: MutableList<FileRollback>,
        created: MutableList<File>,
        onStage: ((RestoreProgress) -> Unit)?,
        resume: Boolean = false,
    ) {
        val total = manifest.files.size + manifest.plugins.size + manifest.readerAssets.size
        var done = 0
        manifest.files.forEach { entry ->
            val source = resolveInside(stagingRoot, entry.path)
            val target = remapFileTarget(filesDir, entry, bookIds, preservedCovers)
            if (resume && !source.isFile && target?.isFile == true) {
                // A previous file-phase attempt already moved the source;
                // target presence is the resume token.
                done++
                onStage?.invoke(RestoreProgress(RestoreStage.FILES, done, total))
                return@forEach
            }
            requireEntryFile(source, entry, entry.path)
            if (target != null) {
                copyFileWithRollback(source, target, entry.path, rollbacks, created)
            }
            done++
            onStage?.invoke(RestoreProgress(RestoreStage.FILES, done, total))
        }
        val pluginRoot = File(filesDir, PLUGIN_ROOT)
        manifest.plugins.forEach { entry ->
            val source = resolveInside(stagingRoot, entry.path)
            val relative = validateRelative(entry.path).removePrefix("$PLUGINS_ENTRY/")
            val target = resolveInside(pluginRoot, relative)
            if (resume && !source.isFile && target.isFile) {
                done++
                onStage?.invoke(RestoreProgress(RestoreStage.FILES, done, total))
                return@forEach
            }
            requireEntryFile(source, entry, entry.path)
            copyFileWithRollback(source, target, entry.path, rollbacks, created)
            done++
            onStage?.invoke(RestoreProgress(RestoreStage.FILES, done, total))
        }
        manifest.readerAssets.forEach { entry ->
            val source = resolveInside(stagingRoot, entry.path)
            val relativeAsset = readerAssetTarget(filesDir, entry.path)
                .relativeTo(filesDir)
                .invariantSeparatorsPath
            val target = resolveInside(filesDir, relativeAsset)
            if (resume && !source.isFile && target.isFile) {
                done++
                onStage?.invoke(RestoreProgress(RestoreStage.FILES, done, total))
                return@forEach
            }
            requireEntryFile(source, entry, entry.path)
            copyFileWithRollback(source, target, entry.path, rollbacks, created)
            done++
            onStage?.invoke(RestoreProgress(RestoreStage.FILES, done, total))
        }
    }

    private fun copyFileWithRollback(
        source: File,
        target: File,
        label: String,
        rollbacks: MutableList<FileRollback>,
        created: MutableList<File>,
    ) {
        if (target.exists() && !target.isFile) {
            error("Cannot restore over a directory: $label")
        }
        val relative = target.relativeTo(filesDir.canonicalFile).invariantSeparatorsPath
        if (target.isFile) {
            val backup = File(File(rollbackRoot, FILES_DIR), relative)
            moveAtomically(target, backup)
            rollbacks += FileRollback(target = target, backup = backup)
        } else {
            val marker = File(File(rollbackRoot, CREATED_DIR), relative)
            marker.parentFile?.mkdirs()
            marker.writeText("1")
            created += target
        }
        moveAtomically(source, target)
    }

    private fun rollbackFiles(
        rollbacks: List<FileRollback>,
        created: List<File>,
    ): Boolean {
        var ok = true
        created.asReversed().forEach { target ->
            runCatching {
                if (target.exists() && !deletePath(target)) {
                    error("Cannot delete ${target.name}")
                }
            }.onFailure { ok = false }
        }
        rollbacks.asReversed().forEach { rollback ->
            runCatching {
                if (rollback.target.exists() && !deletePath(rollback.target)) {
                    error("Cannot delete ${rollback.target.name}")
                }
                rollback.backup?.let { rollbackMove(it, rollback.target) }
            }.onFailure { ok = false }
        }
        return ok
    }

    private fun rollbackMove(source: File, target: File) {
        movePath?.invoke(source, target) ?: moveAtomically(source, target)
    }

    private fun rollbackStagedFiles(): Boolean {
        var ok = true
        val files = File(rollbackRoot, FILES_DIR)
        if (files.isDirectory) {
            files.walkTopDown().filter { it.isFile }.toList().forEach { backup ->
                runCatching {
                    val relative = backup.relativeTo(files).invariantSeparatorsPath
                    val target = resolveInside(filesDir, relative)
                    target.parentFile?.mkdirs()
                    if (target.exists() && !deletePath(target)) {
                        error("Cannot delete ${target.name}")
                    }
                    rollbackMove(backup, target)
                }.onFailure { ok = false }
            }
        }
        val created = File(rollbackRoot, CREATED_DIR)
        if (created.isDirectory) {
            created.walkTopDown().filter { it.isFile }.toList().forEach { marker ->
                runCatching {
                    val relative = marker.relativeTo(created).invariantSeparatorsPath
                    val target = resolveInside(filesDir, relative)
                    if (target.exists() && !deletePath(target)) {
                        error("Cannot delete ${target.name}")
                    }
                }.onFailure { ok = false }
            }
        }
        return ok
    }

    private suspend fun extract(
        input: InputStream,
        temp: File,
        onProgress: (Float) -> Unit,
        onStage: ((RestoreProgress) -> Unit)?,
    ) {
        val zip = ZipInputStream(BufferedInputStream(input))
        val manifestBytes = readManifest(zip)
        val manifest = parseManifest(manifestBytes)
        validateManifest(manifest)
        var total = manifestBytes.size.toLong()
        if (total > maxTotalBytes) error("Backup exceeds the size limit")
        File(temp, MANIFEST_ENTRY).writeBytes(manifestBytes)
        onProgress(0.1f)

        val expected = expectedEntries(manifest, temp)
        val declaredTotal = expected.values.fold(total) { sum, entry -> sum + entry.size }
        if (declaredTotal > maxTotalBytes) error("Backup exceeds the size limit")
        requireFreeSpace(filesDir, declaredTotal, "restore")

        val seen = HashSet<String>()
        var processed = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) {
                zip.closeEntry()
                continue
            }
            val name = entry.name
            val expectedEntry = expected[name]
                ?: error("Unexpected entry in backup: $name")
            if (!seen.add(name)) error("Duplicate entry in backup: $name")
            val parent = expectedEntry.target.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                error("Cannot create staging directory for $name")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var entryBytes = 0L
            expectedEntry.target.outputStream().use { out ->
                val buffer = ByteArray(RESTORE_CHUNK_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = zip.read(buffer)
                    if (read < 0) break
                    entryBytes += read
                    total += read
                    if (entryBytes > expectedEntry.size) {
                        error("Entry is larger than its manifest size: $name")
                    }
                    if (entryBytes > expectedEntry.maxBytes) {
                        error("Entry is too large: $name")
                    }
                    if (total > maxTotalBytes) error("Backup exceeds the size limit")
                    digest.update(buffer, 0, read)
                    out.write(buffer, 0, read)
                }
            }
            if (entryBytes != expectedEntry.size) {
                error("Entry size does not match the manifest: $name")
            }
            if (digest.digest().toHex() != expectedEntry.sha256) {
                error("Entry checksum does not match the manifest: $name")
            }
            processed++
            onProgress(0.15f + 0.70f * processed / expected.size.coerceAtLeast(1))
            onStage?.invoke(
                RestoreProgress(RestoreStage.EXTRACTING, processed, expected.size),
            )
        }

        val missing = expected.keys - seen
        if (missing.isNotEmpty()) {
            error("Backup is missing entries: ${missing.joinToString()}")
        }

        manifest.settings?.let { entry ->
            val settingsFile = resolveInside(temp, entry.name)
            if (settingsFile.length() > MAX_RESTORE_SETTINGS_BYTES) {
                error("settings.json is too large")
            }
            parseSettings(settingsFile)
        }
        manifest.reader?.let { entry ->
            val readerFile = resolveInside(temp, entry.name)
            if (readerFile.length() > MAX_RESTORE_READER_BYTES) {
                error("reader.json is too large")
            }
            parseReaderPrefs(readerFile)
        }
        manifest.pluginKv?.let { entry ->
            val pluginKvFile = resolveInside(temp, entry.name)
            if (pluginKvFile.length() > MAX_RESTORE_PLUGIN_KV_BYTES) {
                error("plugin-kv.json is too large")
            }
            parsePluginKv(pluginKvFile)
        }
        manifest.localSources?.let { entry ->
            val localSourcesFile = resolveInside(temp, entry.name)
            if (localSourcesFile.length() > MAX_RESTORE_LOCAL_SOURCES_BYTES) {
                error("local-sources.json is too large")
            }
            parseLocalSources(localSourcesFile)
        }

        val stagedDb = resolveInside(temp, DATABASE_ENTRY)
        try {
            inspectDatabase(stagedDb)
        } catch (e: Exception) {
            error("Database validation failed: ${e.message ?: "invalid database"}")
        }
        // Reject a manifest whose DB-relative path would land outside filesDir
        // before anything is published.
        manifest.files.forEach { resolveInside(filesDir, it.path.removePrefix("$FILES_DIR/")) }
        manifest.readerAssets.forEach {
            val relative = readerAssetTarget(filesDir, it.path).relativeTo(filesDir)
                .invariantSeparatorsPath
            resolveInside(filesDir, relative)
        }
        onProgress(0.95f)
    }

    // manifest.json is always the first file in a MiruTime archive. Keeping
    // that rule lets restore stream a SAF input exactly once instead of
    // buffering the whole ZIP or reopening it.
    private fun readManifest(zip: ZipInputStream): ByteArray {
        var entries = 0
        while (true) {
            val entry = zip.nextEntry ?: error("Backup has no manifest.json")
            if (entry.isDirectory) continue
            entries++
            if (entries > maxEntries) error("Backup has too many entries")
            if (entry.name != MANIFEST_ENTRY) {
                error("manifest.json must be the first file in the backup")
            }
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(RESTORE_CHUNK_BYTES)
            var size = 0L
            while (true) {
                val read = zip.read(buffer)
                if (read < 0) break
                size += read
                if (size > MAX_RESTORE_MANIFEST_BYTES) error("manifest.json is too large")
                out.write(buffer, 0, read)
            }
            zip.closeEntry()
            return out.toByteArray()
        }
    }

    private fun expectedEntries(
        manifest: BackupManifest,
        temp: File,
    ): Map<String, ExpectedEntry> {
        val expected = LinkedHashMap<String, ExpectedEntry>()
        manifest.payloadEntries(
            maxEntryBytes = maxEntryBytes,
            maxSettingsBytes = MAX_RESTORE_SETTINGS_BYTES,
            maxReaderBytes = MAX_RESTORE_READER_BYTES,
            maxPluginKvBytes = MAX_RESTORE_PLUGIN_KV_BYTES,
            maxLocalSourcesBytes = MAX_RESTORE_LOCAL_SOURCES_BYTES,
        ).forEach { entry ->
            expected[entry.path] = ExpectedEntry(
                size = entry.size,
                sha256 = entry.sha256,
                maxBytes = entry.maxBytes,
                target = resolveInside(temp, entry.path),
            )
        }
        return expected
    }

    private fun parseManifest(bytes: ByteArray): BackupManifest =
        try {
            BackupJson.decodeFromString(BackupManifest.serializer(), bytes.decodeToString())
        } catch (_: Exception) {
            // Never echo the raw payload into a user-visible error.
            error("manifest.json is not a valid MiruTime backup manifest")
        }

    private fun parseSettings(file: File): PersistedSettings {
        if (file.length() > MAX_RESTORE_SETTINGS_BYTES) error("settings.json is too large")
        val text = try {
            file.readText()
        } catch (_: Exception) {
            error("settings.json could not be read")
        }
        return try {
            BackupJson.decodeFromString(PersistedSettings.serializer(), text)
        } catch (_: Exception) {
            // A serialization error can quote nearby JSON, including keys.
            error("settings.json is not a valid settings snapshot")
        }
    }

    private fun parseReaderPrefs(file: File): ReaderBackupPrefs {
        if (file.length() > MAX_RESTORE_READER_BYTES) error("reader.json is too large")
        val text = try {
            file.readText()
        } catch (_: Exception) {
            error("reader.json could not be read")
        }
        val prefs = try {
            BackupJson.decodeFromString(ReaderBackupPrefs.serializer(), text)
        } catch (_: Exception) {
            // Never echo the raw payload into a user-visible error.
            error("reader.json is not a valid reader preferences snapshot")
        }
        if (prefs.version !in 1..ReaderBackupPrefsVersion) {
            error("Unsupported reader preferences version ${prefs.version}")
        }
        return prefs
    }

    private fun parsePluginKv(file: File): PluginKvBackup {
        if (file.length() > MAX_RESTORE_PLUGIN_KV_BYTES) {
            error("plugin-kv.json is too large")
        }
        val text = try {
            file.readText()
        } catch (_: Exception) {
            error("plugin-kv.json could not be read")
        }
        return try {
            BackupJson.decodeFromString(PluginKvBackup.serializer(), text)
        } catch (_: Exception) {
            error("plugin-kv.json is not a valid plugin settings snapshot")
        }
    }

    private fun parseLocalSources(file: File): LocalSourcesBackup {
        if (file.length() > MAX_RESTORE_LOCAL_SOURCES_BYTES) {
            error("local-sources.json is too large")
        }
        val text = try {
            file.readText()
        } catch (_: Exception) {
            error("local-sources.json could not be read")
        }
        return try {
            BackupJson.decodeFromString(LocalSourcesBackup.serializer(), text)
        } catch (_: Exception) {
            error("local-sources.json is not a valid local sources snapshot")
        }
    }

    private fun readStagedManifest(): BackupManifest {
        val file = resolveInside(stagingRoot, MANIFEST_ENTRY)
        if (!file.isFile) error("Staged restore has no manifest.json")
        if (file.length() > MAX_RESTORE_MANIFEST_BYTES) error("manifest.json is too large")
        return parseManifest(file.readBytes())
    }

    private fun validateManifest(manifest: BackupManifest) {
        if (manifest.formatVersion !in SupportedBackupFormats) {
            error(
                "Unsupported backup format ${manifest.formatVersion}; " +
                    "this app reads formats $SupportedBackupFormats",
            )
        }
        // v6 archives are migrated to the current schema in the staging area
        // before verification/import; older archives are rejected outright.
        val schema = manifest.schemaVersion
        if (schema !in MinimumRestorableBackupSchemaVersion..LibrarySchemaVersion) {
            val detail = if (schema < MinimumRestorableBackupSchemaVersion) {
                "schema $schema is too old; this app restores v6 backups " +
                    "by migrating them to v$LibrarySchemaVersion"
            } else {
                "schema $schema is newer than this app supports"
            }
            error(
                "Backup $detail (current schema is $LibrarySchemaVersion)",
            )
        }
        if (manifest.settingsIncluded && manifest.settings == null) {
            error("Backup is missing its settings entry")
        }
        if (!manifest.settingsIncluded && manifest.settings != null) {
            error("Backup settings flag does not match its payload")
        }
        if (!manifest.pluginsIncluded && manifest.plugins.isNotEmpty()) {
            error("Backup plugin flag does not match its payload")
        }
        if (manifest.readerIncluded && manifest.reader == null) {
            error("Backup is missing its reader entry")
        }
        if (!manifest.readerIncluded && manifest.reader != null) {
            error("Backup reader flag does not match its payload")
        }
        if (!manifest.readerIncluded && manifest.readerAssets.isNotEmpty()) {
            error("Backup reader flag does not match its assets")
        }
        if (manifest.database.name != DATABASE_ENTRY) {
            error("Backup database path is not supported")
        }
        manifest.settings?.let { settings ->
            if (settings.name != SETTINGS_ENTRY) {
                error("Backup settings path is not supported")
            }
            if (settings.size < 0 || settings.size > MAX_RESTORE_SETTINGS_BYTES) {
                error("Backup settings are too large")
            }
            if (!SHA256_HEX.matches(settings.sha256)) {
                error("Backup settings have an invalid checksum")
            }
        }
        manifest.reader?.let { reader ->
            if (reader.name != READER_ENTRY) {
                error("Backup reader path is not supported")
            }
            if (reader.size < 0 || reader.size > MAX_RESTORE_READER_BYTES) {
                error("Backup reader preferences are too large")
            }
            if (!SHA256_HEX.matches(reader.sha256)) {
                error("Backup reader preferences have an invalid checksum")
            }
        }
        manifest.pluginKv?.let { kv ->
            if (kv.name != PLUGIN_KV_ENTRY) {
                error("Backup plugin settings path is not supported")
            }
            if (kv.size < 0 || kv.size > MAX_RESTORE_PLUGIN_KV_BYTES) {
                error("Backup plugin settings are too large")
            }
            if (!SHA256_HEX.matches(kv.sha256)) {
                error("Backup plugin settings have an invalid checksum")
            }
            if (!manifest.settingsIncluded && !manifest.pluginsIncluded) {
                error("Backup plugin settings flag does not match its payload")
            }
        }
        manifest.localSources?.let { sources ->
            if (sources.name != LOCAL_SOURCES_ENTRY) {
                error("Backup local sources path is not supported")
            }
            if (sources.size < 0 || sources.size > MAX_RESTORE_LOCAL_SOURCES_BYTES) {
                error("Backup local sources are too large")
            }
            if (!SHA256_HEX.matches(sources.sha256)) {
                error("Backup local sources have an invalid checksum")
            }
            if (!manifest.settingsIncluded) {
                error("Backup local sources flag does not match its payload")
            }
        }
        validateEntry(manifest.database, "database")

        val seen = hashSetOf(MANIFEST_ENTRY, DATABASE_ENTRY)
        manifest.settings?.let { seen += it.name }
        manifest.reader?.let { seen += it.name }
        manifest.pluginKv?.let { seen += it.name }
        manifest.localSources?.let { seen += it.name }
        manifest.files.forEach { entry ->
            val path = validateRelative(entry.path)
            if (!path.startsWith("$FILES_DIR/") || path.length <= FILES_DIR.length + 1) {
                error("Backup contains an invalid file path: ${entry.path}")
            }
            if (!seen.add(path)) error("Backup contains a duplicate path: $path")
            validateEntry(entry, path)
        }
        manifest.plugins.forEach { entry ->
            val path = validateRelative(entry.path)
            val relative = path.removePrefix("$PLUGINS_ENTRY/")
            val parts = relative.split('/')
            if (!path.startsWith("$PLUGINS_ENTRY/") ||
                relative == path ||
                parts.size < 2 ||
                parts.any { it.isBlank() } ||
                parts.first() !in PLUGIN_DIRS
            ) {
                error("Backup contains an invalid plugin path: ${entry.path}")
            }
            if (!seen.add(path)) error("Backup contains a duplicate path: $path")
            validatePluginEntry(entry, path)
        }
        manifest.readerAssets.forEach { entry ->
            val path = validateRelative(entry.path)
            val rest = path.removePrefix("$READER_ASSETS_ENTRY/")
            val parts = rest.split('/')
            if (!path.startsWith("$READER_ASSETS_ENTRY/") ||
                rest == path ||
                parts.size != 2 ||
                parts.any { it.isBlank() } ||
                parts.first() !in READER_ASSET_BUCKETS
            ) {
                error("Backup contains an invalid reader asset path: ${entry.path}")
            }
            if (!seen.add(path)) error("Backup contains a duplicate path: $path")
            validateReaderAssetEntry(entry, path)
        }
        MiruLog.event(
            "backup",
            "restore-manifest",
            "format" to manifest.formatVersion,
            "schema" to manifest.schemaVersion,
            "app" to manifest.appVersion,
            "books" to (manifest.counts.totalBooks.takeIf { it > 0 } ?: manifest.counts.localBooks),
            "files" to manifest.counts.localFiles,
            "plugins" to manifest.counts.pluginFiles,
        )
    }

    private fun validateEntry(entry: BackupEntry, label: String) {
        if (entry.size < 0 || entry.size > maxEntryBytes) {
            error("Backup entry is too large: $label")
        }
        if (!SHA256_HEX.matches(entry.sha256)) {
            error("Backup entry has an invalid checksum: $label")
        }
    }

    private fun validateEntry(entry: BackupFileEntry, label: String) {
        if (entry.size < 0 || entry.size > maxEntryBytes) {
            error("Backup entry is too large: $label")
        }
        if (!SHA256_HEX.matches(entry.sha256)) {
            error("Backup entry has an invalid checksum: $label")
        }
    }

    private fun requireEntryFile(file: File, entry: BackupEntry, label: String) {
        if (!file.isFile) error("Backup is missing $label")
        if (file.length() != entry.size) error("Backup size does not match the manifest: $label")
    }

    private fun requireEntryFile(file: File, entry: BackupFileEntry, label: String) {
        if (!file.isFile) error("Backup is missing $label")
        if (file.length() != entry.size) error("Backup size does not match the manifest: $label")
    }

    private fun requireEntryFile(file: File, entry: BackupReaderAssetEntry, label: String) {
        if (!file.isFile) error("Backup is missing $label")
        if (file.length() != entry.size) error("Backup size does not match the manifest: $label")
    }

    private fun requireEntryFile(file: File, entry: BackupPluginEntry, label: String) {
        if (!file.isFile) error("Backup is missing $label")
        if (file.length() != entry.size) error("Backup size does not match the manifest: $label")
    }

    private fun validateReaderAssetEntry(entry: BackupReaderAssetEntry, label: String) {
        if (entry.size < 0 || entry.size > MAX_READER_ASSET_BYTES) {
            error("Backup entry is too large: $label")
        }
        if (!SHA256_HEX.matches(entry.sha256)) {
            error("Backup entry has an invalid checksum: $label")
        }
    }

    private fun validatePluginEntry(entry: BackupPluginEntry, label: String) {
        if (entry.size < 0 || entry.size > maxEntryBytes) {
            error("Backup entry is too large: $label")
        }
        if (!SHA256_HEX.matches(entry.sha256)) {
            error("Backup entry has an invalid checksum: $label")
        }
    }

    // Rejects absolute paths, "..", empty segments, backslashes and anything
    // that canonicalises outside [root]. ZIP entries are attacker-controlled.
    private fun resolveInside(root: File, relative: String): File {
        val normalized = validateRelative(relative)
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, normalized).canonicalFile
        if (target != canonicalRoot && !target.path.startsWith(canonicalRoot.path + File.separator)) {
            error("Backup path escapes its destination: $relative")
        }
        return target
    }

    private fun validateRelative(relative: String): String {
        if (relative.isEmpty() || relative.length > MAX_RESTORE_PATH_LENGTH) {
            error("Backup contains an invalid path")
        }
        if (relative.startsWith("/") || relative.contains('\\') || relative.contains('\u0000')) {
            error("Backup contains an invalid path: $relative")
        }
        require(relative.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "Backup contains an invalid path: $relative"
        }
        return relative
    }

    private fun cleanupStaleStages() {
        filesDir.listFiles { file -> file.name.startsWith("$STAGING_DIR.tmp-") }
            ?.forEach { it.deleteRecursively() }
    }

    private fun stagingBookCount(staging: File): Int {
        val marker = File(staging, MANIFEST_ENTRY)
        return runCatching {
            parseManifest(marker.readBytes()).counts.localBooks
        }.getOrDefault(0)
    }

    // rename is atomic inside the app data dir; copy+delete is only a fallback
    // for an unexpected cross-device staging path.
    private fun moveAtomically(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (source.renameTo(target)) return
        source.copyTo(target, overwrite = true)
        if (!source.delete()) error("Cannot delete ${source.name}")
    }

    private data class ExpectedEntry(
        val size: Long,
        val sha256: String,
        val maxBytes: Long,
        val target: File,
    )

    private data class FileRollback(
        val target: File,
        val backup: File?,
    )

    companion object {
        const val STAGING_DIR = "restore_pending"
        const val ROLLBACK_DIR = "restore_rollback"
        private const val FILES_DIR = "files"
        private const val CREATED_DIR = "created"
        private const val PREVIOUS_SETTINGS_ENTRY = "settings.previous.json"
        private const val RESTORED_SETTINGS_ENTRY = "settings.restore.json"
        private const val PREVIOUS_READER_ENTRY = "reader.previous.json"
        private const val RESTORED_READER_ENTRY = "reader.restore.json"
        private const val RESTORED_PLUGIN_KV_ENTRY = "plugin-kv.restore.json"
        private const val RESTORED_LOCAL_SOURCES_ENTRY = "local-sources.restore.json"
        private const val PROGRESS_MARKER = "progress"
        private const val PHASE_FILES_COPIED = "files"
        private const val PHASE_DB_FILES = "db-files"
        private const val PHASE_DB_COMMITTED = "db"
        private const val FILE_JOURNAL = "files.journal"
        private const val FILE_JOURNAL_VERSION = "v1"
        private const val RESTORE_CHUNK_BYTES = 64 * 1024
        private const val MAX_RESTORE_MANIFEST_BYTES = 16L * 1024 * 1024
        private const val MAX_RESTORE_SETTINGS_BYTES = 16L * 1024 * 1024
        private const val MAX_RESTORE_READER_BYTES = 4L * 1024 * 1024
        private const val MAX_RESTORE_PLUGIN_KV_BYTES = 4L * 1024 * 1024
        private const val MAX_RESTORE_LOCAL_SOURCES_BYTES = 8L * 1024 * 1024
        private const val MAX_RESTORE_PATH_LENGTH = 1024
        private const val MAX_RESTORE_ENTRY_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_RESTORE_TOTAL_BYTES = 8L * 1024 * 1024 * 1024
        private const val MAX_RESTORE_ENTRIES = 100_000
        private const val STAGING_PROGRESS = 0.9f
        // A NoveLA archive is converted into this staging layout first; its
        // progress owns the bulk of the bar, staging the rest.
        private const val NOVELA_PROGRESS_SHARE = 0.95f
        private val PLUGIN_DIRS = listOf("lua_plugins", "lua_libs")

        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        /**
         * Cover files are stored as `covers/<bookId>/cover`; a remapped book id
         * needs the file name rewritten as well as the DB path.
         */
        internal fun coverPathFor(
            filesDir: File,
            oldBookId: Long,
            newBookId: Long,
            oldPath: String?,
        ): String? {
            if (oldPath.isNullOrBlank()) return null
            val looksLikeCover = oldPath.replace('\\', '/').contains("/covers/") ||
                oldPath.substringAfterLast('/') == "cover"
            if (!looksLikeCover) return oldPath
            val name = File(oldPath).name.ifBlank { "cover" }
            return File(File(filesDir, "covers/$newBookId"), name).absolutePath
        }

        private fun remapFileTarget(
            filesDir: File,
            entry: BackupFileEntry,
            bookIds: Map<Long, Long>,
            preservedCovers: Set<Long>,
        ): File? {
            val relative = entry.path.removePrefix("$FILES_DIR/")
            if (entry.kind != KIND_COVER) {
                return File(filesDir.canonicalFile, relative)
            }
            val newBookId = bookIds[entry.bookId] ?: return null
            if (newBookId in preservedCovers) return null
            val name = relative.substringAfterLast('/').ifBlank { "cover" }
            return File(File(filesDir.canonicalFile, "covers/$newBookId"), name)
        }
    }
}

internal typealias ImportDatabase = suspend (
    source: File,
    coverPathFor: (oldBookId: Long, newBookId: Long, oldPath: String?) -> String?,
    journalId: Long,
    journalNow: Long,
    onCommitted: suspend (LibraryImportReport) -> Unit,
) -> LibraryImportReport

internal typealias StageNovelaBackup = suspend (
    input: InputStream,
    staging: File,
    onProgress: (Float) -> Unit,
    onStage: ((RestoreProgress) -> Unit)?,
) -> NovelaImportReport

// Temp-file + rename so a crash never leaves a half-written marker/journal.
internal fun writeTextAtomic(file: File, text: String) {
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, "${file.name}.tmp")
    temp.writeText(text)
    if (!temp.renameTo(file)) {
        file.delete()
        check(temp.renameTo(file)) { "Cannot write ${file.name}" }
    }
}

data class RestoreStageResult(
    val success: Boolean,
    val localBookCount: Int = 0,
    val error: String? = null,
)

data class RestoreApplyResult(
    val applied: Boolean,
    val settings: PersistedSettings? = null,
    val pluginsRestored: Int = 0,
    val error: String? = null,
)

enum class RestoreStage {
    VALIDATING,
    EXTRACTING,
    DATABASE,
    FILES,
    SETTINGS,
    CACHE,
}

data class RestoreProgress(
    val stage: RestoreStage,
    val current: Int = 0,
    val total: Int = 0,
) {
    val fraction: Float get() = if (total > 0) current.toFloat() / total else 0f
}
