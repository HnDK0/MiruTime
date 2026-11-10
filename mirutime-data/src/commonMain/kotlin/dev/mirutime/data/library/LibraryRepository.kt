package dev.mirutime.data.library

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

// Catalogue membership: URL matches (same source) + title counts (another
// source). Returned by LibraryRepository.libraryMembership in one batch.
data class LibraryMembership(
    val urls: Set<String> = emptySet(),
    // Install-aware keys: two plugins may expose different works under one URL.
    val keys: Set<String> = emptySet(),
    val titles: Map<String, Int> = emptyMap(),
)

/** Outcome of the one-shot repair of rows whose URL is an opaque route key. */
data class RepairReport(
    val rewrittenInPlace: Int = 0,
    val deletedEmpty: Int = 0,
    val ambiguous: Int = 0,
    val ambiguousSoftRemoved: Int = 0,
    val unrepairable: Int = 0,
    val failed: Int = 0,
    val orphanPaths: List<String> = emptyList(),
) {
    val changed: Int get() = rewrittenInPlace + deletedEmpty
}

/**
 * Repository over the v2 library schema. Identity is [BookEntity.bookId] and
 * [ChapterEntity.chapterId]; URL-based overloads exist only while app screens
 * still navigate by URL.
 */
class LibraryRepository(
    private val dao: LibraryDao,
    private val bookLocks: BookWriteLocks = BookWriteLocks(),
    // Runs before a book row disappears; the app uses it to cancel unique
    // download/translation work so a worker cannot resurrect deleted rows.
    private val onBeforeDelete: suspend (List<Long>) -> Unit = {},
) {
    private companion object {
        // SQLite's default bind limit is 999; leave headroom for other args.
        const val SqliteIdChunk = 900
    }

    // --- shelves -------------------------------------------------------------

    suspend fun ensureDefaultShelves() {
        if (dao.shelfCount() == 0) {
            dao.upsertShelves(DefaultShelves)
        }
    }

    suspend fun shelves(): List<ShelfEntity> = dao.shelves()

    suspend fun saveShelf(shelf: ShelfEntity) = dao.upsertShelf(shelf)

    suspend fun deleteShelf(id: String) = dao.deleteShelf(id)

    // System shelves are pinned first; the rest keep the caller's order and
    // every row gets a fresh position so the DAO ordering is unambiguous.
    suspend fun reorderShelves(shelves: List<ShelfEntity>) {
        val ordered = shelves.sortedBy { if (it.id in SystemShelfIds) 0 else 1 }
        dao.upsertShelves(ordered.mapIndexed { index, shelf -> shelf.copy(position = index) })
    }

    // --- installs ------------------------------------------------------------

    suspend fun installs(): List<PluginInstallEntity> = dao.installs()

    suspend fun install(installId: String): PluginInstallEntity? = dao.install(installId)

    suspend fun saveInstall(install: PluginInstallEntity) = dao.upsertInstall(install)

    /**
     * Rewrites only URLs of one install; book/chapter ids, progress, sessions
     * and downloads stay untouched. Prefixes that do not match are left alone
     * and counted out by the caller's before/after totals.
     */
    suspend fun rebaseInstall(
        installId: String,
        oldBase: String,
        newBase: String,
        override: String?,
        effective: String,
        at: Long,
    ): RebaseReport {
        val old = oldBase.trim().trimEnd('/')
        val new = newBase.trim().trimEnd('/')
        if (old.isEmpty() || new.isEmpty() || old == new) {
            return RebaseReport(0, 0)
        }
        val (books, chapters) = dao.applyRebase(
            installId = installId,
            oldBase = old,
            newBase = new,
            override = override,
            effective = effective,
            at = at,
        )
        return RebaseReport(books, chapters)
    }

    // --- books ---------------------------------------------------------------

    suspend fun book(bookId: Long): BookEntity? = dao.book(bookId)

    suspend fun book(bookUrl: String): BookEntity? = bookRow(bookUrl)

    // A route/UI may pass an opaque BookKey. Decode it before touching the DB;
    // a plain URL keeps the legacy first-row fallback.
    private suspend fun bookRow(bookUrl: String): BookEntity? {
        val identity = decodeLibraryBookKey(bookUrl)
        return if (identity != null) {
            dao.bookByInstallAndUrl(identity.installId, identity.url)
        } else {
            dao.bookByUrl(bookUrl)
        }
    }

    suspend fun book(installId: String, url: String): BookEntity? =
        dao.bookByInstallAndUrl(installId, url)

    suspend fun libraryBooks(): List<BookEntity> = dao.libraryBooks()

    /**
     * Keyset page for the library. Fetches [limit] + 1 rows, so hasMore and
     * the next cursor are decided by one query; total uses the same filters.
     * Order is deterministic on (sortKey, addedAt/title/unread, bookId).
     */
    suspend fun libraryPage(
        limit: Int = LibraryPageSize,
        cursor: LibraryPageCursor? = null,
        filters: LibraryPageFilters = LibraryPageFilters(),
        sort: LibrarySort = LibrarySort.LastRead,
        direction: LibrarySortDirection = LibrarySortDirection.Desc,
    ): LibraryPage {
        val pageSize = limit.coerceAtLeast(1)
        val total = dao.libraryPageCount(libraryCountQuery(filters).toRawQuery())
        val rows = dao.libraryPageRows(
            libraryPageQuery(pageSize, cursor, filters, sort, direction).toRawQuery(),
        )
        val hasMore = rows.size > pageSize
        val visible = if (hasMore) rows.take(pageSize) else rows
        val nextCursor = if (hasMore) visible.lastOrNull()?.toCursor(sort, direction) else null
        return LibraryPage(
            books = visible.map { it.book },
            nextCursor = nextCursor,
            hasMore = hasMore,
            total = total,
        )
    }

    /** Count for the same filters without fetching rows. */
    suspend fun libraryPageCount(filters: LibraryPageFilters = LibraryPageFilters()): Int =
        dao.libraryPageCount(libraryCountQuery(filters).toRawQuery())

    /** All matching URLs for select-all; intentionally unbounded. */
    suspend fun matchingLibraryUrls(
        filters: LibraryPageFilters = LibraryPageFilters(),
    ): List<String> = dao.libraryPageUrls(libraryUrlsQuery(filters).toRawQuery())

    /**
     * Global facet values for paginated screens. Sources are install ids;
     * genres merge tags with valid genresJson arrays.
     */
    suspend fun libraryFacets(): LibraryFacets = LibraryFacets(
        sources = dao.librarySources(),
        genres = dao.libraryPageGenres(libraryGenresQuery().toRawQuery())
            .map { it.name }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted(),
    )

    /** Per-shelf count and a representative cover/book title. */
    suspend fun shelfSummaries(): List<ShelfSummary> = dao.shelfSummaries().map { row ->
        ShelfSummary(
            shelfId = row.shelfId,
            bookCount = row.bookCount,
            coverUrl = row.coverUrl,
            coverTitle = row.coverTitle,
            coverBookId = row.coverBookId,
        )
    }

    /**
     * Batch membership lookup for catalogue cards: returns only the requested
     * URLs whose row is in the library. One query, never N+1.
     */
    suspend fun libraryUrls(urls: Collection<String>): Set<String> {
        val requested = urls.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (requested.isEmpty()) return emptySet()
        val found = LinkedHashSet<String>()
        for (chunk in requested.chunked(SqliteIdChunk)) {
            currentCoroutineContext().ensureActive()
            val inLibrary = dao.libraryUrls(chunk).toHashSet()
            chunk.forEach { url -> if (url in inLibrary) found += url }
        }
        return found
    }

    /**
     * Catalogue membership in two grouped queries: URL matches (same source)
     * and title counts (the book exists from another plugin). Chunks keep both
     * sides below SQLite's bind limit; no per-card lookup.
     */
    suspend fun libraryMembership(
        urls: Collection<String>,
        titles: Collection<String>,
    ): LibraryMembership {
        val cleanUrls = urls.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
        val cleanTitles = titles.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
        val foundUrls = LinkedHashSet<String>()
        val foundKeys = LinkedHashSet<String>()
        val titleCounts = LinkedHashMap<String, Int>()
        for (chunk in cleanUrls.chunked(SqliteIdChunk)) {
            currentCoroutineContext().ensureActive()
            dao.bookKeysByUrls(chunk).forEach { row ->
                foundUrls += row.url
                foundKeys += libraryBookKey(row.installId, row.url)
            }
        }
        for (chunk in cleanTitles.chunked(SqliteIdChunk)) {
            currentCoroutineContext().ensureActive()
            dao.libraryTitleCounts(chunk).forEach { titleCounts[it.title] = it.count }
        }
        return LibraryMembership(urls = foundUrls, keys = foundKeys, titles = titleCounts)
    }

    /** Dashboard next-up candidates in library order, finished books excluded. */
    suspend fun unfinishedLibraryBooks(limit: Int): List<BookEntity> =
        dao.unfinishedLibraryBooks(limit.coerceAtLeast(0))
    suspend fun history(limit: Int): List<BookEntity> = dao.history(limit)

    /**
     * Saves a book and refreshes its FTS row in one call. New rows (bookId 0)
     * take the generated id so the index rowid matches the books row.
     */
    suspend fun saveBook(book: BookEntity): Long {
        val result = dao.upsertBookByIdentity(book)
        if (result.id > 0L) {
            // Index what the store actually holds: the merge may keep the
            // stored title when the incoming one is blank.
            indexBookWithTags(dao.book(result.id) ?: book.copy(bookId = result.id))
        }
        return result.id
    }

    /**
     * Direct-SAF history entry: the book row and local file row (copyPath =
     * null) commit in one short transaction, then chapters reconcile chunk by
     * chunk. A failure rolls back a freshly created book; an existing book is
     * left for the idempotent retry. The FTS rebuild stays after the chunks
     * and does not touch files.
     *
     * A second open with the same file identity (URI + mtime + size hash) and
     * the same chapter URL sequence is a no-op: no book/file/chapter rewrite,
     * no FTS rebuild. Chapter ids (and therefore translation/progress identity)
     * stay exactly as they were.
     */
    suspend fun ensureDirectBook(
        book: BookEntity,
        file: LocalBookFileEntity,
        seeds: List<ChapterSeed>,
        at: Long,
    ): Long {
        directBookIfCurrent(file, seeds)?.let { return it }
        val write = dao.upsertDirectBook(book, file)
        try {
            dao.reconcileChapters(write.bookId, seeds, at)
        } catch (e: CancellationException) {
            rollbackDirectBook(write)
            throw e
        } catch (e: Exception) {
            rollbackDirectBook(write)
            throw e
        }
        indexBookWithTags(book.copy(bookId = write.bookId))
        return write.bookId
    }

    private suspend fun notifyBeforeDelete(bookIds: List<Long>) {
        try {
            onBeforeDelete(bookIds)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Cancelling work is best-effort; the DB cascade is the source of truth.
        }
    }

    private suspend fun rollbackDirectBook(write: DirectBookWrite) {
        if (!write.created) return
        runCatching {
            withContext(NonCancellable) { dao.deleteBookWithSearch(write.bookId) }
        }
    }

    private suspend fun directBookIfCurrent(
        file: LocalBookFileEntity,
        seeds: List<ChapterSeed>,
    ): Long? {
        val existingFile = dao.localBookFileByUri(file.uri) ?: return null
        if (existingFile.sha256 != file.sha256) return null
        if (existingFile.format != file.format) return null
        if (existingFile.displayName != file.displayName) return null
        val existingBook = dao.book(existingFile.bookId) ?: return null
        val incomingUrls = seeds.filter { it.url.isNotBlank() }.distinctBy { it.url }.map { it.url }
        val existingUrls = dao.allChapters(existingFile.bookId)
            .filter { it.removedAt == null }
            .map { it.url }
        if (existingUrls != incomingUrls) return null
        if (existingBook.totalChapters != incomingUrls.size) return null
        return existingBook.bookId
    }

    suspend fun deleteBook(bookId: Long) {
        bookLocks.withLock(bookLocks.bookKey(bookId)) {
            notifyBeforeDelete(listOf(bookId))
            dao.deleteBookWithSearch(bookId)
        }
    }

    suspend fun deleteBook(bookUrl: String) {
        bookRow(bookUrl)?.let { deleteBook(it.bookId) }
    }

    /**
     * Single-book remove used by the membership action: resolves exactly one
     * row (URL lookup is LIMIT 1 even if two installs share a URL), captures
     * its files and deletes the row + FTS in the same transaction.
     */
    suspend fun deleteBookWithFiles(
        bookUrl: String,
        installId: String? = null,
    ): BulkDeleteResult {
        // A URL alone is ambiguous when two installs carry the same work. The
        // UI must pass the card's installId; URL-only stays as a fallback for
        // old callers and single-install libraries.
        val identity = decodeLibraryBookKey(bookUrl)
        val targetInstallId = installId?.takeIf { it.isNotBlank() } ?: identity?.installId
        val targetUrl = identity?.url ?: bookUrl
        val row = if (!targetInstallId.isNullOrBlank()) {
            // An explicit source must never widen to a URL-wide delete: a
            // stale install id is "missing", not permission to delete another
            // install's book.
            dao.bookByInstallAndUrl(targetInstallId, targetUrl)
        } else {
            dao.bookByUrl(targetUrl)
        } ?: return BulkDeleteResult(emptySet(), setOf(bookUrl), emptyList())
        return bookLocks.withLock(bookLocks.bookKey(row.bookId)) {
            notifyBeforeDelete(listOf(row.bookId))
            val files = groupBookFiles(dao.deleteBooksWithFiles(listOf(row.bookId)))
            BulkDeleteResult(deleted = setOf(row.url), missing = emptySet(), files = files)
        }
    }

    /**
     * Hard-deletes every found book in write chunks (books + FTS;
     * chapters/progress/local files cascade) and returns the file paths that
     * were collected in the same transaction, before the rows disappeared.
     *
     * [onCommittedFiles] runs after each chunk transaction, so the caller can
     * delete those files on IO without waiting for the whole batch. If the
     * batch is cancelled or fails between chunks, the already-committed files
     * are delivered again under [NonCancellable] before the exception is
     * rethrown: no committed row leaves its files orphaned. Delivery is
     * idempotent, so a repeat after a crash/retry is safe.
     */
    suspend fun deleteBooks(
        bookUrls: List<String>,
        // url -> installId for cards that know their source. Without it a URL
        // shared by two installs resolves to every matching row.
        sources: Map<String, Set<String>> = emptyMap(),
        onCommittedFiles: suspend (List<LibraryBookFiles>) -> Unit = {},
    ): BulkDeleteResult {
        val requested = bookUrls.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (requested.isEmpty()) return BulkDeleteResult(emptySet(), emptySet(), emptyList())

        // Resolve every URL chunk first; a failed lookup stays "missing" and
        // never turns into a silent success. Writes happen per id chunk below.
        val idsByUrl = LinkedHashMap<String, MutableList<Long>>()
        for (chunk in requested.chunked(SqliteIdChunk)) {
            currentCoroutineContext().ensureActive()
            try {
                val unsourced = chunk.filter { sources[it].isNullOrEmpty() }
                if (unsourced.isNotEmpty()) {
                    dao.booksByUrls(unsourced).forEach { row ->
                        idsByUrl.getOrPut(row.url) { mutableListOf() } += row.bookId
                    }
                }
                val sourced = chunk.filter { !sources[it].isNullOrEmpty() }
                if (sourced.isNotEmpty()) {
                    dao.bookKeysByUrlsAll(sourced).forEach { row ->
                        if (row.installId in sources[row.url].orEmpty()) {
                            idsByUrl.getOrPut(row.url) { mutableListOf() } += row.bookId
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Unreadable chunk: these URLs remain missing.
            }
        }

        val ids = idsByUrl.values.flatten()
        val deletedIds = HashSet<Long>()
        val files = mutableListOf<LibraryBookFiles>()
        try {
            for (chunk in ids.chunked(SqliteIdChunk)) {
                currentCoroutineContext().ensureActive()
                val chunkFiles = try {
                    bookLocks.withLocks(chunk.map(bookLocks::bookKey)) {
                        notifyBeforeDelete(chunk)
                        groupBookFiles(dao.deleteBooksWithFiles(chunk))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The chunk transaction rolled back; those rows stay in the DB.
                    continue
                }
                deletedIds += chunk
                if (chunkFiles.isNotEmpty()) {
                    files += chunkFiles
                    // Commit already happened; the caller removes files on IO.
                    onCommittedFiles(chunkFiles)
                }
            }
        } catch (e: CancellationException) {
            deliverCommittedFiles(files, onCommittedFiles)
            throw e
        } catch (e: Exception) {
            deliverCommittedFiles(files, onCommittedFiles)
            throw e
        }

        val deleted = requested.filterTo(LinkedHashSet()) { url ->
            idsByUrl[url]?.all { it in deletedIds } == true
        }
        return BulkDeleteResult(deleted, requested - deleted, files)
    }

    // Cancellation may land after a chunk commit but before/inside the
    // callback; re-deliver what was committed so the files are still removed.
    private suspend fun deliverCommittedFiles(
        files: List<LibraryBookFiles>,
        onCommittedFiles: suspend (List<LibraryBookFiles>) -> Unit,
    ) {
        if (files.isEmpty()) return
        withContext(NonCancellable) {
            runCatching { onCommittedFiles(files) }
        }
    }

    // --- FTS search ----------------------------------------------------------

    /**
     * Library search over title, author, tags, description and translated
     * title. A blank query (or one with no searchable characters) returns the
     * normal library page. Results are ranked by bm25, then by bookId.
     */
    suspend fun searchBooks(
        query: String,
        limit: Int = 50,
        offset: Int = 0,
        libraryOnly: Boolean = true,
    ): List<BookEntity> {
        val pageLimit = limit.coerceAtLeast(0)
        val pageOffset = offset.coerceAtLeast(0)
        val match = buildFtsMatch(query)
            ?: return dao.libraryBooksPage(pageLimit, pageOffset)
        return dao.searchBooks(match, libraryOnly, pageLimit, pageOffset)
    }

    /** Writes the FTS row without loading tags/translation. */
    suspend fun indexBook(book: BookEntity) {
        if (book.bookId <= 0L) return
        dao.upsertBookSearch(
            book.toSearchRow(tags = "", translatedTitle = ""),
        )
    }

    /**
     * Rebuilds one FTS row from the book plus its tags and latest title.
     * Passing [source]/[target] keeps the indexed title in the active language
     * pair; null means "any" for callers that have no config.
     */
    suspend fun indexBookWithTags(
        book: BookEntity,
        source: String? = null,
        target: String? = null,
    ) {
        if (book.bookId <= 0L) return
        val tags = dao.tagsForBook(book.bookId).joinToString(" ") { it.name }
        val translatedTitle = dao.latestBookTranslationTitle(book.bookId, source, target).orEmpty()
        dao.upsertBookSearch(book.toSearchRow(tags, translatedTitle))
    }

    suspend fun rebuildSearchIndex() {
        dao.clearBookSearch()
        for (book in dao.allBooks()) {
            indexBookWithTags(book)
        }
    }

    suspend fun setShelf(bookId: Long, shelfId: String) = dao.setShelf(bookId, shelfId)

    suspend fun setShelf(bookUrl: String, shelfId: String) {
        bookRow(bookUrl)?.let { dao.setShelf(it.bookId, shelfId) }
    }

    /** Moves every found book in id chunks; system shelves stay untouched. */
    suspend fun moveBooks(bookUrls: List<String>, shelfId: String): BulkBookResult {
        val requested = bookUrls.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (requested.isEmpty()) return BulkBookResult(emptySet(), emptySet())
        if (dao.shelves().none { it.id == shelfId }) {
            return BulkBookResult(emptySet(), requested)
        }

        val idsByUrl = LinkedHashMap<String, MutableList<Long>>()
        for (chunk in requested.chunked(SqliteIdChunk)) {
            currentCoroutineContext().ensureActive()
            try {
                val identities = chunk.associateWith(::decodeLibraryBookKey)
                val decoded = identities.values.filterNotNull()
                val rows = if (decoded.isEmpty()) {
                    emptyList()
                } else {
                    dao.bookKeysByUrlsAll(decoded.map { it.url }.distinct())
                }
                identities.forEach { (key, identity) ->
                    if (identity != null) {
                        rows.firstOrNull {
                            it.url == identity.url && it.installId == identity.installId
                        }?.let { row ->
                            idsByUrl.getOrPut(key) { mutableListOf() } += row.bookId
                        }
                    }
                }
                val plain = chunk.filter { identities[it] == null }
                if (plain.isNotEmpty()) {
                    dao.booksByUrls(plain).forEach { row ->
                        idsByUrl.getOrPut(row.url) { mutableListOf() } += row.bookId
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Unreadable chunk: leave those URLs unfound.
            }
        }

        val movedIds = HashSet<Long>()
        for (chunk in idsByUrl.values.flatten().chunked(SqliteIdChunk)) {
            currentCoroutineContext().ensureActive()
            try {
                if (dao.setShelfForBooks(chunk, shelfId) == chunk.size) {
                    movedIds += chunk
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The chunk failed as a whole; its books stay put.
            }
        }

        val moved = requested.filterTo(LinkedHashSet()) { url ->
            idsByUrl[url]?.all { it in movedIds } == true
        }
        return BulkBookResult(moved, requested - moved)
    }

    /**
     * Marks every chapter of the found books read/unread with one SQL UPDATE.
     * Books without chapter rows still count as handled: there was nothing to
     * write. `readChapters` is derived from these rows, so no book row changes.
     */
    suspend fun markBooksRead(
        bookUrls: List<String>,
        read: Boolean,
        at: Long,
    ): BulkBookResult {
        val requested = bookUrls.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (requested.isEmpty()) return BulkBookResult(emptySet(), emptySet())

        val idsByKey = LinkedHashMap<String, Long>()
        for (chunk in requested.chunked(SqliteIdChunk)) {
            currentCoroutineContext().ensureActive()
            val identities = chunk.associateWith(::decodeLibraryBookKey)
            try {
                val decoded = identities.values.filterNotNull()
                val rows = if (decoded.isEmpty()) {
                    emptyList()
                } else {
                    dao.bookKeysByUrlsAll(decoded.map { it.url }.distinct())
                }
                identities.forEach { (key, identity) ->
                    if (identity != null) {
                        rows.firstOrNull {
                            it.url == identity.url && it.installId == identity.installId
                        }?.let { row -> idsByKey[key] = row.bookId }
                    }
                }
                val plain = chunk.filter { identities[it] == null }
                if (plain.isNotEmpty()) {
                    dao.booksByUrls(plain).forEach { row -> idsByKey[row.url] = row.bookId }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Unreadable chunk: those keys stay unmarked.
            }
        }

        val committedIds = HashSet<Long>()
        for (idsChunk in idsByKey.values.distinct().chunked(SqliteIdChunk)) {
            currentCoroutineContext().ensureActive()
            try {
                dao.setChaptersReadForBookIds(idsChunk, read, at)
                committedIds += idsChunk
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The chunk did not commit; those books stay unmarked.
            }
        }
        val succeeded = requested.filterTo(LinkedHashSet()) { key ->
            idsByKey[key]?.let { it in committedIds } == true
        }
        return BulkBookResult(succeeded, requested - succeeded)
    }

    suspend fun setInLibrary(bookId: Long, inLibrary: Boolean) =
        dao.setInLibrary(bookId, inLibrary)

    suspend fun addToLibrary(bookId: Long, at: Long) = dao.addToLibrary(bookId, at)

    suspend fun addToLibrary(bookUrl: String, at: Long) {
        bookRow(bookUrl)?.let { dao.addToLibrary(it.bookId, at) }
    }

    suspend fun setInLibrary(bookUrl: String, inLibrary: Boolean) {
        bookRow(bookUrl)?.let { dao.setInLibrary(it.bookId, inLibrary) }
    }

    suspend fun updateTotalChapters(bookId: Long, total: Int) =
        dao.updateTotalChapters(bookId, total)

    suspend fun recordUpdateCheck(
        bookId: Long,
        total: Int,
        newChapters: Int,
        hash: String?,
        at: Long,
    ) = dao.recordUpdateCheck(bookId, total, newChapters, hash, at)

    suspend fun markUpdatesSeen(bookId: Long, at: Long) = dao.markUpdatesSeen(bookId, at)

    suspend fun booksWithUpdates(): List<BookEntity> = dao.booksWithUpdates()

    suspend fun updateTotalChapters(bookUrl: String, total: Int) {
        bookRow(bookUrl)?.let { dao.updateTotalChapters(it.bookId, total) }
    }

    suspend fun isInLibrary(bookId: Long): Boolean = dao.inLibrary(bookId) == true

    suspend fun isInLibrary(bookUrl: String): Boolean =
        bookRow(bookUrl)?.let { dao.inLibrary(it.bookId) == true } == true

    suspend fun touchHistory(bookId: Long, chapterId: Long?, chapterIndex: Int?, at: Long) =
        dao.touchHistoryAndRecord(bookId, chapterId, chapterIndex, at)

    suspend fun touchHistory(bookUrl: String, chapterIndex: Int, at: Long) {
        val book = bookRow(bookUrl) ?: return
        val chapter = dao.chapterByIndex(book.bookId, chapterIndex)
        dao.touchHistoryAndRecord(book.bookId, chapter?.chapterId, chapterIndex, at)
    }

    /** Deletes one history record; the book, progress and library are untouched. */
    suspend fun deleteHistory(bookId: Long) = dao.deleteHistory(bookId)

    /** Deletes the selected history records; other book state is untouched. */
    suspend fun deleteHistory(bookIds: List<Long>) {
        bookIds.distinct().chunked(SqliteIdChunk).forEach { chunk ->
            currentCoroutineContext().ensureActive()
            dao.deleteHistoryFor(chunk)
        }
    }

    /** Clears every history record; books and their state stay in place. */
    suspend fun clearHistory() = dao.clearHistory()

    // --- chapters ------------------------------------------------------------

    suspend fun chapters(bookId: Long): List<ChapterEntity> = dao.chapters(bookId)

    suspend fun chapterByIndex(bookId: Long, index: Int): ChapterEntity? =
        dao.chapterByIndex(bookId, index)

    suspend fun chapterByUrl(bookId: Long, url: String): ChapterEntity? =
        dao.chapterByUrl(bookId, url)

    suspend fun chapter(chapterId: Long): ChapterEntity? = dao.chapter(chapterId)

    /**
     * Reconciles a fetched chapter list with the DB. Existing rows keep their
     * id, read/downloaded state and progress references; rows absent from the
     * new list are soft-removed, not hard-deleted.
     */
    suspend fun replaceChapters(
        bookId: Long,
        seeds: List<ChapterSeed>,
        at: Long,
    ): List<ChapterEntity> = dao.reconcileChapters(bookId, seeds, at)

    suspend fun deleteChaptersByIndex(bookId: Long, from: Int, to: Int, at: Long) =
        dao.deleteChaptersByIndex(bookId, from, to, at)

    suspend fun markChapterRangeRead(
        bookId: Long,
        from: Int,
        to: Int,
        read: Boolean,
        at: Long,
    ) = dao.setChapterReadRange(bookId, from, to, read, at)

    suspend fun markChapterRemoved(chapterId: Long, at: Long) =
        dao.markChapterRemoved(chapterId, at)

    suspend fun markChapterRead(bookId: Long, index: Int, read: Boolean, at: Long) =
        dao.setChapterRead(bookId, index, read, at)

    suspend fun markChapterRead(bookUrl: String, chapterIndex: Int, read: Boolean, at: Long) {
        bookRow(bookUrl)?.let { dao.setChapterRead(it.bookId, chapterIndex, read, at) }
    }

    /** One UPDATE for an arbitrary selected set, not a call per chapter. */
    suspend fun markChaptersRead(
        bookUrl: String,
        chapterIndexes: Set<Int>,
        read: Boolean,
        at: Long,
    ) {
        if (chapterIndexes.isEmpty()) return
        bookRow(bookUrl)?.let { book ->
            for (chunk in chapterIndexes.chunked(SqliteIdChunk)) {
                currentCoroutineContext().ensureActive()
                dao.setChaptersRead(book.bookId, chunk, read, at)
            }
        }
    }

    suspend fun readChapterIndexes(bookId: Long): List<Int> =
        dao.readChapterIndexes(bookId)

    /** One GROUP BY query; the list/dashboard screens use this instead of N+1. */
    suspend fun readChapterStats(): Map<Long, BookReadStats> =
        dao.readChapterStats().associate { row ->
            row.bookId to BookReadStats(
                readCount = row.readCount,
                maxReadIndex = row.maxReadIndex,
            )
        }

    suspend fun readChapterCounts(): Map<Long, Int> =
        readChapterStats().mapValues { it.value.readCount }

    /**
     * Same result as [readChapterStats] restricted to [bookIds]. Ids are
     * chunked below SQLite's bind limit, so list screens no longer scan every
     * chapter row just to render the visible page.
     */
    suspend fun readChapterStatsFor(bookIds: Collection<Long>): Map<Long, BookReadStats> {
        val ids = bookIds.asSequence().filter { it > 0 }.distinct().toList()
        if (ids.isEmpty()) return emptyMap()
        return buildMap {
            ids.chunked(SqliteIdChunk).forEach { chunk ->
                dao.readChapterStatsFor(chunk).forEach { row ->
                    put(
                        row.bookId,
                        BookReadStats(
                            readCount = row.readCount,
                            maxReadIndex = row.maxReadIndex,
                        ),
                    )
                }
            }
        }
    }

    suspend fun readChapterIndexes(bookUrl: String): List<Int> =
        bookRow(bookUrl)?.let { dao.readChapterIndexes(it.bookId) }.orEmpty()

    /**
     * One book's chapter status in a fixed number of queries: chapters (+
     * read/downloaded/bodies/pages), complete translations, and the last-read
     * chapter. Callers overlay it on the chapter list, never per row.
     */
    suspend fun chapterStatusSnapshot(bookUrl: String): ChapterStatusSnapshot? =
        bookRow(bookUrl)?.let { chapterStatusSnapshot(it.bookId) }

    suspend fun chapterStatusSnapshot(bookId: Long): ChapterStatusSnapshot? {
        dao.book(bookId) ?: return null
        val rows = dao.chapterStatusRows(bookId)
        val translated = dao.translatedChapterRows(bookId)
        val translationStates = dao.chapterTranslationQueueRows(bookId)
            .groupBy { it.idx }
            .mapValues { (_, rows) -> rows.first().status }
        val lastRead = dao.lastReadChapterRow(bookId)
        val last = rows.maxByOrNull { it.idx }
        val downloaded = rows.filter { it.downloaded || it.hasBody || it.hasPages }
        val indexByChapterId = rows.associate { it.chapterId to it.idx }
        val downloads = dao.downloadTasksForBook(bookId).mapNotNull { task ->
            val index = indexByChapterId[task.chapterId] ?: return@mapNotNull null
            index to ChapterDownloadState(task.state, task.progress)
        }.toMap()
        return ChapterStatusSnapshot(
            bookId = bookId,
            chapterCount = rows.size,
            readIndexes = rows.filter { it.read }.mapTo(HashSet()) { it.idx },
            downloadedIndexes = downloaded.mapTo(HashSet()) { it.idx },
            downloadedUrls = downloaded.mapTo(HashSet()) { it.url },
            translatedIndexes = translated.mapTo(HashSet()) { it.idx },
            translatedUrls = translated.mapTo(HashSet()) { it.url },
            lastIndex = last?.idx,
            lastChapterId = last?.chapterId,
            lastReadIndex = lastRead?.idx,
            lastReadUrl = lastRead?.url,
            downloads = downloads,
            translationStates = translationStates,
            sizeBytes = rows.associate { it.idx to it.sizeBytes },
        )
    }

    // --- progress / sessions -------------------------------------------------

    suspend fun progress(bookId: Long): ProgressEntity? = dao.progress(bookId)

    suspend fun saveProgress(progress: ProgressEntity) = dao.saveProgressIfNewer(progress)

    suspend fun clearProgress(bookId: Long) = dao.deleteProgress(bookId)

    suspend fun videoProgress(bookId: Long, chapterId: Long): VideoProgressEntity? =
        dao.videoProgress(bookId, chapterId)

    /**
     * Persists the playback position and marks the chapter read past 95% of a
     * known duration. The threshold lives here, not in the DAO, so the raw
     * row stays a plain position update.
     */
    suspend fun saveVideoProgress(
        bookId: Long,
        chapterId: Long,
        positionMs: Long,
        durationMs: Long,
        at: Long,
    ): VideoProgressEntity {
        val row = VideoProgressEntity(
            bookId = bookId,
            chapterId = chapterId,
            positionMs = positionMs,
            durationMs = durationMs,
            updatedAt = at,
        )
        dao.upsertVideoProgress(row)
        if (durationMs > 0 && positionMs * 100 >= durationMs * 95) {
            dao.setChapterReadById(chapterId, read = true, at = at)
        }
        return row
    }

    suspend fun clearVideoProgress(bookId: Long, chapterId: Long) =
        dao.deleteVideoProgress(bookId, chapterId)

    suspend fun saveSession(session: ReadingSessionEntity) {
        val key = session.sessionKey
        if (key != null && dao.sessionByKey(key) != null) return
        dao.upsertSession(session)
    }

    suspend fun sessions(bookId: Long, limit: Int): List<ReadingSessionEntity> =
        dao.sessions(bookId, limit)

    suspend fun durationBetween(from: Long, to: Long, kind: String): Long =
        dao.durationBetween(from, to, kind)

    suspend fun durationForBook(bookId: Long, kind: String): Long =
        dao.durationForBook(bookId, kind)

    // --- bodies / download tasks (used by the download lane) -----------------

    suspend fun chapterBody(chapterId: Long): ChapterBodyEntity? = dao.chapterBody(chapterId)

    suspend fun saveChapterBody(body: ChapterBodyEntity) = dao.upsertChapterBody(body)

    suspend fun deleteChapterBody(chapterId: Long) = dao.deleteChapterBody(chapterId)

    suspend fun saveDownloadTask(task: DownloadTaskEntity) = dao.upsertDownloadTask(task)

    suspend fun saveDownloadTasks(tasks: List<DownloadTaskEntity>) =
        dao.upsertDownloadTasks(tasks)

    suspend fun downloadTasks(): List<DownloadTaskEntity> = dao.downloadTasks()

    suspend fun downloadTasks(state: String): List<DownloadTaskEntity> =
        dao.downloadTasks(state)

    suspend fun downloadTask(id: Long): DownloadTaskEntity? = dao.downloadTask(id)

    suspend fun downloadTaskForChapter(chapterId: Long): DownloadTaskEntity? =
        dao.downloadTaskForChapter(chapterId)

    suspend fun downloadTasksForBook(bookId: Long): List<DownloadTaskEntity> =
        dao.downloadTasksForBook(bookId)

    fun downloadTasksFlow(): Flow<List<DownloadTaskEntity>> =
        dao.downloadTasksFlow()

    fun downloadTasksForBookFlow(bookId: Long): Flow<List<DownloadTaskEntity>> =
        dao.downloadTasksForBookFlow(bookId)

    suspend fun deleteDownloadTask(id: Long) = dao.deleteDownloadTask(id)

    suspend fun deleteDownloadTasksForBook(bookId: Long) =
        dao.deleteDownloadTasksForBook(bookId)

    suspend fun updateDownloadTaskState(id: Long, state: String, error: String?, at: Long) =
        dao.updateDownloadTaskState(id, state, error, at)

    suspend fun booksWithActiveDownloads(): List<Long> = dao.booksWithActiveDownloads()

    suspend fun setChapterDownloaded(chapterId: Long, downloaded: Boolean) =
        dao.setChapterDownloaded(chapterId, downloaded)

    suspend fun deleteChapterPage(chapterId: Long) = dao.deleteChapterPage(chapterId)

    // --- v4 metadata / content / local / translation / highlights ----------

    suspend fun saveBookCover(cover: BookCoverEntity) = dao.upsertBookCover(cover)

    suspend fun bookCover(bookId: Long): BookCoverEntity? = dao.bookCover(bookId)

    /** All cover rows in one query for list/dashboard batch mapping. */
    suspend fun bookCovers(): Map<Long, BookCoverEntity> =
        dao.bookCovers().associateBy { it.bookId }

    /**
     * Same result as [bookCovers] restricted to [bookIds]. Ids are chunked
     * below SQLite's bind limit, so a single-row read never scans the table.
     */
    suspend fun bookCoversFor(bookIds: Collection<Long>): Map<Long, BookCoverEntity> {
        val ids = bookIds.asSequence().filter { it > 0 }.distinct().toList()
        if (ids.isEmpty()) return emptyMap()
        return buildMap {
            ids.chunked(SqliteIdChunk).forEach { chunk ->
                dao.bookCoversFor(chunk).forEach { put(it.bookId, it) }
            }
        }
    }

    suspend fun coversDue(at: Long): List<BookCoverEntity> = dao.coversDue(at)

    suspend fun saveSourceIcon(icon: SourceIconEntity) = dao.upsertSourceIcon(icon)

    suspend fun sourceIcon(installId: String, version: String, urlHash: String): SourceIconEntity? =
        dao.sourceIcon(installId, version, urlHash)

    suspend fun deleteSourceIcons(installId: String) = dao.deleteSourceIcons(installId)

    suspend fun saveTag(tag: TagEntity) {
        val id = dao.upsertTag(tag)
        val tagId = if (tag.tagId == 0L) id else tag.tagId
        if (tagId <= 0L) return
        // A tag rename changes the indexed text of every book carrying it.
        for (bookId in dao.bookIdsForTag(tagId)) {
            reindexBook(bookId)
        }
    }

    suspend fun tagByNormalized(name: String): TagEntity? = dao.tagByNormalized(name)

    suspend fun saveBookTag(bookTag: BookTagEntity) {
        dao.upsertBookTag(bookTag)
        reindexBook(bookTag.bookId)
    }

    /**
     * Replaces every tag link of one book in a single batch, then rebuilds its
     * FTS row once. Resolving/creating tags does not reindex unrelated books;
     * the per-tag [saveTag]/[saveBookTag] path stays for the single-edit UI.
     */
    suspend fun replaceBookTags(bookId: Long, tags: List<TagEntity>) {
        dao.deleteBookTags(bookId)
        val links = tags
            .filter { it.name.isNotBlank() && it.normalizedName.isNotBlank() }
            .distinctBy { it.normalizedName }
            .mapIndexedNotNull { position, tag ->
                val tagId = dao.tagByNormalized(tag.normalizedName)?.tagId
                    ?: dao.upsertTag(tag)
                if (tagId <= 0L) {
                    null
                } else {
                    BookTagEntity(
                        bookId = bookId,
                        tagId = tagId,
                        source = "PLUGIN",
                        position = position,
                    )
                }
            }
        if (links.isNotEmpty()) dao.upsertBookTags(links)
        reindexBook(bookId)
    }

    suspend fun tagsForBook(bookId: Long): List<TagEntity> = dao.tagsForBook(bookId)

    /** One JOIN query for every book's tag names, preserving DAO order. */
    suspend fun tagsByBook(): Map<Long, List<String>> =
        dao.tagsByBook().groupBy({ it.bookId }, { it.name })

    /**
     * Same result as [tagsByBook] restricted to [bookIds]. Ids are chunked
     * below SQLite's bind limit; chunk concatenation keeps the per-book
     * position order and duplicate names.
     */
    suspend fun tagsByBookFor(bookIds: Collection<Long>): Map<Long, List<String>> {
        val ids = bookIds.asSequence().filter { it > 0 }.distinct().toList()
        if (ids.isEmpty()) return emptyMap()
        return ids.chunked(SqliteIdChunk)
            .flatMap { chunk -> dao.tagsByBookFor(chunk) }
            .groupBy({ it.bookId }, { it.name })
    }

    suspend fun deleteBookTags(bookId: Long) {
        dao.deleteBookTags(bookId)
        reindexBook(bookId)
    }

    suspend fun highlights(bookId: Long): List<HighlightEntity> = dao.highlights(bookId)

    suspend fun saveHighlight(highlight: HighlightEntity): Long = dao.upsertHighlight(highlight)

    suspend fun deleteHighlight(id: Long) = dao.deleteHighlight(id)

    suspend fun saveChapterPage(page: ChapterPageEntity) = dao.upsertChapterPage(page)

    suspend fun chapterPage(chapterId: Long): ChapterPageEntity? = dao.chapterPage(chapterId)

    suspend fun saveLocalBookFile(file: LocalBookFileEntity) = dao.upsertLocalBookFile(file)

    suspend fun localBookFile(bookId: Long): LocalBookFileEntity? = dao.localBookFile(bookId)

    suspend fun localBookFileByUri(uri: String): LocalBookFileEntity? =
        dao.localBookFileByUri(uri)

    suspend fun localBookFileBySha(sha256: String): LocalBookFileEntity? =
        dao.localBookFileBySha(sha256)

    suspend fun libraryBookCount(installId: String): Int = dao.libraryBookCount(installId)

    /** Every in-library book, regardless of install/source. */
    suspend fun libraryBookCount(): Int = dao.libraryBookCount()

    /** Every book row in the DB snapshot, including history-only rows. */
    suspend fun bookCount(): Int = dao.bookCount()

    suspend fun outOfLibraryBookCount(): Int = dao.outOfLibraryBookCount()

    suspend fun bookIds(): List<Long> = dao.bookIds()

    suspend fun bookCoverIds(): List<Long> = dao.bookCoverIds()

    suspend fun localBookCopyPaths(): List<String> = dao.localBookCopyPaths()

    suspend fun localBookBodyPaths(): List<String> =
        dao.localBackupBodyPaths().map { it.path }

    suspend fun localBookPagePaths(): List<String> =
        dao.localPagePathsWithoutCopy().map { it.pathsJson }

    suspend fun libraryBookUrls(): List<String> = dao.libraryBookUrls()

    /**
     * Removes every book that is no longer in the library and all of its owned
     * rows/files (chapters, bodies, pages, progress, sessions, history,
     * downloads, covers, translations, TTS, local files). Library books are
     * never touched. Files are delivered after each committed chunk.
     */
    suspend fun purgeOutOfLibrary(
        onCommittedFiles: suspend (List<LibraryBookFiles>) -> Unit = {},
    ): BulkDeleteResult {
        val rows = dao.outOfLibraryBooks()
        if (rows.isEmpty()) return BulkDeleteResult(emptySet(), emptySet(), emptyList())
        val urlById = rows.associate { it.bookId to it.url }
        val deleted = LinkedHashSet<String>()
        val missing = LinkedHashSet<String>()
        try {
            for (chunk in rows.map { it.bookId }.chunked(SqliteIdChunk)) {
                currentCoroutineContext().ensureActive()
                // History-only rows have no live unique WorkManager request;
                // calling notifyBeforeDelete per row would queue thousands of
                // pointless cancelUniqueWork writes.
                val chunkFiles = try {
                    bookLocks.withLocks(chunk.map(bookLocks::bookKey)) {
                        groupBookFiles(dao.deleteBooksWithFiles(chunk))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    chunk.forEach { bookId -> urlById[bookId]?.let(missing::add) }
                    continue
                }
                chunk.forEach { bookId -> urlById[bookId]?.let(deleted::add) }
                if (chunkFiles.isNotEmpty()) {
                    // Files are off the DB row now; the caller deletes them on
                    // IO. Do not accumulate every path in memory.
                    onCommittedFiles(chunkFiles)
                }
            }
        } catch (e: CancellationException) {
            throw e
        }
        return BulkDeleteResult(deleted = deleted, missing = missing, files = emptyList())
    }

    /**
     * Every in-library local book with the paths of the files that must go
     * into a backup (copy, cover, chapter bodies, manga pages). The archive
     * re-checks inLibrary before packing, so a book toggled out mid-run is
     * never written.
     */
    suspend fun localBackupBooks(): List<LocalBackupBook> {
        val rows = dao.localBackupBooks()
        if (rows.isEmpty()) return emptyList()
        val bodies = dao.localBackupBodyPaths().groupBy({ it.bookId }, { it.path })
        return rows.map { row ->
            LocalBackupBook(
                bookId = row.bookId,
                installId = row.installId,
                title = row.title,
                inLibrary = row.inLibrary,
                copyPath = row.copyPath,
                format = row.format,
                coverLocalPath = row.coverLocalPath,
                bodyPaths = bodies[row.bookId].orEmpty(),
            )
        }
    }

    suspend fun saveImportJob(job: ImportJobEntity): Long = dao.upsertImportJob(job)
    /**
     * Covers of every in-library book, including plugin books. The caller
     * decides whether non-override covers are cache (includeImages) while a
     * user override is always user data.
     */
    suspend fun localBackupCovers(): List<LocalBackupCover> =
        dao.localBackupCovers().mapNotNull { row ->
            val path = row.coverLocalPath?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            LocalBackupCover(
                bookId = row.bookId,
                installId = row.installId,
                title = row.title,
                coverLocalPath = path,
                isUserOverride = row.isUserOverride,
            )
        }


    suspend fun importJobs(): List<ImportJobEntity> = dao.importJobs()

    suspend fun saveChapterTranslation(translation: ChapterTranslationEntity): Long {
        // Identity excludes sourceHash: a re-translated body updates the row
        // instead of piling up one row per revision.
        val existing = dao.chapterTranslationAnySource(
            chapterId = translation.chapterId,
            source = translation.source,
            target = translation.target,
            provider = translation.provider,
            promptHash = translation.promptHash,
            pipelineVersion = translation.pipelineVersion,
        )
        if (existing != null) {
            dao.upsertChapterTranslation(translation.copy(translationId = existing.translationId))
            return existing.translationId
        }
        return dao.upsertChapterTranslation(translation)
    }

    /**
     * Atomic cache write: the COMPLETE status and its blocks commit together,
     * so an interrupted save cannot leave a status row without content.
     */
    suspend fun saveChapterTranslationWithBlocks(
        translation: ChapterTranslationEntity,
        blocks: List<TranslationBlockEntity>,
    ): Long = dao.saveTranslationWithBlocks(translation, blocks)

    /** Atomic FAILED status + stale-block removal. */
    suspend fun saveChapterTranslationFailure(
        translation: ChapterTranslationEntity,
    ): Long = dao.saveTranslationFailure(translation)

    suspend fun chapterTranslation(
        chapterId: Long,
        source: String,
        target: String,
        provider: String,
        promptHash: String,
        sourceHash: String,
        pipelineVersion: String,
    ): ChapterTranslationEntity? = dao.chapterTranslation(
        chapterId = chapterId,
        source = source,
        target = target,
        provider = provider,
        promptHash = promptHash,
        sourceHash = sourceHash,
        pipelineVersion = pipelineVersion,
    )

    /** Batch read for overlays: one query, newest row per book/config. */
    suspend fun bookTranslations(
        bookIds: List<Long>,
        source: String,
        target: String,
        provider: String,
        promptHash: String,
        pipelineVersion: String,
    ): Map<Long, BookTranslationEntity> {
        val ids = bookIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        val newest = LinkedHashMap<Long, BookTranslationEntity>()
        for (chunk in ids.chunked(SqliteIdChunk)) {
            currentCoroutineContext().ensureActive()
            dao.bookTranslations(
                bookIds = chunk,
                source = source,
                target = target,
                provider = provider,
                promptHash = promptHash,
                pipelineVersion = pipelineVersion,
            ).forEach { row -> newest.putIfAbsent(row.bookId, row) }
        }
        return newest
    }

    suspend fun bookIdByUrl(bookUrl: String): Long? = bookRow(bookUrl)?.bookId

    /**
     * Resolves a URL only when it identifies exactly one book. The books table
     * is unique per (installId, url), so a URL-only lookup can land on another
     * install's row; translation caches prefer a cache miss over a wrong hit.
     */
    suspend fun bookIdByUrlUnique(bookUrl: String): Long? {
        val identity = decodeLibraryBookKey(bookUrl)
        if (identity != null) {
            return dao.bookByInstallAndUrl(identity.installId, identity.url)?.bookId
        }
        return dao.booksByUrl(bookUrl).singleOrNull()?.bookId
    }

    /** installId per URL; first row wins when two installs share a URL. */
    suspend fun bookSourcesByUrls(bookUrls: List<String>): Map<String, Set<String>> {
        val urls = bookUrls.distinct()
        if (urls.isEmpty()) return emptyMap()
        val sources = LinkedHashMap<String, MutableSet<String>>()
        for (chunk in urls.chunked(SqliteIdChunk)) {
            currentCoroutineContext().ensureActive()
            dao.bookKeysByUrls(chunk).forEach { row ->
                sources.getOrPut(row.url) { linkedSetOf() } += row.installId
            }
        }
        return sources
    }

    suspend fun bookIdsByUrls(bookUrls: List<String>): Map<String, Long> {
        val urls = bookUrls.distinct()
        if (urls.isEmpty()) return emptyMap()
        val ids = LinkedHashMap<String, Long>()
        for (chunk in urls.chunked(SqliteIdChunk)) {
            currentCoroutineContext().ensureActive()
            val identities = chunk.associateWith(::decodeLibraryBookKey)
            identities.forEach { (key, identity) ->
                if (identity != null) {
                    dao.bookByInstallAndUrl(identity.installId, identity.url)?.let { row ->
                        ids[key] = row.bookId
                    }
                }
            }
            val plain = chunk.filter { identities[it] == null }
            if (plain.isNotEmpty()) {
                dao.booksByUrls(plain).forEach { ids[it.url] = it.bookId }
            }
        }
        return ids
    }

    /**
     * Same as [bookIdsByUrls] but only for URLs that identify exactly one book.
     * Translation caches use this: a wrong row is worse than a cache miss.
     */
    suspend fun uniqueBookIdsByUrls(bookUrls: List<String>): Map<String, Long> {
        val urls = bookUrls.distinct()
        if (urls.isEmpty()) return emptyMap()
        val ids = LinkedHashMap<String, Long>()
        for (chunk in urls.chunked(SqliteIdChunk)) {
            currentCoroutineContext().ensureActive()
            val identities = chunk.associateWith(::decodeLibraryBookKey)
            identities.forEach { (key, identity) ->
                if (identity != null) {
                    dao.bookByInstallAndUrl(identity.installId, identity.url)?.let { row ->
                        ids[key] = row.bookId
                    }
                }
            }
            val plain = chunk.filter { identities[it] == null }
            if (plain.isNotEmpty()) {
                val byUrl = dao.booksByUrls(plain).groupBy { it.url }
                plain.forEach { url ->
                    byUrl[url]?.singleOrNull()?.let { ids[url] = it.bookId }
                }
            }
        }
        return ids
    }

    /**
     * Latest translation row for a chapter/config regardless of source hash.
     * The download-intent path needs this because the body (and therefore the
     * real source hash) does not exist until the download finishes.
     */
    suspend fun latestChapterTranslation(
        chapterId: Long,
        source: String,
        target: String,
        provider: String,
        promptHash: String,
        pipelineVersion: String,
    ): ChapterTranslationEntity? = dao.chapterTranslationAnySource(
        chapterId = chapterId,
        source = source,
        target = target,
        provider = provider,
        promptHash = promptHash,
        pipelineVersion = pipelineVersion,
    )

    /**
     * Latest COMPLETE translation for a chapter/language pair/provider, even
     * when the source text or prompt changed. The reader uses this as the
     * fallback when the original cannot be loaded.
     */
    suspend fun latestCompleteChapterTranslation(
        chapterId: Long,
        source: String,
        target: String,
        provider: String,
    ): ChapterTranslationEntity? = dao.latestCompleteChapterTranslation(
        chapterId = chapterId,
        source = source,
        target = target,
        provider = provider,
    )

    suspend fun chapterTranslationQueueRows(bookId: Long): List<ChapterTranslationQueueRow> =
        dao.chapterTranslationQueueRows(bookId)

    fun chapterTranslationQueueRowsFlow(): Flow<List<ChapterTranslationQueueRow>> =
        dao.chapterTranslationQueueRowsFlow()

    /**
     * Pending/failed rows only, using the same "latest row per chapter" rule
     * as [chapterTranslationQueueRowsFlow]. Downloads uses this to count work
     * without loading every COMPLETE translation into memory.
     */
    fun chapterTranslationQueuePendingRowsFlow(): Flow<List<ChapterTranslationQueueRow>> =
        dao.chapterTranslationQueuePendingRowsFlow()

    suspend fun deleteChapterTranslation(translationId: Long) =
        dao.deleteChapterTranslation(translationId)

    /** Books with durable PENDING translation intents; used on app start. */
    suspend fun bookIdsWithPendingTranslations(): List<Long> =
        dao.bookIdsWithPendingTranslations()

    suspend fun saveTranslationBlock(block: TranslationBlockEntity) = dao.upsertTranslationBlock(block)

    suspend fun translationBlocks(translationId: Long): List<TranslationBlockEntity> =
        dao.translationBlocks(translationId)

    suspend fun deleteTranslationBlocks(translationId: Long) = dao.deleteTranslationBlocks(translationId)

    /** Deletes every target/provider translation row for the selected chapters. */
    suspend fun deleteChapterTranslations(bookUrl: String, chapterIndexes: Set<Int>): Int {
        if (chapterIndexes.isEmpty()) return 0
        val actualUrl = bookKeyUrl(bookUrl)
        var deleted = 0
        for (chunk in chapterIndexes.chunked(SqliteIdChunk)) {
            currentCoroutineContext().ensureActive()
            deleted += dao.deleteChapterTranslations(actualUrl, chunk)
        }
        return deleted
    }

    suspend fun saveBookTranslation(translation: BookTranslationEntity): Long {
        val existing = dao.bookTranslation(
            bookId = translation.bookId,
            source = translation.source,
            target = translation.target,
            provider = translation.provider,
            promptHash = translation.promptHash,
            pipelineVersion = translation.pipelineVersion,
        )
        val id = if (existing == null) {
            dao.upsertBookTranslation(translation)
        } else {
            dao.upsertBookTranslation(translation.copy(translationId = existing.translationId))
            existing.translationId
        }
        dao.book(translation.bookId)?.let { book ->
            indexBookWithTags(book, translation.source, translation.target)
        }
        return id
    }

    suspend fun bookTranslation(
        bookId: Long,
        source: String,
        target: String,
        provider: String,
        promptHash: String,
        pipelineVersion: String,
    ): BookTranslationEntity? = dao.bookTranslation(
        bookId = bookId,
        source = source,
        target = target,
        provider = provider,
        promptHash = promptHash,
        pipelineVersion = pipelineVersion,
    )

    suspend fun saveTtsChunk(chunk: TtsChunkEntity) = dao.upsertTtsChunk(chunk)

    suspend fun ttsChunks(chapterId: Long): List<TtsChunkEntity> = dao.ttsChunks(chapterId)

    suspend fun saveBookMigration(migration: BookMigrationEntity): Long =
        dao.upsertBookMigration(migration)

    suspend fun bookMigrations(): List<BookMigrationEntity> = dao.bookMigrations()

    suspend fun deleteBookMigrationBatch(batchId: String): Int =
        dao.deleteBookMigrationsForBatch(batchId)

    // --- storage accounting --------------------------------------------------

    suspend fun storageUsage(): LibraryStorageUsage {
        val totals = dao.storageTotals()
        return LibraryStorageUsage(
            chapterBodyBytes = totals.chapterBodyBytes,
            chapterPageBytes = totals.chapterPageBytes,
            coverBytes = totals.coverBytes,
            sourceIconBytes = totals.sourceIconBytes,
            localNovelBytes = totals.localNovelBytes,
            localMangaBytes = totals.localMangaBytes,
            chapterBodyFiles = dao.chapterBodyFiles(),
            chapterPageFiles = dao.chapterPageFiles(),
            coverFiles = dao.coverFiles(),
            sourceIconFiles = dao.sourceIconFiles(),
            localNovelFiles = dao.localNovelFiles(),
            localMangaFiles = dao.localMangaFiles(),
            bookCount = totals.bookCount,
            chapterCount = totals.chapterCount,
            coverCount = totals.coverCount,
            localFileCount = totals.localFileCount,
            clearableChapterBodyCount = totals.clearableChapterBodyCount,
            clearableChapterPageCount = totals.clearableChapterPageCount,
            clearableCoverCount = totals.clearableCoverCount,
            sourceIconCount = totals.sourceIconCount,
        )
    }

    /** Deletes only re-downloadable chapter text rows; returns their paths. */
    suspend fun clearRemovableChapterBodies(): List<String> {
        val paths = dao.removableChapterBodyPaths()
        dao.deleteRemovableChapterBodies()
        dao.resetOrphanedDownloadedFlags()
        return paths
    }

    /** Deletes only re-downloadable manga page rows; returns their path JSON. */
    suspend fun clearRemovableChapterPages(): List<String> {
        val paths = dao.removableChapterPagePathsJson()
        dao.deleteRemovableChapterPages()
        dao.resetOrphanedDownloadedFlags()
        return paths
    }

    /** Deletes remote covers (not overrides/imported ones) and source icons. */
    suspend fun clearImageCache(): List<String> {
        val paths = dao.removableCoverPaths() + dao.removableSourceIconPaths()
        dao.deleteRemovableCovers()
        dao.deleteSourceIconsCache()
        return paths
    }

    /**
     * Repairs rows created by the identity regression, where `books.url` holds
     * an opaque route key instead of the real URL. A row that owns any child is
     * never deleted: when one side is childless it is merged into the other,
     * and a row with children on both sides is only reported.
     */
    suspend fun repairKeyUrls(
        onBookKeyRewritten: suspend (oldKey: String, newUrl: String) -> Unit = { _, _ -> },
    ): RepairReport {
        var report = RepairReport()
        val orphanPaths = mutableListOf<String>()
        var afterId = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.keyUrlBooks(afterId, LibraryBookKeyPrefix, RepairPageSize)
            if (page.isEmpty()) break
            for (keyRow in page) {
                currentCoroutineContext().ensureActive()
                val identity = decodeLibraryBookKey(keyRow.url)
                if (identity == null) {
                    report = report.copy(unrepairable = report.unrepairable + 1)
                    continue
                }
                val outcome = try {
                    repairBookKeyRow(keyRow, identity, onBookKeyRewritten, orphanPaths)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    RepairOutcome(failed = 1)
                }
                report = report.copy(
                    rewrittenInPlace = report.rewrittenInPlace + outcome.rewritten,
                    deletedEmpty = report.deletedEmpty + outcome.deleted,
                    ambiguous = report.ambiguous + outcome.ambiguous,
                    ambiguousSoftRemoved = report.ambiguousSoftRemoved + outcome.ambiguousSoft,
                    failed = report.failed + outcome.failed,
                )
            }
            afterId = page.last().bookId
        }
        dao.deleteOrphanBookSearch()
        return report.copy(orphanPaths = orphanPaths.toList())
    }

    private suspend fun repairBookKeyRow(
        keyRow: BookEntity,
        identity: BookIdentity,
        onBookKeyRewritten: suspend (String, String) -> Unit,
        orphanPaths: MutableList<String>,
    ): RepairOutcome {
        val twin = dao.bookByInstallAndUrl(identity.installId, identity.url)
            ?: dao.legacyBookByUrl(identity.url)
        val locks = buildList {
            add(bookLocks.bookKey(keyRow.bookId))
            twin?.let { add(bookLocks.bookKey(it.bookId)) }
            add(bookLocks.targetKey(identity.installId, identity.url))
        }
        val action = bookLocks.withLocks(locks) {
            // One transaction per book; the twin is re-read inside it.
            dao.repairBookKeyRow(keyRow, identity.installId, identity.url, orphanPaths)
        }
        return when (action) {
            BookKeyRepairAction.REWRITTEN -> {
                onBookKeyRewritten(keyRow.url, identity.url)
                RepairOutcome(rewritten = 1)
            }
            BookKeyRepairAction.DELETED -> {
                // The surviving row kept the real URL; move the settings that
                // were saved under the opaque key too.
                onBookKeyRewritten(keyRow.url, identity.url)
                RepairOutcome(deleted = 1)
            }
            BookKeyRepairAction.AMBIGUOUS -> RepairOutcome(ambiguous = 1)
            BookKeyRepairAction.AMBIGUOUS_SOFT -> RepairOutcome(ambiguousSoft = 1)
            BookKeyRepairAction.NONE -> RepairOutcome()
        }
    }

    private data class RepairOutcome(
        val rewritten: Int = 0,
        val deleted: Int = 0,
        val ambiguous: Int = 0,
        val ambiguousSoft: Int = 0,
        val failed: Int = 0,
    )

    private suspend fun reindexBook(bookId: Long) {
        dao.book(bookId)?.let { indexBookWithTags(it) }
    }

    private fun groupBookFiles(rows: List<BookFileRow>): List<LibraryBookFiles> =
        rows.groupBy { it.bookId }.map { (bookId, bookRows) ->
            LibraryBookFiles(
                bookId = bookId,
                coverPath = bookRows.firstNotNullOfOrNull { it.coverPath.cleanPath() },
                copyPath = bookRows.firstNotNullOfOrNull { it.copyPath.cleanPath() },
                folderCopy = bookRows.any { it.parserVersion == 2 },
                bodyPaths = bookRows.mapNotNull { it.bodyPath.cleanPath() }.distinct(),
                pagePathsJson = bookRows.mapNotNull { it.pagePathsJson.cleanPath() }.distinct(),
            )
        }
}

private fun String?.cleanPath(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Turns free text into an FTS5 MATCH expression: every term is quoted (so
 * FTS operators and punctuation are literal) and marked as a prefix. Returns
 * null when nothing searchable remains, which callers treat as "no query".
 */
internal fun buildFtsMatch(query: String): String? {
    val terms = query
        .split(Whitespace)
        .map { it.trim() }
        .filter { term -> term.any { it.isLetterOrDigit() } }
    if (terms.isEmpty()) return null
    return terms.joinToString(" ") { term ->
        "\"" + term.replace("\"", "\"\"") + "\"*"
    }
}

private fun BookEntity.toSearchRow(tags: String, translatedTitle: String) = BookSearchEntity(
    bookId = bookId,
    title = title,
    author = authorRaw.ifBlank { author },
    tags = tags,
    description = description,
    translatedTitle = translatedTitle,
)

private val Whitespace = Regex("\\s+")

private const val RepairPageSize = 200

data class ChapterSeed(
    val index: Int,
    val url: String,
    val title: String,
    val hash: String? = null,
)

data class RebaseReport(
    val books: Int,
    val chapters: Int,
)

val DefaultShelves: List<ShelfEntity> = listOf(
    ShelfEntity(id = "reading", label = "Reading", position = 0),
    ShelfEntity(id = "completed", label = "Completed", position = 1),
)

private val SystemShelfIds = DefaultShelves.map { it.id }.toSet()
