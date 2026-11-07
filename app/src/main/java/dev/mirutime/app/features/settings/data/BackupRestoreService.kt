package dev.mirutime.app.features.settings.data

import android.content.Context
import dev.mirutime.core.log.MiruLog
import dev.mirutime.data.library.LibraryDatabase
import dev.mirutime.data.library.LibraryImportReport
import dev.mirutime.data.library.LibrarySchemaVersion
import dev.mirutime.data.library.MinimumRestorableBackupSchemaVersion
import dev.mirutime.data.library.RestoreJournalStatus
import dev.mirutime.data.library.importLibraryBackup
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
class BackupRestoreService(
    private val filesDir: File,
    private val databasePath: File,
    private val settingsStore: SettingsStore,
    private val readerStore: ReaderBackupStore = NoopReaderBackupStore,
    private val appDataDir: File = filesDir.parentFile ?: filesDir,
    private val verifyDatabase: (File) -> Unit = { verifyDatabaseSnapshot(it.absolutePath) },
    private val inspectDatabase: (File) -> Int = { inspectBackupDatabase(it.absolutePath) },
    private val migrateDatabase: (File, Int) -> Unit = { file, fromVersion ->
        migrateBackupDatabase(file.absolutePath, fromVersion, LibrarySchemaVersion)
    },
    private val importDatabase: ImportDatabase = { _, _, _, _, _ ->
        error("Live database import is not configured")
    },
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
    private val maxEntryBytes: Long = MAX_RESTORE_ENTRY_BYTES,
    private val maxTotalBytes: Long = MAX_RESTORE_TOTAL_BYTES,
    private val maxEntries: Int = MAX_RESTORE_ENTRIES,
) {
    constructor(
        context: Context,
        databasePath: File,
        settingsStore: SettingsStore,
        database: LibraryDatabase,
        reloadPlugins: suspend () -> Unit = {},
        clearImageCaches: () -> Unit = {},
        readerStore: ReaderBackupStore = NoopReaderBackupStore,
    ) : this(
        filesDir = context.applicationContext.filesDir,
        databasePath = databasePath,
        settingsStore = settingsStore,
        readerStore = readerStore,
        appDataDir = context.applicationContext.filesDir.parentFile
            ?: context.applicationContext.filesDir,
        importDatabase = { sourceFile, coverPathFor, journalId, journalNow, onBooksReady ->
            val source = libraryDatabaseAt(context, sourceFile)
            try {
                database.importLibraryBackup(
                    source = source.libraryDao(),
                    coverPathFor = coverPathFor,
                    onBooksReady = onBooksReady,
                    journalId = journalId,
                    journalNow = journalNow,
                )
            } finally {
                source.close()
            }
        },
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
            return@withContext RestoreStageResult(
                success = false,
                error = "Another restore is already in progress.",
            )
        }
        val temp = File(filesDir, "$STAGING_DIR.tmp-${newId()}")
        try {
            cleanupStaleStages()
            temp.mkdirs()
            onProgress(0.01f)
            extract(input, temp, onProgress, onStage)
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
            val dbCommitted = marker.phase == PHASE_DB_COMMITTED ||
                (marker.operationId?.let { journalExists(it) } ?: false)
            val result = if (dbCommitted) {
                val restored = File(rollbackRoot, RESTORED_SETTINGS_ENTRY)
                val settings = if (restored.isFile) parseSettings(restored) else null
                val restoredReader = File(rollbackRoot, RESTORED_READER_ENTRY)
                val reader = if (restoredReader.isFile) parseReaderPrefs(restoredReader) else null
                val settingsOk = runCatching {
                    settings?.let { settingsStore.save(it) }
                    reader?.let { readerStore.restore(it) }
                }.isSuccess
                val journalOk = runCatching {
                    marker.operationId?.let { deleteJournal(it) }
                }.isSuccess
                if (!settingsOk || !journalOk) {
                    // The DB is committed; keep the marker/rollback for retry
                    // but never report a rollback.
                    return RestoreApplyResult(applied = true, settings = settings)
                }
                RestoreApplyResult(applied = true, settings = settings)
            } else {
                val filesRestored = rollbackStagedFiles()
                val previous = File(rollbackRoot, PREVIOUS_SETTINGS_ENTRY)
                val settings = if (previous.isFile) parseSettings(previous) else null
                val previousReader = File(rollbackRoot, PREVIOUS_READER_ENTRY)
                val reader = if (previousReader.isFile) parseReaderPrefs(previousReader) else null
                val settingsRestored = runCatching {
                    settings?.let { settingsStore.save(it) }
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
        } catch (_: Exception) {
            RestoreApplyResult(applied = false)
        }
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

            val settings = manifest.settings?.let { entry ->
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

            previousSettings = runCatching { settingsStore.load() }.getOrNull()
            previousReader = runCatching { readerStore.snapshot() }.getOrNull()
            prepareRollback(previousSettings, settings, previousReader, reader, operationId)
            onProgress(STAGING_PROGRESS * 0.99f)

            onStage?.invoke(RestoreProgress(RestoreStage.DATABASE, 0, 1))
            importDatabase(
                stagedDb,
                { oldBookId, newBookId, oldPath ->
                    coverPathFor(filesDir, oldBookId, newBookId, oldPath)
                },
                operationId,
                System.currentTimeMillis(),
            ) { bookIds, preservedCovers ->
                copyStagedFiles(
                    manifest = manifest,
                    bookIds = bookIds,
                    preservedCovers = preservedCovers,
                    rollbacks = rollbacks,
                    created = created,
                    onStage = onStage,
                )
            }
            onStage?.invoke(RestoreProgress(RestoreStage.DATABASE, 1, 1))
            phase = PHASE_DB_COMMITTED
            // The DB is durable from here on. Cancellation must never reach
            // the file rollback path; finish settings/journal/cleanup and
            // report the restore as applied even if the caller was cancelled.
            return withContext(NonCancellable) {
                try {
                    File(rollbackRoot, PROGRESS_MARKER).writeText(
                        "$PHASE_DB_COMMITTED\n$operationId",
                    )
                    onProgress(0.99f)

                    if (settings != null) {
                        onStage?.invoke(RestoreProgress(RestoreStage.SETTINGS, 0, 1))
                        settingsStore.save(settings)
                        AppSettings.applyPersisted(settings)
                        onStage?.invoke(RestoreProgress(RestoreStage.SETTINGS, 1, 1))
                    }
                    if (reader != null) {
                        readerStore.restore(reader)
                    }

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
                    )
                } catch (e: Exception) {
                    // Leave the DB_COMMITTED marker + journal + rollback for
                    // the next start; recovery completes settings/cleanup.
                    RestoreApplyResult(
                        applied = true,
                        settings = settings,
                        pluginsRestored = manifest.plugins.size,
                        error = e.message,
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
        if (phase == PHASE_DB_COMMITTED) {
            // The database transaction is already durable; only settings may
            // still need to land. Leave the marker for next-start recovery if
            // this save fails.
            runCatching {
                val restored = File(rollbackRoot, RESTORED_SETTINGS_ENTRY)
                if (restored.isFile) {
                    val settings = parseSettings(restored)
                    settingsStore.save(settings)
                    AppSettings.applyPersisted(settings)
                }
                val restoredReader = File(rollbackRoot, RESTORED_READER_ENTRY)
                if (restoredReader.isFile) readerStore.restore(parseReaderPrefs(restoredReader))
                deleteJournal(operationId)
                rollbackRoot.deleteRecursively()
            }
            stagingRoot.deleteRecursively()
        } else {
            val filesRolledBack = rollbackFiles(rollbacks, created)
            val settingsRolledBack = previousSettings?.let {
                runCatching {
                    settingsStore.save(it)
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
        File(rollbackRoot, PROGRESS_MARKER).writeText("$PHASE_FILES_COPIED\n$operationId")
    }

    private fun copyStagedFiles(
        manifest: BackupManifest,
        bookIds: Map<Long, Long>,
        preservedCovers: Set<Long>,
        rollbacks: MutableList<FileRollback>,
        created: MutableList<File>,
        onStage: ((RestoreProgress) -> Unit)?,
    ) {
        val total = manifest.files.size + manifest.plugins.size + manifest.readerAssets.size
        var done = 0
        manifest.files.forEach { entry ->
            val source = resolveInside(stagingRoot, entry.path)
            requireEntryFile(source, entry, entry.path)
            val target = remapFileTarget(filesDir, entry, bookIds, preservedCovers)
            if (target != null) {
                copyFileWithRollback(source, target, entry.path, rollbacks, created)
            }
            done++
            onStage?.invoke(RestoreProgress(RestoreStage.FILES, done, total))
        }
        val pluginRoot = File(filesDir, PLUGIN_ROOT)
        manifest.plugins.forEach { entry ->
            val source = resolveInside(stagingRoot, entry.path)
            requireEntryFile(source, entry, entry.path)
            val relative = validateRelative(entry.path).removePrefix("$PLUGINS_ENTRY/")
            val target = resolveInside(pluginRoot, relative)
            copyFileWithRollback(source, target, entry.path, rollbacks, created)
            done++
            onStage?.invoke(RestoreProgress(RestoreStage.FILES, done, total))
        }
        manifest.readerAssets.forEach { entry ->
            val source = resolveInside(stagingRoot, entry.path)
            requireEntryFile(source, entry, entry.path)
            val relativeAsset = readerAssetTarget(filesDir, entry.path)
                .relativeTo(filesDir)
                .invariantSeparatorsPath
            val target = resolveInside(filesDir, relativeAsset)
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
        validateEntry(manifest.database, "database")

        val seen = hashSetOf(MANIFEST_ENTRY, DATABASE_ENTRY)
        manifest.settings?.let { seen += it.name }
        manifest.reader?.let { seen += it.name }
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
        private const val PROGRESS_MARKER = "progress"
        private const val PHASE_FILES_COPIED = "files"
        private const val PHASE_DB_COMMITTED = "db"
        private const val RESTORE_CHUNK_BYTES = 64 * 1024
        private const val MAX_RESTORE_MANIFEST_BYTES = 1024L * 1024
        private const val MAX_RESTORE_SETTINGS_BYTES = 16L * 1024 * 1024
        private const val MAX_RESTORE_READER_BYTES = 4L * 1024 * 1024
        private const val MAX_RESTORE_PATH_LENGTH = 1024
        private const val MAX_RESTORE_ENTRY_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_RESTORE_TOTAL_BYTES = 8L * 1024 * 1024 * 1024
        private const val MAX_RESTORE_ENTRIES = 100_000
        private const val STAGING_PROGRESS = 0.9f
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
    onBooksReady: suspend (bookIds: Map<Long, Long>, preservedCovers: Set<Long>) -> Unit,
) -> LibraryImportReport

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
