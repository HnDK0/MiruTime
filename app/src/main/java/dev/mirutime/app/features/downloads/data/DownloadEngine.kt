package dev.mirutime.app.features.downloads.data

import dev.mirutime.core.log.MiruLog
import dev.mirutime.app.core.buildPluginErrorReport
import dev.mirutime.app.core.redact
import dev.mirutime.app.features.book.data.BookDataProvider
import dev.mirutime.app.features.book.data.ChapterDownloadException
import dev.mirutime.app.features.book.data.ChapterFailureKind
import dev.mirutime.app.features.finder.data.isLocalFileBook
import dev.mirutime.app.features.library.data.LocalBookProvider
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.ChapterBodyEntity
import dev.mirutime.data.library.ChapterEntity
import dev.mirutime.data.library.ChapterPageEntity
import dev.mirutime.data.library.DownloadTaskEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Actual result of a destructive chapter action. [failed] must be inspected:
 * a positive [deleted] alone does not mean the requested selection is gone.
 */
data class ChapterDeleteResult(val deleted: Int, val failed: Int)

/**
 * Real download queue. Room `download_tasks` is the source of truth; a unique
 * WorkManager job per book does the transfer. The engine itself has no
 * in-memory job registry, so process death loses nothing but the current
 * network call.
 */
class DownloadEngine(
    private val store: DownloadStore,
    private val provider: BookDataProvider,
    private val files: DownloadFileStore,
    private val scheduler: DownloadScheduler,
    private val notifier: DownloadNotifier? = null,
    private val fetchPage: suspend (bookUrl: String, url: String) -> ByteArray? = { _, _ -> null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { MiruLog.i("downloads", it) },
    // Read per chapter, so a settings change applies to an already-running queue.
    private val chapterDelayMs: () -> Long = { 0L },
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    // Optional "download + translate": durable intent and its own worker. Null
    // in tests/previews that do not exercise translation.
    private val translationQueue: dev.mirutime.app.features.translation.data.DownloadTranslationQueue? = null,
    private val scheduleTranslation: ((Long) -> Unit)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Serializes the small commit/cancel windows only; network work stays
    // outside the lock so pause/cancel is never blocked by a slow page.
    private val bookLocks = ConcurrentHashMap<Long, Mutex>()

    fun observeAll(): Flow<List<DownloadTaskEntity>> = store.tasksFlow()

    fun observeBook(bookId: Long): Flow<List<DownloadTaskEntity>> =
        store.tasksForBookFlow(bookId)

    suspend fun bookId(bookUrl: String): Long? = store.bookByUrl(bookUrl)?.bookId

    suspend fun bookTitle(bookId: Long): String? = store.book(bookId)?.title

    fun pause(bookId: Long) {
        scope.launch { runCatching { pauseNow(bookId) } }
    }

    fun resume(bookId: Long) {
        scope.launch { runCatching { resumeNow(bookId) } }
    }

    fun retry(bookId: Long) {
        scope.launch { runCatching { retryNow(bookId) } }
    }

    fun cancel(bookId: Long) {
        scope.launch { runCatching { cancelNow(bookId) } }
    }

    fun delete(bookId: Long) {
        scope.launch { runCatching { deleteNow(bookId) } }
    }

    fun deleteChapters(bookUrl: String, indexes: Set<Int>) {
        scope.launch { runCatching { deleteChaptersNow(bookUrl, indexes) } }
    }

    suspend fun enqueueNow(
        bookUrl: String,
        indexes: Collection<Int>?,
        translateAfter: Boolean = false,
    ): EnqueueResult = withContext(Dispatchers.IO) {
        val book = store.bookByUrl(bookUrl)
            ?: return@withContext EnqueueResult(reason = EnqueueFailure.BOOK_NOT_SAVED)
        if (isOfflineBook(book, bookUrl)) {
            return@withContext EnqueueResult(reason = EnqueueFailure.LOCAL_BOOK)
        }
        val chapters = loadChapters(book, bookUrl)
        if (chapters.isEmpty()) {
            return@withContext EnqueueResult(reason = EnqueueFailure.NO_CHAPTERS)
        }
        val kind = kindOf(book)
        val existing = store.tasks(book.bookId).associateBy { it.chapterId }
        val byIndex = chapters.associateBy { it.idx }
        val requested = indexes ?: chapters.map { it.idx }
        var queued = 0
        var skipped = 0
        var unknown = 0
        val requestedChapters = ArrayList<ChapterEntity>()
        requested.asSequence().filter { it > 0 }.distinct().forEach { index ->
            val chapter = byIndex[index]
            if (chapter == null) {
                unknown++
                return@forEach
            }
            requestedChapters += chapter
            val current = existing[chapter.chapterId]
            if (isStored(kind, chapter.chapterId)) {
                if (current != null && current.state != DownloadState.COMPLETED.wire) {
                    store.saveTask(
                        current.copy(
                            state = DownloadState.COMPLETED.wire,
                            progress = 1.0,
                            error = null,
                            updatedAt = clock(),
                        ),
                    )
                }
                skipped++
                return@forEach
            }
            val active = current != null &&
                DownloadState.from(current.state) in ACTIVE_OR_PAUSED
            if (active) {
                skipped++
                return@forEach
            }
            val now = clock()
            val task = current?.copy(
                state = DownloadState.QUEUED.wire,
                kind = kind.wire,
                progress = 0.0,
                error = null,
                updatedAt = now,
            ) ?: DownloadTaskEntity(
                bookId = book.bookId,
                chapterId = chapter.chapterId,
                kind = kind.wire,
                state = DownloadState.QUEUED.wire,
                progress = 0.0,
                createdAt = now,
                updatedAt = now,
            )
            store.saveTask(task)
            queued++
        }
        if (queued > 0) scheduler.enqueue(book.bookId)
        val translation = if (!translateAfter) {
            null
        } else if (translationQueue == null) {
            dev.mirutime.app.features.translation.data.TranslationEnqueueResult(configured = false)
        } else {
            val textChapters = if (kind == DownloadKind.MANGA) {
                emptyList()
            } else {
                requestedChapters.filter { store.page(it.chapterId) == null }
            }
            translationQueue.enqueue(bookUrl, textChapters).also { result ->
                if (result.queued > 0 || result.alreadyQueued > 0) {
                    scheduleTranslation?.invoke(book.bookId)
                }
            }
        }
        log(
            "action=enqueue bookId=${book.bookId} requested=${requested.size} " +
                "queued=$queued skipped=$skipped unknown=$unknown " +
                "translate=$translateAfter translations=${translation?.queued ?: 0}",
        )
        EnqueueResult(
            queued = queued,
            skipped = skipped,
            unknown = unknown,
            translationRequested = translateAfter,
            translationConfigured = translation?.configured ?: true,
            translationsQueued = translation?.queued ?: 0,
            translationsAlreadyQueued = translation?.alreadyQueued ?: 0,
            translationsSkipped = (translation?.skippedPages ?: 0) +
                if (kind == DownloadKind.MANGA) requestedChapters.size else 0,
        )
    }

    suspend fun enqueueNext100Now(
        bookUrl: String,
        translateAfter: Boolean = false,
    ): EnqueueResult {
        val book = store.bookByUrl(bookUrl)
            ?: return EnqueueResult(reason = EnqueueFailure.BOOK_NOT_SAVED)
        if (isOfflineBook(book, bookUrl)) return EnqueueResult(reason = EnqueueFailure.LOCAL_BOOK)
        val chapters = loadChapters(book, bookUrl)
        if (chapters.isEmpty()) return EnqueueResult(reason = EnqueueFailure.NO_CHAPTERS)
        val snapshot = store.statusSnapshot(book.bookId)
        val indexByChapterId = chapters.associate { it.chapterId to it.idx }
        val active = store.tasks(book.bookId)
            .filter { DownloadState.from(it.state) in ACTIVE_OR_PAUSED }
            .mapNotNull { task -> indexByChapterId[task.chapterId] }
            .toSet()
        val next = DownloadPlanner.next100(
            allIndexes = chapters.map { it.idx },
            downloaded = snapshot?.downloadedIndexes.orEmpty(),
            active = active,
        )
        if (next.isEmpty()) {
            return EnqueueResult(skipped = chapters.size)
        }
        return enqueueNow(bookUrl, next, translateAfter)
    }

    /**
     * Runs every QUEUED/RUNNING task for one book. A single chapter failure
     * never aborts the batch: auth/CF/parse failures become terminal FAILED,
     * transport failures stay QUEUED for a bounded WorkManager retry, and the
     * loop continues with the next chapter.
     */
    suspend fun run(
        bookId: Long,
        attempt: Int = 0,
        onProgress: suspend (DownloadProgress) -> Unit = {},
    ): DownloadRunOutcome = withContext(Dispatchers.IO) {
        val startedNanos = System.nanoTime()
        val book = store.book(bookId) ?: run {
            log("run-summary bookId=$bookId attempt=$attempt candidates=0 " +
                "completed=0 failed=0 retryable=0 pending=0 elapsedMs=0 result=book-missing")
            return@withContext DownloadRunOutcome()
        }
        val candidates = store.tasks(bookId).filter {
            DownloadState.from(it.state) in RUNNABLE_STATES
        }
        log(
            "run-start bookId=$bookId attempt=$attempt candidates=${candidates.size} " +
                "kind=${kindOf(book).wire}",
        )
        var completed = 0
        var failed = 0
        var retryable = 0
        var needsSourceAction = false
        var waitingNetwork = false
        for ((position, candidate) in candidates.withIndex()) {
            val taskStarted = System.nanoTime()
            val chapter = store.chapter(candidate.chapterId)
            if (chapter == null) {
                if (failQueued(candidate, "Chapter record missing")) failed++
                log(
                    "chapter bookId=$bookId chapterId=${candidate.chapterId} index=- " +
                        "from=${candidate.state} state=FAILED kind=SOURCE " +
                        "errorClass=MissingChapter elapsedMs=${elapsedMs(taskStarted)}",
                )
                continue
            }
            val task = lockFor(bookId).withLock {
                val current = store.task(candidate.id) ?: return@withLock null
                val state = DownloadState.from(current.state) ?: return@withLock null
                if (state !in RUNNABLE_STATES) return@withLock null
                current.copy(
                    state = DownloadState.RUNNING.wire,
                    error = null,
                    updatedAt = clock(),
                ).also { store.saveTask(it) }
            }
            if (task == null) {
                log(
                    "chapter bookId=$bookId chapterId=${candidate.chapterId} " +
                        "index=${chapter.idx} state=SKIPPED reason=state-changed",
                )
                continue
            }
            onProgress(progressOf(bookId, chapter.title))
            var succeeded = false
            try {
                if (process(book, chapter, task)) {
                    completed++
                    succeeded = true
                    logChapterResult(
                        bookId,
                        chapter,
                        from = candidate.state,
                        state = "COMPLETED",
                        startedNanos = taskStarted,
                    )
                } else {
                    val current = store.task(task.id)?.state ?: "UNKNOWN"
                    log(
                        "chapter bookId=$bookId chapterId=${chapter.chapterId} " +
                            "index=${chapter.idx} from=${candidate.state} state=$current " +
                            "reason=stopped elapsedMs=${elapsedMs(taskStarted)}",
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                log(
                    "chapter bookId=$bookId chapterId=${chapter.chapterId} " +
                        "index=${chapter.idx} from=${candidate.state} state=CANCELLED " +
                        "reason=coroutine elapsedMs=${elapsedMs(taskStarted)}",
                )
                log(
                    "run-summary bookId=$bookId attempt=$attempt candidates=${candidates.size} " +
                        "completed=$completed failed=$failed retryable=$retryable pending=unknown " +
                        "elapsedMs=${elapsedMs(startedNanos)} result=cancelled",
                )
                throw e
            } catch (t: Throwable) {
                val failure = classifyFailure(t, book)
                // Manga pages are all-or-nothing: a failed chapter must not
                // leave an unreferenced page directory behind.
                if (DownloadKind.from(task.kind) == DownloadKind.MANGA) {
                    files.deleteChapterFiles(book.bookId, chapter.chapterId)
                }
                when (failure.block) {
                    FailureBlock.SOURCE_ACTION -> {
                        // One CF/login/429 applies to the whole source, so the
                        // book pauses instead of turning the rest into FAILED.
                        needsSourceAction = true
                        markNeedsSourceAction(book.bookId, failure.message)
                    }
                    FailureBlock.NETWORK -> {
                        waitingNetwork = true
                        markWaitingNetwork(task, failure.message)
                    }
                    FailureBlock.RETRY -> {
                        if (markRetryQueued(task, failure.message)) retryable++
                    }
                    FailureBlock.TERMINAL -> {
                        if (markFailed(task, failure.message)) failed++
                    }
                }
                log(
                    "chapter bookId=$bookId chapterId=${chapter.chapterId} " +
                        "index=${chapter.idx} from=${candidate.state} " +
                        "state=${store.task(task.id)?.state ?: "UNKNOWN"} " +
                        "kind=${failure.kind} retryable=${failure.retryable} " +
                        "errorClass=${t::class.simpleName} error=\"${failure.message}\" " +
                        "elapsedMs=${elapsedMs(taskStarted)}",
                )
            }
            onProgress(progressOf(bookId, chapter.title))
            if (needsSourceAction || waitingNetwork) break
            // NoveLA spaces successful chapter requests; a paused/cancelled
            // worker cancels this delay, and the next state check skips rows.
            if (succeeded && position < candidates.lastIndex) waitBetweenChapters(bookId)
        }
        val pending = store.tasks(bookId).count {
            DownloadState.from(it.state) in RUNNABLE_STATES
        }
        log(
            "run-summary bookId=$bookId attempt=$attempt candidates=${candidates.size} " +
                "completed=$completed failed=$failed retryable=$retryable pending=$pending " +
                "elapsedMs=${elapsedMs(startedNanos)} " +
                "result=${runResult(needsSourceAction, waitingNetwork, retryable)}",
        )
        DownloadRunOutcome(
            completed = completed,
            failed = failed,
            retryable = retryable,
            pending = pending,
            needsSourceAction = needsSourceAction,
            waitingNetwork = waitingNetwork,
        )
    }

    private suspend fun waitBetweenChapters(bookId: Long) {
        val millis = chapterDelayMs().coerceAtLeast(0L)
        if (millis <= 0L) return
        log("delay bookId=$bookId chapterDelayMs=$millis")
        sleep(millis)
    }

    private fun runResult(
        needsSourceAction: Boolean,
        waitingNetwork: Boolean,
        retryable: Int,
    ): String = when {
        needsSourceAction -> "needs-source-action"
        waitingNetwork -> "waiting-network"
        retryable > 0 -> "retry"
        else -> "done"
    }

    suspend fun pauseNow(bookId: Long) = lockFor(bookId).withLock {
        val tasks = store.tasks(bookId)
        var paused = 0
        for (task in tasks) {
            val state = DownloadState.from(task.state) ?: continue
            // WAITING_NETWORK is already parked; pausing it keeps the pause
            // button responsive while WorkManager is backing off.
            if (state in RUNNABLE_STATES) {
                store.saveTask(
                    task.copy(
                        state = DownloadState.PAUSED.wire,
                        updatedAt = clock(),
                    ),
                )
                paused++
            }
        }
        scheduler.cancel(bookId)
        log("action=pause bookId=$bookId paused=$paused")
        if (paused > 0) {
            val book = store.book(bookId)
            notifier?.paused(
                bookId = bookId,
                title = book?.title.orEmpty(),
                progress = progressOf(bookId, null).fraction,
            )
        }
    }

    suspend fun resumeNow(bookId: Long) = lockFor(bookId).withLock {
        var queued = 0
        for (task in store.tasks(bookId)) {
            if (DownloadState.from(task.state) == DownloadState.PAUSED) {
                store.saveTask(task.copy(state = DownloadState.QUEUED.wire, updatedAt = clock()))
                queued++
            }
        }
        log("action=resume bookId=$bookId queued=$queued")
        notifier?.clearStatus(bookId)
        if (queued > 0) scheduler.enqueue(bookId)
    }

    suspend fun retryNow(bookId: Long) = lockFor(bookId).withLock {
        val tasks = store.tasks(bookId)
        // A source-auth block means the user just solved CF/login (or asked to):
        // re-arm only that block instead of treating every row as a fresh retry.
        if (tasks.any { DownloadState.from(it.state) == DownloadState.NEEDS_SOURCE_ACTION }) {
            resumeAfterSourceActionLocked(bookId)
            return@withLock
        }
        var queued = 0
        for (task in tasks) {
            val state = DownloadState.from(task.state) ?: continue
            // FAILED includes the legacy 43 terminal rows. NEEDS_SOURCE_ACTION
            // and WAITING_NETWORK are the explicit Retry after CF/login or a
            // connectivity loss. COMPLETED is never re-queued.
            if (state == DownloadState.FAILED ||
                state == DownloadState.CANCELLED ||
                state == DownloadState.NEEDS_SOURCE_ACTION ||
                state == DownloadState.WAITING_NETWORK
            ) {
                store.saveTask(
                    task.copy(
                        state = DownloadState.QUEUED.wire,
                        progress = 0.0,
                        error = null,
                        updatedAt = clock(),
                    ),
                )
                queued++
            }
        }
        log("action=retry bookId=$bookId queued=$queued")
        notifier?.clearStatus(bookId)
        if (queued > 0) scheduler.enqueue(bookId)
    }

    /**
     * After the user solves the source challenge, re-arm the rows it parked
     * (plus legacy FAILED rows whose reason is CF/login) and enqueue the book.
     * COMPLETED rows and unrelated terminal failures are untouched.
     */
    suspend fun resumeAfterSourceAction(bookId: Long) = lockFor(bookId).withLock {
        resumeAfterSourceActionLocked(bookId)
    }

    private suspend fun resumeAfterSourceActionLocked(bookId: Long) {
        var queued = 0
        for (task in store.tasks(bookId)) {
            val state = DownloadState.from(task.state) ?: continue
            if (state != DownloadState.NEEDS_SOURCE_ACTION &&
                !(state == DownloadState.FAILED && isSourceAuthReason(task.error))
            ) {
                continue
            }
            store.saveTask(
                task.copy(
                    state = DownloadState.QUEUED.wire,
                    progress = 0.0,
                    error = null,
                    updatedAt = clock(),
                ),
            )
            queued++
        }
        log("action=resume-source bookId=$bookId queued=$queued")
        notifier?.clearStatus(bookId)
        if (queued > 0) scheduler.enqueue(bookId)
    }

    /** Pauses first, then cancels the worker, then removes partial output. */
    suspend fun cancelNow(bookId: Long) = lockFor(bookId).withLock {
        val tasks = store.tasks(bookId)
        val toClean = tasks.filter {
            val state = DownloadState.from(it.state)
            state != DownloadState.COMPLETED && state != DownloadState.CANCELLED
        }
        for (task in toClean) {
            store.saveTask(
                task.copy(
                    state = DownloadState.CANCELLED.wire,
                    error = null,
                    updatedAt = clock(),
                ),
            )
        }
        scheduler.cancel(bookId)
        toClean.forEach { deleteChapterOutput(bookId, it.chapterId) }
        log("action=cancel bookId=$bookId cancelled=${toClean.size}")
        notifier?.clearStatus(bookId)
    }

    /** Removes only the selected chapters, leaving the rest of the queue. */
    suspend fun deleteChaptersNow(bookUrl: String, indexes: Set<Int>): ChapterDeleteResult =
        withContext(Dispatchers.IO) {
            if (indexes.isEmpty()) return@withContext ChapterDeleteResult(0, 0)
            val book = store.bookByUrl(bookUrl) ?: return@withContext ChapterDeleteResult(0, 0)
            if (isOfflineBook(book, bookUrl)) return@withContext ChapterDeleteResult(0, 0)
            // Cache-on-read writes body/pages without a download_tasks row, so
            // the snapshot (downloaded flag + body/pages + tasks) decides which
            // selected chapters actually own output. Never touch the rest.
            val snapshot = store.statusSnapshot(book.bookId)
                ?: return@withContext ChapterDeleteResult(0, 0)
            val affected = indexes intersect (snapshot.downloadedIndexes + snapshot.downloads.keys)
            if (affected.isEmpty()) return@withContext ChapterDeleteResult(0, 0)
            val chapters = store.chapters(book.bookId).filter { it.idx in affected }
            if (chapters.isEmpty()) return@withContext ChapterDeleteResult(0, 0)
            val chapterIds = chapters.mapTo(HashSet()) { it.chapterId }
            var deleted = 0
            var failed = 0
            var touched = false
            try {
                for (chunk in chapters.chunked(DELETE_CHUNK)) {
                    ensureActive()
                    lockFor(book.bookId).withLock {
                        // Re-read under the same lock the download writer uses:
                        // a concurrent delete/download may have changed these
                        // rows after the snapshot, and only real changes count.
                        val currentById = store.chapters(book.bookId)
                            .filter { it.chapterId in chapterIds }
                            .associateBy { it.chapterId }
                        val tasksByChapter = store.tasks(book.bookId)
                            .filter { it.chapterId in chapterIds }
                            .groupBy { it.chapterId }
                        for (chapter in chunk) {
                            ensureActive()
                            val current = currentById[chapter.chapterId] ?: continue
                            val tasks = tasksByChapter[current.chapterId].orEmpty()
                            try {
                                // DB rows commit first; files are deleted in
                                // the same lock-backed step so a writer cannot
                                // recreate a row after its file was removed.
                                // One bounded chapter is non-cancellable so
                                // cancellation never leaves rows without files.
                                val changed = withContext(NonCancellable) {
                                    deleteChapterOutputLocked(
                                        bookId = book.bookId,
                                        chapterId = current.chapterId,
                                        downloaded = current.downloaded,
                                        tasks = tasks,
                                    )
                                }
                                if (changed) {
                                    deleted++
                                    touched = true
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                touched = true
                                failed++
                                log(
                                    "delete-chapters-failed bookId=${book.bookId} " +
                                        "chapterId=${current.chapterId} " +
                                        "errorClass=${t::class.simpleName}",
                                )
                                // Recover: drop files only when no DB row still
                                // references them; otherwise retry owns them.
                                runCatching {
                                    withContext(NonCancellable) {
                                        if (store.body(current.chapterId) == null &&
                                            store.page(current.chapterId) == null
                                        ) {
                                            files.deleteChapterFiles(
                                                book.bookId,
                                                current.chapterId,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                // A partial failure must still drop the cached chapter status,
                // otherwise the UI keeps showing a stale downloaded snapshot.
                if (touched) {
                    provider.setChapterDownloaded(
                        bookUrl,
                        chapters.minOf { it.idx }..chapters.maxOf { it.idx },
                        false,
                    )
                }
            }
            log(
                "action=delete-chapters bookId=${book.bookId} requested=${indexes.size} " +
                    "matched=${chapters.size} deleted=$deleted failed=$failed",
            )
            ChapterDeleteResult(deleted = deleted, failed = failed)
        }

    suspend fun deleteNow(bookId: Long) = lockFor(bookId).withLock {
        scheduler.cancel(bookId)
        val book = store.book(bookId)
        if (book != null && !isOfflineBook(book, book.url)) {
            for (task in store.tasks(bookId)) {
                deleteChapterOutput(bookId, task.chapterId)
            }
            files.deleteBookFiles(bookId)
        }
        store.deleteTasks(bookId)
        notifier?.clearStatus(bookId)
        log("action=delete bookId=$bookId")
    }

    /** Re-arm persisted QUEUED/RUNNING rows after process death or a reboot. */
    suspend fun reconcile() {
        store.activeBookIds().forEach(scheduler::enqueue)
    }

    /**
     * Final attempt / terminal worker failure: no automatic retry is coming,
     * so both QUEUED and RUNNING attempts become FAILED. PAUSED/CANCELLED stay
     * untouched because those are explicit user decisions.
     */
    suspend fun failPending(bookId: Long, message: String) = lockFor(bookId).withLock {
        val safe = safeMessage(message)
        var failed = 0
        for (task in store.tasks(bookId)) {
            val state = DownloadState.from(task.state) ?: continue
            if (state == DownloadState.QUEUED || state == DownloadState.RUNNING) {
                store.saveTask(
                    task.copy(
                        state = DownloadState.FAILED.wire,
                        error = safe,
                        updatedAt = clock(),
                    ),
                )
                failed++
            }
        }
        log("pending-failed bookId=$bookId count=$failed error=\"$safe\"")
    }

    /**
     * No automatic retry remains but the failure was transport-only: park the
     * rows as WAITING_NETWORK instead of pretending they are terminal failures.
     */
    suspend fun parkWaitingNetwork(bookId: Long, message: String) =
        parkPending(bookId, DownloadState.WAITING_NETWORK, message)

    private suspend fun parkPending(bookId: Long, state: DownloadState, message: String) =
        lockFor(bookId).withLock {
            val safe = safeMessage(message)
            var parked = 0
            for (task in store.tasks(bookId)) {
                if (DownloadState.from(task.state) !in RUNNABLE_STATES) continue
                store.saveTask(
                    task.copy(
                        state = state.wire,
                        error = safe,
                        updatedAt = clock(),
                    ),
                )
                parked++
            }
            log("pending-parked bookId=$bookId state=${state.wire} count=$parked error=\"$safe\"")
        }

    /** Source page for an "Open source" action; null for non-HTTP/local books. */
    suspend fun bookSourceUrl(bookId: Long): String? =
        store.book(bookId)?.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    suspend fun progressOf(bookId: Long, chapterTitle: String?): DownloadProgress {
        val tasks = store.tasks(bookId)
        return DownloadProgress(
            bookId = bookId,
            completed = tasks.count { it.state == DownloadState.COMPLETED.wire },
            total = tasks.size,
            chapterTitle = chapterTitle,
        )
    }

    private suspend fun process(
        book: BookEntity,
        chapter: ChapterEntity,
        task: DownloadTaskEntity,
    ): Boolean {
        val kind = DownloadKind.from(task.kind) ?: kindOf(book)
        if (isStored(kind, chapter.chapterId)) {
            lockFor(book.bookId).withLock {
                if (stillRunning(task.id)) complete(task)
            }
            return true
        }
        return when (kind) {
            DownloadKind.TEXT -> processText(book, chapter, task)
            DownloadKind.MANGA -> processManga(book, chapter, task)
        }
    }

    private suspend fun processText(
        book: BookEntity,
        chapter: ChapterEntity,
        task: DownloadTaskEntity,
    ): Boolean {
        val html = provider.downloadChapterText(book.url, chapter.idx)
        if (html.isBlank()) error("Chapter text is empty")
        // File write and row commit share the book lock, so a concurrent
        // delete (or a second writer) cannot interleave them.
        return lockFor(book.bookId).withLock {
            if (!stillRunning(task.id)) {
                discardUncommittedOutput(book.bookId, chapter.chapterId, task.id)
                return@withLock false
            }
            val stored = files.writeText(book.bookId, chapter.chapterId, html)
            store.saveBody(
                ChapterBodyEntity(
                    chapterId = chapter.chapterId,
                    format = "html",
                    path = stored.path,
                    bytes = stored.bytes,
                    sha256 = stored.sha256,
                    fetchedAt = clock(),
                ),
            )
            store.setChapterDownloaded(chapter.chapterId, true)
            complete(task)
            true
        }
    }

    private suspend fun processManga(
        book: BookEntity,
        chapter: ChapterEntity,
        task: DownloadTaskEntity,
    ): Boolean {
        val urls = provider.downloadChapterPages(book.url, chapter.idx)
        if (urls.isEmpty()) error("Chapter pages are unavailable")
        val paths = ArrayList<String>(urls.size)
        var totalBytes = 0L
        urls.forEachIndexed { index, url ->
            if (!stillRunning(task.id)) {
                lockFor(book.bookId).withLock {
                    discardUncommittedOutput(book.bookId, chapter.chapterId, task.id)
                }
                return false
            }
            val bytes = fetchPage(book.url, url) ?: error("Page download failed")
            // Page write and the commit/progress update are one locked step;
            // deletion never sees a half-written page set outside the lock.
            var active = true
            lockFor(book.bookId).withLock {
                if (!stillRunning(task.id)) {
                    discardUncommittedOutput(book.bookId, chapter.chapterId, task.id)
                    active = false
                    return@withLock
                }
                val path = files.writePage(
                    bookId = book.bookId,
                    chapterId = chapter.chapterId,
                    index = index,
                    url = url,
                    bytes = bytes,
                )
                paths += path
                totalBytes += bytes.size
                val fraction = ((index + 1).toDouble() / urls.size).coerceIn(0.0, 0.99)
                val current = store.task(task.id) ?: return@withLock
                if (DownloadState.from(current.state) == DownloadState.RUNNING) {
                    store.saveTask(current.copy(progress = fraction, updatedAt = clock()))
                }
            }
            if (!active) return false
        }
        val json = files.encodePagePaths(paths)
        return lockFor(book.bookId).withLock {
            if (!stillRunning(task.id)) {
                discardUncommittedOutput(book.bookId, chapter.chapterId, task.id)
                return@withLock false
            }
            store.savePage(
                ChapterPageEntity(
                    chapterId = chapter.chapterId,
                    format = "images",
                    pagePathsJson = json,
                    totalBytes = totalBytes,
                ),
            )
            store.setChapterDownloaded(chapter.chapterId, true)
            complete(task)
            true
        }
    }

    private suspend fun complete(task: DownloadTaskEntity) {
        val current = store.task(task.id) ?: return
        if (DownloadState.from(current.state) != DownloadState.RUNNING) return
        store.saveTask(
            current.copy(
                state = DownloadState.COMPLETED.wire,
                progress = 1.0,
                error = null,
                updatedAt = clock(),
            ),
        )
    }

    private suspend fun markFailed(task: DownloadTaskEntity, message: String): Boolean =
        lockFor(task.bookId).withLock {
            val current = store.task(task.id) ?: return@withLock false
            if (DownloadState.from(current.state) != DownloadState.RUNNING) return@withLock false
            store.saveTask(
                current.copy(
                    state = DownloadState.FAILED.wire,
                    error = safeMessage(message),
                    updatedAt = clock(),
                ),
            )
            true
        }

    private suspend fun markRetryQueued(task: DownloadTaskEntity, message: String): Boolean =
        lockFor(task.bookId).withLock {
            val current = store.task(task.id) ?: return@withLock false
            if (DownloadState.from(current.state) != DownloadState.RUNNING) return@withLock false
            store.saveTask(
                current.copy(
                    state = DownloadState.QUEUED.wire,
                    progress = 0.0,
                    error = safeMessage(message),
                    updatedAt = clock(),
                ),
            )
            true
        }

    /**
     * Book-wide stop for Cloudflare/login/429. Every runnable row moves to
     * NEEDS_SOURCE_ACTION with one bounded reason; COMPLETED rows stay intact.
     */
    private suspend fun markNeedsSourceAction(bookId: Long, message: String) {
        val safe = safeMessage(message)
        var marked = 0
        lockFor(bookId).withLock {
            for (task in store.tasks(bookId)) {
                if (DownloadState.from(task.state) !in RUNNABLE_STATES) continue
                store.saveTask(
                    task.copy(
                        state = DownloadState.NEEDS_SOURCE_ACTION.wire,
                        error = safe,
                        updatedAt = clock(),
                    ),
                )
                marked++
            }
        }
        log("book-source-action bookId=$bookId count=$marked error=\"$safe\"")
    }

    private suspend fun markWaitingNetwork(task: DownloadTaskEntity, message: String): Boolean =
        lockFor(task.bookId).withLock {
            val current = store.task(task.id) ?: return@withLock false
            if (DownloadState.from(current.state) != DownloadState.RUNNING) return@withLock false
            store.saveTask(
                current.copy(
                    state = DownloadState.WAITING_NETWORK.wire,
                    error = safeMessage(message),
                    updatedAt = clock(),
                ),
            )
            true
        }

    private suspend fun failQueued(task: DownloadTaskEntity, message: String): Boolean =
        lockFor(task.bookId).withLock {
            val current = store.task(task.id) ?: return@withLock false
            val state = DownloadState.from(current.state) ?: return@withLock false
            if (state != DownloadState.QUEUED && state != DownloadState.RUNNING) {
                return@withLock false
            }
            store.saveTask(
                current.copy(
                    state = DownloadState.FAILED.wire,
                    error = safeMessage(message),
                    updatedAt = clock(),
                ),
            )
            true
        }

    private fun logChapterResult(
        bookId: Long,
        chapter: ChapterEntity,
        from: String,
        state: String,
        startedNanos: Long,
    ) {
        log(
            "chapter bookId=$bookId chapterId=${chapter.chapterId} index=${chapter.idx} " +
                "from=$from state=$state elapsedMs=${elapsedMs(startedNanos)}",
        )
    }

    private fun elapsedMs(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos) / 1_000_000).coerceAtLeast(0)

    private enum class FailureBlock { SOURCE_ACTION, NETWORK, RETRY, TERMINAL }

    private data class FailureInfo(
        val kind: String,
        val retryable: Boolean,
        val block: FailureBlock,
        val message: String,
    )

    private fun classifyFailure(error: Throwable, book: BookEntity): FailureInfo {
        if (error is ChapterDownloadException) {
            val block = when (error.kind) {
                ChapterFailureKind.CLOUDFLARE,
                ChapterFailureKind.AUTH_REQUIRED,
                ChapterFailureKind.RATE_LIMITED -> FailureBlock.SOURCE_ACTION
                ChapterFailureKind.NETWORK -> FailureBlock.NETWORK
                ChapterFailureKind.SERVER -> FailureBlock.RETRY
                ChapterFailureKind.NOT_FOUND,
                ChapterFailureKind.SOURCE -> FailureBlock.TERMINAL
            }
            return FailureInfo(
                kind = error.kind.name,
                retryable = error.retryable,
                block = block,
                message = typedFailureMessage(error, book),
            )
        }
        return FailureInfo(
            kind = "UNKNOWN",
            retryable = false,
            block = FailureBlock.TERMINAL,
            message = safeMessage(error.message ?: error::class.simpleName ?: "Download failed"),
        )
    }

    // Typed failures store the same report (source/op + HTTP/class + host+path +
    // code) every other surface copies and shares; the exception text is only
    // the fallback when no PluginError was attached.
    private fun typedFailureMessage(error: ChapterDownloadException, book: BookEntity): String {
        val pluginError = error.pluginError
            ?: return safeMessage(error.message ?: error.kind.name)
        return safeMessage(
            buildPluginErrorReport(
                error = pluginError,
                sourceId = book.installId,
                operation = "download",
                identity = book.url,
            ).reportText,
        )
    }

    private suspend fun stillRunning(taskId: Long): Boolean =
        DownloadState.from(store.task(taskId)?.state) == DownloadState.RUNNING

    private suspend fun deleteChapterOutput(bookId: Long, chapterId: Long): Boolean =
        deleteChapterOutputLocked(
            bookId = bookId,
            chapterId = chapterId,
            downloaded = store.chapter(chapterId)?.downloaded == true,
            tasks = emptyList(),
        )

    /**
     * Must run under [lockFor]: DB rows first, then files, and only for the
     * chapter whose rows are actually gone. A partial DB failure throws before
     * the file delete, so the retry still owns the output.
     */
    private suspend fun deleteChapterOutputLocked(
        bookId: Long,
        chapterId: Long,
        downloaded: Boolean,
        tasks: List<DownloadTaskEntity>,
    ): Boolean {
        var changed = false
        if (store.body(chapterId) != null) {
            store.deleteBody(chapterId)
            changed = true
        }
        if (store.page(chapterId) != null) {
            store.deletePage(chapterId)
            changed = true
        }
        tasks.forEach { task ->
            store.deleteTask(task.id)
            changed = true
        }
        if (downloaded) {
            store.setChapterDownloaded(chapterId, false)
            changed = true
        }
        files.deleteChapterFiles(bookId, chapterId)
        return changed
    }

    /**
     * A stale writer must not delete output that another task already
     * committed, or that another running writer is still assembling.
     */
    private suspend fun discardUncommittedOutput(bookId: Long, chapterId: Long, taskId: Long) {
        if (store.body(chapterId) != null || store.page(chapterId) != null) return
        val anotherRunning = store.tasks(bookId).any { task ->
            task.chapterId == chapterId &&
                task.id != taskId &&
                DownloadState.from(task.state) == DownloadState.RUNNING
        }
        if (anotherRunning) return
        files.deleteChapterFiles(bookId, chapterId)
    }

    private suspend fun isStored(kind: DownloadKind, chapterId: Long): Boolean = when (kind) {
        DownloadKind.TEXT -> store.body(chapterId)?.let { files.textExists(it.path) } == true
        DownloadKind.MANGA -> store.page(chapterId)?.let { files.pagesExist(it.pagePathsJson) } == true
    }

    private suspend fun loadChapters(book: BookEntity, bookUrl: String): List<ChapterEntity> {
        val stored = store.chapters(book.bookId)
        if (stored.isNotEmpty()) return stored
        runCatching { provider.chapters(bookUrl) }
        return store.chapters(book.bookId)
    }

    private suspend fun isOfflineBook(book: BookEntity, bookUrl: String): Boolean =
        store.localBookFile(book.bookId) != null ||
            LocalBookProvider.isLocalBook(bookUrl) ||
            isLocalFileBook(bookUrl)

    private fun kindOf(book: BookEntity): DownloadKind =
        if (book.contentType.equals("Manga", ignoreCase = true)) {
            DownloadKind.MANGA
        } else {
            DownloadKind.TEXT
        }

    private fun lockFor(bookId: Long): Mutex =
        bookLocks.getOrPut(bookId) { Mutex() }

    // Legacy FAILED rows from before NEEDS_SOURCE_ACTION existed still carry
    // the CF/login reason; only those are re-armed by the source-action flow.
    private fun isSourceAuthReason(error: String?): Boolean {
        val text = error?.lowercase() ?: return false
        return SOURCE_AUTH_MARKERS.any { it in text }
    }

    // Bounded and URL-free: logs/DB errors must never carry a chapter URL,
    // query string or image address. Redaction is the shared report helper, so
    // there is exactly one implementation and one output shape.
    private fun safeMessage(message: String): String {
        val cleaned = redact(message)
            .replace(WHITESPACE, " ")
            .trim()
            .take(MAX_ERROR_CHARS)
        return cleaned.ifBlank { "Download failed" }
    }

    private companion object {
        const val MAX_ERROR_CHARS = 200
        // Bounded DB+file delete batch; cancellation is checked between chunks.
        const val DELETE_CHUNK = 200
        val WHITESPACE = Regex("\\s+")
        // A blocked queue still owns its chapters: re-enqueue must not reset
        // the reason or duplicate work.
        val ACTIVE_OR_PAUSED = setOf(
            DownloadState.QUEUED,
            DownloadState.RUNNING,
            DownloadState.PAUSED,
            DownloadState.WAITING_NETWORK,
            DownloadState.NEEDS_SOURCE_ACTION,
        )
        val RUNNABLE_STATES = setOf(
            DownloadState.QUEUED,
            DownloadState.RUNNING,
            DownloadState.WAITING_NETWORK,
        )
        val SOURCE_AUTH_MARKERS = listOf(
            "cloudflare",
            "verification required",
            "login required",
            "rate limit",
        )
    }
}
