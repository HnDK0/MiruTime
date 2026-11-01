package dev.mirutime.app.features.library.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dev.mirutime.app.MainActivity
import dev.mirutime.app.R
import dev.mirutime.app.features.settings.data.AppLanguage
import dev.mirutime.app.features.settings.data.AppSettings

/**
 * One result notification per update run, never one per book. Errors are also
 * surfaced, because a silent failure is indistinguishable from "no new
 * chapters".
 */
object LibraryUpdateNotifications {
    const val ChannelId = "library_updates"
    private const val NotificationId = 7301
    private const val RequestCode = 7302

    fun ensureChannel(context: Context) {
        val localized = AppLanguage.wrap(context, AppSettings.appLanguage)
        val manager = localized.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                ChannelId,
                localized.getString(R.string.st_updates_notif_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = localized.getString(R.string.st_updates_notif_channel_desc)
                setShowBadge(false)
            },
        )
    }

    fun notifyResult(context: Context, newChapters: Int, updatedBooks: Int, failedBooks: Int) {
        val localized = AppLanguage.wrap(context, AppSettings.appLanguage)
        val title: String
        val text: String
        if (newChapters > 0) {
            title = localized.getString(R.string.st_updates_notif_new_title)
            text = if (failedBooks > 0) {
                localized.getString(
                    R.string.st_updates_notif_new_failed_fmt,
                    newChapters,
                    updatedBooks,
                    failedBooks,
                )
            } else {
                localized.getString(R.string.st_updates_notif_new_fmt, newChapters, updatedBooks)
            }
        } else if (failedBooks > 0) {
            title = localized.getString(R.string.st_updates_notif_failed_title)
            text = localized.getString(R.string.st_updates_notif_failed_fmt, failedBooks)
        } else {
            return
        }
        post(context, title, text)
    }

    fun notifyFailure(context: Context, message: String?) {
        val localized = AppLanguage.wrap(context, AppSettings.appLanguage)
        val text = message?.takeIf { it.isNotBlank() }
            ?: localized.getString(R.string.st_updates_notif_failed_generic)
        post(context, localized.getString(R.string.st_updates_notif_failed_title), text)
    }

    private fun post(context: Context, title: String, text: String) {
        if (!canPost(context)) return
        ensureChannel(context)
        val openUpdates = PendingIntent.getActivity(
            context,
            RequestCode,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_UPDATES, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = Notification.Builder(context, ChannelId)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openUpdates)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.notify(NotificationId, notification)
        }
    }

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
