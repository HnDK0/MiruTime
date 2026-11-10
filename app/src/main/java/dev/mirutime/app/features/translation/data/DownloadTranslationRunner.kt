package dev.mirutime.app.features.translation.data

import dev.mirutime.core.log.MiruLog
import dev.mirutime.app.features.downloads.data.DownloadFileStore
import dev.mirutime.app.features.translation.domain.TranslationEngine
import dev.mirutime.core.api.MiruError
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginError
import dev.mirutime.data.library.ChapterTranslationEntity
import dev.mirutime.data.library.ChapterTranslationQueueRow
import dev.mirutime.data.library.libraryBookKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

/** Counts one background translation pass; no UI state is fabricated. */
data class TranslationRunOutcome(
    val translated: Int = 0,
    val cached: Int = 0,
    val failed: Int = 0,
    /** Left PENDING for a bounded WorkManager retry. */
    val retryable: Int = 0,
    /** Body not downloaded yet (or queue paused); not attempted. */
    val waiting: Int = 0,
) {
    val shouldRetry: Boolean get() = retryable > 0
}

/**
 * Translates chapters whose downloaded body already exists and whose
 * `chapter_translations` intent row is PENDING. Room is both queue and cache,
 * so process death resumes from the same rows; a duplicate worker for the same
 * book is prevented by unique WorkManager work, not by an in-memory set.
 */
class DownloadTranslationRunner(
    private val store: TranslationQueueStore,
    private val engine: TranslationEngine,
    private val cache: ChapterTranslationCache,
    private val files: DownloadFileStore,
    private val configFor: (bookUrl: String) -> ChapterTranslationConfig? =
        { ChapterTranslationPlanner.config(it, activeTranslationMode(it)) },
    private val now: () -> Long = System::currentTimeMillis,
    private val chapterDelayMs: () -> Long = { DEFAULT_CHAPTER_DELAY_MS },
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val chapterTimeout: Duration = DefaultChapterTimeout,
) {
    suspend fun run(bookId: Long, attempt: Int = 1): TranslationRunOutcome {
        val book = store.book(bookId) ?: return TranslationRunOutcome()
        val allRows = store.queueRows(bookId)
        // The worker sees the stored raw URL; per-book settings live under the
        // route BookKey. Without this fallback every row reports configured=false.
        val config = translationConfigKeys(book.url, book.installId)
            .firstNotNullOfOrNull(configFor)
        MiruLog.event(
            "translation",
            "download-translation-start",
            "bookId" to bookId,
            "rows" to allRows.size,
            "pending" to allRows.count {
                it.status.equals(DownloadTranslationQueue.STATUS_PENDING, true)
            },
            "configured" to (config != null),
        )
        if (config == null) return TranslationRunOutcome()
        val source = config.mode.source
        val target = config.mode.target
        val provider = config.mode.provider
        val promptHash = ChapterTranslationPlanner.promptHash(config.prompt)
        val pending = allRows
            .filter { it.status.equals(DownloadTranslationQueue.STATUS_PENDING, true) }

        var translated = 0
        var cached = 0
        var failed = 0
        var retryable = 0
        var waiting = 0
        val rows = ArrayList(pending)
        for ((position, row) in rows.withIndex()) {
            val sameConfig = row.source == source &&
                row.target == target &&
                row.provider == provider &&
                row.promptHash == promptHash &&
                row.pipelineVersion == ChapterTranslationPlanner.PIPELINE_VERSION
            if (!sameConfig) {
                // Settings changed without a new enqueue; the old intent can
                // never become a valid cache key.
                mark(row, DownloadTranslationQueue.STATUS_CANCELLED)
                continue
            }
            if (store.book(bookId) == null) {
                // The book was deleted while this pass was running; its
                // chapter_translations rows are already gone with the cascade.
                store.delete(row.translationId)
                continue
            }
            val chapter = store.chapter(row.chapterId)
            if (chapter == null) {
                failed++
                mark(row, DownloadTranslationQueue.STATUS_FAILED)
                continue
            }
            if (store.downloadState(row.chapterId) == PAUSED) {
                waiting++
                continue
            }
            val body = store.body(row.chapterId)
            if (body == null) {
                waiting++
                continue
            }
            val html = files.readText(body.path)
            if (html == null) {
                failed++
                mark(row, DownloadTranslationQueue.STATUS_FAILED)
                MiruLog.w(
                    "translation",
                    "download-translation missing body chapterId=${row.chapterId}",
                )
                continue
            }
            val request = ChapterTranslationPlanner.requestFromHtml(
                // The reader identifies a book by the route key; the raw URL
                // can be ambiguous (two installs, one URL) and the cache would
                // never resolve the chapter.
                bookUrl = libraryBookKey(book.installId, book.url),
                chapterUrl = chapter.url,
                html = html,
                config = config,
            )
            if (request == null) {
                // Nothing textual (image-only or empty parse); not an error and
                // not a cache entry.
                mark(row, DownloadTranslationQueue.STATUS_SKIPPED)
                continue
            }
            val actualHash = request.revision.sourceHash
            val exact = store.exact(
                chapterId = row.chapterId,
                source = source,
                target = target,
                provider = provider,
                promptHash = promptHash,
                sourceHash = actualHash,
                pipelineVersion = ChapterTranslationPlanner.PIPELINE_VERSION,
            )
            if (exact != null &&
                exact.status.equals(DownloadTranslationQueue.STATUS_COMPLETE, true) &&
                store.blocks(exact.translationId).isNotEmpty()
            ) {
                if (exact.translationId == row.translationId) {
                    mark(row, DownloadTranslationQueue.STATUS_COMPLETE)
                } else {
                    // A reader run already produced this exact cache entry.
                    store.delete(row.translationId)
                }
                cached++
                continue
            }
            if (exact == null || exact.translationId == row.translationId) {
                mark(row, DownloadTranslationQueue.STATUS_PENDING, sourceHash = actualHash)
            }
            when (
                val outcome = try {
                    withTimeout(chapterTimeout) { engine.translateChapter(request) }
                } catch (timeout: TimeoutCancellationException) {
                    MiruLog.w(
                        "translation",
                        "download-translation timeout chapterId=${row.chapterId} " +
                            "timeoutMs=${chapterTimeout.inWholeMilliseconds}",
                    )
                    null
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    MiruLog.e("translation", "download-translation failed", error)
                    null
                }
            ) {
                is Outcome.Ok -> {
                    if (store.book(bookId) == null || store.chapter(row.chapterId) == null) {
                        store.delete(row.translationId)
                        continue
                    }
                    try {
                        cache.save(request, outcome.value)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        MiruLog.e("translation", "download-translation save failed", e)
                        try {
                            cache.markFailed(request)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Exception) {
                            // Best effort; the row will be retried on the next pass.
                        }
                        failed++
                        continue
                    }
                    if (exact != null && exact.translationId != row.translationId) {
                        store.delete(row.translationId)
                    }
                    translated++
                    if (position < rows.lastIndex) waitBetweenChapters()
                }
                is Outcome.Err -> {
                    if (outcome.error.isRetryable() && attempt < MAX_ATTEMPTS) {
                        retryable++
                    } else {
                        cache.markFailed(request)
                        if (exact != null && exact.translationId != row.translationId) {
                            store.delete(row.translationId)
                        }
                        failed++
                    }
                }
                null -> {
                    cache.markFailed(request)
                    if (exact != null && exact.translationId != row.translationId) {
                        store.delete(row.translationId)
                    }
                    failed++
                }
            }
        }
        MiruLog.i(
            "translation",
            "download-translation-summary bookId=$bookId pending=${rows.size} " +
                "translated=$translated cached=$cached failed=$failed " +
                "retryable=$retryable waiting=$waiting attempt=$attempt",
        )
        return TranslationRunOutcome(
            translated = translated,
            cached = cached,
            failed = failed,
            retryable = retryable,
            waiting = waiting,
        )
    }

    private suspend fun mark(
        row: ChapterTranslationQueueRow,
        status: String,
        sourceHash: String? = null,
    ) {
        store.save(entity(row).copy(status = status, sourceHash = sourceHash ?: row.sourceHash, updatedAt = now()))
    }

    private fun entity(row: ChapterTranslationQueueRow) = ChapterTranslationEntity(
        translationId = row.translationId,
        chapterId = row.chapterId,
        source = row.source,
        target = row.target,
        provider = row.provider,
        model = null,
        promptHash = row.promptHash,
        sourceHash = row.sourceHash,
        pipelineVersion = row.pipelineVersion,
        status = row.status,
        updatedAt = row.updatedAt,
    )

    private suspend fun waitBetweenChapters() {
        val millis = chapterDelayMs().coerceAtLeast(0L)
        if (millis > 0) sleep(millis)
    }

    private fun dev.mirutime.core.api.MiruError.isRetryable(): Boolean = when (this) {
        is PluginError.Network, is PluginError.DnsFailure -> true
        else -> false
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val DEFAULT_CHAPTER_DELAY_MS = 1_000L
        private const val PAUSED = "PAUSED"
    }
}
