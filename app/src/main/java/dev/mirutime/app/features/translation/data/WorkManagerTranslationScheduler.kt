package dev.mirutime.app.features.translation.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.mirutime.app.features.downloads.data.DownloadRetryPolicy
import java.util.concurrent.TimeUnit

/** One unique WorkManager chain per book for the durable translation queue. */
class WorkManagerTranslationScheduler(
    context: Context,
) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun enqueue(bookId: Long) {
        val request = OneTimeWorkRequestBuilder<TranslationWorker>()
            .setInputData(workDataOf(TranslationWorker.KEY_BOOK_ID to bookId))
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
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    fun cancel(bookId: Long) {
        workManager.cancelUniqueWork(workName(bookId))
    }

    private fun workName(bookId: Long): String = "translate-book-$bookId"

    private companion object {
        const val TAG = "translate-book"
    }
}
