package dev.mirutime.app.features.library.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
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

    fun notifyResult(
        context: Context,
        newChapters: Int,
        checked: Int,
        updatedBooks: Int,
        failedBooks: Int,
        lines: List<Pair<String, Int>>,
    ) {
        val localized = AppLanguage.wrap(context, AppSettings.appLanguage)
        if (newChapters > 0) {
            val title = localized.getString(R.string.st_updates_notif_new_title)
            val text = if (failedBooks > 0) {
                localized.getString(
                    R.string.st_updates_notif_new_failed_fmt,
                    newChapters,
                    updatedBooks,
                    failedBooks,
                )
            } else {
                localized.getString(R.string.st_updates_notif_new_fmt, newChapters, updatedBooks)
            }
            postSuccess(
                context = context,
                localized = localized,
                title = title,
                text = text,
                checked = checked,
                updatedBooks = updatedBooks,
                failedBooks = failedBooks,
                lines = lines,
            )
        } else if (failedBooks > 0) {
            postFailure(
                context = context,
                title = localized.getString(R.string.st_updates_notif_failed_title),
                text = localized.getString(R.string.st_updates_notif_failed_fmt, failedBooks),
            )
        }
    }

    fun notifyFailure(context: Context, message: String?) {
        val localized = AppLanguage.wrap(context, AppSettings.appLanguage)
        val text = message?.takeIf { it.isNotBlank() }
            ?: localized.getString(R.string.st_updates_notif_failed_generic)
        postFailure(
            context = context,
            title = localized.getString(R.string.st_updates_notif_failed_title),
            text = text,
        )
    }

    private fun postSuccess(
        context: Context,
        localized: Context,
        title: String,
        text: String,
        checked: Int,
        updatedBooks: Int,
        failedBooks: Int,
        lines: List<Pair<String, Int>>,
    ) {
        if (!canPost(context)) return
        ensureChannel(context)
        val style = Notification.InboxStyle().setBigContentTitle(title)
        libraryUpdateLines(
            books = lines,
            format = { bookTitle, count ->
                localized.getString(R.string.st_updates_notif_book_line_fmt, bookTitle, count)
            },
            more = { remaining ->
                localized.getString(R.string.st_updates_notif_more_fmt, remaining)
            },
        ).forEach(style::addLine)
        post(
            context = context,
            title = title,
            text = text,
            style = style,
            subText = localized.getString(
                R.string.st_updates_notif_summary_fmt,
                checked,
                updatedBooks,
                failedBooks,
            ),
        )
    }

    private fun postFailure(context: Context, title: String, text: String) {
        post(context = context, title = title, text = text, style = null, subText = null)
    }

    private fun post(
        context: Context,
        title: String,
        text: String,
        style: Notification.Style?,
        subText: String?,
    ) {
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
        val builder = Notification.Builder(context, ChannelId)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setLargeIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openUpdates)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
        if (subText != null) builder.setSubText(subText)
        if (style != null) builder.setStyle(style)
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(NotificationId, builder.build())
        }
    }

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}

/**
 * Inbox lines for the library notification. Keeps the first [limit] books and
 * collapses the rest into one "and N more" line.
 */
internal fun libraryUpdateLines(
    books: List<Pair<String, Int>>,
    limit: Int = 5,
    format: (String, Int) -> String,
    more: (Int) -> String,
): List<String> {
    val valid = books.filter { it.first.isNotBlank() && it.second > 0 }
    val shown = valid.take(limit).map { (title, count) -> format(title, count) }
    val remaining = valid.size - shown.size
    return if (remaining > 0) shown + more(remaining) else shown
}
