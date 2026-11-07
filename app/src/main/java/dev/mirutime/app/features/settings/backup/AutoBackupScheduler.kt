package dev.mirutime.app.features.settings.backup

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.mirutime.app.MiruApp
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.PersistedSettings
import java.util.concurrent.TimeUnit

enum class AutoBackupStatus {
    Disabled,
    PermissionLost,
    Scheduled,
}

/**
 * Owns the unique auto-backup periodic work. Safe to call after every settings
 * save: an unchanged schedule is not re-enqueued.
 */
object AutoBackupScheduler {
    const val UniquePeriodicWork = "mirutime-auto-backup"
    const val UniqueRunNowWork = "mirutime-auto-backup-now"

    private val lock = Any()
    private var lastAppliedKey: String? = null

    fun loadSettings(context: Context): PersistedSettings =
        (context.applicationContext as MiruApp).settingsStore.load()

    fun sync(context: Context, settings: PersistedSettings): AutoBackupStatus {
        val action = AutoBackupPolicy.decide(
            autoBackup = settings.autoBackup,
            isTreeUri = SafBackupTree.isTreeUri(settings.backupDirectory),
            permissionGranted = SafBackupTree.hasPersistedPermission(
                context,
                settings.backupDirectory,
            ),
        )
        return when (action) {
            AutoBackupAction.Cancel -> {
                cancel(context)
                AutoBackupStatus.Disabled
            }
            AutoBackupAction.DisablePermissionLost -> {
                // The grant may be partially revoked; clean `.part` leftovers
                // best-effort before the schedule is removed.
                settings.backupDirectory
                    .takeIf { SafBackupTree.isTreeUri(it) }
                    ?.let { raw -> runCatching { SafBackupTree.cleanupParts(context, Uri.parse(raw)) } }
                disablePermissionLost(context)
                AutoBackupStatus.PermissionLost
            }
            AutoBackupAction.Schedule -> {
                schedule(context, settings)
                AutoBackupStatus.Scheduled
            }
        }
    }

    fun enable(context: Context) {
        val updated = updateAutoBackupSetting(context, enabled = true)
        sync(context, updated)
    }

    fun disable(context: Context) {
        updateAutoBackupSetting(context, enabled = false)
        cancel(context)
    }

    /**
     * Stores a SAF tree grant chosen by the settings UI and immediately applies
     * the same schedule decision as a settings save. The current in-memory
     * snapshot is the base so folder selection does not roll back edits that
     * are still inside the persistence debounce window.
     */
    fun setBackupDirectory(context: Context, treeUri: Uri): AutoBackupStatus {
        val app = context.applicationContext as MiruApp
        val updated = AppSettings.persistedSnapshot()
            .copy(backupDirectory = treeUri.toString())
        app.settingsStore.save(updated)
        AppSettings.applyPersisted(updated)
        lastAppliedKey = null
        return sync(context, updated)
    }

    fun reschedule(context: Context): AutoBackupStatus =
        sync(context, AppSettings.persistedSnapshot())

    fun runNow(context: Context, settings: PersistedSettings): AutoBackupStatus {
        val action = AutoBackupPolicy.decide(
            autoBackup = true,
            isTreeUri = SafBackupTree.isTreeUri(settings.backupDirectory),
            permissionGranted = SafBackupTree.hasPersistedPermission(
                context,
                settings.backupDirectory,
            ),
        )
        if (action == AutoBackupAction.DisablePermissionLost) {
            disablePermissionLost(context)
            return AutoBackupStatus.PermissionLost
        }
        if (action == AutoBackupAction.Cancel) return AutoBackupStatus.Disabled
        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setConstraints(constraints())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BackupSchedule.BackoffMinutes,
                TimeUnit.MINUTES,
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UniqueRunNowWork,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        return AutoBackupStatus.Scheduled
    }

    /** Called by the Worker when the persisted tree grant disappeared. */
    fun disablePermissionLost(context: Context) {
        updateAutoBackupSetting(context, enabled = false)
        cancel(context)
        BackupNotifications.notifyAutoDisabled(context)
    }

    fun cancel(context: Context) {
        lastAppliedKey = null
        runCatching {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(UniquePeriodicWork)
                // A queued/running "Run now" belongs to the same feature and
                // must not survive an explicit disable.
                cancelUniqueWork(UniqueRunNowWork)
            }
        }
    }

    private fun schedule(context: Context, settings: PersistedSettings) {
        val spec = BackupSchedule.spec(settings)
        val key = "${settings.backupDirectory}|${spec.intervalMinutes}|${spec.flexMinutes}|" +
            "${settings.backupMaxCount}|${settings.backupIncludeImages}|" +
            "${settings.backupIncludeSettings}|${settings.backupIncludePlugins}|" +
            "${settings.backupIncludeHistoryOnly}"
        synchronized(lock) {
            if (key == lastAppliedKey) return
            lastAppliedKey = key
        }
        val request = buildPeriodicRequest(spec)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UniquePeriodicWork,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    internal fun buildPeriodicRequest(spec: BackupScheduleSpec): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<AutoBackupWorker>(
            spec.intervalMinutes,
            TimeUnit.MINUTES,
            spec.flexMinutes,
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BackupSchedule.BackoffMinutes,
                TimeUnit.MINUTES,
            )
            .build()

    private fun constraints(): Constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .setRequiresCharging(false)
        .build()

    /**
     * Base on the in-memory snapshot so toggling auto backup never rolls back
     * edits still inside the 300 ms persistence debounce window.
     */
    private fun updateAutoBackupSetting(
        context: Context,
        enabled: Boolean,
    ): PersistedSettings {
        val app = context.applicationContext as MiruApp
        val current = AppSettings.persistedSnapshot()
        if (current.autoBackup == enabled) return current
        val updated = current.copy(autoBackup = enabled)
        app.settingsStore.save(updated)
        AppSettings.applyPersisted(updated)
        lastAppliedKey = null
        return updated
    }
}
