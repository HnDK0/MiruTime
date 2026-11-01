package dev.mirutime.app.features.settings.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.mirutime.app.MiruApp
import dev.mirutime.app.R
import dev.mirutime.core.log.MiruLog
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.BackupOptions
import dev.mirutime.app.features.settings.data.BackupResult
import dev.mirutime.app.features.settings.data.PersistedSettings
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Terminal attempt mapping: never retry a transient failure forever. */
internal enum class AutoBackupAttemptDecision { Retry, GiveUp }

internal object AutoBackupRetryPolicy {
    const val MaxAttempts = 3

    fun decide(runAttemptCount: Int): AutoBackupAttemptDecision =
        if (runAttemptCount + 1 < MaxAttempts) {
            AutoBackupAttemptDecision.Retry
        } else {
            AutoBackupAttemptDecision.GiveUp
        }
}

/**
 * Process-wide serialization of periodic and "Run now" workers. It is
 * deliberately separate from [BackupService][dev.mirutime.app.features.settings.data.BackupService]'s
 * BackupOperationGate: that gate is acquired inside createBackup, so taking it
 * here would deadlock. Rotation runs under this lock and can therefore never
 * delete an archive another worker is still writing.
 */
internal object AutoBackupRunGate {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}

/**
 * Periodic SAF backup. It never retries a lost tree permission: that is a
 * terminal configuration problem, so auto backup is disabled with a result
 * notification instead of waking the device every interval.
 *
 * A denied foreground-service start (Android 12+) only downgrades progress to
 * a normal notification; it does not abort the backup or trigger retries.
 */
class AutoBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? MiruApp ?: return Result.failure()
        // Persisted snapshot, not the store: scheduling options must not roll
        // back edits that are still inside the 300 ms save debounce.
        val settings = AppSettings.persistedSnapshot()
        val action = AutoBackupPolicy.decide(
            autoBackup = settings.autoBackup,
            isTreeUri = SafBackupTree.isTreeUri(settings.backupDirectory),
            permissionGranted = SafBackupTree.hasPersistedPermission(
                app,
                settings.backupDirectory,
            ),
        )
        val treeUri = settings.backupDirectory
            .takeIf { SafBackupTree.isTreeUri(it) }
            ?.let(Uri::parse)
        when (action) {
            AutoBackupAction.Cancel -> return Result.success()
            AutoBackupAction.DisablePermissionLost -> {
                // Best effort: with the grant gone the provider usually
                // rejects the query, but the stale `.part` cleanup must not be
                // skipped when it is only partially revoked.
                treeUri?.let { uri -> runCatching { SafBackupTree.cleanupParts(app, uri) } }
                AutoBackupScheduler.disablePermissionLost(app)
                return Result.success()
            }
            AutoBackupAction.Schedule -> Unit
        }
        if (treeUri == null) return Result.success()

        return AutoBackupRunGate.withLock { runScheduledBackup(app, treeUri, settings) }
    }

    private suspend fun runScheduledBackup(
        app: MiruApp,
        treeUri: Uri,
        settings: PersistedSettings,
    ): Result {
        runCatching { SafBackupTree.cleanupParts(app, treeUri) }

        val finalName = BackupRotation.fileName(System.currentTimeMillis(), auto = true)
        val initial = BackupNotificationContentFactory.backup(0)
        val foreground = setForegroundProgress(initial)
        if (!foreground) BackupNotifications.notifyProgress(app, initial)

        var partDocument: Uri? = null
        try {
            val part = SafBackupTree.createBackupPart(app, treeUri, finalName)
            partDocument = part
            val percent = MutableStateFlow(0)
            val result = coroutineScope {
                val pump = launch {
                    percent.collectLatest { value ->
                        val content = BackupNotificationContentFactory.backup(value)
                        if (foreground) {
                            setForegroundProgress(content)
                        } else {
                            BackupNotifications.notifyProgress(app, content)
                        }
                    }
                }
                try {
                    app.contentResolver.openOutputStream(part, "w")?.use { output ->
                        app.backupService.createBackup(
                            output,
                            BackupOptions(
                                includeSettings = settings.backupIncludeSettings,
                                includeImages = settings.backupIncludeImages,
                                includePlugins = settings.backupIncludePlugins,
                            ),
                        ) { fraction ->
                            percent.value = (fraction * 100).toInt().coerceIn(0, 100)
                        }
                    } ?: BackupResult(
                        success = false,
                        error = "Cannot open backup destination",
                    )
                } finally {
                    pump.cancel()
                }
            }

            if (!result.success) {
                deleteQuietly(app, part)
                partDocument = null
                return failureResult(
                    app,
                    result.error ?: app.getString(R.string.backup_notification_failed_title),
                )
            }

            // Only a validated archive reaches the final app name; rename
            // replaces the whole visible file, so rotation only ever sees
            // completed backups.
            runCatching { SafBackupTree.finalizeBackup(app, part, finalName) }
                .onFailure { error ->
                    deleteQuietly(app, part)
                    partDocument = null
                    return failureResult(
                        app,
                        error.message
                            ?: app.getString(R.string.backup_notification_failed_title),
                    )
                }
            partDocument = null

            val rotated = runCatching {
                SafBackupTree.rotate(
                    app,
                    treeUri,
                    settings.backupMaxCount,
                    excludedNames = setOf(finalName),
                )
            }.getOrDefault(0)
            updateLastTimestamp(app, System.currentTimeMillis())
            MiruLog.event(
                "backup",
                "auto-result",
                "ok" to true,
                "rotated" to rotated,
                "bytes" to result.bytes,
                "entries" to result.entryCount,
            )
            BackupNotifications.notifyResult(
                app,
                success = true,
                restore = false,
                message = app.getString(
                    R.string.backup_notification_auto_done_fmt,
                    rotated,
                ),
            )
            return Result.success()
        } catch (e: CancellationException) {
            partDocument?.let { deleteQuietly(app, it) }
            throw e
        } catch (e: Exception) {
            partDocument?.let { deleteQuietly(app, it) }
            return failureResult(
                app,
                e.message ?: app.getString(R.string.backup_notification_failed_title),
            )
        } finally {
            if (!foreground) BackupNotifications.clearProgress(app)
        }
    }

    private fun failureResult(app: MiruApp, message: String): Result =
        when (AutoBackupRetryPolicy.decide(runAttemptCount)) {
            AutoBackupAttemptDecision.Retry -> {
                MiruLog.event("backup", "auto-result", "ok" to false, "retry" to true)
                Result.retry()
            }
            AutoBackupAttemptDecision.GiveUp -> {
                MiruLog.event("backup", "auto-result", "ok" to false, "retry" to false)
                BackupNotifications.notifyResult(
                    app,
                    success = false,
                    restore = false,
                    message = message,
                )
                Result.failure()
            }
        }

    private suspend fun setForegroundProgress(content: BackupNotificationContent): Boolean =
        try {
            setForeground(
                BackupNotifications.foregroundInfo(
                    applicationContext,
                    content,
                    cancelable = false,
                ),
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Android 12+ may forbid a background foreground-service start.
            false
        }

    private fun deleteQuietly(context: Context, document: Uri) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, document) }
    }

    private fun updateLastTimestamp(context: Context, at: Long) {
        val app = context as? MiruApp ?: return
        runCatching {
            val updated = AppSettings.persistedSnapshot()
                .copy(autoBackupLastTimestamp = at)
            app.settingsStore.save(updated)
            AppSettings.applyPersisted(updated)
        }
    }
}
