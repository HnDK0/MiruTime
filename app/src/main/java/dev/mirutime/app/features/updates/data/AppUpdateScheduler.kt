package dev.mirutime.app.features.updates.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import androidx.work.BackoffPolicy
import dev.mirutime.app.MainActivity
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.mirutime.app.R
import dev.mirutime.app.features.settings.data.AppLanguage
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.PersistedSettings
import java.util.concurrent.TimeUnit

/** Daily app-release check promised by Settings → Updates. */
object AppUpdateScheduler {
    private const val UniqueWork = "mirutime-app-update"
    private const val IntervalHours = 24L
    private const val BackoffMinutes = 60L

    fun sync(context: Context, settings: PersistedSettings) {
        val manager = try {
            WorkManager.getInstance(context)
        } catch (_: Exception) {
            return
        }
        if (!settings.appUpdateCheck) {
            runCatching { manager.cancelUniqueWork(UniqueWork) }
            return
        }
        val request = PeriodicWorkRequestBuilder<AppUpdateWorker>(
            IntervalHours,
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BackoffMinutes,
                TimeUnit.MINUTES,
            )
            .build()
        runCatching {
            manager.enqueueUniquePeriodicWork(
                UniqueWork,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }

    fun notifyAvailable(
        context: Context,
        release: AppUpdateResult.Available,
        currentVersion: String,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val localized = AppLanguage.wrap(context, AppSettings.appLanguage)
        val manager = localized.getSystemService(NotificationManager::class.java) ?: return
        val channelId = "app_updates"
        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                localized.getString(R.string.st_updates_app_notif_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = localized.getString(R.string.st_updates_app_notif_channel_desc)
            },
        )
        val text = localized.getString(R.string.st_updates_app_notif_fmt, release.version)
        val notes = appUpdateReleaseNotes(release.body)
        val openUpdates = PendingIntent.getActivity(
            context,
            7402,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_UPDATES, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openRelease = PendingIntent.getActivity(
            context,
            7403,
            Intent(Intent.ACTION_VIEW, Uri.parse(release.url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = Notification.Builder(localized, channelId)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setLargeIcon(Icon.createWithResource(localized, R.mipmap.ic_launcher))
            .setContentTitle(localized.getString(R.string.st_updates_app_notif_title))
            .setContentText(text)
            .setSubText(
                localized.getString(
                    R.string.st_updates_app_notif_installed_fmt,
                    currentVersion,
                ),
            )
            .setStyle(Notification.BigTextStyle().bigText(notes.ifBlank { text }))
            .setContentIntent(openUpdates)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(localized, R.drawable.ic_stat_download),
                    localized.getString(R.string.st_updates_app_notif_open_release),
                    openRelease,
                ).build(),
            )
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(7401, notification) }
    }
}

/** GitHub release notes are markdown; the shade shows their plain text. */
internal fun appUpdateReleaseNotes(body: String?, maxChars: Int = 800): String =
    body.orEmpty()
        .lineSequence()
        .map { it.trim().replace(Regex("^[#>*\\-\\s]+"), "") }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
        .take(maxChars)
        .trim()
