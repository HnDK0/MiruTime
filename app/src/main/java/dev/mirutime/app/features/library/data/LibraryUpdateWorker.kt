package dev.mirutime.app.features.library.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.mirutime.app.MiruApp
import dev.mirutime.core.log.MiruLog
import dev.mirutime.app.features.settings.data.AppSettings
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the existing [LibraryUpdateService] in the background. Results are one
 * aggregate notification (new chapters or errors), never per book.
 */
class LibraryUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? MiruApp ?: return Result.failure()
        // A disabled setting must win over work that was already queued.
        if (!AppSettings.persistedSnapshot().libraryAutoUpdate) return Result.success()

        return withContext(Dispatchers.IO) {
            try {
                val service = LibraryUpdateService(
                    provider = app.libraryBookRefreshPort,
                    library = app.libraryRepository,
                )
                // Never let an unexpected exception look like a successful run.
                val summary = service.checkAll()
                LibraryUpdateScheduler.recordRun(summary)
                AppSettings.libraryLastRunAt = System.currentTimeMillis()
                MiruLog.i(
                    "updates",
                    "library run checked=${summary.checked} updated=${summary.updated} " +
                        "failed=${summary.failed}",
                )
                val updated = app.libraryRepository.booksWithUpdates()
                LibraryUpdateNotifications.notifyResult(
                    context = app,
                    newChapters = updated.sumOf { it.newChapters },
                    checked = summary.checked,
                    updatedBooks = summary.updated,
                    failedBooks = summary.failed,
                    lines = updated.map { it.title to it.newChapters },
                )
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LibraryUpdateScheduler.recordFailure(e.message)
                // Never log Ktor's raw message (it carries the full URL).
                MiruLog.w(
                    "updates",
                    "library run failed host=${updateErrorHost(e.message)} " +
                        "class=${e::class.simpleName}",
                )
                if (runAttemptCount < MAX_ATTEMPTS - 1) {
                    Result.retry()
                } else {
                    LibraryUpdateNotifications.notifyFailure(app, null)
                    Result.failure()
                }
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
