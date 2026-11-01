package dev.mirutime.app.features.video

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import dev.mirutime.app.MainActivity
import dev.mirutime.app.MiruApp
import dev.mirutime.app.R
import dev.mirutime.app.features.video.data.VideoDownloadActionReceiver
import dev.mirutime.app.features.video.data.VideoDownloadActions

/**
 * Foreground host for the media3 download manager. Downloads run in the
 * service; the app UI observes the same singleton manager.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class VideoDownloadService : DownloadService(
    FOREGROUND_ID,
    DownloadService.DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.video_dl_channel_name,
    R.string.video_dl_channel_desc,
) {
    override fun getDownloadManager() =
        (application as MiruApp).videoDownloadManager.downloadManager

    // Downloads resume when the user returns; no job-scheduler re-arm is
    // required for this pass.
    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(
        downloads: List<Download>,
        notMetRequirements: Int,
    ): Notification {
        val active = downloads.filter { download ->
            download.state == Download.STATE_QUEUED ||
                download.state == Download.STATE_DOWNLOADING ||
                download.state == Download.STATE_RESTARTING ||
                download.state == Download.STATE_STOPPED
        }
        val percentages = active.mapNotNull { download ->
            download.percentDownloaded.takeIf { it >= 0f }
        }
        val percent = percentages.takeIf { it.isNotEmpty() }
            ?.average()
            ?.toInt()
            ?.coerceIn(0, 100)
            ?: 0
        val unknown = percentages.isEmpty() &&
            active.any { it.bytesDownloaded > 0L }

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(getString(R.string.video_dl_notification_title))
            .setContentText(
                getString(R.string.video_dl_notification_text_fmt, active.size),
            )
            .setContentIntent(contentIntent())
            .setProgress(100, percent, unknown)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.dl_action_pause),
                    action(VideoDownloadActions.ACTION_PAUSE),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.dl_action_resume),
                    action(VideoDownloadActions.ACTION_RESUME),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.dl_action_cancel),
                    action(VideoDownloadActions.ACTION_CANCEL),
                ).build(),
            )
            .build()
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(action: String): PendingIntent = PendingIntent.getBroadcast(
        this,
        action.hashCode(),
        Intent(this, VideoDownloadActionReceiver::class.java).apply { this.action = action },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    @androidx.annotation.OptIn(UnstableApi::class)
    companion object {
        private const val CHANNEL_ID = "video_downloads"
        private const val FOREGROUND_ID = 7100

        /** Starts the dataSync foreground service and adds one request. */
        fun sendAddDownload(context: Context, request: DownloadRequest, foreground: Boolean) {
            DownloadService.sendAddDownload(
                context,
                VideoDownloadService::class.java,
                request,
                foreground,
            )
        }
    }
}
