package dev.mirutime.app.features.video.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.mirutime.app.MiruApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object VideoDownloadActions {
    const val ACTION_PAUSE = "dev.mirutime.app.video.downloads.PAUSE"
    const val ACTION_RESUME = "dev.mirutime.app.video.downloads.RESUME"
    const val ACTION_CANCEL = "dev.mirutime.app.video.downloads.CANCEL"
}

/** Notification actions for the video download service. */
class VideoDownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? MiruApp ?: return
        val action = intent.action ?: return
        val pending = goAsync()
        // Manager creation and every manager call belong to the main thread.
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try {
                val manager = app.videoDownloadManager
                when (action) {
                    VideoDownloadActions.ACTION_PAUSE -> manager.pauseAll()
                    VideoDownloadActions.ACTION_RESUME -> manager.resumeAll()
                    VideoDownloadActions.ACTION_CANCEL -> manager.removeActive()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
