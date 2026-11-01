package dev.mirutime.app.features.translation.data

import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.ChapterBodyEntity
import dev.mirutime.data.library.ChapterEntity
import dev.mirutime.data.library.ChapterTranslationEntity
import dev.mirutime.data.library.ChapterTranslationQueueRow
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.TranslationBlockEntity

/**
 * Persistence seam for the download-triggered translation queue. Room is the
 * production implementation; tests use an in-memory fake. The queue lives in
 * the existing `chapter_translations` table, so no schema bump is involved.
 */
interface TranslationQueueStore {
    suspend fun book(bookId: Long): BookEntity?
    suspend fun chapter(chapterId: Long): ChapterEntity?
    suspend fun body(chapterId: Long): ChapterBodyEntity?
    suspend fun hasPages(chapterId: Long): Boolean
    suspend fun downloadState(chapterId: Long): String?
    suspend fun queueRows(bookId: Long): List<ChapterTranslationQueueRow>
    suspend fun latest(
        chapterId: Long,
        source: String,
        target: String,
        provider: String,
        promptHash: String,
        pipelineVersion: String,
    ): ChapterTranslationEntity?
    suspend fun save(row: ChapterTranslationEntity): Long
    suspend fun blocks(translationId: Long): List<TranslationBlockEntity>
    suspend fun delete(translationId: Long)

    /** Exact cache row for the real (post-download) source hash. */
    suspend fun exact(
        chapterId: Long,
        source: String,
        target: String,
        provider: String,
        promptHash: String,
        sourceHash: String,
        pipelineVersion: String,
    ): ChapterTranslationEntity?
}

class RoomTranslationQueueStore(
    private val library: LibraryRepository,
) : TranslationQueueStore {
    override suspend fun book(bookId: Long): BookEntity? = library.book(bookId)

    override suspend fun chapter(chapterId: Long): ChapterEntity? = library.chapter(chapterId)

    override suspend fun body(chapterId: Long): ChapterBodyEntity? = library.chapterBody(chapterId)

    override suspend fun hasPages(chapterId: Long): Boolean = library.chapterPage(chapterId) != null

    override suspend fun downloadState(chapterId: Long): String? =
        library.downloadTaskForChapter(chapterId)?.state

    override suspend fun queueRows(bookId: Long): List<ChapterTranslationQueueRow> =
        library.chapterTranslationQueueRows(bookId)

    override suspend fun latest(
        chapterId: Long,
        source: String,
        target: String,
        provider: String,
        promptHash: String,
        pipelineVersion: String,
    ): ChapterTranslationEntity? = library.latestChapterTranslation(
        chapterId = chapterId,
        source = source,
        target = target,
        provider = provider,
        promptHash = promptHash,
        pipelineVersion = pipelineVersion,
    )

    override suspend fun save(row: ChapterTranslationEntity): Long =
        library.saveChapterTranslation(row)

    override suspend fun blocks(translationId: Long): List<TranslationBlockEntity> =
        library.translationBlocks(translationId)

    override suspend fun delete(translationId: Long) = library.deleteChapterTranslation(translationId)

    override suspend fun exact(
        chapterId: Long,
        source: String,
        target: String,
        provider: String,
        promptHash: String,
        sourceHash: String,
        pipelineVersion: String,
    ): ChapterTranslationEntity? = library.chapterTranslation(
        chapterId = chapterId,
        source = source,
        target = target,
        provider = provider,
        promptHash = promptHash,
        sourceHash = sourceHash,
        pipelineVersion = pipelineVersion,
    )
}
