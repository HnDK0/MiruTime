package dev.mirutime.data.library

import androidx.room3.withWriteTransaction

/**
 * Restores a backup database into the live database without replacing its
 * file. All writes happen inside one write transaction, so a failure leaves
 * the live library exactly as it was.
 *
 * Identity is merged, never duplicated:
 * - books match on `(installId, url)`, chapters on `(bookId, url)`, tags on the
 *   normalized name, sessions on `sessionKey` (or their natural identity) and
 *   downloads on `(bookId, chapterId, kind)`;
 * - every other row is remapped through those id maps before it is written.
 *
 * Local user overrides are kept: an existing book keeps its shelf, a user cover
 * (`isUserOverride`) is not replaced, and an install keeps its base-URL
 * override.
 */
const val RestoreJournalStatus = "restore-journal"

data class LibraryImportReport(
    val books: Int = 0,
    val chapters: Int = 0,
    val progress: Int = 0,
    val videoProgress: Int = 0,
    val sessions: Int = 0,
    val downloads: Int = 0,
    val highlights: Int = 0,
    val localFiles: Int = 0,
    val translations: Int = 0,
    val ttsChunks: Int = 0,
    val bookIdMap: Map<Long, Long> = emptyMap(),
    val chapterIdMap: Map<Long, Long> = emptyMap(),
    val preservedCoverBookIds: Set<Long> = emptySet(),
    val journalId: Long? = null,
)

/**
 * Imports [source] into this database inside a single transaction.
 *
 * [coverPathFor] lets the host rewrite a cover path whose file name depends on
 * `bookId` (the app stores covers under `covers/<bookId>/cover`). The caller
 * copies staged files after this transaction commits, using the returned maps.
 * runs after book/cover ids are known and before the transaction commits, so
 * callers can copy the matching files after the transaction commits.
 * [preservedCovers] are existing user-override covers whose file must not be
 * overwritten by the backup.
 */
suspend fun LibraryDatabase.importLibraryBackup(
    source: LibraryDao,
    pageSize: Int = DEFAULT_IMPORT_PAGE_SIZE,
    coverPathFor: (oldBookId: Long, newBookId: Long, oldPath: String?) -> String? =
        { _, _, path -> path },
    journalId: Long? = null,
    journalNow: Long = 0L,
): LibraryImportReport {
    val report = withWriteTransaction {
        importLibraryBackupRows(
            source = source,
            target = libraryDao(),
            pageSize = pageSize,
            coverPathFor = coverPathFor,
            journalId = journalId,
            journalNow = journalNow,
        )
    }
    // Derived state: rebuild the FTS index so search sees restored books
    // immediately. Cache cleanup is intentionally NOT part of restore: dropping
    // rows here would orphan cached files and could remove offline content.
    // Users clear caches explicitly from Settings -> Data.
    val repository = LibraryRepository(libraryDao())
    runCatching {
        repository.ensureDefaultShelves()
        repository.rebuildSearchIndex()
    }
    return report
}

private suspend fun importLibraryBackupRows(
    source: LibraryDao,
    target: LibraryDao,
    pageSize: Int,
    coverPathFor: (Long, Long, String?) -> String?,
    journalId: Long?,
    journalNow: Long,
): LibraryImportReport {
    // --- parents -------------------------------------------------------------
    source.installs().forEach { install ->
        val existing = target.install(install.installId)
        target.upsertInstall(
            if (existing == null || existing.baseUrlOverride == null) {
                install
            } else {
                install.copy(
                    baseUrlOverride = existing.baseUrlOverride,
                    baseUrlEffective = existing.baseUrlEffective,
                )
            },
        )
    }
    target.upsertShelves(source.shelves())

    val localBookIds = source.localBookFileIds().toHashSet()
    val bookIds = LinkedHashMap<Long, Long>()
    val lastChapterIds = HashMap<Long, Long?>()
    forEachPage(pageSize, BookEntity::bookId, source::backupBooks) { book ->
        val existing = target.bookByInstallAndUrl(book.installId, book.url)
        bookIds[book.bookId] = if (existing == null) {
            target.upsertBook(book.copy(bookId = 0))
        } else {
            // Keep the local shelf assignment; everything else is restored.
            target.upsertBook(book.copy(bookId = existing.bookId, shelfId = existing.shelfId))
            existing.bookId
        }.also { newId ->
            if (book.lastChapterId != null) lastChapterIds[newId] = book.lastChapterId
        }
    }
    val preservedCovers = LinkedHashSet<Long>()
    forEachPage(pageSize, BookCoverEntity::bookId, source::backupCovers) { cover ->
        val bookId = bookIds[cover.bookId] ?: return@forEachPage
        val existing = target.bookCover(bookId)
        if (existing?.isUserOverride == true) {
            preservedCovers += bookId
            return@forEachPage
        }
        target.upsertBookCover(
            cover.copy(
                bookId = bookId,
                localPath = coverPathFor(cover.bookId, bookId, cover.localPath),
            ),
        )
    }

    val localBookTargetIds = localBookIds.mapNotNull { bookIds[it] }.toHashSet()
    val chapterIds = LinkedHashMap<Long, Long>()
    val chapterBookIds = HashMap<Long, Long>()
    forEachPage(pageSize, ChapterEntity::chapterId, source::backupChapters) { chapter ->
        val bookId = bookIds[chapter.bookId] ?: return@forEachPage
        chapterBookIds[chapter.chapterId] = bookId
        val existing = target.chapterByUrl(bookId, chapter.url)
        chapterIds[chapter.chapterId] = if (existing == null) {
            target.upsertChapter(chapter.copy(chapterId = 0, bookId = bookId))
        } else {
            target.upsertChapter(
                chapter.copy(chapterId = existing.chapterId, bookId = bookId),
            )
            existing.chapterId
        }
    }

    lastChapterIds.forEach { (bookId, oldChapterId) ->
        val chapterId = oldChapterId?.let { chapterIds[it] }
        if (chapterId != oldChapterId) target.setLastChapterId(bookId, chapterId)
    }

    // --- reading state -------------------------------------------------------
    var progressCount = 0
    forEachPage(pageSize, ProgressEntity::bookId, source::backupProgress) { progress ->
        val bookId = bookIds[progress.bookId] ?: return@forEachPage
        val chapterId = chapterIds[progress.chapterId] ?: return@forEachPage
        target.saveProgressIfNewer(
            progress.copy(bookId = bookId, chapterId = chapterId),
        )
        progressCount++
    }

    var videoProgressCount = 0
    var lastVideoBookId = 0L
    var lastVideoChapterId = 0L
    while (true) {
        val rows = source.backupVideoProgress(lastVideoBookId, lastVideoChapterId, pageSize)
        if (rows.isEmpty()) break
        rows.forEach { progress ->
            val bookId = bookIds[progress.bookId] ?: return@forEach
            val chapterId = chapterIds[progress.chapterId] ?: return@forEach
            target.upsertVideoProgress(
                progress.copy(bookId = bookId, chapterId = chapterId),
            )
            videoProgressCount++
        }
        lastVideoBookId = rows.last().bookId
        lastVideoChapterId = rows.last().chapterId
        if (rows.size < pageSize) break
    }

    var sessionCount = 0
    forEachPage(pageSize, ReadingSessionEntity::id, source::backupSessions) { session ->
        val bookId = bookIds[session.bookId] ?: return@forEachPage
        val chapterId = session.chapterId?.let { chapterIds[it] }
            ?: if (session.chapterId == null) null else return@forEachPage
        val key = session.sessionKey
        if (key != null && target.sessionByKey(key) != null) return@forEachPage
        if (target.sessionWithNaturalKeyCount(
                bookId = bookId,
                chapterId = chapterId,
                startedAt = session.startedAt,
                durationMs = session.durationMs,
                kind = session.kind,
            ) > 0
        ) {
            return@forEachPage
        }
        target.upsertSession(
            session.copy(id = 0, bookId = bookId, chapterId = chapterId),
        )
        sessionCount++
    }

    var downloadCount = 0
    forEachPage(pageSize, DownloadTaskEntity::id, source::backupDownloadTasks) { task ->
        val bookId = bookIds[task.bookId] ?: return@forEachPage
        val chapterId = chapterIds[task.chapterId] ?: return@forEachPage
        val existing = target.downloadTask(bookId, chapterId, task.kind)
        target.upsertDownloadTask(
            task.copy(
                id = existing?.id ?: 0,
                bookId = bookId,
                chapterId = chapterId,
            ),
        )
        downloadCount++
    }

    // The archive only carries files for imported local books. Cached plugin
    // bodies/pages would be dangling rows, so they are left out and can be
    // fetched again on demand.
    forEachPage(pageSize, ChapterBodyEntity::chapterId, source::backupChapterBodies) { body ->
        val chapterId = chapterIds[body.chapterId] ?: return@forEachPage
        val bookId = chapterBookIds[body.chapterId] ?: return@forEachPage
        if (bookId !in localBookTargetIds) return@forEachPage
        target.upsertChapterBody(body.copy(chapterId = chapterId))
    }

    forEachPage(pageSize, ChapterPageEntity::chapterId, source::backupChapterPages) { page ->
        val chapterId = chapterIds[page.chapterId] ?: return@forEachPage
        val bookId = chapterBookIds[page.chapterId] ?: return@forEachPage
        if (bookId !in localBookTargetIds) return@forEachPage
        target.upsertChapterPage(page.copy(chapterId = chapterId))
    }

    // --- tags / local files --------------------------------------------------
    val tagIds = LinkedHashMap<Long, Long>()
    forEachPage(pageSize, TagEntity::tagId, source::backupTags) { tag ->
        val existing = target.tagByNormalized(tag.normalizedName)
        tagIds[tag.tagId] = existing?.tagId ?: target.upsertTag(tag.copy(tagId = 0))
    }
    forEachOffsetPage(pageSize, source::backupBookTags) { tag ->
        val bookId = bookIds[tag.bookId] ?: return@forEachOffsetPage
        val tagId = tagIds[tag.tagId] ?: return@forEachOffsetPage
        target.upsertBookTag(tag.copy(bookId = bookId, tagId = tagId))
    }

    var localFileCount = 0
    forEachPage(pageSize, LocalBookFileEntity::bookId, source::backupLocalBookFiles) { file ->
        val bookId = bookIds[file.bookId] ?: return@forEachPage
        target.upsertLocalBookFile(file.copy(bookId = bookId))
        localFileCount++
    }

    var highlightCount = 0
    forEachPage(pageSize, HighlightEntity::id, source::backupHighlights) { highlight ->
        val bookId = bookIds[highlight.bookId] ?: return@forEachPage
        val chapterId = chapterIds[highlight.chapterId] ?: return@forEachPage
        val existingId = target.highlightId(
            bookId = bookId,
            chapterId = chapterId,
            blockIndex = highlight.blockIndex,
            startOffset = highlight.startOffset,
            endOffset = highlight.endOffset,
            textKind = highlight.textKind,
            sourceHash = highlight.sourceHash,
        )
        target.upsertHighlight(
            highlight.copy(
                id = existingId ?: 0,
                bookId = bookId,
                chapterId = chapterId,
            ),
        )
        highlightCount++
    }

    // --- translations / TTS --------------------------------------------------
    val chapterTranslationIds = LinkedHashMap<Long, Long>()
    var translationCount = 0
    forEachPage(
        pageSize,
        ChapterTranslationEntity::translationId,
        source::backupChapterTranslations,
    ) { translation ->
        val chapterId = chapterIds[translation.chapterId] ?: return@forEachPage
        // v7 identity: same chapter + language pair + provider + prompt +
        // pipeline updates the existing row; a different sourceHash is just a
        // newer revision of the same translation.
        val existing = target.chapterTranslationAnySource(
            chapterId = chapterId,
            source = translation.source,
            target = translation.target,
            provider = translation.provider,
            promptHash = translation.promptHash,
            pipelineVersion = translation.pipelineVersion,
        )
        chapterTranslationIds[translation.translationId] = existing?.translationId
            ?: target.upsertChapterTranslation(
                translation.copy(translationId = 0, chapterId = chapterId),
            )
        translationCount++
    }
    forEachOffsetPage(pageSize, source::backupTranslationBlocks) { block ->
        val translationId = chapterTranslationIds[block.translationId] ?: return@forEachOffsetPage
        target.upsertTranslationBlock(block.copy(translationId = translationId))
    }

    forEachPage(
        pageSize,
        BookTranslationEntity::translationId,
        source::backupBookTranslations,
    ) { translation ->
        val bookId = bookIds[translation.bookId] ?: return@forEachPage
        val existing = target.bookTranslation(
            bookId = bookId,
            source = translation.source,
            target = translation.target,
            provider = translation.provider,
            promptHash = translation.promptHash,
            pipelineVersion = translation.pipelineVersion,
        )
        target.upsertBookTranslation(
            translation.copy(
                translationId = existing?.translationId ?: 0,
                bookId = bookId,
            ),
        )
    }

    var ttsCount = 0
    forEachOffsetPage(pageSize, source::backupTtsChunks) { chunk ->
        val chapterId = chapterIds[chunk.chapterId] ?: return@forEachOffsetPage
        target.upsertTtsChunk(chunk.copy(chapterId = chapterId))
        ttsCount++
    }

    if (journalId != null) {
        // Reserved restore journal inside the same transaction. Recovery uses
        // its primary key to tell "committed but marker missing" from "rolled
        // back"; user-facing migration queries filter the status out.
        target.upsertBookMigration(
            BookMigrationEntity(
                id = journalId,
                oldBookId = null,
                newBookId = null,
                oldInstallId = null,
                newInstallId = null,
                batchId = journalId.toString(),
                optionsJson = "{\"kind\":\"restore-journal\"}",
                unmatchedJson = "[]",
                chaptersTotal = chapterIds.size,
                chaptersMatched = chapterIds.size,
                chaptersWithProgress = progressCount,
                status = RestoreJournalStatus,
                error = null,
                createdAt = journalNow,
                finishedAt = null,
            ),
        )
    }

    return LibraryImportReport(
        books = bookIds.size,
        chapters = chapterIds.size,
        progress = progressCount,
        videoProgress = videoProgressCount,
        sessions = sessionCount,
        downloads = downloadCount,
        highlights = highlightCount,
        localFiles = localFileCount,
        translations = translationCount,
        ttsChunks = ttsCount,
        bookIdMap = bookIds,
        chapterIdMap = chapterIds,
        preservedCoverBookIds = preservedCovers,
        journalId = journalId,
    )
}

private suspend fun <T> forEachPage(
    pageSize: Int,
    id: (T) -> Long,
    fetch: suspend (afterId: Long, limit: Int) -> List<T>,
    block: suspend (T) -> Unit,
) {
    var after = 0L
    while (true) {
        val rows = fetch(after, pageSize)
        if (rows.isEmpty()) return
        rows.forEach { block(it) }
        after = id(rows.last())
        if (rows.size < pageSize) return
    }
}

private suspend fun <T> forEachOffsetPage(
    pageSize: Int,
    fetch: suspend (offset: Int, limit: Int) -> List<T>,
    block: suspend (T) -> Unit,
) {
    var offset = 0
    while (true) {
        val rows = fetch(offset, pageSize)
        if (rows.isEmpty()) return
        rows.forEach { block(it) }
        if (rows.size < pageSize) return
        offset += rows.size
    }
}

private const val DEFAULT_IMPORT_PAGE_SIZE = 256
