package dev.mirutime.app.features.settings.backup

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.DocumentsContract
import dev.mirutime.app.MiruApp
import dev.mirutime.app.R
import dev.mirutime.core.log.MiruLog
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.BackupOptions
import dev.mirutime.app.features.settings.data.BackupResult
import dev.mirutime.app.features.settings.data.RestoreApplyResult
import dev.mirutime.app.features.settings.data.RestoreStage
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Single foreground owner for manual backup/restore work started by an Intent.
 *
 * The underlying services already enforce a process-wide gate, so this class
 * only serializes start commands inside one service instance and turns their
 * progress into a dataSync notification. It is deliberately not sticky: after
 * a process death the system must not resurrect a half-written operation.
 */
class BackupOperationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var job: Job? = null
    private var lastPercent = -1
    private var lastText = -1

    override fun onCreate() {
        super.onCreate()
        BackupNotifications.ensureChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            if (!running.get()) stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            BackupOperationRequest.ActionCancel -> {
                val current = BackupOperationMonitor.state.value
                if (current.running) {
                    BackupOperationMonitor.finish(
                        kind = current.kind ?: BackupOperationKind.Backup,
                        success = false,
                        message = getString(R.string.backup_notification_cancelled),
                    )
                }
                job?.cancel()
                stopSelf()
                return START_NOT_STICKY
            }
            BackupOperationRequest.ActionBackup,
            BackupOperationRequest.ActionRestore -> {
                val request = BackupOperationRequest.from(intent)
                if (request == null) {
                    BackupNotifications.notifyResult(
                        this,
                        success = false,
                        restore = intent.action == BackupOperationRequest.ActionRestore,
                        message = getString(R.string.backup_notification_failed_title),
                    )
                    if (!running.get()) stopSelf()
                    return START_NOT_STICKY
                }
                if (!running.compareAndSet(false, true)) {
                    BackupNotifications.notifyBusy(
                        this,
                        restore = request.kind == BackupOperationRequest.Kind.Restore,
                    )
                    return START_NOT_STICKY
                }
                BackupOperationMonitor.begin(request.kind.monitorKind())
                startForegroundNotification(request)
                job = scope.launch { execute(request) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val current = BackupOperationMonitor.state.value
        if (current.running) {
            BackupOperationMonitor.finish(
                kind = current.kind ?: BackupOperationKind.Backup,
                success = false,
                message = getString(R.string.backup_notification_cancelled),
            )
        }
        job?.cancel()
        scope.cancel()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private fun startForegroundNotification(request: BackupOperationRequest) {
        lastPercent = -1
        lastText = -1
        val content = if (request.kind == BackupOperationRequest.Kind.Restore) {
            BackupNotificationContentFactory.restore(
                dev.mirutime.app.features.settings.data.RestoreProgress(
                    dev.mirutime.app.features.settings.data.RestoreStage.VALIDATING,
                ),
            )
        } else {
            BackupNotificationContentFactory.backup(0)
        }
        val notification = BackupNotifications.progress(this, content, cancelable = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                BackupNotifications.ForegroundId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(BackupNotifications.ForegroundId, notification)
        }
    }

    private suspend fun execute(request: BackupOperationRequest) {
        var success = false
        var message = ""
        MiruLog.event("backup", "start", "kind" to request.kind.name, "source" to request.source.name)
        try {
            when (request.kind) {
                BackupOperationRequest.Kind.Backup -> {
                    val result = runBackup(request)
                    success = result.success
                    message = result.error ?: buildBackupMessage(result)
                    MiruLog.event(
                        "backup",
                        "result",
                        "kind" to "backup",
                        "ok" to result.success,
                        "bytes" to result.bytes,
                        "entries" to result.entryCount,
                        "books" to result.bookCount,
                        "library" to result.libraryBookCount,
                        "files" to result.localFileCount,
                        "plugins" to result.pluginFileCount,
                    )
                }
                BackupOperationRequest.Kind.Restore -> {
                    val result = runRestore(request)
                    success = result.applied
                    message = result.error ?: getString(R.string.backup_notification_restore_done_title)
                    MiruLog.event(
                        "backup",
                        "result",
                        "kind" to "restore",
                        "ok" to result.applied,
                        "plugins" to result.pluginsRestored,
                    )
                }
            }
        } catch (e: CancellationException) {
            MiruLog.event("backup", "result", "kind" to request.kind.name, "ok" to false, "reason" to "cancelled")
            message = getString(R.string.backup_notification_cancelled)
            throw e
        } catch (e: Exception) {
            MiruLog.event(
                "backup",
                "result",
                "kind" to request.kind.name,
                "ok" to false,
                "error" to (e::class.simpleName ?: "error"),
            )
            message = e.message ?: e::class.simpleName ?: "Operation failed"
        } finally {
            if (!success && message.isBlank()) {
                message = getString(R.string.backup_notification_failed_title)
            }
            BackupOperationMonitor.finish(
                kind = request.kind.monitorKind(),
                success = success,
                message = message,
            )
            BackupNotifications.notifyResult(
                this,
                success = success,
                restore = request.kind == BackupOperationRequest.Kind.Restore,
                message = message,
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            running.set(false)
            stopSelf()
        }
    }

    private suspend fun runBackup(request: BackupOperationRequest): BackupResult {
        val app = application as MiruApp
        val requestedUri = Uri.parse(request.uri)
        // A killed manual backup into a tree folder can leave a stale `.part`.
        // The auto worker already does this; manual tree backups did not.
        if (SafBackupTree.isTreeUri(request.uri)) {
            runCatching { SafBackupTree.cleanupParts(this, requestedUri) }
        }
        var createdDocument: Uri? = null
        var finalName: String? = null
        val destination = if (request.source == BackupOperationRequest.Source.Auto ||
            SafBackupTree.isTreeUri(request.uri)
        ) {
            val name = BackupRotation.fileName(
                System.currentTimeMillis(),
                auto = request.source == BackupOperationRequest.Source.Auto,
            )
            finalName = name
            SafBackupTree.createBackupPart(this, requestedUri, name)
                .also { createdDocument = it }
        } else {
            requestedUri
        }
        val result = try {
            contentResolver.openOutputStream(destination, "w")?.use { output ->
                app.backupService.createBackup(output, request.options) { fraction ->
                    BackupOperationMonitor.backupProgress(fraction)
                    postProgress(BackupNotificationContentFactory.backup((fraction * 100).toInt()))
                }
            } ?: BackupResult(success = false, error = "Cannot open backup destination")
        } catch (e: CancellationException) {
            createdDocument?.let { deleteDocument(it) }
            throw e
        } catch (e: Exception) {
            createdDocument?.let { deleteDocument(it) }
            throw e
        }
        if (result.success) {
            val part = createdDocument
            val name = finalName
            if (part != null && name != null) {
                runCatching { SafBackupTree.finalizeBackup(this, part, name) }
                    .onFailure { error ->
                        deleteDocument(part)
                        return BackupResult(
                            success = false,
                            error = error.message ?: "Cannot finalize the backup document",
                        )
                    }
                createdDocument = null
            }
            persistManualTimestamp(request.source == BackupOperationRequest.Source.Auto)
        } else {
            createdDocument?.let { deleteDocument(it) }
        }
        return result
    }

    private fun deleteDocument(document: Uri) {
        runCatching { DocumentsContract.deleteDocument(contentResolver, document) }
    }

    private suspend fun runRestore(request: BackupOperationRequest): RestoreApplyResult {
        val app = application as MiruApp
        val input = contentResolver.openInputStream(Uri.parse(request.uri))
            ?: return RestoreApplyResult(applied = false, error = "Cannot open backup file")
        return input.use { stream ->
            var lastStage: RestoreStage? = null
            app.restoreService.restoreWithStages(
                input = stream,
                onStage = { progress ->
                    if (progress.stage != lastStage) {
                        lastStage = progress.stage
                        MiruLog.event(
                            "backup",
                            "restore-stage",
                            "stage" to progress.stage.name,
                            "total" to progress.total,
                        )
                    }
                    BackupOperationMonitor.restoreProgress(progress)
                    postProgress(BackupNotificationContentFactory.restore(progress))
                },
            )
        }
    }

    private fun persistManualTimestamp(auto: Boolean) {
        runCatching {
            val app = application as MiruApp
            // Snapshot base: a manual backup must not roll back settings edits
            // that are still inside the debounced save window.
            val current = AppSettings.persistedSnapshot()
            val now = System.currentTimeMillis()
            val updated = if (auto) {
                current.copy(autoBackupLastTimestamp = now)
            } else {
                current.copy(backupLastTimestamp = now)
            }
            app.settingsStore.save(updated)
            AppSettings.applyPersisted(updated)
        }
    }

    private fun postProgress(content: BackupNotificationContent) {
        if (content.percent == lastPercent && content.textRes == lastText) return
        lastPercent = content.percent
        lastText = content.textRes
        runCatching {
            getSystemService(android.app.NotificationManager::class.java)
                ?.notify(
                    BackupNotifications.ForegroundId,
                    BackupNotifications.progress(this, content, cancelable = true),
                )
        }
    }

    private fun buildBackupMessage(result: BackupResult): String =
        if (result.bookCount == result.libraryBookCount) {
            getString(
                R.string.backup_notification_result_same_fmt,
                result.bookCount,
                result.localFileCount,
                result.pluginFileCount,
            )
        } else {
            getString(
                R.string.backup_notification_result_fmt,
                result.bookCount,
                result.libraryBookCount,
                result.localFileCount,
                result.pluginFileCount,
            )
        }

    companion object {
        fun backupIntent(
            context: Context,
            uri: Uri,
            options: BackupOptions,
            source: BackupOperationRequest.Source = BackupOperationRequest.Source.Manual,
        ): Intent = Intent(context, BackupOperationService::class.java).apply {
            action = BackupOperationRequest.ActionBackup
            putExtra(BackupOperationRequest.ExtraUri, uri.toString())
            putExtra(BackupOperationRequest.ExtraIncludeSettings, options.includeSettings)
            putExtra(BackupOperationRequest.ExtraIncludeImages, options.includeImages)
            putExtra(BackupOperationRequest.ExtraIncludePlugins, options.includePlugins)
            putExtra(BackupOperationRequest.ExtraIncludeHistoryOnly, options.includeHistoryOnly)
            putExtra(BackupOperationRequest.ExtraSource, source.name)
        }

        fun restoreIntent(context: Context, uri: Uri): Intent =
            Intent(context, BackupOperationService::class.java).apply {
                action = BackupOperationRequest.ActionRestore
                putExtra(BackupOperationRequest.ExtraUri, uri.toString())
                putExtra(
                    BackupOperationRequest.ExtraSource,
                    BackupOperationRequest.Source.Manual.name,
                )
            }

        fun startBackup(
            context: Context,
            uri: Uri,
            options: BackupOptions,
            source: BackupOperationRequest.Source = BackupOperationRequest.Source.Manual,
        ) {
            startForeground(context, backupIntent(context, uri, options, source))
        }

        fun startRestore(context: Context, uri: Uri) {
            startForeground(context, restoreIntent(context, uri))
        }

        private fun startForeground(context: Context, intent: Intent) {
            context.startForegroundService(intent)
        }

        fun cancelIntent(context: Context): Intent =
            Intent(context, BackupOperationService::class.java).apply {
                action = BackupOperationRequest.ActionCancel
            }
    }
}

private fun BackupOperationRequest.Kind.monitorKind(): BackupOperationKind = when (this) {
    BackupOperationRequest.Kind.Backup -> BackupOperationKind.Backup
    BackupOperationRequest.Kind.Restore -> BackupOperationKind.Restore
}

data class BackupOperationRequest(
    val kind: Kind,
    val uri: String,
    val options: BackupOptions,
    val source: Source,
) {
    enum class Kind { Backup, Restore }
    enum class Source { Manual, Auto }

    companion object {
        const val ActionBackup = "dev.mirutime.app.action.BACKUP"
        const val ActionRestore = "dev.mirutime.app.action.RESTORE"
        const val ActionCancel = "dev.mirutime.app.action.CANCEL_BACKUP"
        const val ExtraUri = "backup_uri"
        const val ExtraIncludeSettings = "backup_include_settings"
        const val ExtraIncludeImages = "backup_include_images"
        const val ExtraIncludePlugins = "backup_include_plugins"
        const val ExtraIncludeHistoryOnly = "backup_include_history_only"
        const val ExtraSource = "backup_source"

        fun from(intent: Intent): BackupOperationRequest? {
            val kind = when (intent.action) {
                ActionBackup -> Kind.Backup
                ActionRestore -> Kind.Restore
                else -> return null
            }
            val uri = intent.getStringExtra(ExtraUri)?.takeIf { it.isNotBlank() } ?: return null
            val source = runCatching {
                Source.valueOf(intent.getStringExtra(ExtraSource) ?: Source.Manual.name)
            }.getOrDefault(Source.Manual)
            return BackupOperationRequest(
                kind = kind,
                uri = uri,
                options = BackupOptions(
                    includeSettings = intent.getBooleanExtra(ExtraIncludeSettings, true),
                    includeImages = intent.getBooleanExtra(ExtraIncludeImages, true),
                    includePlugins = intent.getBooleanExtra(ExtraIncludePlugins, false),
                    includeHistoryOnly = intent.getBooleanExtra(ExtraIncludeHistoryOnly, false),
                ),
                source = source,
            )
        }
    }
}
