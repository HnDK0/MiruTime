package dev.mirutime.app.features.library.data

import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.libraryBookKey
import dev.mirutime.core.api.PluginError
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

data class UpdateCheckSummary(
    val checked: Int,
    val updated: Int,
    val failed: Int,
    val firstError: PluginError? = null,
    val firstErrorSource: String? = null,
)

/** Live progress for the Updates screen: books finished / total. */
data class UpdateProgress(
    val done: Int,
    val total: Int,
    val title: String,
)

/**
 * Pulls the current chapter list for library books and records the delta.
 * It never deletes/updates a book on a failed fetch and serializes writers
 * through the repository's normal transactions.
 */
class LibraryUpdateService(
    private val provider: LibraryBookRefreshPort,
    private val library: LibraryRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxConcurrency: Int = 3,
) {
    suspend fun checkAll(
        onProgress: (UpdateProgress) -> Unit = {},
    ): UpdateCheckSummary = updateGate.withLock { runCheckAll(onProgress) }

    private suspend fun runCheckAll(
        onProgress: (UpdateProgress) -> Unit,
    ): UpdateCheckSummary {
        // A shelf can opt out of update checks; honoring it here keeps the
        // worker and the manual "check now" action consistent.
        val excludedShelves = excludedShelfIds()
        val books = library.libraryBooks()
            .filterNot { it.isLocalOnly() || it.shelfId in excludedShelves }
        if (books.isEmpty()) return UpdateCheckSummary(0, 0, 0)
        val gate = Semaphore(maxConcurrency.coerceAtLeast(1))
        val total = books.size
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val checked = AtomicInteger(0)
        val updated = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val firstError = AtomicReference<UpdateResult.Failed?>(null)
        val progressLock = Any()
        fun report(progress: UpdateProgress) = synchronized(progressLock) { onProgress(progress) }
        report(UpdateProgress(0, total, ""))
        coroutineScope {
            books.forEach { row ->
                launch {
                    gate.withPermit {
                        report(UpdateProgress(done.get(), total, row.title))
                        when (val result = check(
                            row.bookId,
                            row.url,
                            row.totalChapters,
                            row.chapterListHash,
                            row.installId,
                        )) {
                            is UpdateResult.Failed -> {
                                failed.incrementAndGet()
                                firstError.compareAndSet(null, result)
                            }
                            UpdateResult.NoChange -> checked.incrementAndGet()
                            is UpdateResult.Updated -> {
                                checked.incrementAndGet()
                                updated.incrementAndGet()
                            }
                        }
                        val completed = done.incrementAndGet()
                        report(UpdateProgress(completed, total, row.title))
                    }
                }
            }
        }
        val first = firstError.get()
        return UpdateCheckSummary(
            checked = checked.get(),
            updated = updated.get(),
            failed = failed.get(),
            firstError = first?.error,
            firstErrorSource = first?.takeIf { it.error != null }?.sourceId,
        )
    }

    /** Refresh one book; returns the number of newly discovered chapters. */
    suspend fun checkBook(bookUrl: String): Int = updateGate.withLock { runCheckBook(bookUrl) }

    private suspend fun runCheckBook(bookUrl: String): Int {
        val row = library.book(bookUrl) ?: return 0
        if (row.isLocalOnly() || row.shelfId in excludedShelfIds()) return 0
        return when (
            val result = check(
                row.bookId,
                row.url,
                row.totalChapters,
                row.chapterListHash,
                row.installId,
            )
        ) {
            is UpdateResult.Failed, UpdateResult.NoChange -> 0
            is UpdateResult.Updated -> result.newChapters
        }
    }

    suspend fun markSeen(bookUrl: String) {
        library.book(bookUrl)?.let { library.markUpdatesSeen(it.bookId, now()) }
    }

    private suspend fun excludedShelfIds(): Set<String> =
        library.shelves().filter { it.excludeFromUpdates }.map { it.id }.toSet()

    private fun BookEntity.isLocalOnly(): Boolean =
        installId.startsWith("local-") || url.startsWith("local://")

    private suspend fun check(
        bookId: Long,
        bookUrl: String,
        before: Int,
        localHash: String?,
        sourceId: String,
    ): UpdateResult {
        // Variant 1 (NoveLA getChapterListHash): a plugin-provided hash that
        // matches the stored one means the list is unchanged; skip the full
        // chapter fetch entirely. Variant 2 (no hash / changed hash): fetch
        // the chapter list and compare the count.
        val bookKey = libraryBookKey(sourceId, bookUrl)
        val remoteHash = try {
            provider.chapterListHash(bookKey)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (remoteHash != null && remoteHash == localHash) return UpdateResult.NoChange
        val refreshed = try {
            provider.refreshChapterCountResult(bookKey)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return UpdateResult.Failed(null, sourceId)
        }
        val error = refreshed.error
        val after = refreshed.count
        if (after == null || after <= 0) return UpdateResult.Failed(error, sourceId)
        // `refreshChapterCount` writes the fetched chapters and may already
        // update books.totalChapters, so the delta must come from the count
        // captured before the refresh, not from the current DB value.
        val newChapters = (after - before).coerceAtLeast(0)
        library.recordUpdateCheck(bookId, after, newChapters, remoteHash, now())
        return if (newChapters > 0) {
            UpdateResult.Updated(newChapters)
        } else {
            UpdateResult.NoChange
        }
    }

    private sealed interface UpdateResult {
        data object NoChange : UpdateResult
        data class Failed(val error: PluginError?, val sourceId: String) : UpdateResult
        data class Updated(val newChapters: Int) : UpdateResult
    }

    private companion object {
        /**
         * One check run at a time per process. The badge accumulates deltas, so
         * a background worker and the Updates screen firing together would both
         * read the old total and count the same new chapters twice.
         */
        val updateGate = Mutex()
    }
}
