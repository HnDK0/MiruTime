package dev.mirutime.app.features.updates.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.mirutime.app.BuildConfig
import dev.mirutime.app.MiruApp
import dev.mirutime.app.features.settings.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One daily check; only a genuinely available release produces a notification. */
class AppUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!AppSettings.persistedSnapshot().appUpdateCheck) return Result.success()
        return withContext(Dispatchers.IO) {
            val graph = (applicationContext as MiruApp).pluginGraph
            val fetch = AppReleaseFetch { url, headers, timeoutMs ->
                graph.fetchRemote(url, headers, timeoutMs, followRedirects = true)
            }
            when (val result = AppUpdateChecker(fetch).check(BuildConfig.VERSION_NAME)) {
                is AppUpdateResult.Available ->
                    AppUpdateScheduler.notifyAvailable(applicationContext, result.version, result.url)
                // Up-to-date, no releases and errors are not worth a daily
                // notification. Errors are deliberately never reported as
                // success.
                else -> Unit
            }
            Result.success()
        }
    }
}
