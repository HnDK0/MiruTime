package dev.mirutime.data.library

import androidx.room3.withWriteTransaction
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
 * [importCachedBodies] also restores cached plugin bodies (pages stay local-only).
 */
suspend fun LibraryDatabase.importLibraryBackup(
    source: LibraryDao,
    pageSize: Int = DEFAULT_IMPORT_PAGE_SIZE,
    importCachedBodies: Boolean = false,
    coverPathFor: (oldBookId: Long, newBookId: Long, oldPath: String?) -> String? =
        { _, _, path -> path },
    journalId: Long? = null,
    journalNow: Long = 0L,
    onBookKeyRewritten: suspend (oldUrl: String, newUrl: String) -> Unit = { _, _ -> },
    repair: suspend () -> Unit = {},
    onCommitted: suspend (LibraryImportReport) -> Unit = {},
): LibraryImportReport {
    val rewrites = mutableListOf<Pair<String, String>>()
    val report = withWriteTransaction {
        importLibraryBackupRows(
            source = source,
            target = libraryDao(),
            pageSize = pageSize,
            importCachedBodies = importCachedBodies,
            coverPathFor = coverPathFor,
            journalId = journalId,
            journalNow = journalNow,
            rewrites = rewrites,
        )
    }
    // The transaction is durable now. Tell the caller before any post-commit
    // work (repair/FTS) so a cancellation there cannot be mistaken for a
    // failed import and roll back committed files.
    onCommitted(report)
    // Settings writes are not part of the DB transaction: apply them only after
    // a successful commit. Cancellation is never swallowed.
    rewrites.forEach { (oldUrl, newUrl) ->
        runIgnoringFailure { onBookKeyRewritten(oldUrl, newUrl) }
    }
    // Derived state: rebuild the FTS index so search sees restored books
    // immediately. Cache cleanup is intentionally NOT part of restore: dropping
    // rows here would orphan cached files and could remove offline content.
    // Users clear caches explicitly from Settings -> Data.
    val repository = LibraryRepository(libraryDao())
    // Legacy key-URL rows are repaired before shelves and the one FTS rebuild,
    // so a live restore is clean in the same session.
    runIgnoringFailure { repair() }
    runIgnoringFailure { repository.ensureDefaultShelves() }
    runIgnoringFailure { repository.rebuildSearchIndex() }
    return report
}

private suspend fun runIgnoringFailure(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }
}

private suspend fun importLibraryBackupRows(
    source: LibraryDao,
    target: LibraryDao,
    pageSize: Int,
    importCachedBodies: Boolean,
    coverPathFor: (Long, Long, String?) -> String?,
    journalId: Long?,
    journalNow: Long,
    rewrites: MutableList<Pair<String, String>>,
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
    val insertedBookIds = LinkedHashSet<Long>()
    val lastChapterIds = HashMap<Long, Long?>()
    val lastChapterIndexes = HashMap<Long, Int?>()
    val lastReadAts = HashMap<Long, Long?>()
    val resolvedIds = HashMap<Pair<String, String>, Long>()
    forEachPage(pageSize, BookEntity::bookId, source::backupBooks) { book ->
        val archiveUrl = book.url
        val url = bookKeyUrl(archiveUrl)
        if (url != archiveUrl) rewrites += archiveUrl to url
        val identity = book.installId to url
        // The archive id belongs to another database; the resolver merges by
        // (installId, url) and owns the live-field precedence.
        val result = target.upsertBookByIdentity(book.copy(bookId = 0, url = url))
        val newId = resolvedIds[identity] ?: result.id
        resolvedIds[identity] = newId
        if (result.created) insertedBookIds += result.id
        bookIds[book.bookId] = newId
        if (book.lastChapterId != null) {
            lastChapterIds.putIfAbsent(newId, book.lastChapterId)
            lastChapterIndexes.putIfAbsent(newId, book.lastChapterIndex)
            lastReadAts.putIfAbsent(newId, book.lastReadAt)
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

    // One batched read of the live rows, then a documented precedence: an
    // inserted book always takes the archive position; a matched book only when
    // the archive is not older than its live row.
    val liveRows = LinkedHashMap<Long, BookEntity>()
    for (chunk in bookIds.values.distinct().chunked(900)) {
        currentCoroutineContext().ensureActive()
        target.booksByIds(chunk).forEach { liveRows[it.bookId] = it }
    }
    lastChapterIds.forEach { (bookId, oldChapterId) ->
        val mappedChapterId = oldChapterId?.let { chapterIds[it] }
        val archiveReadAt = lastReadAts[bookId]
        val liveReadAt = liveRows[bookId]?.lastReadAt
        val apply = bookId in insertedBookIds ||
            (archiveReadAt != null && (liveReadAt == null || archiveReadAt >= liveReadAt))
        if (apply) {
            target.setLastRead(
                bookId = bookId,
                readAt = archiveReadAt ?: liveReadAt,
                // A missing archive chapter keeps the live position.
                chapterId = mappedChapterId ?: liveRows[bookId]?.lastChapterId,
                chapterIndex = lastChapterIndexes[bookId] ?: liveRows[bookId]?.lastChapterIndex,
            )
        }
    }

    // --- history -------------------------------------------------------------
    // Explicit rows win; an archive without the table gets its history rebuilt
    // from the per-book last-read columns for books this import inserted.
    forEachPage(pageSize, HistoryEntity::bookId, source::backupHistory) { history ->
        val bookId = bookIds[history.bookId] ?: return@forEachPage
        target.upsertHistoryMerged(
            bookId = bookId,
            readAt = history.readAt,
            chapterIndex = history.chapterIndex,
            chapterId = history.chapterId?.let { chapterIds[it] },
        )
    }
    for (chunk in insertedBookIds.chunked(900)) {
        currentCoroutineContext().ensureActive()
        target.backfillHistoryFromBooks(chunk)
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

    // The archive only carries files for imported local books unless
    // importCachedBodies is set. Cached pages are always left out: they would be
    // dangling rows, so they can be fetched again on demand. Body paths are
    // already id-free in the snapshot, so a verbatim copy cannot collide.
    forEachPage(pageSize, ChapterBodyEntity::chapterId, source::backupChapterBodies) { body ->
        val chapterId = chapterIds[body.chapterId] ?: return@forEachPage
        val bookId = chapterBookIds[body.chapterId] ?: return@forEachPage
        if (!importCachedBodies && bookId !in localBookTargetIds) return@forEachPage
        target.upsertChapterBody(body.copy(chapterId = chapterId))
    }

    forEachPage(pageSize, ChapterPageEntity::chapterId, source::backupChapterPages) { page ->
        val chapterId = chapterIds[page.chapterId] ?: return@forEachPage
        val bookId = chapterBookIds[page.chapterId] ?: return@forEachPage
        if (bookId !in localBookTargetIds) return@forEachPage
        target.upsertChapterPage(page.copy(chapterId = chapterId))
    }

    // A restored plugin chapter has no local body/page, so it must not claim to
    // be offline. Bodies/pages are written above, so imported local books keep
    // their real flags. Bounded to the imported books and chunked.
    for (chunk in bookIds.values.distinct().chunked(900)) {
        currentCoroutineContext().ensureActive()
        target.resetOrphanedDownloadedFlagsFor(chunk)
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
        val targetId = if (existing != null) {
            target.upsertChapterTranslation(
                translation.copy(translationId = existing.translationId, chapterId = chapterId),
            )
            target.deleteTranslationBlocks(existing.translationId)
            existing.translationId
        } else {
            target.upsertChapterTranslation(
                translation.copy(translationId = 0, chapterId = chapterId),
            )
        }
        chapterTranslationIds[translation.translationId] = targetId
        translationCount++
    }
    forEachOffsetPageBatch(pageSize, source::backupTranslationBlocks) { blocks ->
        val mapped = blocks.mapNotNull { block ->
            val translationId = chapterTranslationIds[block.translationId] ?: return@mapNotNull null
            block.copy(translationId = translationId)
        }
        if (mapped.isNotEmpty()) target.upsertTranslationBlocks(mapped)
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
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
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
    forEachOffsetPageBatch(pageSize, fetch) { rows ->
        rows.forEach { block(it) }
    }
}

private suspend fun <T> forEachOffsetPageBatch(
    pageSize: Int,
    fetch: suspend (offset: Int, limit: Int) -> List<T>,
    block: suspend (List<T>) -> Unit,
) {
    var offset = 0
    while (true) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val rows = fetch(offset, pageSize)
        if (rows.isEmpty()) return
        block(rows)
        if (rows.size < pageSize) return
        offset += rows.size
    }
}

private const val DEFAULT_IMPORT_PAGE_SIZE = 256
