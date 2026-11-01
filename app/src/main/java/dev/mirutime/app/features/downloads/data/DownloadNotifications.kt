package dev.mirutime.app.features.downloads.data

import android.Manifest
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
import dev.mirutime.app.features.network.cf.CfSolveActivity

object DownloadNotificationActions {
    const val ACTION_PAUSE = "dev.mirutime.app.downloads.PAUSE"
    const val ACTION_RESUME = "dev.mirutime.app.downloads.RESUME"
    const val ACTION_CANCEL = "dev.mirutime.app.downloads.CANCEL"
    const val ACTION_RETRY = "dev.mirutime.app.downloads.RETRY"
    const val EXTRA_BOOK_ID = "bookId"
}

/** Foreground + paused notifications for the queue. Best-effort on Android 13+. */
object DownloadNotifications {
    const val ChannelId = "downloads"
    private const val ForegroundBase = 5100
    private const val StatusBase = 6200
    private const val ContentRequestBase = 5300
    private const val PauseRequestBase = 5301
    private const val ResumeRequestBase = 5302
    private const val CancelRequestBase = 5303
    private const val RetryRequestBase = 5304
    private const val SourceRequestBase = 5305

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                ChannelId,
                context.getString(R.string.dl_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.dl_channel_desc)
                setShowBadge(false)
            },
        )
    }

    fun active(context: Context, progress: DownloadProgress, bookTitle: String): Notification {
        ensureChannel(context)
        val percent = (progress.fraction * 100).toInt().coerceIn(0, 100)
        return base(context)
            .setContentTitle(bookTitle.ifBlank { context.getString(R.string.dl_notification_downloading) })
            .setContentText(
                context.getString(
                    R.string.dl_notification_progress_fmt,
                    progress.completed,
                    progress.total,
                ),
            )
            .setSubText(
                progress.chapterTitle?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.dl_tab_queue),
            )
            .setProgress(100, percent, false)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.dl_action_pause),
                    action(context, DownloadNotificationActions.ACTION_PAUSE, progress.bookId, PauseRequestBase),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.dl_action_cancel),
                    action(context, DownloadNotificationActions.ACTION_CANCEL, progress.bookId, CancelRequestBase),
                ).build(),
            )
            .build()
    }

    fun foregroundInfo(context: Context, progress: DownloadProgress, bookTitle: String): ForegroundInfo {
        val notification = active(context, progress, bookTitle)
        val id = idSlot(progress.bookId) + ForegroundBase
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    fun paused(context: Context, bookId: Long, bookTitle: String, progress: Float) {
        val percent = (progress * 100).toInt().coerceIn(0, 100)
        post(
            context = context,
            bookId = bookId,
            bookTitle = bookTitle,
            state = DownloadState.PAUSED,
            title = context.getString(R.string.dl_notification_paused),
            text = context.getString(R.string.dl_notification_paused_fmt, percent),
            progress = progress,
        )
    }

    fun failed(context: Context, bookId: Long, bookTitle: String, message: String) {
        post(
            context = context,
            bookId = bookId,
            bookTitle = bookTitle,
            state = DownloadState.FAILED,
            title = context.getString(R.string.dl_notification_failed),
            text = context.getString(R.string.dl_notification_failed_fmt, message),
        )
    }

    fun needsSourceAction(
        context: Context,
        bookId: Long,
        bookTitle: String,
        progress: Float,
        sourceUrl: String?,
    ) {
        post(
            context = context,
            bookId = bookId,
            bookTitle = bookTitle,
            state = DownloadState.NEEDS_SOURCE_ACTION,
            title = context.getString(R.string.dl_notification_source_action),
            text = context.getString(R.string.dl_notification_source_action_text),
            progress = progress,
            sourceUrl = sourceUrl,
        )
    }

    fun waitingNetwork(context: Context, bookId: Long, bookTitle: String, progress: Float) {
        post(
            context = context,
            bookId = bookId,
            bookTitle = bookTitle,
            state = DownloadState.WAITING_NETWORK,
            title = context.getString(R.string.dl_notification_waiting_network),
            text = context.getString(R.string.dl_notification_waiting_network_text),
            progress = progress,
        )
    }

    private fun post(
        context: Context,
        bookId: Long,
        bookTitle: String,
        state: DownloadState,
        title: String,
        text: String,
        progress: Float? = null,
        sourceUrl: String? = null,
    ) {
        if (!canPost(context)) return
        ensureChannel(context)
        val safeSource = sourceUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val builder = base(context)
            .setContentTitle(bookTitle.ifBlank { title })
            .setContentText(text)
            .setOngoing(state == DownloadState.PAUSED || state == DownloadState.WAITING_NETWORK)
            .setAutoCancel(state != DownloadState.PAUSED && state != DownloadState.WAITING_NETWORK)
        if (progress != null) {
            builder.setProgress(100, (progress * 100).toInt().coerceIn(0, 100), false)
        }
        DownloadNotificationPolicy.actionsFor(state, safeSource != null).forEach { action ->
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(action.labelRes()),
                    pendingIntent(context, action, bookId, safeSource),
                ).build(),
            )
        }
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(statusId(bookId), builder.build())
        }
    }

    private fun pendingIntent(
        context: Context,
        action: DownloadNotificationAction,
        bookId: Long,
        sourceUrl: String?,
    ): PendingIntent = when (action) {
        DownloadNotificationAction.OPEN_SOURCE -> PendingIntent.getActivity(
            context,
            requestCode(SourceRequestBase, bookId),
            if (sourceUrl != null) {
                // Solve the challenge in-app, store the clearance and re-arm the
                // parked book. MainActivity is only the no-URL fallback.
                Intent(context, CfSolveActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(CfSolveActivity.EXTRA_BOOK_ID, bookId)
                    putExtra(CfSolveActivity.EXTRA_SOURCE_URL, sourceUrl)
                }
            } else {
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        DownloadNotificationAction.RETRY ->
            action(context, DownloadNotificationActions.ACTION_RETRY, bookId, RetryRequestBase)
        DownloadNotificationAction.RESUME ->
            action(context, DownloadNotificationActions.ACTION_RESUME, bookId, ResumeRequestBase)
        DownloadNotificationAction.PAUSE ->
            action(context, DownloadNotificationActions.ACTION_PAUSE, bookId, PauseRequestBase)
        DownloadNotificationAction.CANCEL ->
            action(context, DownloadNotificationActions.ACTION_CANCEL, bookId, CancelRequestBase)
    }

    private fun DownloadNotificationAction.labelRes(): Int = when (this) {
        DownloadNotificationAction.RETRY -> R.string.dl_action_retry
        DownloadNotificationAction.OPEN_SOURCE -> R.string.dl_action_open_source
        DownloadNotificationAction.RESUME -> R.string.dl_action_resume
        DownloadNotificationAction.PAUSE -> R.string.dl_action_pause
        DownloadNotificationAction.CANCEL -> R.string.dl_action_cancel
    }

    fun clearStatus(context: Context, bookId: Long) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(statusId(bookId))
        }
    }

    private fun base(context: Context): Notification.Builder =
        Notification.Builder(context, ChannelId)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    requestCode(ContentRequestBase, 0L),
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOnlyAlertOnce(true)
            .setShowWhen(false)

    private fun action(
        context: Context,
        action: String,
        bookId: Long,
        requestCodeBase: Int,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode(requestCodeBase, bookId),
        Intent(context, DownloadActionReceiver::class.java).apply {
            this.action = action
            putExtra(DownloadNotificationActions.EXTRA_BOOK_ID, bookId)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun statusId(bookId: Long): Int = StatusBase + idSlot(bookId)

    private fun idSlot(bookId: Long): Int = bookId.hashCode().and(0x3FF)

    private fun requestCode(base: Int, bookId: Long): Int =
        (base shl 16) or (bookId.hashCode().and(0xFFFF))

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
