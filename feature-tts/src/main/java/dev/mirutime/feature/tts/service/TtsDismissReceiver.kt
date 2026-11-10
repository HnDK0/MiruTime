package dev.mirutime.feature.tts.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.mirutime.core.log.MiruLog
import dev.mirutime.feature.tts.TtsPlaybackIntent

/**
 * Notification dismissal is a user pause, not a headless restart. The receiver
 * keeps the session cursor and asks the service to go away; the controller
 * remains attached to the app graph so "resume" continues where it stopped.
 *
 * A broadcast (instead of startService) matches the dismissible-notification
 * pattern that works on Android 12/16: the system delivers deleteIntent even
 * when the service itself is already leaving the foreground.
 */
class TtsDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TtsPlaybackIntent.ActionDismiss) return
        MiruLog.event("tts", "notification-dismissed")
        TtsServiceHost.current?.pauseFromSystem()
        context.stopService(Intent(context, TtsPlaybackService::class.java))
    }

    companion object {
        private const val REQUEST_DISMISS = 7304

        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_DISMISS,
            Intent(context, TtsDismissReceiver::class.java)
                .setAction(TtsPlaybackIntent.ActionDismiss),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
