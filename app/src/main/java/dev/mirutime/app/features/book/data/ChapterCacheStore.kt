package dev.mirutime.app.features.book.data

import dev.mirutime.app.features.downloads.data.DownloadFileStore
import dev.mirutime.data.library.BookWriteLocks
import dev.mirutime.data.library.ChapterBodyEntity
import dev.mirutime.data.library.ChapterEntity
import dev.mirutime.data.library.ChapterPageEntity
import dev.mirutime.data.library.LibraryRepository

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
            if (existing != null && files.textExists(existing.path)) return@withLock
            val stored = files.writeText(chapter.bookId, chapter.chapterId, html)
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
        if (cached != null && files.pagesExist(cached.pagePathsJson)) return true
        // Network work stays outside the per-book write lock; only file rows
        // and the DB commit need serialization.
        val bytesByPage = pageUrls.map { url -> fetchPage(url) ?: return false }
        return bookLocks.withLock(bookLocks.bookKey(chapter.bookId)) {
            val existing = repository.chapterPage(chapter.chapterId)
            if (existing != null && files.pagesExist(existing.pagePathsJson)) {
                return@withLock true
            }
            val paths = ArrayList<String>(pageUrls.size)
            var totalBytes = 0L
            pageUrls.forEachIndexed { index, url ->
                val bytes = bytesByPage[index]
                paths += files.writePage(chapter.bookId, chapter.chapterId, index, url, bytes)
                totalBytes += bytes.size
            }
            repository.saveChapterPage(
                ChapterPageEntity(
                    chapterId = chapter.chapterId,
                    format = "images",
                    pagePathsJson = files.encodePagePaths(paths),
                    totalBytes = totalBytes,
                ),
            )
            true
        }
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
