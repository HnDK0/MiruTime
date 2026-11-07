package dev.mirutime.app.features.downloads.data

import dev.mirutime.app.features.book.data.BookDataProvider
import dev.mirutime.app.features.book.data.MembershipResult
import dev.mirutime.app.features.book.domain.BookChapter
import dev.mirutime.app.features.book.domain.BookDetails
import dev.mirutime.core.reader.ChapterContent
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.ChapterBodyEntity
import dev.mirutime.data.library.ChapterDownloadState
import dev.mirutime.data.library.ChapterEntity
import dev.mirutime.data.library.ChapterPageEntity
import dev.mirutime.data.library.ChapterStatusSnapshot
import dev.mirutime.data.library.DownloadTaskEntity
import dev.mirutime.data.library.LocalBookFileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

internal fun testBook(
    bookId: Long = 1,
    url: String = "https://example.test/book/1",
    contentType: String = "Novel",
    totalChapters: Int = 3,
): BookEntity = BookEntity(
    bookId = bookId,
    installId = "test",
    url = url,
    title = "Test book",
    author = "Author",
    coverUrl = null,
    contentType = contentType,
    rating = 0.0,
    shelfId = "reading",
    totalChapters = totalChapters,
    inLibrary = true,
    addedAt = 1,
    updatedAt = 1,
    lastReadAt = null,
    lastChapterId = null,
    lastChapterIndex = null,
    chapterListHash = null,
    lastUpdateAt = null,
    genresJson = "[]",
)

internal fun testChapter(
    chapterId: Long,
    bookId: Long = 1,
    idx: Int,
    title: String = "Chapter $idx",
): ChapterEntity = ChapterEntity(
    chapterId = chapterId,
    bookId = bookId,
    idx = idx,
    url = "https://example.test/book/1/$idx",
    title = title,
    updatedAt = 1,
)

internal class FakeDownloadStore(
    var bookRow: BookEntity? = testBook(),
    chapters: List<ChapterEntity> = (1..3).map { testChapter(it.toLong(), idx = it) },
) : DownloadStore {
    val chapterRows = chapters.toMutableList()
    val tasksById = LinkedHashMap<Long, DownloadTaskEntity>()
    val bodies = HashMap<Long, ChapterBodyEntity>()
    val pages = HashMap<Long, ChapterPageEntity>()
    val downloaded = HashSet<Long>()
    // Threads that executed DB deletion, so tests can assert IO dispatcher use.
    val deleteThreads = mutableListOf<String>()
    var failDeleteBodyChapterId: Long? = null
    // Optional test gate for the first N chapter-list reads (concurrent delete).
    var chaptersGate: (() -> Unit)? = null
    private val chaptersGateHits = java.util.concurrent.atomic.AtomicInteger()
    private var nextId = 1L

    fun addTask(
        chapterId: Long,
        state: DownloadState = DownloadState.QUEUED,
        kind: DownloadKind = DownloadKind.TEXT,
        error: String? = null,
    ): DownloadTaskEntity {
        val task = DownloadTaskEntity(
            id = nextId++,
            bookId = bookRow?.bookId ?: 1,
            chapterId = chapterId,
            kind = kind.wire,
            state = state.wire,
            createdAt = nextId,
            updatedAt = nextId,
            error = error,
        )
        tasksById[task.id] = task
        return task
    }

    override suspend fun book(bookId: Long): BookEntity? = bookRow?.takeIf { it.bookId == bookId }

    override suspend fun bookByUrl(bookUrl: String): BookEntity? =
        bookRow?.takeIf { it.url == bookUrl }

    override suspend fun chapters(bookId: Long): List<ChapterEntity> {
        chaptersGate?.let { gate ->
            // Only the pre-lock snapshots of two concurrent deletes meet here;
            // the fresh in-lock reads must not block.
            if (chaptersGateHits.incrementAndGet() <= 2) gate()
        }
        return chapterRows.map { chapter ->
            // The set stands in for the `chapters.downloaded` column.
            if (chapter.chapterId in downloaded) chapter.copy(downloaded = true) else chapter
        }
    }

    override suspend fun chapter(chapterId: Long): ChapterEntity? =
        chapterRows.firstOrNull { it.chapterId == chapterId }?.let { chapter ->
            if (chapter.chapterId in downloaded) chapter.copy(downloaded = true) else chapter
        }

    override suspend fun localBookFile(bookId: Long): LocalBookFileEntity? = null

    override suspend fun statusSnapshot(bookId: Long): ChapterStatusSnapshot? = bookRow?.let {
        val taskStates = tasksById.values.mapNotNull { task ->
            val index = chapterRows.firstOrNull { it.chapterId == task.chapterId }?.idx
                ?: return@mapNotNull null
            index to ChapterDownloadState(task.state, task.progress)
        }.toMap()
        ChapterStatusSnapshot(
            bookId = bookId,
            chapterCount = chapterRows.size,
            readIndexes = emptySet(),
            downloadedIndexes = chapterRows.filter {
                it.chapterId in downloaded ||
                    it.chapterId in bodies ||
                    it.chapterId in pages
            }.mapTo(HashSet()) { it.idx },
            downloadedUrls = emptySet(),
            translatedIndexes = emptySet(),
            translatedUrls = emptySet(),
            lastIndex = chapterRows.maxOfOrNull { it.idx },
            lastChapterId = chapterRows.maxByOrNull { it.idx }?.chapterId,
            lastReadIndex = null,
            lastReadUrl = null,
            downloads = taskStates,
        )
    }

    override suspend fun tasks(bookId: Long): List<DownloadTaskEntity> =
        tasksById.values.filter { it.bookId == bookId }

    override suspend fun task(id: Long): DownloadTaskEntity? = tasksById[id]

    override suspend fun saveTask(task: DownloadTaskEntity): Long {
        val id = if (task.id == 0L) nextId++ else task.id
        tasksById[id] = task.copy(id = id)
        return id
    }

    override suspend fun deleteTask(id: Long) {
        tasksById.remove(id)
    }

    override suspend fun deleteTasks(bookId: Long) {
        tasksById.values.removeAll { it.bookId == bookId }
    }

    override suspend fun activeBookIds(): List<Long> =
        tasksById.values.filter {
            DownloadState.from(it.state)?.isActive == true
        }.map { it.bookId }.distinct()

    override fun tasksFlow(): Flow<List<DownloadTaskEntity>> =
        MutableStateFlow(tasksById.values.toList())

    override fun tasksForBookFlow(bookId: Long): Flow<List<DownloadTaskEntity>> =
        MutableStateFlow(tasksById.values.filter { it.bookId == bookId })

    override suspend fun body(chapterId: Long): ChapterBodyEntity? = bodies[chapterId]

    override suspend fun saveBody(body: ChapterBodyEntity) {
        bodies[body.chapterId] = body
    }

    override suspend fun deleteBody(chapterId: Long) {
        deleteThreads += Thread.currentThread().name
        if (chapterId == failDeleteBodyChapterId) error("delete body failed")
        bodies.remove(chapterId)
    }

    override suspend fun page(chapterId: Long): ChapterPageEntity? = pages[chapterId]

    override suspend fun savePage(page: ChapterPageEntity) {
        pages[page.chapterId] = page
    }

    override suspend fun deletePage(chapterId: Long) {
        pages.remove(chapterId)
    }

    override suspend fun setChapterDownloaded(chapterId: Long, downloaded: Boolean) {
        if (downloaded) this.downloaded += chapterId else this.downloaded -= chapterId
    }
}

internal class FakeScheduler : DownloadScheduler {
    val enqueued = mutableListOf<Long>()
    val cancelled = mutableListOf<Long>()

    override fun enqueue(bookId: Long) {
        enqueued += bookId
    }

    override fun cancel(bookId: Long) {
        cancelled += bookId
    }
}

internal class FakeProvider : BookDataProvider {
    var contents: MutableMap<Int, Update> = mutableMapOf()
    // Test seam: runs after the chapter payload is produced, before the engine
    // commits it.
    var onContent: (suspend () -> Unit)? = null
    var pageUrls: MutableMap<Int, List<String>> = mutableMapOf()
    var failures: MutableMap<Int, Throwable> = mutableMapOf()
    val chapterDownloadedCalls = mutableListOf<Triple<String, IntRange, Boolean>>()

    data class Update(val text: String?, val pages: List<String> = emptyList())

    override suspend fun book(bookUrl: String): BookDetails? = null

    override suspend fun ensureHistoryBook(bookUrl: String) = Unit

    override suspend fun chapters(bookUrl: String): List<BookChapter> = emptyList()

    override suspend fun chapterContent(bookUrl: String, chapterIndex: Int): ChapterContent? {
        failures[chapterIndex]?.let { throw it }
        val update = contents[chapterIndex] ?: return null
        val text = update.text ?: return null
        onContent?.invoke()
        return ChapterContent(
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            title = "Chapter $chapterIndex",
            html = text,
        )
    }

    override suspend fun chapterPages(bookUrl: String, chapterIndex: Int): List<String> {
        failures[chapterIndex]?.let { throw it }
        return pageUrls[chapterIndex].orEmpty()
    }

    override suspend fun moveShelf(bookUrl: String, shelfId: String) = Unit

    override suspend fun setInLibrary(bookUrl: String, inLibrary: Boolean): MembershipResult =
        MembershipResult(success = true)

    override fun setChapterDownloaded(bookUrl: String, range: IntRange, downloaded: Boolean) {
        chapterDownloadedCalls += Triple(bookUrl, range, downloaded)
    }

    override suspend fun downloadNext100(bookUrl: String) = Unit

    override suspend fun markChapterRead(bookUrl: String, chapterIndex: Int, read: Boolean) = Unit
}
