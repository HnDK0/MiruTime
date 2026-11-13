package dev.mirutime.app.features.settings.backup

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ForegroundInfo
import dev.mirutime.app.MainActivity
import dev.mirutime.app.R
import dev.mirutime.app.features.settings.data.AppLanguage
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.RestoreProgress
import dev.mirutime.app.features.settings.data.RestoreStage
import kotlin.math.roundToInt

data class BackupNotificationContent(
    val titleRes: Int,
    val textRes: Int,
    val percent: Int,
)

/** Pure stage→notification mapping, unit-tested without an Android runtime. */
object BackupNotificationContentFactory {
    fun backup(percent: Int): BackupNotificationContent = BackupNotificationContent(
        titleRes = R.string.backup_notification_backup_title,
        textRes = R.string.backup_stage_backing_up,
        percent = percent.coerceIn(0, 100),
    )

    fun restore(progress: RestoreProgress): BackupNotificationContent {
        val (start, end) = when (progress.stage) {
            RestoreStage.VALIDATING -> 0 to 5
            RestoreStage.EXTRACTING -> 5 to 35
            RestoreStage.DATABASE -> 35 to 70
            RestoreStage.FILES -> 70 to 90
            RestoreStage.SETTINGS -> 90 to 97
            RestoreStage.CACHE -> 97 to 100
        }
        val fraction = progress.fraction.coerceIn(0f, 1f)
        val percent = (start + (end - start) * fraction).roundToInt().coerceIn(0, 100)
        val textRes = when (progress.stage) {
            RestoreStage.VALIDATING -> R.string.backup_stage_validating
            RestoreStage.EXTRACTING -> R.string.backup_stage_extracting
            RestoreStage.DATABASE -> R.string.backup_stage_database
            RestoreStage.FILES -> R.string.backup_stage_files
            RestoreStage.SETTINGS -> R.string.backup_stage_settings
            RestoreStage.CACHE -> R.string.backup_stage_cache
        }
        return BackupNotificationContent(
            titleRes = R.string.backup_notification_restore_title,
            textRes = textRes,
            percent = percent,
        )
    }
}

/**
 * One notification channel for manual service work and auto-backup workers.
 * Notifications are best-effort: Android 13+ can deny them while the
 * foreground operation still runs.
 */
object BackupNotifications {
    const val ChannelId = "backup_operations"
    const val ForegroundId = 4201
    const val ResultId = 4202
    const val WorkerProgressId = 4206
    private const val ContentRequestCode = 4203
    private const val CancelRequestCode = 4204
    const val PermissionRequestCode = 4205

    private fun localized(context: Context): Context =
        AppLanguage.wrap(context, AppSettings.appLanguage)

    fun ensureChannel(context: Context) {
        val ui = localized(context)
        val manager = ui.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                ChannelId,
                ui.getString(R.string.backup_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = ui.getString(R.string.backup_channel_desc)
                setShowBadge(false)
            },
        )
    }

    fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Call from an Activity when the user enables backups; denial is harmless. */
    fun requestPostNotifications(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !canPostNotifications(activity)
        ) {
            activity.requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                PermissionRequestCode,
            )
        }
    }

    fun progress(
        context: Context,
        content: BackupNotificationContent,
        cancelable: Boolean,
    ): Notification {
        ensureChannel(context)
        val ui = localized(context)
        val builder = Notification.Builder(context, ChannelId)
            .setSmallIcon(R.drawable.ic_stat_backup)
            .setContentTitle(ui.getString(content.titleRes))
            .setContentText(ui.getString(content.textRes))
            .setSubText(ui.getString(R.string.backup_notification_percent_fmt, content.percent))
            .setProgress(100, content.percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent(context))
        if (cancelable) {
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    ui.getString(R.string.backup_notification_cancel),
                    cancelIntent(context),
                ).build(),
            )
        }
        return builder.build()
    }

    fun foregroundInfo(
        context: Context,
        content: BackupNotificationContent,
        cancelable: Boolean = true,
    ): ForegroundInfo {
        val notification = progress(context, content, cancelable)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                ForegroundId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(ForegroundId, notification)
        }
    }

    fun result(
        context: Context,
        success: Boolean,
        restore: Boolean,
        message: String,
    ): Notification {
        ensureChannel(context)
        val ui = localized(context)
        val title = when {
            success && restore -> R.string.backup_notification_restore_done_title
            success -> R.string.backup_notification_done_title
            restore -> R.string.backup_notification_restore_failed_title
            else -> R.string.backup_notification_failed_title
        }
        return Notification.Builder(context, ChannelId)
            .setSmallIcon(R.drawable.ic_stat_backup)
            .setContentTitle(ui.getString(title))
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build()
    }

    fun notifyResult(
        context: Context,
        success: Boolean,
        restore: Boolean,
        message: String,
    ) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.notify(ResultId, result(context, success, restore, message))
        }
    }

    /**
     * Normal-notification fallback for workers that may not enter the
     * foreground (Android 12+ background start restriction). It is best-effort
     * and uses its own id so it can never clear a manual FGS progress
     * notification that shares the channel.
     */
    fun notifyProgress(context: Context, content: BackupNotificationContent) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.notify(
                WorkerProgressId,
                progress(context, content, cancelable = false),
            )
        }
    }

    fun clearProgress(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(WorkerProgressId)
        }
    }

    fun notifyBusy(context: Context, restore: Boolean) {
        notifyResult(
            context,
            success = false,
            restore = restore,
            message = localized(context).getString(R.string.backup_notification_busy),
        )
    }

    fun notifyAutoDisabled(context: Context) {
        notifyResult(
            context,
            success = false,
            restore = false,
            message = localized(context).getString(R.string.backup_notification_auto_disabled),
        )
    }

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            ContentRequestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelIntent(context: Context): PendingIntent {
        val intent = BackupOperationService.cancelIntent(context)
        return PendingIntent.getService(
            context,
            CancelRequestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
