package dev.mirutime.data.library.migration

import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.BookMigrationEntity
import dev.mirutime.data.library.BookTranslationEntity
import dev.mirutime.data.library.ChapterBodyEntity
import dev.mirutime.data.library.ChapterEntity
import dev.mirutime.data.library.ChapterPageEntity
import dev.mirutime.data.library.ChapterTranslationEntity
import dev.mirutime.data.library.DownloadTaskEntity
import dev.mirutime.data.library.LibraryDao
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.ProgressEntity
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import dev.mirutime.data.library.BookWriteLocks
import kotlinx.coroutines.withContext

/**
 * Executes one old->new book migration by stable ids. The caller supplies the
 * target source book/chapters (no plugin search, no UI); this class never
 * discovers sources itself.
 *
 * Ordering rules:
 * - the plan is computed before any book row is created;
 * - DB writes are short chunks (<=200 rows / <=900 bind ids);
 * - chapter files are written outside DB transactions and rows commit after
 *   each file chunk;
 * - a newly created target is removed with its files on error/cancel; an
 *   existing target only receives gap-filling writes, so a retry can finish
 *   it without overwriting fresher state;
 * - the old book/files/settings are removed only after the new state and the
 *   migration record commit.
 */
class BookMigrationCoordinator(
    private val dao: LibraryDao,
    private val repository: LibraryRepository = LibraryRepository(dao),
    private val files: BookMigrationFileStore,
    private val settings: BookMigrationSettingsStore,
    private val planner: BookMigrationPlanner = BookMigrationPlanner(),
    private val now: () -> Long,
    private val bookLocks: BookWriteLocks = BookWriteLocks(),
) {

    suspend fun plan(
        oldBookId: Long,
        target: BookMigrationTarget,
    ): BookMigrationOutcome<BookMigrationPlan> {
        val oldBook = dao.book(oldBookId)
            ?: return BookMigrationOutcome.Failure("Old book $oldBookId is not in the library")
        val chapters = dao.chapters(oldBookId)
        val normalized = normalizeTarget(target)
            ?: return BookMigrationOutcome.Failure(
                "Target chapters are empty, blank or duplicated",
            )
        return BookMigrationOutcome.Success(planner.plan(oldBook, chapters, normalized))
    }

    suspend fun migrate(
        oldBookId: Long,
        target: BookMigrationTarget,
        options: BookMigrationOptions = BookMigrationOptions(),
    ): BookMigrationOutcome<BookMigrationResult> {
        val oldBook = dao.book(oldBookId)
            ?: return BookMigrationOutcome.Failure("Old book $oldBookId is not in the library")
        val normalized = normalizeTarget(target)
            ?: return BookMigrationOutcome.Failure(
                "Target chapters are empty, blank or duplicated",
            )
        val oldKey = bookLocks.bookKey(oldBookId)
        val newKey = bookLocks.targetKey(normalized.book.installId, normalized.book.url)
        return withLocks(listOf(oldKey, newKey)) {
            migrateLocked(oldBookId, normalized, options)
        }
    }

    private suspend fun migrateLocked(
        oldBookId: Long,
        target: BookMigrationTarget,
        options: BookMigrationOptions,
    ): BookMigrationOutcome<BookMigrationResult> {
        val oldBook = dao.book(oldBookId)
            ?: return BookMigrationOutcome.Failure("Old book $oldBookId is not in the library")
        val oldChapters = dao.chapters(oldBookId)
        val plan = planner.plan(oldBook, oldChapters, target)
        val batchId = batchId(oldBook, target)
        val startedAt = now()

        val previous = dao.bookMigrationByBatch(batchId)
        if (previous != null && previous.status in FinishedStatuses) {
            return finishIdempotent(previous, oldBook, options)
        }

        val existingBook = resolveTargetBook(target.book)
        if (target.book.bookId > 0 && existingBook == null) {
            return BookMigrationOutcome.Failure(
                "Target book ${target.book.bookId} is not in the library",
            )
        }
        if (existingBook?.bookId == oldBookId) {
            return BookMigrationOutcome.Failure("Source and target book are the same row")
        }

        var record = BookMigrationEntity(
            id = previous?.id ?: 0,
            oldBookId = oldBookId,
            newBookId = null,
            oldInstallId = oldBook.installId,
            newInstallId = target.book.installId,
            batchId = batchId,
            optionsJson = optionsJson(options),
            unmatchedJson = unmatchedJson(plan),
            chaptersTotal = plan.chaptersTotal,
            chaptersMatched = plan.chaptersMatched,
            chaptersWithProgress = 0,
            status = MigrationStatusRunning,
            error = null,
            createdAt = previous?.createdAt ?: startedAt,
            finishedAt = null,
        )
        val savedRecordId = dao.upsertBookMigration(record)
        if (record.id == 0L) record = record.copy(id = savedRecordId)

        val state = MigrationState()
        var committed = false
        try {
            val merged = mergeBook(target.book, oldBook, existingBook, target.chapters.size, startedAt)
            val upserted = dao.upsertBookByIdentity(merged)
            val newBookId = upserted.id
            check(newBookId > 0L) { "Cannot create or resolve the target book" }
            check(newBookId != oldBookId) { "Source and target book are the same row" }
            state.newBookId = newBookId
            state.bookCreated = upserted.created
            runCatching { repository.indexBook(merged.copy(bookId = newBookId)) }

            val chapters = writeChapters(plan, newBookId, options, state, startedAt)
            val progressCount = copyProgressAndLastChapter(
                plan = plan,
                oldBook = oldBook,
                oldToNew = chapters.oldToNew,
                newBookId = newBookId,
                bookCreated = state.bookCreated,
                options = options,
                state = state,
                at = startedAt,
            )
            if (options.transferBodies) {
                copyPayloads(plan, chapters.oldToNew, newBookId, state, startedAt)
            }
            if (options.transferTranslations) {
                copyTranslations(plan, chapters.oldToNew, newBookId, state)
                copyBookTranslations(oldBookId, newBookId, state)
            }
            copyDownloadTasks(oldBookId, newBookId, chapters.oldToNew, state)
            currentCoroutineContext().ensureActive()
            settings.copyNovelSettings(oldBook.url, target.book.url)

            val status = if (plan.chaptersMatched == plan.chaptersTotal) {
                MigrationStatusCompleted
            } else {
                MigrationStatusPartial
            }
            val finished = record.copy(
                newBookId = newBookId,
                chaptersWithProgress = progressCount,
                status = status,
                error = null,
                finishedAt = now(),
            )
            dao.upsertBookMigration(finished)
            record = finished
            committed = true
            currentCoroutineContext().ensureActive()

            var oldDeleted = false
            if (options.deleteOldBook) {
                deleteOldBook(oldBook)
                oldDeleted = true
            }
            return BookMigrationOutcome.Success(
                finished.toResult(oldDeleted = oldDeleted, bookCreated = state.bookCreated),
            )
        } catch (e: CancellationException) {
            if (!committed) {
                cleanup(state, record, oldBook, batchId, MigrationStatusCancelled, "cancelled")
            }
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e::class.simpleName ?: "Migration failed"
            if (!committed) {
                cleanup(state, record, oldBook, batchId, MigrationStatusFailed, message)
            }
            return BookMigrationOutcome.Failure(message, e)
        }
    }

    private suspend fun finishIdempotent(
        previous: BookMigrationEntity,
        oldBook: BookEntity,
        options: BookMigrationOptions,
    ): BookMigrationOutcome<BookMigrationResult> {
        var oldDeleted = false
        if (options.deleteOldBook) {
            val stillThere = dao.book(oldBook.bookId)
            if (stillThere != null) {
                try {
                    deleteOldBook(stillThere)
                    oldDeleted = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return BookMigrationOutcome.Failure(
                        e.message ?: "Cannot remove the old book",
                        e,
                    )
                }
            }
        }
        return BookMigrationOutcome.Success(previous.toResult(oldDeleted = oldDeleted))
    }

    private suspend fun writeChapters(
        plan: BookMigrationPlan,
        newBookId: Long,
        options: BookMigrationOptions,
        state: MigrationState,
        at: Long,
    ): ChapterWriteResult {
        val oldByNewUrl = plan.pairs.associateBy { it.new.url }
        val savedByUrl = LinkedHashMap<String, ChapterEntity>()
        for (chunk in plan.target.chapters.chunked(MigrationWriteChunk)) {
            currentCoroutineContext().ensureActive()
            val urls = chunk.map { it.url }
            val existingByUrl = dao.chaptersByUrls(newBookId, urls).associateBy { it.url }
            val rows = chunk.map { chapter ->
                val existing = existingByUrl[chapter.url]
                val old = oldByNewUrl[chapter.url]?.old
                val oldRead = options.transferProgress && old?.read == true
                ChapterEntity(
                    chapterId = existing?.chapterId ?: 0,
                    bookId = newBookId,
                    idx = chapter.index,
                    url = chapter.url,
                    title = chapter.title,
                    hash = existing?.hash,
                    read = existing?.read == true || oldRead,
                    readAt = when {
                        existing?.read == true -> existing.readAt
                        oldRead -> old.readAt
                        else -> existing?.readAt
                    },
                    downloaded = existing?.downloaded == true,
                    removedAt = null,
                    updatedAt = at,
                    volume = chapter.volume?.toString() ?: existing?.volume,
                    uploadedAt = chapter.uploadedAt ?: existing?.uploadedAt,
                    numberRaw = chapter.numberRaw ?: existing?.numberRaw,
                )
            }
            dao.upsertChapters(rows)
            val saved = dao.chaptersByUrls(newBookId, urls).associateBy { it.url }
            savedByUrl.putAll(saved)
            saved.forEach { (url, row) ->
                if (existingByUrl[url] == null) state.createdChapterIds += row.chapterId
            }
        }

        val oldToNew = LinkedHashMap<Long, ChapterEntity>()
        plan.pairs.forEach { pair ->
            savedByUrl[pair.new.url]?.let { oldToNew[pair.old.chapterId] = it }
        }
        return ChapterWriteResult(oldToNew = oldToNew)
    }

    private suspend fun copyProgressAndLastChapter(
        plan: BookMigrationPlan,
        oldBook: BookEntity,
        oldToNew: Map<Long, ChapterEntity>,
        newBookId: Long,
        bookCreated: Boolean,
        options: BookMigrationOptions,
        state: MigrationState,
        at: Long,
    ): Int {
        val oldProgress = if (options.transferProgress) dao.progress(oldBook.bookId) else null
        val mappedProgress = oldProgress?.let { progress ->
            oldToNew[progress.chapterId]?.let { chapter ->
                progress.copy(bookId = newBookId, chapterId = chapter.chapterId)
            }
        }
        if (mappedProgress != null && (bookCreated || dao.progress(newBookId) == null)) {
            dao.upsertProgress(mappedProgress)
            state.copiedProgress = true
        }

        val mappedLast = oldBook.lastChapterId?.let { oldToNew[it] }
            ?: oldProgress?.chapterId?.let { oldToNew[it] }
        if (mappedLast != null) {
            val currentBook = dao.book(newBookId)
            if (bookCreated || currentBook?.lastChapterId == null) {
                val readAt = listOfNotNull(
                    currentBook?.lastReadAt,
                    oldBook.lastReadAt,
                ).maxOrNull() ?: at
                dao.touchHistory(
                    bookId = newBookId,
                    chapterId = mappedLast.chapterId,
                    chapterIndex = mappedLast.idx,
                    at = readAt,
                )
                state.copiedLastChapterId = mappedLast.chapterId
            }
        }

        if (!options.transferProgress) return 0
        val readIds = LinkedHashSet<Long>()
        plan.pairs.forEach { pair ->
            if (pair.old.read) {
                oldToNew[pair.old.chapterId]?.let { readIds += it.chapterId }
            }
        }
        mappedProgress?.let { readIds += it.chapterId }
        return readIds.size
    }

    private suspend fun copyPayloads(
        plan: BookMigrationPlan,
        oldToNew: Map<Long, ChapterEntity>,
        newBookId: Long,
        state: MigrationState,
        at: Long,
    ) {
        for (chunk in plan.pairs.chunked(MigrationWriteChunk)) {
            currentCoroutineContext().ensureActive()
            val pairs = chunk.mapNotNull { pair ->
                oldToNew[pair.old.chapterId]?.let { pair to it }
            }
            if (pairs.isEmpty()) continue
            val oldIds = pairs.map { it.first.old.chapterId }
            val newIds = pairs.map { it.second.chapterId }
            val oldBodies = dao.chapterBodiesFor(oldIds).associateBy { it.chapterId }
            val oldPages = dao.chapterPagesFor(oldIds).associateBy { it.chapterId }
            val newBodies = if (state.bookCreated) {
                emptyMap()
            } else {
                dao.chapterBodiesFor(newIds).associateBy { it.chapterId }
            }
            val newPages = if (state.bookCreated) {
                emptyMap()
            } else {
                dao.chapterPagesFor(newIds).associateBy { it.chapterId }
            }

            val bodyRows = mutableListOf<ChapterBodyEntity>()
            val pageRows = mutableListOf<ChapterPageEntity>()
            val downloaded = LinkedHashSet<Long>()
            for ((pair, chapter) in pairs) {
                val oldBody = oldBodies[pair.old.chapterId]
                if (oldBody != null && (state.bookCreated || newBodies[chapter.chapterId] == null)) {
                    val text = files.readText(oldBody.path)
                    if (text != null) {
                        currentCoroutineContext().ensureActive()
                        val stored = files.writeText(newBookId, chapter.chapterId, text)
                        bodyRows += ChapterBodyEntity(
                            chapterId = chapter.chapterId,
                            format = oldBody.format,
                            path = stored.path,
                            bytes = stored.bytes,
                            sha256 = stored.sha256,
                            fetchedAt = at,
                        )
                        downloaded += chapter.chapterId
                        state.copiedBodyChapterIds += chapter.chapterId
                    }
                }

                val oldPage = oldPages[pair.old.chapterId]
                if (oldPage != null && (state.bookCreated || newPages[chapter.chapterId] == null)) {
                    val sourcePaths = files.pagePaths(oldPage.pagePathsJson)
                    if (sourcePaths.isNotEmpty()) {
                        val bytes = sourcePaths.map { files.readBytes(it) }
                        if (bytes.all { it != null }) {
                            currentCoroutineContext().ensureActive()
                            val newPaths = bytes.mapIndexed { index, payload ->
                                files.writePage(
                                    bookId = newBookId,
                                    chapterId = chapter.chapterId,
                                    index = index,
                                    sourcePath = sourcePaths[index],
                                    bytes = payload!!,
                                )
                            }
                            pageRows += ChapterPageEntity(
                                chapterId = chapter.chapterId,
                                format = oldPage.format,
                                pagePathsJson = files.encodePagePaths(newPaths),
                                totalBytes = bytes.sumOf { it!!.size.toLong() },
                                quality = oldPage.quality,
                                parserVersion = oldPage.parserVersion,
                            )
                            downloaded += chapter.chapterId
                            state.copiedPageChapterIds += chapter.chapterId
                        }
                    }
                }
            }
            if (bodyRows.isNotEmpty() || pageRows.isNotEmpty() || downloaded.isNotEmpty()) {
                dao.saveMigrationPayloadChunk(
                    bodies = bodyRows,
                    pages = pageRows,
                    downloadedChapterIds = downloaded.toList(),
                    at = at,
                )
            }
        }
    }

    private suspend fun copyTranslations(
        plan: BookMigrationPlan,
        oldToNew: Map<Long, ChapterEntity>,
        newBookId: Long,
        state: MigrationState,
    ) {
        val oldToNewTranslation = LinkedHashMap<Long, Long>()
        for (chunk in plan.pairs.chunked(MigrationWriteChunk)) {
            currentCoroutineContext().ensureActive()
            val oldIds = chunk.map { it.old.chapterId }
            val newIds = chunk.mapNotNull { oldToNew[it.old.chapterId]?.chapterId }
            if (newIds.isEmpty()) continue
            val oldRows = dao.chapterTranslationsFor(oldIds)
            if (oldRows.isEmpty()) continue
            val existing = if (state.bookCreated) {
                emptyMap<String, ChapterTranslationEntity>()
            } else {
                dao.chapterTranslationsFor(newIds)
                    .associateBy { it.identityKey() }
            }
            val pending = oldRows.mapNotNull { row ->
                val chapter = oldToNew[row.chapterId] ?: return@mapNotNull null
                val copy = row.copy(translationId = 0, chapterId = chapter.chapterId)
                if (copy.identityKey() in existing) return@mapNotNull null
                row.translationId to copy
            }
            if (pending.isEmpty()) continue
            val ids = dao.insertChapterTranslations(pending.map { it.second })
            pending.forEachIndexed { index, (oldId, _) ->
                val newId = ids.getOrNull(index)?.takeIf { it > 0 } ?: return@forEachIndexed
                oldToNewTranslation[oldId] = newId
                state.insertedTranslationIds += newId
            }
        }

        if (oldToNewTranslation.isEmpty()) return
        for (chunk in oldToNewTranslation.keys.toList().chunked(MigrationIdChunk)) {
            currentCoroutineContext().ensureActive()
            val blocks = dao.translationBlocksFor(chunk)
            if (blocks.isEmpty()) continue
            val copies = blocks.mapNotNull { block ->
                val newId = oldToNewTranslation[block.translationId] ?: return@mapNotNull null
                block.copy(translationId = newId)
            }
            if (copies.isNotEmpty()) dao.upsertTranslationBlocks(copies)
        }
    }

    private suspend fun copyBookTranslations(
        oldBookId: Long,
        newBookId: Long,
        state: MigrationState,
    ) {
        val oldRows = dao.bookTranslationsForBook(oldBookId)
        if (oldRows.isEmpty()) return
        val existing = if (state.bookCreated) {
            emptyMap<String, BookTranslationEntity>()
        } else {
            dao.bookTranslationsForBook(newBookId).associateBy { it.identityKey() }
        }
        val pending = oldRows.filter { it.identityKey() !in existing }
            .map { it.copy(translationId = 0, bookId = newBookId) }
        if (pending.isEmpty()) return
        val ids = dao.insertBookTranslations(pending)
        ids.filter { it > 0 }.forEach { state.insertedBookTranslationIds += it }
    }

    private suspend fun copyDownloadTasks(
        oldBookId: Long,
        newBookId: Long,
        oldToNew: Map<Long, ChapterEntity>,
        state: MigrationState,
    ) {
        val oldTasks = dao.downloadTasksForBook(oldBookId)
        if (oldTasks.isEmpty()) return
        val existing = if (state.bookCreated) {
            emptySet()
        } else {
            dao.downloadTasksForBook(newBookId)
                .map { Triple(it.bookId, it.chapterId, it.kind) }
                .toSet()
        }
        val pending = oldTasks.mapNotNull { task ->
            val chapter = oldToNew[task.chapterId] ?: return@mapNotNull null
            if (Triple(newBookId, chapter.chapterId, task.kind) in existing) {
                return@mapNotNull null
            }
            task.copy(id = 0, bookId = newBookId, chapterId = chapter.chapterId)
        }
        if (pending.isEmpty()) return
        for (chunk in pending.chunked(MigrationWriteChunk)) {
            currentCoroutineContext().ensureActive()
            val ids = dao.insertDownloadTasks(chunk)
            ids.filter { it > 0 }.forEach { state.insertedTaskIds += it }
        }
    }

    private suspend fun cleanup(
        state: MigrationState,
        record: BookMigrationEntity,
        oldBook: BookEntity,
        batchId: String,
        status: String,
        error: String,
    ) {
        withContext(NonCancellable) {
            if (state.bookCreated && state.newBookId > 0) {
                runCatching { dao.deleteBookWithSearch(state.newBookId) }
                runCatching { files.deleteBookFiles(state.newBookId) }
            } else if (state.newBookId > 0) {
                state.copiedBodyChapterIds.forEach { chapterId ->
                    runCatching { files.deleteText(state.newBookId, chapterId) }
                }
                state.copiedPageChapterIds.forEach { chapterId ->
                    runCatching { files.deletePages(state.newBookId, chapterId) }
                }
                for (chunk in state.copiedBodyChapterIds.toList().chunked(MigrationIdChunk)) {
                    runCatching { dao.deleteChapterBodies(chunk) }
                }
                for (chunk in state.copiedPageChapterIds.toList().chunked(MigrationIdChunk)) {
                    runCatching { dao.deleteChapterPages(chunk) }
                }
                for (chunk in state.insertedTranslationIds.toList().chunked(MigrationIdChunk)) {
                    runCatching { dao.deleteChapterTranslationsByIds(chunk) }
                }
                for (chunk in state.insertedBookTranslationIds.toList().chunked(MigrationIdChunk)) {
                    runCatching { dao.deleteBookTranslationsByIds(chunk) }
                }
                for (chunk in state.insertedTaskIds.toList().chunked(MigrationIdChunk)) {
                    runCatching { dao.deleteDownloadTasksByIds(chunk) }
                }
                if (state.copiedProgress) runCatching { dao.deleteProgress(state.newBookId) }
                state.copiedLastChapterId?.let { chapterId ->
                    runCatching {
                        dao.clearLastChapterForChapters(state.newBookId, listOf(chapterId))
                    }
                }
                for (chunk in state.createdChapterIds.toList().chunked(MigrationIdChunk)) {
                    runCatching { dao.deleteChapterRows(chunk) }
                }
            }
            runCatching {
                val current = dao.bookMigrationByBatch(batchId) ?: record
                dao.upsertBookMigration(
                    current.copy(
                        id = record.id,
                        newBookId = if (state.bookCreated) null else state.newBookId.takeIf { it > 0 },
                        status = status,
                        error = error,
                        finishedAt = now(),
                    ),
                )
            }
        }
    }

    private suspend fun deleteOldBook(oldBook: BookEntity) {
        withContext(NonCancellable) {
            dao.deleteBooksWithFiles(listOf(oldBook.bookId))
            runCatching { files.deleteBookFiles(oldBook.bookId) }
            runCatching { settings.deleteNovelSettings(oldBook.url) }
        }
    }

    private suspend fun resolveTargetBook(target: BookEntity): BookEntity? =
        if (target.bookId > 0) {
            dao.book(target.bookId)
        } else {
            dao.bookByInstallAndUrl(target.installId, target.url)
        }

    private fun normalizeTarget(target: BookMigrationTarget): BookMigrationTarget? {
        if (target.book.installId.isBlank() || target.book.url.isBlank()) return null
        if (target.chapters.isEmpty()) return null
        if (target.chapters.any { it.url.isBlank() }) return null
        if (target.chapters.map { it.url }.toSet().size != target.chapters.size) return null
        if (target.chapters.map { it.index }.toSet().size != target.chapters.size) return null
        return target
    }

    private fun batchId(oldBook: BookEntity, target: BookMigrationTarget): String =
        "book:${oldBook.bookId}:${target.book.installId}:${target.book.url}"

    private suspend fun <T> withLocks(keys: List<String>, block: suspend () -> T): T =
        bookLocks.withLocks(keys, block)

    private fun mergeBook(
        target: BookEntity,
        old: BookEntity,
        existing: BookEntity?,
        totalChapters: Int,
        at: Long,
    ): BookEntity = if (existing == null) {
        target.copy(
            bookId = 0,
            title = target.title.ifBlank { old.title },
            author = old.author.ifBlank { target.author },
            coverUrl = old.coverUrl ?: target.coverUrl,
            contentType = old.contentType.ifBlank { target.contentType },
            rating = if (old.rating != 0.0) old.rating else target.rating,
            shelfId = old.shelfId.ifBlank { target.shelfId },
            totalChapters = totalChapters,
            inLibrary = true,
            addedAt = old.addedAt,
            updatedAt = at,
            lastReadAt = old.lastReadAt,
            lastChapterId = null,
            lastChapterIndex = null,
            chapterListHash = null,
            lastUpdateAt = old.lastUpdateAt ?: target.lastUpdateAt ?: at,
            genresJson = old.genresJson.ifBlank { target.genresJson },
            firstSeenAt = old.firstSeenAt,
            addedToLibraryAt = old.addedToLibraryAt ?: target.addedToLibraryAt,
            subtitle = old.subtitle ?: target.subtitle,
            altTitlesJson = old.altTitlesJson.ifBlank { target.altTitlesJson },
            authorRaw = old.authorRaw.ifBlank { target.authorRaw },
            authorsJson = old.authorsJson.ifBlank { target.authorsJson },
            description = old.description.ifBlank { target.description },
            originLanguage = old.originLanguage ?: target.originLanguage,
            ratingRaw = old.ratingRaw.ifBlank { target.ratingRaw },
            statusRaw = old.statusRaw.ifBlank { target.statusRaw },
            statusNormalized = old.statusNormalized.ifBlank { target.statusNormalized },
            lastUpdatedRaw = old.lastUpdatedRaw.ifBlank { target.lastUpdatedRaw },
            lastUpdatedAt = old.lastUpdatedAt ?: target.lastUpdatedAt,
            newChapters = 0,
            lastUpdateCheckAt = old.lastUpdateCheckAt ?: target.lastUpdateCheckAt,
            extrasJson = old.extrasJson.ifBlank { target.extrasJson },
            metadataSource = old.metadataSource.ifBlank { target.metadataSource },
            metadataUpdatedAt = old.metadataUpdatedAt,
            metadataHash = old.metadataHash,
        )
    } else {
        existing.copy(
            title = existing.title.ifBlank { target.title },
            author = existing.author.ifBlank { target.author.ifBlank { old.author } },
            coverUrl = existing.coverUrl ?: target.coverUrl ?: old.coverUrl,
            totalChapters = totalChapters,
            inLibrary = true,
            updatedAt = at,
            genresJson = existing.genresJson.ifBlank { target.genresJson.ifBlank { old.genresJson } },
            description = existing.description.ifBlank {
                target.description.ifBlank { old.description }
            },
            subtitle = existing.subtitle ?: target.subtitle ?: old.subtitle,
            originLanguage = existing.originLanguage ?: target.originLanguage ?: old.originLanguage,
            statusNormalized = existing.statusNormalized.ifBlank {
                target.statusNormalized.ifBlank { old.statusNormalized }
            },
            newChapters = 0,
            metadataUpdatedAt = maxOf(existing.metadataUpdatedAt, at),
        )
    }

    private fun optionsJson(options: BookMigrationOptions): String =
        "{\"progress\":${options.transferProgress}," +
            "\"bodies\":${options.transferBodies}," +
            "\"translations\":${options.transferTranslations}," +
            "\"deleteOld\":${options.deleteOldBook}}"

    private fun unmatchedJson(plan: BookMigrationPlan): String =
        plan.unmatchedOld.joinToString(prefix = "[", postfix = "]") { it.chapterId.toString() }

    private class MigrationState {
        var newBookId: Long = 0
        var bookCreated: Boolean = false
        var copiedProgress: Boolean = false
        var copiedLastChapterId: Long? = null
        val createdChapterIds = LinkedHashSet<Long>()
        val copiedBodyChapterIds = LinkedHashSet<Long>()
        val copiedPageChapterIds = LinkedHashSet<Long>()
        val insertedTranslationIds = LinkedHashSet<Long>()
        val insertedBookTranslationIds = LinkedHashSet<Long>()
        val insertedTaskIds = LinkedHashSet<Long>()
    }

    private data class ChapterWriteResult(
        val oldToNew: Map<Long, ChapterEntity>,
    )

    private companion object {
        val FinishedStatuses = setOf(MigrationStatusCompleted, MigrationStatusPartial)
    }
}

private fun BookMigrationEntity.toResult(
    oldDeleted: Boolean,
    bookCreated: Boolean = false,
): BookMigrationResult =
    BookMigrationResult(
        migrationId = id,
        oldBookId = oldBookId ?: 0,
        newBookId = newBookId ?: 0,
        chaptersTotal = chaptersTotal,
        chaptersMatched = chaptersMatched,
        chaptersWithProgress = chaptersWithProgress,
        unmatchedOld = parseIds(unmatchedJson),
        status = status,
        bookCreated = bookCreated,
        oldDeleted = oldDeleted,
    )

private fun parseIds(json: String): List<Long> =
    json.removePrefix("[").removeSuffix("]")
        .split(',')
        .mapNotNull { it.trim().toLongOrNull() }

private fun ChapterTranslationEntity.identityKey(): String =
    "$chapterId|$source|$target|$provider|$promptHash|$pipelineVersion"

private fun BookTranslationEntity.identityKey(): String =
    "$bookId|$source|$target|$provider|$promptHash|$pipelineVersion"

