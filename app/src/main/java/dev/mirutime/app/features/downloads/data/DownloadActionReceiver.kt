package dev.mirutime.app.features.downloads.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.mirutime.app.MiruApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Notification actions. Work stays in the engine/WorkManager, not here. */
class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? MiruApp ?: return
        val bookId = intent.getLongExtra(DownloadNotificationActions.EXTRA_BOOK_ID, -1L)
        if (bookId <= 0) return
        val engine = app.downloadEngine
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching {
                    when (intent.action) {
                        DownloadNotificationActions.ACTION_PAUSE -> engine.pauseNow(bookId)
                        DownloadNotificationActions.ACTION_RESUME -> engine.resumeNow(bookId)
                        DownloadNotificationActions.ACTION_CANCEL -> engine.cancelNow(bookId)
                        DownloadNotificationActions.ACTION_RETRY -> engine.retryNow(bookId)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
