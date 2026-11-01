package dev.mirutime.app.features.translation.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.mirutime.app.MiruApp
import dev.mirutime.core.log.MiruLog
import kotlin.coroutines.cancellation.CancellationException

/**
 * Unique translation pass per book. It only touches PENDING rows whose body is
 * already downloaded; a not-yet-downloaded chapter stays queued and the next
 * download pass schedules this worker again. All state is Room, so process
 * death loses nothing but the current provider call.
 */
class TranslationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? MiruApp ?: return Result.failure()
        val bookId = inputData.getLong(KEY_BOOK_ID, -1L)
        if (bookId <= 0) return Result.failure()
        val attempt = runAttemptCount + 1
        return try {
            val outcome = app.downloadTranslationRunner.run(bookId, attempt)
            when {
                outcome.shouldRetry && attempt < DownloadTranslationRunner.MAX_ATTEMPTS -> {
                    MiruLog.i(
                        "translation",
                        "download-translation-work bookId=$bookId attempt=$attempt result=retry " +
                            "retryable=${outcome.retryable} translated=${outcome.translated}",
                    )
                    Result.retry()
                }
                else -> {
                    MiruLog.i(
                        "translation",
                        "download-translation-work bookId=$bookId attempt=$attempt result=done " +
                            "translated=${outcome.translated} cached=${outcome.cached} " +
                            "failed=${outcome.failed} waiting=${outcome.waiting}",
                    )
                    Result.success()
                }
            }
        } catch (cancellation: CancellationException) {
            MiruLog.i(
                "translation",
                "download-translation-work bookId=$bookId attempt=$attempt result=cancelled",
            )
            throw cancellation
        } catch (error: Throwable) {
            MiruLog.w(
                "translation",
                "download-translation-work bookId=$bookId attempt=$attempt " +
                    "result=failure errorClass=${error::class.simpleName}",
            )
            if (attempt < DownloadTranslationRunner.MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_BOOK_ID = "bookId"
    }
}
