package dev.mirutime.app.features.downloads.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.mirutime.app.MiruApp
import dev.mirutime.app.R
import dev.mirutime.core.log.MiruLog
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs one book's queue. Progress is written to Room while the worker runs;
 * WorkManager only decides *when* work runs and keeps it alive as a dataSync
 * foreground service while possible.
 *
 * Retry contract: transport failures park rows as WAITING_NETWORK and use the
 * bounded WorkManager backoff. Auth/CF/429 marks the whole book
 * NEEDS_SOURCE_ACTION and never consumes a retry. Parse/not-found rows are
 * terminal FAILED. On the final attempt no QUEUED/RUNNING row is left behind:
 * transport leftovers become WAITING_NETWORK, real terminal ones become FAILED.
 */
class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? MiruApp ?: return Result.failure()
        val engine = app.downloadEngine
        val bookId = inputData.getLong(KEY_BOOK_ID, -1L)
        if (bookId <= 0) return Result.failure()
        val title = engine.bookTitle(bookId).orEmpty()
        val progress = engine.progressOf(bookId, null)
        var foreground = try {
            setForeground(DownloadNotifications.foregroundInfo(app, progress, title))
            true
        } catch (_: Exception) {
            // Android 12+ can reject a background FGS start and Android 13 can
            // hide notifications; the queue still runs from Room/WorkManager.
            false
        }
        MiruLog.i(
            "downloads",
            "work-start bookId=$bookId attempt=${runAttemptCount + 1} " +
                "foreground=$foreground total=${progress.total}",
        )

        var lastPercent = (progress.fraction * 100).toInt()
        return try {
            val outcome = engine.run(bookId, attempt = runAttemptCount + 1) { update ->
                val percent = (update.fraction * 100).toInt()
                if (foreground && percent != lastPercent) {
                    lastPercent = percent
                    foreground = try {
                        setForeground(DownloadNotifications.foregroundInfo(app, update, title))
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
            }
            // Bodies written by this pass make PENDING translation intents
            // runnable; the translation worker keeps its own unique chain.
            runCatching { app.schedulePendingTranslations(bookId) }
            when (DownloadWorkPolicy.decide(outcome, runAttemptCount + 1)) {
                DownloadWorkDecision.RETRY -> {
                    MiruLog.i(
                        "downloads",
                        "work-result bookId=$bookId attempt=${runAttemptCount + 1} " +
                            "result=retry retryable=${outcome.retryable} " +
                            "failed=${outcome.failed} completed=${outcome.completed}",
                    )
                    Result.retry()
                }
                DownloadWorkDecision.EXHAUSTED -> {
                    when {
                        outcome.needsSourceAction -> notifySourceAction(app, engine, bookId, title)
                        outcome.waitingNetwork -> {
                            // No attempt remains, but this is not terminal: keep
                            // the queue explicitly waiting for the user/network.
                            engine.parkWaitingNetwork(bookId, NETWORK_WAITING)
                            DownloadNotifications.waitingNetwork(app, bookId, title, progressOf(engine, bookId))
                        }
                        else -> {
                            // Real terminal leftovers (5xx/parse) close as FAILED
                            // so no QUEUED/RUNNING row is left without work.
                            engine.failPending(bookId, RETRY_EXHAUSTED)
                            DownloadNotifications.failed(app, bookId, title, RETRY_EXHAUSTED)
                        }
                    }
                    MiruLog.i(
                        "downloads",
                        "work-result bookId=$bookId attempt=${runAttemptCount + 1} " +
                            "result=exhausted failed=${outcome.failed} " +
                            "retryable=${outcome.retryable} completed=${outcome.completed} " +
                            "needsSourceAction=${outcome.needsSourceAction} " +
                            "waitingNetwork=${outcome.waitingNetwork}",
                    )
                    Result.success()
                }
                DownloadWorkDecision.DONE -> {
                    val resultLabel = if (outcome.failed > 0) "failed-chapters" else "success"
                    when {
                        outcome.needsSourceAction -> notifySourceAction(app, engine, bookId, title)
                        outcome.waitingNetwork ->
                            DownloadNotifications.waitingNetwork(app, bookId, title, progressOf(engine, bookId))
                        outcome.failed > 0 -> DownloadNotifications.failed(
                            app,
                            bookId,
                            title,
                            app.getString(R.string.dl_notification_failed_chapters_fmt, outcome.failed),
                        )
                        else -> DownloadNotifications.clearStatus(app, bookId)
                    }
                    MiruLog.i(
                        "downloads",
                        "work-result bookId=$bookId attempt=${runAttemptCount + 1} " +
                            "result=$resultLabel " +
                            "completed=${outcome.completed} failed=${outcome.failed} " +
                            "pending=${outcome.pending} needsSourceAction=${outcome.needsSourceAction} " +
                            "waitingNetwork=${outcome.waitingNetwork}",
                    )
                    Result.success()
                }
            }
        } catch (e: CancellationException) {
            // Pause/cancel already persisted the target state before stopping
            // the work; nothing here may overwrite it.
            MiruLog.i(
                "downloads",
                "work-result bookId=$bookId attempt=${runAttemptCount + 1} result=cancelled",
            )
            throw e
        } catch (t: Throwable) {
            if (DownloadRetryPolicy.shouldRetry(runAttemptCount + 1)) {
                MiruLog.w(
                    "downloads",
                    "work-result bookId=$bookId attempt=${runAttemptCount + 1} " +
                        "result=retry errorClass=${t::class.simpleName} " +
                        "error=\"${safeWorkerMessage(t)}\"",
                )
                Result.retry()
            } else {
                engine.failPending(bookId, RETRY_EXHAUSTED)
                DownloadNotifications.failed(app, bookId, title, RETRY_EXHAUSTED)
                MiruLog.w(
                    "downloads",
                    "work-result bookId=$bookId attempt=${runAttemptCount + 1} " +
                        "result=failure errorClass=${t::class.simpleName} " +
                        "error=\"${safeWorkerMessage(t)}\"",
                )
                Result.failure()
            }
        }
    }

    private suspend fun notifySourceAction(
        app: MiruApp,
        engine: DownloadEngine,
        bookId: Long,
        title: String,
    ) {
        val progress = engine.progressOf(bookId, null)
        DownloadNotifications.needsSourceAction(
            context = app,
            bookId = bookId,
            bookTitle = title,
            progress = progress.fraction,
            sourceUrl = engine.bookSourceUrl(bookId),
        )
    }

    private suspend fun progressOf(engine: DownloadEngine, bookId: Long): Float =
        engine.progressOf(bookId, null).fraction

    private fun safeWorkerMessage(error: Throwable): String =
        (error.message ?: error::class.simpleName ?: "Download failed")
        .replace(Regex("(https?|content|file)://\\S+", RegexOption.IGNORE_CASE), "<url>")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(200)

    companion object {
        const val KEY_BOOK_ID = "bookId"
        private const val RETRY_EXHAUSTED = "Retry attempts exhausted"
        private const val NETWORK_WAITING = "No network; retry when connected"
    }
}
