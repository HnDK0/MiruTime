package dev.mirutime.app.features.downloads.data

import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.ChapterBodyEntity
import dev.mirutime.data.library.ChapterEntity
import dev.mirutime.data.library.ChapterPageEntity
import dev.mirutime.data.library.ChapterStatusSnapshot
import dev.mirutime.data.library.DownloadTaskEntity
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.LocalBookFileEntity
import kotlinx.coroutines.flow.Flow

/** Room-backed source of truth. No queue state lives only in memory. */
class RoomDownloadStore(
    private val repository: LibraryRepository,
) : DownloadStore {
    override suspend fun book(bookId: Long): BookEntity? = repository.book(bookId)

    override suspend fun bookByUrl(bookUrl: String): BookEntity? = repository.book(bookUrl)

    override suspend fun chapters(bookId: Long): List<ChapterEntity> = repository.chapters(bookId)

    override suspend fun chapter(chapterId: Long): ChapterEntity? = repository.chapter(chapterId)

    override suspend fun localBookFile(bookId: Long): LocalBookFileEntity? =
        repository.localBookFile(bookId)

    override suspend fun statusSnapshot(bookId: Long): ChapterStatusSnapshot? =
        repository.chapterStatusSnapshot(bookId)

    override suspend fun tasks(bookId: Long): List<DownloadTaskEntity> =
        repository.downloadTasksForBook(bookId)

    override suspend fun task(id: Long): DownloadTaskEntity? = repository.downloadTask(id)

    override suspend fun saveTask(task: DownloadTaskEntity): Long =
        repository.saveDownloadTask(task)

    override suspend fun deleteTask(id: Long) = repository.deleteDownloadTask(id)

    override suspend fun deleteTasks(bookId: Long) =
        repository.deleteDownloadTasksForBook(bookId)

    override suspend fun activeBookIds(): List<Long> = repository.booksWithActiveDownloads()

    override fun tasksFlow(): Flow<List<DownloadTaskEntity>> = repository.downloadTasksFlow()

    override fun tasksForBookFlow(bookId: Long): Flow<List<DownloadTaskEntity>> =
        repository.downloadTasksForBookFlow(bookId)

    override suspend fun body(chapterId: Long): ChapterBodyEntity? =
        repository.chapterBody(chapterId)

    override suspend fun saveBody(body: ChapterBodyEntity) = repository.saveChapterBody(body)

    override suspend fun deleteBody(chapterId: Long) = repository.deleteChapterBody(chapterId)

    override suspend fun page(chapterId: Long): ChapterPageEntity? =
        repository.chapterPage(chapterId)

    override suspend fun savePage(page: ChapterPageEntity) = repository.saveChapterPage(page)

    override suspend fun deletePage(chapterId: Long) = repository.deleteChapterPage(chapterId)

    override suspend fun setChapterDownloaded(chapterId: Long, downloaded: Boolean) =
        repository.setChapterDownloaded(chapterId, downloaded)
}
