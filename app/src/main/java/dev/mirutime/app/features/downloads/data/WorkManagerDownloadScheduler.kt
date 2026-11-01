package dev.mirutime.app.features.downloads.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** One unique WorkManager chain per book; Room rows decide what is left. */
class WorkManagerDownloadScheduler(
    context: Context,
) : DownloadScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun enqueue(bookId: Long) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_BOOK_ID to bookId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                DownloadRetryPolicy.INITIAL_BACKOFF_MS,
                TimeUnit.MILLISECONDS,
            )
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(
            workName(bookId),
            // Appends when a worker is already running; a finished chain is
            // replaced so a later "+100" starts fresh work.
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    override fun cancel(bookId: Long) {
        workManager.cancelUniqueWork(workName(bookId))
    }

    private fun workName(bookId: Long): String = "download-book-$bookId"

    private companion object {
        const val TAG = "download-book"
    }
}
