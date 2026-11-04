package dev.mirutime.feature.tts.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import dev.mirutime.core.log.MiruLog
import dev.mirutime.feature.tts.TtsStatus

/**
 * Routes hardware/Bluetooth media-button events to the live TTS session. The
 * platform delivers ACTION_MEDIA_BUTTON here (through the MediaSession's media
 * button receiver); acting on the shared controller keeps the reader, mini
 * player, notification and headset on ONE session (FR-044).
 */
class TtsMediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val key = intent.mediaButtonKey() ?: return
        // Only the down edge: the up edge would double-fire the same press.
        if (key.action != KeyEvent.ACTION_DOWN) return
        val controller = TtsServiceHost.current ?: return
        when (key.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> controller.resume()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> controller.pause()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                if (controller.state.value.status == TtsStatus.Playing) {
                    controller.pause()
                } else {
                    controller.resume()
                }
            KeyEvent.KEYCODE_MEDIA_STOP -> controller.stop()
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
            -> controller.skipNextChapter()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
            -> controller.skipPreviousChapter()
            else -> return
        }
        MiruLog.event("tts", "media-button", "code" to key.keyCode)
    }

    companion object {
        private const val REQUEST_MEDIA_BUTTON = 7303

        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_MEDIA_BUTTON,
            Intent(context, TtsMediaButtonReceiver::class.java)
                .setAction(Intent.ACTION_MEDIA_BUTTON),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

internal fun Intent.mediaButtonKey(): KeyEvent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_KEY_EVENT)
    }
