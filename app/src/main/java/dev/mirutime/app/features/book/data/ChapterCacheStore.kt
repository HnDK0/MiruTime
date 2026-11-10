package dev.mirutime.app.features.book.data

import dev.mirutime.app.features.downloads.data.DownloadFileStore
import dev.mirutime.data.library.BookWriteLocks
import dev.mirutime.data.library.ChapterBodyEntity
import dev.mirutime.data.library.ChapterEntity
import dev.mirutime.data.library.ChapterPageEntity
import dev.mirutime.data.library.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cache-on-read writer for remote chapters. It uses the download engine's file
 * store and body/page tables, so a successful reader open survives restart and
 * counts as downloaded, but deliberately never writes download_tasks and never
 * completes a user's queued download.
 */
internal class ChapterCacheStore(
    private val repository: ChapterCacheRepository,
    private val files: DownloadFileStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val bookLocks: BookWriteLocks = BookWriteLocks(),
) {
    suspend fun saveText(bookUrl: String, chapterIndex: Int, html: String) {
        if (html.isBlank()) return
        val chapter = repository.chapter(bookUrl, chapterIndex) ?: return
        bookLocks.withLock(bookLocks.bookKey(chapter.bookId)) {
            val existing = repository.chapterBody(chapter.chapterId)
            if (existing != null && withContext(Dispatchers.IO) { files.textExists(existing.path) }) {
                return@withLock
            }
            val stored = withContext(Dispatchers.IO) {
                files.writeText(chapter.bookId, chapter.chapterId, html)
            }
            repository.saveChapterBody(
                ChapterBodyEntity(
                    chapterId = chapter.chapterId,
                    format = "html",
                    path = stored.path,
                    bytes = stored.bytes,
                    sha256 = stored.sha256,
                    fetchedAt = now(),
                ),
            )
        }
    }

    /**
     * Stores the page bytes so the chapter opens offline after a restart. An
     * already complete page row is skipped (no re-download). A page failure
     * aborts the write, so the next open retries instead of claiming a partial
     * chapter is downloaded.
     */
    suspend fun savePages(
        bookUrl: String,
        chapterIndex: Int,
        pageUrls: List<String>,
        fetchPage: suspend (String) -> ByteArray?,
    ): Boolean {
        if (pageUrls.isEmpty()) return false
        val chapter = repository.chapter(bookUrl, chapterIndex) ?: return false
        val cached = repository.chapterPage(chapter.chapterId)
        if (cached != null && withContext(Dispatchers.IO) { files.pagesExist(cached.pagePathsJson) }) return true
        // Fetch one page at a time: holding a whole manga chapter in memory is
        // an OOM. The network stays outside the per-book lock; only each file
        // write and the final row commit are serialized.
        val bookKey = bookLocks.bookKey(chapter.bookId)
        val paths = ArrayList<String>(pageUrls.size)
        var totalBytes = 0L
        for ((index, url) in pageUrls.withIndex()) {
            val bytes = fetchPage(url) ?: return false
            var alreadyCached = false
            bookLocks.withLock(bookKey) {
                val existing = repository.chapterPage(chapter.chapterId)
                if (existing != null && withContext(Dispatchers.IO) {
                        files.pagesExist(existing.pagePathsJson)
                    }
                ) {
                    alreadyCached = true
                    return@withLock
                }
                paths += withContext(Dispatchers.IO) {
                    files.writePage(chapter.bookId, chapter.chapterId, index, url, bytes)
                }
                totalBytes += bytes.size
            }
            if (alreadyCached) return true
        }
        bookLocks.withLock(bookKey) {
            repository.saveChapterPage(
                ChapterPageEntity(
                    chapterId = chapter.chapterId,
                    format = "images",
                    pagePathsJson = files.encodePagePaths(paths),
                    totalBytes = totalBytes,
                ),
            )
        }
        return true
    }
}

/** Narrow persistence seam so cache-on-read tests need no Room database. */
internal interface ChapterCacheRepository {
    suspend fun chapter(bookUrl: String, chapterIndex: Int): ChapterEntity?
    suspend fun chapterBody(chapterId: Long): ChapterBodyEntity?
    suspend fun chapterPage(chapterId: Long): ChapterPageEntity?
    suspend fun saveChapterBody(body: ChapterBodyEntity)
    suspend fun saveChapterPage(page: ChapterPageEntity)
}

internal class LibraryChapterCacheRepository(
    private val library: LibraryRepository,
) : ChapterCacheRepository {
    override suspend fun chapter(bookUrl: String, chapterIndex: Int): ChapterEntity? {
        val book = library.book(bookUrl) ?: return null
        return library.chapterByIndex(book.bookId, chapterIndex)
    }

    override suspend fun chapterBody(chapterId: Long): ChapterBodyEntity? =
        library.chapterBody(chapterId)

    override suspend fun chapterPage(chapterId: Long): ChapterPageEntity? =
        library.chapterPage(chapterId)

    override suspend fun saveChapterBody(body: ChapterBodyEntity) =
        library.saveChapterBody(body)

    override suspend fun saveChapterPage(page: ChapterPageEntity) =
        library.saveChapterPage(page)
}
