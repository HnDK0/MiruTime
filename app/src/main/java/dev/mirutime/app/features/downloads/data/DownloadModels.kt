package dev.mirutime.app.features.downloads.data

import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.ChapterBodyEntity
import dev.mirutime.data.library.ChapterEntity
import dev.mirutime.data.library.ChapterPageEntity
import dev.mirutime.data.library.ChapterStatusSnapshot
import dev.mirutime.data.library.DownloadTaskEntity
import dev.mirutime.data.library.LocalBookFileEntity
import kotlinx.coroutines.flow.Flow

/** Persisted queue states. The wire value is what `download_tasks.state` holds. */
enum class DownloadState(val wire: String) {
    QUEUED("QUEUED"),
    RUNNING("RUNNING"),
    PAUSED("PAUSED"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED"),
    CANCELLED("CANCELLED"),

    /** Transport is down; the bounded WorkManager retry will resume it. */
    WAITING_NETWORK("WAITING_NETWORK"),

    /** Cloudflare/login/429: the whole book pauses until the user acts. */
    NEEDS_SOURCE_ACTION("NEEDS_SOURCE_ACTION"),
    ;

    /** Active work that the worker may pick up. */
    val isActive: Boolean get() = this == QUEUED || this == RUNNING

    /** Blocked rows that should not be auto-scheduled without the user. */
    val isBlocked: Boolean
        get() = this == WAITING_NETWORK || this == NEEDS_SOURCE_ACTION

    fun canTransitionTo(next: DownloadState): Boolean = when (this) {
        QUEUED -> next in setOf(RUNNING, PAUSED, FAILED, CANCELLED, WAITING_NETWORK)
        RUNNING -> next in setOf(
            COMPLETED,
            FAILED,
            PAUSED,
            CANCELLED,
            WAITING_NETWORK,
            NEEDS_SOURCE_ACTION,
        )
        PAUSED -> next in setOf(QUEUED, CANCELLED)
        FAILED -> next in setOf(QUEUED, CANCELLED)
        COMPLETED -> next == CANCELLED
        CANCELLED -> next == QUEUED
        WAITING_NETWORK -> next in setOf(QUEUED, RUNNING, PAUSED, CANCELLED, NEEDS_SOURCE_ACTION)
        NEEDS_SOURCE_ACTION -> next in setOf(QUEUED, PAUSED, CANCELLED)
    }

    companion object {
        fun from(wire: String?): DownloadState? = entries.firstOrNull { it.wire == wire }
    }
}

enum class DownloadKind(val wire: String) {
    TEXT("TEXT"),
    MANGA("MANGA"),
    ;

    companion object {
        fun from(wire: String?): DownloadKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * WorkManager retry bounds. The scheduler uses the same ceiling; the worker
 * consults [shouldRetry] so a permanently failing plugin cannot loop forever.
 */
object DownloadRetryPolicy {
    const val MAX_ATTEMPTS = 3
    const val INITIAL_BACKOFF_MS = 30_000L
    const val MAX_BACKOFF_MS = 30L * 60L * 1_000L

    fun shouldRetry(nextAttempt: Int): Boolean = nextAttempt < MAX_ATTEMPTS

    fun backoffMillis(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 10)
        return (INITIAL_BACKOFF_MS shl exponent).coerceAtMost(MAX_BACKOFF_MS)
    }
}

/** Pure dedupe: requested indexes minus stored/queued, in reading order. */
object DownloadPlanner {
    fun pending(
        requested: Iterable<Int>,
        downloaded: Set<Int>,
        active: Set<Int>,
    ): List<Int> = requested.asSequence()
        .filter { it > 0 }
        .distinct()
        .filter { it !in downloaded && it !in active }
        .sorted()
        .toList()

    fun nextN(
        allIndexes: List<Int>,
        downloaded: Set<Int>,
        active: Set<Int>,
        count: Int,
    ): List<Int> = pending(allIndexes, downloaded, active).take(count.coerceAtLeast(0))

    fun next100(
        allIndexes: List<Int>,
        downloaded: Set<Int>,
        active: Set<Int>,
    ): List<Int> = nextN(allIndexes, downloaded, active, 100)
}

data class EnqueueResult(
    val queued: Int = 0,
    val skipped: Int = 0,
    val unknown: Int = 0,
    val reason: EnqueueFailure? = null,
    // "Download + translate" intent written to chapter_translations.
    val translationRequested: Boolean = false,
    val translationConfigured: Boolean = true,
    val translationsQueued: Int = 0,
    val translationsAlreadyQueued: Int = 0,
    val translationsSkipped: Int = 0,
    // Already downloaded rows adopted into the queue, and cache-on-read bodies
    // that are readable but are not downloads. Both explain a "0 queued" toast.
    val adopted: Int = 0,
    val cached: Int = 0,
) {
    val success: Boolean get() = queued > 0
}

enum class EnqueueFailure {
    BOOK_NOT_SAVED,
    LOCAL_BOOK,
    NO_CHAPTERS,
    NOT_CONFIGURED,
    /** Audiobooks stream from their catalogue and have nothing to download. */
    UNSUPPORTED_CONTENT,
}

enum class DownloadWorkDecision { RETRY, EXHAUSTED, DONE }

/** One place that decides whether a worker result schedules a real retry. */
object DownloadWorkPolicy {
    fun decide(outcome: DownloadRunOutcome, attemptNumber: Int): DownloadWorkDecision = when {
        // Cloudflare/login/429 is a user-action condition, never a retry loop.
        outcome.needsSourceAction -> DownloadWorkDecision.DONE
        outcome.waitingNetwork && DownloadRetryPolicy.shouldRetry(attemptNumber) ->
            DownloadWorkDecision.RETRY
        outcome.waitingNetwork -> DownloadWorkDecision.EXHAUSTED
        outcome.shouldRetry && DownloadRetryPolicy.shouldRetry(attemptNumber) ->
            DownloadWorkDecision.RETRY
        outcome.shouldRetry -> DownloadWorkDecision.EXHAUSTED
        else -> DownloadWorkDecision.DONE
    }
}

data class DownloadRunOutcome(
    val completed: Int = 0,
    /** Final FAILED rows (parse/not-found/server exhausted) that must not auto-retry. */
    val failed: Int = 0,
    /** Transport/server failures left QUEUED for a bounded WorkManager retry. */
    val retryable: Int = 0,
    /** Still active when the run ended (new work or a cancelled process). */
    val pending: Int = 0,
    /** The book stopped because the source needs login/CF/rate-limit action. */
    val needsSourceAction: Boolean = false,
    /** The book stopped because the device has no working network. */
    val waitingNetwork: Boolean = false,
) {
    val shouldRetry: Boolean get() = retryable > 0 || waitingNetwork
}

/** Actions a download status notification may offer. */
enum class DownloadNotificationAction {
    RETRY,
    OPEN_SOURCE,
    RESUME,
    PAUSE,
    CANCEL,
}

/** Pure mapping used by [DownloadNotifications] and covered by unit tests. */
object DownloadNotificationPolicy {
    fun actionsFor(
        state: DownloadState,
        hasSourceUrl: Boolean,
    ): List<DownloadNotificationAction> = when (state) {
        DownloadState.NEEDS_SOURCE_ACTION -> buildList {
            add(DownloadNotificationAction.RETRY)
            if (hasSourceUrl) add(DownloadNotificationAction.OPEN_SOURCE)
            add(DownloadNotificationAction.CANCEL)
        }
        DownloadState.WAITING_NETWORK -> listOf(
            DownloadNotificationAction.RETRY,
            DownloadNotificationAction.PAUSE,
            DownloadNotificationAction.CANCEL,
        )
        DownloadState.PAUSED -> listOf(
            DownloadNotificationAction.RESUME,
            DownloadNotificationAction.CANCEL,
        )
        DownloadState.FAILED -> listOf(
            DownloadNotificationAction.RETRY,
            DownloadNotificationAction.CANCEL,
        )
        DownloadState.QUEUED, DownloadState.RUNNING -> listOf(
            DownloadNotificationAction.PAUSE,
            DownloadNotificationAction.CANCEL,
        )
        DownloadState.COMPLETED, DownloadState.CANCELLED -> emptyList()
    }
}

data class DownloadProgress(
    val bookId: Long,
    val completed: Int,
    val total: Int,
    val chapterTitle: String? = null,
) {
    val fraction: Float
        get() = if (total <= 0) 0f else completed.toFloat() / total
}

data class DownloadBookSnapshot(
    val book: BookEntity,
    val tasks: List<DownloadTaskEntity>,
    val downloaded: Int,
    val total: Int,
)

/** Persistence seam: Room today, in-memory fake in unit tests. */
interface DownloadStore {
    suspend fun book(bookId: Long): BookEntity?
    suspend fun bookByUrl(bookUrl: String): BookEntity?
    suspend fun chapters(bookId: Long): List<ChapterEntity>
    suspend fun chapter(chapterId: Long): ChapterEntity?
    suspend fun localBookFile(bookId: Long): LocalBookFileEntity?
    suspend fun statusSnapshot(bookId: Long): ChapterStatusSnapshot?

    suspend fun tasks(bookId: Long): List<DownloadTaskEntity>
    suspend fun task(id: Long): DownloadTaskEntity?
    suspend fun saveTask(task: DownloadTaskEntity): Long
    suspend fun saveTasks(tasks: List<DownloadTaskEntity>) {
        tasks.forEach { saveTask(it) }
    }
    suspend fun deleteTask(id: Long)
    suspend fun deleteTasks(bookId: Long)
    suspend fun activeBookIds(): List<Long>
    fun tasksFlow(): Flow<List<DownloadTaskEntity>>
    fun tasksForBookFlow(bookId: Long): Flow<List<DownloadTaskEntity>>

    suspend fun body(chapterId: Long): ChapterBodyEntity?
    suspend fun saveBody(body: ChapterBodyEntity)
    suspend fun deleteBody(chapterId: Long)
    suspend fun page(chapterId: Long): ChapterPageEntity?
    suspend fun savePage(page: ChapterPageEntity)
    suspend fun deletePage(chapterId: Long)
    suspend fun setChapterDownloaded(chapterId: Long, downloaded: Boolean)
}

/** App-private file writer; no SAF/user documents are ever touched. */
interface DownloadFileStore {
    fun writeText(bookId: Long, chapterId: Long, html: String): StoredText
    fun readText(path: String): String?
    fun writePage(bookId: Long, chapterId: Long, index: Int, url: String, bytes: ByteArray): String
    fun textExists(path: String): Boolean
    fun pagesExist(pagePathsJson: String): Boolean
    fun pagePaths(pagePathsJson: String): List<String>
    fun encodePagePaths(paths: List<String>): String
    fun deleteChapterFiles(bookId: Long, chapterId: Long)
    fun deleteBookFiles(bookId: Long)
}

data class StoredText(
    val path: String,
    val bytes: Long,
    val sha256: String,
)

/** Unique-work facade; the app implementation is WorkManager. */
interface DownloadScheduler {
    fun enqueue(bookId: Long)
    fun cancel(bookId: Long)
}

/** Optional user-visible status outside the WorkManager foreground notification. */
interface DownloadNotifier {
    fun paused(bookId: Long, title: String, progress: Float)
    fun needsSourceAction(bookId: Long, title: String, progress: Float, sourceUrl: String?)
    fun waitingNetwork(bookId: Long, title: String, progress: Float)
    fun clearStatus(bookId: Long)
}
