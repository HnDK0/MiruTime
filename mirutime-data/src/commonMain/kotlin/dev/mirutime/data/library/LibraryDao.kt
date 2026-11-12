package dev.mirutime.data.library

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.RawQuery
import androidx.room3.RoomRawQuery
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow

/** Minimal projection for URL-based book lookups (never a full row). */
data class BookUrlRow(val bookId: Long, val url: String)

data class BookKeyRow(val bookId: Long, val installId: String, val url: String)

/** Result of an identity-based book upsert; [created] is false for a merge. */
data class UpsertResult(val id: Long, val created: Boolean)

/** Outcome of repairing one row whose URL is an opaque route key. */
enum class BookKeyRepairAction { REWRITTEN, DELETED, AMBIGUOUS, AMBIGUOUS_SOFT, NONE }

@Dao
interface LibraryDao {
    // --- shelves -------------------------------------------------------------

    @Query("SELECT * FROM shelves ORDER BY position, label")
    suspend fun shelves(): List<ShelfEntity>

    @Query("SELECT COUNT(*) FROM shelves")
    suspend fun shelfCount(): Int

    @Upsert
    suspend fun upsertShelf(shelf: ShelfEntity)

    @Upsert
    suspend fun upsertShelves(shelves: List<ShelfEntity>)

    @Query("DELETE FROM shelves WHERE id = :id")
    suspend fun deleteShelfRow(id: String)

    @Query("UPDATE books SET shelfId = :toId WHERE shelfId = :fromId")
    suspend fun moveBooksToShelf(fromId: String, toId: String)

    @Query("UPDATE shelves SET position = :position WHERE id = :id")
    suspend fun setShelfPosition(id: String, position: Int)

    // Deleting a shelf never drops its books: they fall back to "reading".
    @Transaction
    suspend fun deleteShelf(id: String) {
        moveBooksToShelf(fromId = id, toId = ReadingShelfId)
        deleteShelfRow(id)
    }

    // --- installs ------------------------------------------------------------

    @Query("SELECT * FROM plugin_installs ORDER BY installId")
    suspend fun installs(): List<PluginInstallEntity>

    @Query("SELECT * FROM plugin_installs WHERE installId = :installId")
    suspend fun install(installId: String): PluginInstallEntity?

    @Upsert
    suspend fun upsertInstall(install: PluginInstallEntity)

    @Query(
        "UPDATE plugin_installs SET baseUrlOverride = :override, " +
            "baseUrlEffective = :effective WHERE installId = :installId",
    )
    suspend fun setInstallBaseUrl(
        installId: String,
        override: String?,
        effective: String,
    )

    // --- books ---------------------------------------------------------------

    @Query("SELECT * FROM books WHERE bookId = :bookId")
    suspend fun book(bookId: Long): BookEntity?

    @Query("SELECT * FROM books WHERE bookId IN (:bookIds)")
    suspend fun booksByIds(bookIds: List<Long>): List<BookEntity>

    // Compatibility path for the URL-based navigation while screens migrate
    // to bookId. New code should use book(installId, url).
    @Query("SELECT * FROM books WHERE url = :url LIMIT 1")
    suspend fun bookByUrl(url: String): BookEntity?

    // All rows sharing one URL. The table is unique per (installId, url), so
    // more than one row means the URL alone cannot identify a book. Two rows
    // are enough to detect that ambiguity.
    @Query("SELECT bookId, url FROM books WHERE url = :url LIMIT 2")
    suspend fun booksByUrl(url: String): List<BookUrlRow>

    @Query("SELECT * FROM books WHERE installId = :installId AND url = :url LIMIT 1")
    suspend fun bookByInstallAndUrl(installId: String, url: String): BookEntity?

    @Query(
        "SELECT * FROM books WHERE inLibrary = 1 " +
            "ORDER BY lastReadAt IS NULL, lastReadAt DESC, addedAt DESC",
    )
    suspend fun libraryBooks(): List<BookEntity>

    @Query(
        "SELECT * FROM books WHERE inLibrary = 1 " +
            "ORDER BY lastReadAt IS NULL, lastReadAt DESC, addedAt DESC, bookId " +
            "LIMIT :limit OFFSET :offset",
    )
    suspend fun libraryBooksPage(limit: Int, offset: Int): List<BookEntity>

    // --- keyset library paging ----------------------------------------------
    // SQL is built in LibraryPaging; every caller value is bound. The old
    // libraryBooks/libraryBooksPage stay for search fallback and other callers.

    @RawQuery(observedEntities = [BookEntity::class])
    suspend fun libraryPageRows(query: RoomRawQuery): List<LibraryPageRow>

    @RawQuery(observedEntities = [BookEntity::class])
    suspend fun libraryPageCount(query: RoomRawQuery): Int

    @RawQuery(observedEntities = [BookEntity::class])
    suspend fun libraryPageUrls(query: RoomRawQuery): List<String>

    @RawQuery(observedEntities = [BookEntity::class])
    suspend fun libraryPageGenres(query: RoomRawQuery): List<GenreNameRow>

    @Query(
        "SELECT DISTINCT installId FROM books WHERE inLibrary = 1 " +
            "AND installId != '' ORDER BY installId",
    )
    suspend fun librarySources(): List<String>

    @Query(
        """
        SELECT b.shelfId AS shelfId, COUNT(*) AS bookCount,
            (SELECT b2.bookId FROM books b2
                WHERE b2.inLibrary = 1 AND b2.shelfId = b.shelfId
                ORDER BY b2.lastReadAt IS NULL, b2.lastReadAt DESC,
                    b2.addedAt DESC, b2.bookId
                LIMIT 1) AS coverBookId,
            (SELECT b2.coverUrl FROM books b2
                WHERE b2.inLibrary = 1 AND b2.shelfId = b.shelfId
                ORDER BY b2.lastReadAt IS NULL, b2.lastReadAt DESC,
                    b2.addedAt DESC, b2.bookId
                LIMIT 1) AS coverUrl,
            (SELECT b3.title FROM books b3
                WHERE b3.inLibrary = 1 AND b3.shelfId = b.shelfId
                ORDER BY b3.lastReadAt IS NULL, b3.lastReadAt DESC,
                    b3.addedAt DESC, b3.bookId
                LIMIT 1) AS coverTitle
        FROM books b
        WHERE b.inLibrary = 1
        GROUP BY b.shelfId
        ORDER BY b.shelfId
        """,
    )
    suspend fun shelfSummaries(): List<ShelfSummaryRow>

    @Query("SELECT * FROM books ORDER BY bookId")
    suspend fun allBooks(): List<BookEntity>

    // Dashboard next-up: finished means totalChapters > 0 and every remaining
    // chapter row is read. LIMIT keeps the candidate set small; the batch read
    // stats still provide the exact counters for the returned rows.
    @Query(
        """
        SELECT * FROM books b
        WHERE b.inLibrary = 1 AND NOT (
            b.totalChapters > 0 AND (
                SELECT COUNT(*) FROM chapters c
                WHERE c.bookId = b.bookId AND c.read = 1 AND c.removedAt IS NULL
            ) >= b.totalChapters
        )
        ORDER BY b.lastReadAt IS NULL, b.lastReadAt DESC, b.addedAt DESC, b.bookId
        LIMIT :limit
        """,
    )
    suspend fun unfinishedLibraryBooks(limit: Int): List<BookEntity>

    @Query("SELECT bookId, url FROM books WHERE url IN (:urls) LIMIT 1800")
    suspend fun booksByUrls(urls: List<String>): List<BookUrlRow>

    // One query for catalogue membership: only URLs that are actually in the
    // library. Keeps the list rendering free of per-card DB lookups.
    @Query("SELECT url FROM books WHERE inLibrary = 1 AND url IN (:urls)")
    suspend fun libraryUrls(urls: List<String>): List<String>

    // Title matches for catalogue cards: a book from another source shares
    // the title but not the URL. One grouped query, chunked by the repository.
    @Query(
        "SELECT title, COUNT(DISTINCT installId) AS count FROM books " +
            "WHERE inLibrary = 1 AND title IN (:titles) GROUP BY title",
    )
    suspend fun libraryTitleCounts(titles: List<String>): List<LibraryTitleCount>

    // Foreign-key cascades drop chapters/progress/sessions/downloads/local
    // files; the FTS table is standalone and has to go in the same transaction.
    @Query("DELETE FROM books WHERE bookId IN (:bookIds)")
    suspend fun deleteBooks(bookIds: List<Long>)

    @Query("DELETE FROM book_search WHERE rowid IN (:bookIds)")
    suspend fun deleteBookSearchRows(bookIds: List<Long>)

    // One query per bulk delete: every file path a book owns (local copy,
    // cover, chapter bodies, manga pages) before the cascade removes the rows.
    @Query(
        """
        SELECT b.bookId AS bookId,
            c.localPath AS coverPath,
            l.copyPath AS copyPath,
            l.parserVersion AS parserVersion,
            cb.path AS bodyPath,
            cp.pagePathsJson AS pagePathsJson
        FROM books b
        LEFT JOIN book_covers c ON c.bookId = b.bookId
        LEFT JOIN local_book_files l ON l.bookId = b.bookId
        LEFT JOIN chapters ch ON ch.bookId = b.bookId
        LEFT JOIN chapter_bodies cb ON cb.chapterId = ch.chapterId
        LEFT JOIN chapter_pages cp ON cp.chapterId = ch.chapterId
        WHERE b.bookId IN (:bookIds)
        """,
    )
    suspend fun bookFileRows(bookIds: List<Long>): List<BookFileRow>

    @Transaction
    suspend fun deleteBooksWithFiles(bookIds: List<Long>): List<BookFileRow> {
        val files = bookFileRows(bookIds)
        deleteBookSearchRows(bookIds)
        deleteBooks(bookIds)
        return files
    }

    @Query("UPDATE books SET shelfId = :shelfId WHERE bookId IN (:bookIds)")
    suspend fun setShelfForBooks(bookIds: List<Long>, shelfId: String): Int

    // One SQL statement over every matching chapter row; readChapters in the UI
    // is derived from these rows, so no per-book write is required.
    @Query(
        "UPDATE chapters SET read = :read, " +
            "readAt = CASE WHEN :read = 1 THEN :at ELSE NULL END, updatedAt = :at " +
            "WHERE removedAt IS NULL AND bookId IN " +
            "(SELECT bookId FROM books WHERE url IN (:urls))",
    )
    suspend fun setChaptersReadForBookUrls(urls: List<String>, read: Boolean, at: Long): Int

    @Query(
        "UPDATE chapters SET read = :read, " +
            "readAt = CASE WHEN :read = 1 THEN :at ELSE NULL END, updatedAt = :at " +
            "WHERE removedAt IS NULL AND bookId IN (:bookIds)",
    )
    suspend fun setChaptersReadForBookIds(bookIds: List<Long>, read: Boolean, at: Long): Int

    // Independent history: the history table owns the list and its order, so
    // deleting a history row never affects the library sort (books.lastReadAt)
    // or any other book state (FR-061/FR-065).
    @Query(
        "SELECT b.* FROM books b INNER JOIN history h ON h.bookId = b.bookId " +
            "ORDER BY h.readAt DESC LIMIT :limit",
    )
    suspend fun history(limit: Int): List<BookEntity>

    @Upsert
    suspend fun upsertHistory(row: HistoryEntity)

    @Query(
        "INSERT OR IGNORE INTO history(bookId, readAt, chapterIndex, chapterId) " +
            "VALUES(:bookId, :readAt, :chapterIndex, :chapterId)",
    )
    suspend fun insertHistoryIfAbsent(
        bookId: Long,
        readAt: Long,
        chapterIndex: Int?,
        chapterId: Long?,
    )

    @Query(
        "UPDATE history SET readAt = :readAt, chapterIndex = :chapterIndex, " +
            "chapterId = :chapterId WHERE bookId = :bookId AND readAt < :readAt",
    )
    suspend fun updateHistoryIfNewer(
        bookId: Long,
        readAt: Long,
        chapterIndex: Int?,
        chapterId: Long?,
    )

    @Transaction
    suspend fun upsertHistoryMerged(
        bookId: Long,
        readAt: Long,
        chapterIndex: Int?,
        chapterId: Long?,
    ) {
        insertHistoryIfAbsent(bookId, readAt, chapterIndex, chapterId)
        updateHistoryIfNewer(bookId, readAt, chapterIndex, chapterId)
    }

    @Query(
        "INSERT OR IGNORE INTO history(bookId, readAt, chapterIndex, chapterId) " +
            "SELECT bookId, lastReadAt, lastChapterIndex, lastChapterId FROM books " +
            "WHERE bookId IN (:bookIds) AND lastReadAt IS NOT NULL",
    )
    suspend fun backfillHistoryFromBooks(bookIds: List<Long>)

    @Query("DELETE FROM history WHERE bookId = :bookId")
    suspend fun deleteHistory(bookId: Long)

    @Query("DELETE FROM history WHERE bookId IN (:bookIds)")
    suspend fun deleteHistoryFor(bookIds: List<Long>)

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    // Legacy rows from before per-install identity carry no installId. They can
    // be adopted by the first write that resolves the same URL.
    @Query("SELECT * FROM books WHERE url = :url AND installId = '' LIMIT 1")
    suspend fun legacyBookByUrl(url: String): BookEntity?

    @Query(
        "SELECT * FROM books WHERE bookId > :afterId AND url LIKE :prefix || '%' " +
            "ORDER BY bookId LIMIT :limit",
    )
    suspend fun keyUrlBooks(afterId: Long, prefix: String, limit: Int): List<BookEntity>

    @Query(
        """
        SELECT CASE WHEN
            EXISTS(SELECT 1 FROM chapters WHERE bookId = :bookId) OR
            EXISTS(SELECT 1 FROM progress WHERE bookId = :bookId) OR
            EXISTS(SELECT 1 FROM history WHERE bookId = :bookId) OR
            EXISTS(SELECT 1 FROM reading_sessions WHERE bookId = :bookId) OR
            EXISTS(SELECT 1 FROM highlights WHERE bookId = :bookId) OR
            EXISTS(SELECT 1 FROM video_progress WHERE bookId = :bookId) OR
            EXISTS(SELECT 1 FROM download_tasks WHERE bookId = :bookId) OR
            EXISTS(SELECT 1 FROM book_covers WHERE bookId = :bookId) OR
            EXISTS(SELECT 1 FROM local_book_files WHERE bookId = :bookId) OR
            EXISTS(SELECT 1 FROM book_tags WHERE bookId = :bookId) OR
            EXISTS(SELECT 1 FROM book_translations WHERE bookId = :bookId)
        THEN 1 ELSE 0 END
        """,
    )
    suspend fun hasBookChildren(bookId: Long): Int

    @Query(
        """
        SELECT CASE WHEN
            EXISTS(SELECT 1 FROM chapters WHERE bookId = :bookId AND removedAt IS NULL) OR
            EXISTS(SELECT 1 FROM progress WHERE bookId = :bookId) OR
            EXISTS(SELECT 1 FROM history WHERE bookId = :bookId) OR
            EXISTS(SELECT 1 FROM reading_sessions WHERE bookId = :bookId) OR
            EXISTS(SELECT 1 FROM highlights WHERE bookId = :bookId) OR
            EXISTS(SELECT 1 FROM video_progress WHERE bookId = :bookId) OR
            EXISTS(SELECT 1 FROM download_tasks WHERE bookId = :bookId) OR
            EXISTS(SELECT 1 FROM book_covers WHERE bookId = :bookId) OR
            EXISTS(SELECT 1 FROM local_book_files WHERE bookId = :bookId) OR
            EXISTS(SELECT 1 FROM book_tags WHERE bookId = :bookId) OR
            EXISTS(SELECT 1 FROM book_translations WHERE bookId = :bookId)
        THEN 1 ELSE 0 END
        """,
    )
    suspend fun hasLiveBookChildren(bookId: Long): Int

    @Query("UPDATE books SET installId = :installId, url = :url WHERE bookId = :bookId")
    suspend fun updateBookIdentity(bookId: Long, installId: String, url: String)

    @Query("DELETE FROM book_search WHERE rowid NOT IN (SELECT bookId FROM books)")
    suspend fun deleteOrphanBookSearch()

    @Query("SELECT chapterId FROM chapters WHERE chapterId = :chapterId AND bookId = :bookId LIMIT 1")
    suspend fun chapterIdForBook(bookId: Long, chapterId: Long): Long?

    /**
     * One write identity for every book upsert: a positive id or the
     * (installId, url) pair, with blank-installId legacy rows adopted. A found
     * row is merged, never overwritten: membership, shelf, history and the
     * unseen badge stay local. `created` drives the direct-SAF rollback.
     */
    @Transaction
    suspend fun upsertBookByIdentity(book: BookEntity): UpsertResult {
        if (book.bookId > 0) {
            val byId = book(book.bookId)
            // A stale or foreign id must never merge into an unrelated row.
            // A blank-install legacy row is adopted only when no real-identity
            // row already holds the same URL (the unique index would reject it).
            val adoptable = byId != null &&
                byId.url == book.url &&
                (byId.installId.isNotBlank() || bookByInstallAndUrl(book.installId, book.url) == null)
            if (adoptable && byId != null) {
                upsertBook(mergeBookRow(byId, book, this))
                return UpsertResult(byId.bookId, false)
            }
        }
        val incoming = book.copy(bookId = 0)
        val existing = bookByInstallAndUrl(incoming.installId, incoming.url)
            ?: legacyBookByUrl(incoming.url)
        if (existing != null) {
            upsertBook(mergeBookRow(existing, incoming, this))
            return UpsertResult(existing.bookId, false)
        }
        val inserted = upsertBook(incoming)
        if (inserted > 0) return UpsertResult(inserted, true)
        val raced = bookByInstallAndUrl(incoming.installId, incoming.url)
            ?: legacyBookByUrl(incoming.url)
        if (raced != null) {
            upsertBook(mergeBookRow(raced, incoming, this))
            return UpsertResult(raced.bookId, false)
        }
        error("Cannot resolve book identity")
    }

    /**
     * One key-URL row repair in a single transaction. A row that owns a child is
     * never deleted: the childless side is merged into the other, and children
     * on both sides are left untouched.
     */
    @Transaction
    suspend fun repairBookKeyRow(
        keyRow: BookEntity,
        installId: String,
        url: String,
        orphanPaths: MutableList<String>,
    ): BookKeyRepairAction {
        val twin = bookByInstallAndUrl(installId, url) ?: legacyBookByUrl(url)
        if (twin == null) {
            updateBookIdentity(keyRow.bookId, installId, url)
            return BookKeyRepairAction.REWRITTEN
        }
        val keyHasChildren = hasBookChildren(keyRow.bookId) > 0
        val twinHasChildren = hasBookChildren(twin.bookId) > 0
        return when {
            !keyHasChildren -> {
                upsertBook(mergeRepairRow(twin, keyRow, this))
                if (twin.installId.isBlank()) updateBookIdentity(twin.bookId, installId, url)
                bookCover(keyRow.bookId)?.localPath?.takeIf { it.isNotBlank() }
                    ?.let(orphanPaths::add)
                localBookFile(keyRow.bookId)?.copyPath?.takeIf { it.isNotBlank() }
                    ?.let(orphanPaths::add)
                deleteBookWithSearch(keyRow.bookId)
                BookKeyRepairAction.DELETED
            }
            !twinHasChildren -> {
                upsertBook(mergeRepairRow(keyRow, twin, this))
                deleteBookWithSearch(twin.bookId)
                updateBookIdentity(keyRow.bookId, installId, url)
                BookKeyRepairAction.REWRITTEN
            }
            else -> {
                val keyLive = hasLiveBookChildren(keyRow.bookId) > 0
                val twinLive = hasLiveBookChildren(twin.bookId) > 0
                if (!keyLive && !twinLive) {
                    BookKeyRepairAction.AMBIGUOUS_SOFT
                } else {
                    BookKeyRepairAction.AMBIGUOUS
                }
            }
        }
    }

    @Upsert
    suspend fun upsertBook(book: BookEntity): Long

    @Query("DELETE FROM books WHERE bookId = :bookId")
    suspend fun deleteBook(bookId: Long)

    // The FTS table is standalone, so the row must disappear in the same
    // transaction as the book (cascades handle the other child tables).
    @Transaction
    suspend fun deleteBookWithSearch(bookId: Long) {
        deleteBookSearch(bookId)
        deleteBook(bookId)
    }

    // --- FTS search index ----------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookSearch(row: BookSearchEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookSearches(rows: List<BookSearchEntity>)

    @Query("DELETE FROM book_search WHERE rowid = :bookId")
    suspend fun deleteBookSearch(bookId: Long)

    @Query("DELETE FROM book_search")
    suspend fun clearBookSearch()

    @Query(
        "SELECT title FROM book_translations WHERE bookId = :bookId " +
            "AND title IS NOT NULL AND title != '' " +
            "AND (:source IS NULL OR source = :source) " +
            "AND (:target IS NULL OR target = :target) " +
            "ORDER BY updatedAt DESC, translationId DESC LIMIT 1",
    )
    suspend fun latestBookTranslationTitle(
        bookId: Long,
        source: String? = null,
        target: String? = null,
    ): String?

    // bm25 weights follow the FTS column order: title, author, tags,
    // description, translatedTitle. bookId is the stable tie-break.
    @Query(
        "SELECT b.* FROM books b " +
            "JOIN book_search ON book_search.rowid = b.bookId " +
            "WHERE book_search MATCH :match " +
            "AND (:libraryOnly = 0 OR b.inLibrary = 1) " +
            "ORDER BY bm25(book_search, 10.0, 5.0, 2.0, 1.0, 4.0), b.bookId " +
            "LIMIT :limit OFFSET :offset",
    )
    suspend fun searchBooks(
        match: String,
        libraryOnly: Boolean,
        limit: Int,
        offset: Int,
    ): List<BookEntity>

    @Query("UPDATE books SET shelfId = :shelfId WHERE bookId = :bookId")
    suspend fun setShelf(bookId: Long, shelfId: String)

    @Query("UPDATE books SET inLibrary = :inLibrary WHERE bookId = :bookId")
    suspend fun setInLibrary(bookId: Long, inLibrary: Boolean)

    @Query(
        "UPDATE books SET inLibrary = 1, " +
            "addedToLibraryAt = COALESCE(addedToLibraryAt, :at), " +
            "updatedAt = :at WHERE bookId = :bookId",
    )
    suspend fun addToLibrary(bookId: Long, at: Long)

    // Direct-SAF history entry: the book row and local file row commit in one
    // short transaction. Chapter reconciliation happens after this call, one
    // chunk transaction at a time, so a 1801-chapter file is not one giant
    // transaction. The repository rolls back a freshly created book when
    // reconcile is cancelled or fails.
    @Transaction
    suspend fun upsertDirectBook(
        book: BookEntity,
        file: LocalBookFileEntity,
    ): DirectBookWrite {
        val result = upsertBookByIdentity(book)
        upsertLocalBookFile(file.copy(bookId = result.id))
        return DirectBookWrite(bookId = result.id, created = result.created)
    }

    @Query("UPDATE books SET totalChapters = :total WHERE bookId = :bookId")
    suspend fun updateTotalChapters(bookId: Long, total: Int)

    // `newChapters` is the UNSEEN badge: it accumulates newly detected chapters
    // and is only cleared by markUpdatesSeen. A later no-change run must never
    // zero a badge the user has not looked at yet (FR-098/FR-099); the delta is
    // measured against the previously recorded total, so it cannot double-count.
    @Query(
        "UPDATE books SET totalChapters = :total, " +
            "newChapters = newChapters + :newChapters, " +
            "chapterListHash = COALESCE(:hash, chapterListHash), " +
            "lastUpdateCheckAt = :at, " +
            "lastUpdateAt = CASE WHEN :newChapters > 0 THEN :at ELSE lastUpdateAt END, " +
            "updatedAt = :at WHERE bookId = :bookId",
    )
    suspend fun recordUpdateCheck(bookId: Long, total: Int, newChapters: Int, hash: String?, at: Long)

    @Query("UPDATE books SET newChapters = 0, updatedAt = :at WHERE bookId = :bookId")
    suspend fun markUpdatesSeen(bookId: Long, at: Long)

    @Query(
        "SELECT * FROM books WHERE inLibrary = 1 AND newChapters > 0 " +
            "ORDER BY lastUpdateAt DESC, addedAt DESC",
    )
    suspend fun booksWithUpdates(): List<BookEntity>

    @Query("SELECT inLibrary FROM books WHERE bookId = :bookId")
    suspend fun inLibrary(bookId: Long): Boolean?

    @Query(
        "UPDATE books SET lastReadAt = :at, lastChapterId = :chapterId, " +
            "lastChapterIndex = :chapterIndex, updatedAt = :at WHERE bookId = :bookId",
    )
    suspend fun touchHistory(
        bookId: Long,
        chapterId: Long?,
        chapterIndex: Int?,
        at: Long,
    )

    /**
     * Moves "last read" for the library sort and records the independent
     * history row in one transaction, so the two never disagree (FR-061).
     */
    @Transaction
    suspend fun touchHistoryAndRecord(
        bookId: Long,
        chapterId: Long?,
        chapterIndex: Int?,
        at: Long,
    ) {
        touchHistory(bookId, chapterId, chapterIndex, at)
        upsertHistory(
            HistoryEntity(
                bookId = bookId,
                readAt = at,
                chapterIndex = chapterIndex,
                chapterId = chapterId,
            ),
        )
    }

    @Query(
        "UPDATE books SET url = :newBase || SUBSTR(url, LENGTH(:oldBase) + 1), " +
            "updatedAt = :at WHERE installId = :installId AND url LIKE :oldBase || '%'",
    )
    suspend fun rebaseBookUrls(
        installId: String,
        oldBase: String,
        newBase: String,
        at: Long,
    ): Int

    // --- chapters ------------------------------------------------------------

    @Query(
        "SELECT * FROM chapters WHERE bookId = :bookId AND removedAt IS NULL " +
            "ORDER BY idx",
    )
    suspend fun chapters(bookId: Long): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY idx")
    suspend fun allChapters(bookId: Long): List<ChapterEntity>

    @Query(
        "SELECT * FROM chapters WHERE bookId = :bookId AND url = :url " +
            "ORDER BY removedAt IS NULL DESC LIMIT 1",
    )
    suspend fun chapterByUrl(bookId: Long, url: String): ChapterEntity?

    // Migration chunk read: one query per <=200 URLs instead of one per row.
    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND url IN (:urls)")
    suspend fun chaptersByUrls(bookId: Long, urls: List<String>): List<ChapterEntity>

    @Query(
        "SELECT * FROM chapters WHERE bookId = :bookId AND idx = :index " +
            "AND removedAt IS NULL LIMIT 1",
    )
    suspend fun chapterByIndex(bookId: Long, index: Int): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE chapterId = :chapterId")
    suspend fun chapter(chapterId: Long): ChapterEntity?

    // One row per chapter; body/pages existence is folded in so the status
    // batch needs no per-chapter follow-up.
    @Query(
        """
        SELECT c.chapterId AS chapterId, c.idx AS idx, c.url AS url,
            c.read AS read, c.downloaded AS downloaded,
            EXISTS(SELECT 1 FROM chapter_bodies cb WHERE cb.chapterId = c.chapterId) AS hasBody,
            EXISTS(SELECT 1 FROM chapter_pages cp WHERE cp.chapterId = c.chapterId) AS hasPages,
            COALESCE((SELECT cb.bytes FROM chapter_bodies cb WHERE cb.chapterId = c.chapterId), 0)
                + COALESCE((SELECT cp.totalBytes FROM chapter_pages cp WHERE cp.chapterId = c.chapterId), 0)
                AS sizeBytes
        FROM chapters c
        WHERE c.bookId = :bookId AND c.removedAt IS NULL
        ORDER BY c.idx
        """,
    )
    suspend fun chapterStatusRows(bookId: Long): List<ChapterStatusRow>

    @Query(
        """
        SELECT c.chapterId AS chapterId, c.idx AS idx, c.url AS url
        FROM chapter_translations t
        INNER JOIN chapters c ON c.chapterId = t.chapterId
        WHERE c.bookId = :bookId AND c.removedAt IS NULL
            AND UPPER(t.status) = 'COMPLETE'
        GROUP BY c.chapterId, c.idx, c.url
        """,
    )
    suspend fun translatedChapterRows(bookId: Long): List<TranslatedChapterRow>

    // Newest first; callers collapse to one current state per chapter. Not a
    // schema change: chapter_translations already exists.
    @Query(
        """
        SELECT t.translationId AS translationId, t.chapterId AS chapterId,
            c.idx AS idx, c.url AS url, c.bookId AS bookId,
            t.source AS source, t.target AS target, t.provider AS provider,
            t.promptHash AS promptHash, t.sourceHash AS sourceHash,
            t.pipelineVersion AS pipelineVersion, t.status AS status,
            t.updatedAt AS updatedAt
        FROM chapter_translations t
        INNER JOIN chapters c ON c.chapterId = t.chapterId
        WHERE c.bookId = :bookId AND c.removedAt IS NULL
        ORDER BY t.updatedAt DESC, t.translationId DESC
        """,
    )
    suspend fun chapterTranslationQueueRows(bookId: Long): List<ChapterTranslationQueueRow>

    @Query(
        """
        SELECT t.translationId AS translationId, t.chapterId AS chapterId,
            c.idx AS idx, c.url AS url, c.bookId AS bookId,
            t.source AS source, t.target AS target, t.provider AS provider,
            t.promptHash AS promptHash, t.sourceHash AS sourceHash,
            t.pipelineVersion AS pipelineVersion, t.status AS status,
            t.updatedAt AS updatedAt
        FROM chapter_translations t
        INNER JOIN chapters c ON c.chapterId = t.chapterId
        WHERE c.removedAt IS NULL
        ORDER BY t.updatedAt DESC, t.translationId DESC
        """,
    )
    fun chapterTranslationQueueRowsFlow(): Flow<List<ChapterTranslationQueueRow>>

    // Same "newest row per chapter" semantics as chapterTranslationQueueRowsFlow,
    // but only chapters whose current state is PENDING/FAILED are returned. The
    // per-chapter lookup keeps an older FAILED row from leaking through when a
    // newer COMPLETE row is the current state.
    @Query(
        """
        SELECT t.translationId AS translationId, t.chapterId AS chapterId,
            c.idx AS idx, c.url AS url, c.bookId AS bookId,
            t.source AS source, t.target AS target, t.provider AS provider,
            t.promptHash AS promptHash, t.sourceHash AS sourceHash,
            t.pipelineVersion AS pipelineVersion, t.status AS status,
            t.updatedAt AS updatedAt
        FROM chapter_translations t
        INNER JOIN chapters c ON c.chapterId = t.chapterId
        WHERE c.removedAt IS NULL
            AND UPPER(t.status) IN ('PENDING', 'FAILED')
            AND t.translationId = (
                SELECT latest.translationId FROM chapter_translations latest
                WHERE latest.chapterId = t.chapterId
                ORDER BY latest.updatedAt DESC, latest.translationId DESC
                LIMIT 1
            )
        ORDER BY t.updatedAt DESC, t.translationId DESC
        """,
    )
    fun chapterTranslationQueuePendingRowsFlow(): Flow<List<ChapterTranslationQueueRow>>

    @Query("DELETE FROM chapter_translations WHERE translationId = :translationId")
    suspend fun deleteChapterTranslation(translationId: Long)

    @Query("SELECT * FROM chapter_translations WHERE chapterId IN (:chapterIds)")
    suspend fun chapterTranslationsFor(chapterIds: List<Long>): List<ChapterTranslationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapterTranslations(
        translations: List<ChapterTranslationEntity>,
    ): List<Long>

    @Query("DELETE FROM chapter_translations WHERE translationId IN (:translationIds)")
    suspend fun deleteChapterTranslationsByIds(translationIds: List<Long>)

    @Query(
        """
        SELECT DISTINCT c.bookId FROM chapter_translations t
        INNER JOIN chapters c ON c.chapterId = t.chapterId
        WHERE c.removedAt IS NULL AND UPPER(t.status) = 'PENDING'
        """,
    )
    suspend fun bookIdsWithPendingTranslations(): List<Long>

    @Query(
        "SELECT * FROM chapter_translations WHERE chapterId = :chapterId " +
            "AND source = :source AND target = :target AND provider = :provider " +
            "AND promptHash = :promptHash AND pipelineVersion = :pipelineVersion " +
            "ORDER BY updatedAt DESC LIMIT 1",
    )
    suspend fun chapterTranslationAnySource(
        chapterId: Long,
        source: String,
        target: String,
        provider: String,
        promptHash: String,
        pipelineVersion: String,
    ): ChapterTranslationEntity?

    @Query(
        "SELECT * FROM chapter_translations WHERE chapterId = :chapterId " +
            "AND source = :source AND target = :target AND provider = :provider " +
            "AND UPPER(status) = 'COMPLETE' " +
            "ORDER BY updatedAt DESC, translationId DESC LIMIT 1",
    )
    suspend fun latestCompleteChapterTranslation(
        chapterId: Long,
        source: String,
        target: String,
        provider: String,
    ): ChapterTranslationEntity?

    // Progress wins over the book row's denormalized lastChapterId; both are
    // optional, so an untouched book returns no row.
    @Query(
        """
        SELECT c.url AS url, c.idx AS idx
        FROM chapters c
        INNER JOIN books b ON b.bookId = c.bookId
        LEFT JOIN progress p ON p.bookId = b.bookId
        WHERE b.bookId = :bookId AND c.removedAt IS NULL
            AND c.chapterId = COALESCE(p.chapterId, b.lastChapterId)
        LIMIT 1
        """,
    )
    suspend fun lastReadChapterRow(bookId: Long): LastReadChapterRow?

    @Upsert
    suspend fun upsertChapter(chapter: ChapterEntity): Long

    @Upsert
    suspend fun upsertChapters(chapters: List<ChapterEntity>)

    @Query("UPDATE chapters SET removedAt = :at WHERE chapterId = :chapterId")
    suspend fun markChapterRemoved(chapterId: Long, at: Long)

    @Query("UPDATE chapters SET removedAt = :at WHERE chapterId IN (:chapterIds)")
    suspend fun markChaptersRemoved(chapterIds: List<Long>, at: Long)

    @Query(
        "UPDATE chapters SET read = :read, readAt = :at, updatedAt = :at " +
            "WHERE bookId = :bookId AND idx = :index AND removedAt IS NULL",
    )
    suspend fun setChapterRead(bookId: Long, index: Int, read: Boolean, at: Long)

    @Query(
        "UPDATE chapters SET read = :read, " +
            "readAt = CASE WHEN :read = 1 THEN :at ELSE NULL END, updatedAt = :at " +
            "WHERE bookId = :bookId AND idx IN (:indexes) AND removedAt IS NULL",
    )
    suspend fun setChaptersRead(bookId: Long, indexes: List<Int>, read: Boolean, at: Long)

    @Query(
        "UPDATE chapters SET read = :read, " +
            "readAt = CASE WHEN :read = 1 THEN :at ELSE NULL END, updatedAt = :at " +
            "WHERE chapterId = :chapterId AND removedAt IS NULL",
    )
    suspend fun setChapterReadById(chapterId: Long, read: Boolean, at: Long)

    @Query(
        "SELECT idx FROM chapters WHERE bookId = :bookId AND read = 1 " +
            "AND removedAt IS NULL ORDER BY idx",
    )
    suspend fun readChapterIndexes(bookId: Long): List<Int>

    // One grouped query for every list screen; no per-book chapter query.
    @Query(
        "SELECT bookId, COUNT(*) AS readCount, " +
            "COALESCE(MAX(idx), 0) AS maxReadIndex " +
            "FROM chapters WHERE read = 1 AND removedAt IS NULL GROUP BY bookId",
    )
    suspend fun readChapterStats(): List<BookReadStatsRow>

    // Same aggregation restricted to the requested books; callers chunk ids
    // below SQLite's bind limit. Missing books simply have no row.
    @Query(
        "SELECT bookId, COUNT(*) AS readCount, " +
            "COALESCE(MAX(idx), 0) AS maxReadIndex " +
            "FROM chapters WHERE read = 1 AND removedAt IS NULL " +
            "AND bookId IN (:bookIds) GROUP BY bookId",
    )
    suspend fun readChapterStatsFor(bookIds: List<Long>): List<BookReadStatsRow>

    @Query("DELETE FROM chapters WHERE chapterId = :chapterId")
    suspend fun deleteChapterRow(chapterId: Long)

    @Query("DELETE FROM chapters WHERE chapterId IN (:chapterIds)")
    suspend fun deleteChapterRows(chapterIds: List<Long>)

    @Query("DELETE FROM progress WHERE bookId = :bookId AND chapterId IN (:chapterIds)")
    suspend fun deleteProgressForChapters(bookId: Long, chapterIds: List<Long>)

    @Query(
        "UPDATE books SET lastChapterId = NULL, lastChapterIndex = NULL " +
            "WHERE bookId = :bookId AND lastChapterId IN (:chapterIds)",
    )
    suspend fun clearLastChapterForChapters(bookId: Long, chapterIds: List<Long>)

    @Query(
        "UPDATE chapters SET read = :read, readAt = :at, updatedAt = :at " +
            "WHERE bookId = :bookId AND idx BETWEEN :from AND :to " +
            "AND removedAt IS NULL",
    )
    suspend fun setChapterReadRange(
        bookId: Long,
        from: Int,
        to: Int,
        read: Boolean,
        at: Long,
    )

    @Transaction
    suspend fun deleteChapterChunk(bookId: Long, chapterIds: List<Long>) {
        deleteProgressForChapters(bookId, chapterIds)
        clearLastChapterForChapters(bookId, chapterIds)
        deleteChapterRows(chapterIds)
    }

    suspend fun deleteChaptersByIndex(
        bookId: Long,
        from: Int,
        to: Int,
        at: Long,
    ) {
        val targets = chapters(bookId).filter { it.idx in from..to }
        if (targets.isEmpty()) return
        for (chunk in targets.map { it.chapterId }.chunked(ChapterChunkSize)) {
            currentCoroutineContext().ensureActive()
            deleteChapterChunk(bookId, chunk)
        }
        updateTotalChapters(bookId, chapters(bookId).size)
    }

    @Transaction
    suspend fun upsertChapterRows(rows: List<ChapterEntity>) {
        if (rows.isNotEmpty()) upsertChapters(rows)
    }

    // Reconcile writes one short transaction per chunk via the helper below.
    suspend fun reconcileChapters(
        bookId: Long,
        seeds: List<ChapterSeed>,
        at: Long,
    ): List<ChapterEntity> = reconcileChapterRows(
        bookId = bookId,
        existing = allChapters(bookId).associateBy { it.url },
        seeds = seeds,
        at = at,
        upsertChunk = { rows -> upsertChapterRows(rows) },
        markRemovedChunk = { ids, removedAt -> markChaptersRemoved(ids, removedAt) },
        updateTotal = { total -> updateTotalChapters(bookId, total) },
        readChapters = { chapters(bookId) },
    )

    @Query(
        "UPDATE chapters SET url = :newBase || SUBSTR(url, LENGTH(:oldBase) + 1), " +
            "updatedAt = :at WHERE bookId IN " +
            "(SELECT bookId FROM books WHERE installId = :installId) " +
            "AND url LIKE :oldBase || '%'",
    )
    suspend fun rebaseChapterUrls(
        installId: String,
        oldBase: String,
        newBase: String,
        at: Long,
    ): Int

    @Transaction
    suspend fun applyRebase(
        installId: String,
        oldBase: String,
        newBase: String,
        override: String?,
        effective: String,
        at: Long,
    ): Pair<Int, Int> {
        val books = rebaseBookUrls(installId, oldBase, newBase, at)
        val chapters = rebaseChapterUrls(installId, oldBase, newBase, at)
        setInstallBaseUrl(installId, override, effective)
        return books to chapters
    }

    // --- progress ------------------------------------------------------------

    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    suspend fun progress(bookId: Long): ProgressEntity?

    @Upsert
    suspend fun upsertProgress(progress: ProgressEntity)

    @Transaction
    suspend fun saveProgressIfNewer(progress: ProgressEntity) {
        val current = progress(progress.bookId)
        if (current == null || progress.seq >= current.seq) {
            upsertProgress(progress)
        }
    }

    @Query("DELETE FROM progress WHERE bookId = :bookId")
    suspend fun deleteProgress(bookId: Long)

    // --- video progress ------------------------------------------------------

    @Query(
        "SELECT * FROM video_progress WHERE bookId = :bookId AND chapterId = :chapterId",
    )
    suspend fun videoProgress(bookId: Long, chapterId: Long): VideoProgressEntity?

    @Upsert
    suspend fun upsertVideoProgress(progress: VideoProgressEntity)

    @Query("DELETE FROM video_progress WHERE bookId = :bookId AND chapterId = :chapterId")
    suspend fun deleteVideoProgress(bookId: Long, chapterId: Long)

    // --- reading sessions ----------------------------------------------------

    @Upsert
    suspend fun upsertSession(session: ReadingSessionEntity)

    @Query(
        "SELECT * FROM reading_sessions WHERE bookId = :bookId " +
            "ORDER BY startedAt DESC LIMIT :limit",
    )
    suspend fun sessions(bookId: Long, limit: Int): List<ReadingSessionEntity>

    @Query(
        "SELECT COALESCE(SUM(durationMs), 0) FROM reading_sessions " +
            "WHERE startedAt >= :from AND startedAt < :to AND kind = :kind",
    )
    suspend fun durationBetween(from: Long, to: Long, kind: String): Long

    @Query(
        "SELECT COALESCE(SUM(durationMs), 0) FROM reading_sessions " +
            "WHERE bookId = :bookId AND kind = :kind",
    )
    suspend fun durationForBook(bookId: Long, kind: String): Long

    // --- chapter bodies ------------------------------------------------------

    @Query("SELECT * FROM chapter_bodies WHERE chapterId = :chapterId")
    suspend fun chapterBody(chapterId: Long): ChapterBodyEntity?

    @Query("SELECT * FROM chapter_bodies WHERE chapterId IN (:chapterIds)")
    suspend fun chapterBodiesFor(chapterIds: List<Long>): List<ChapterBodyEntity>

    @Upsert
    suspend fun upsertChapterBody(body: ChapterBodyEntity)

    @Upsert
    suspend fun upsertChapterBodies(bodies: List<ChapterBodyEntity>)

    @Query("DELETE FROM chapter_bodies WHERE chapterId IN (:chapterIds)")
    suspend fun deleteChapterBodies(chapterIds: List<Long>)

    @Query("DELETE FROM chapter_bodies WHERE chapterId = :chapterId")
    suspend fun deleteChapterBody(chapterId: Long)

    @Query("UPDATE chapters SET downloaded = :downloaded WHERE chapterId = :chapterId")
    suspend fun setChapterDownloaded(chapterId: Long, downloaded: Boolean)

    @Query(
        "UPDATE chapters SET downloaded = 1, updatedAt = :at " +
            "WHERE chapterId IN (:chapterIds)",
    )
    suspend fun markChaptersDownloaded(chapterIds: List<Long>, at: Long)

    @Transaction
    suspend fun saveMigrationPayloadChunk(
        bodies: List<ChapterBodyEntity>,
        pages: List<ChapterPageEntity>,
        downloadedChapterIds: List<Long>,
        at: Long,
    ) {
        if (bodies.isNotEmpty()) upsertChapterBodies(bodies)
        if (pages.isNotEmpty()) upsertChapterPages(pages)
        if (downloadedChapterIds.isNotEmpty()) markChaptersDownloaded(downloadedChapterIds, at)
    }

    // --- download tasks ------------------------------------------------------

    @Upsert
    suspend fun upsertDownloadTask(task: DownloadTaskEntity): Long

    @Upsert
    suspend fun upsertDownloadTasks(tasks: List<DownloadTaskEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDownloadTasks(tasks: List<DownloadTaskEntity>): List<Long>

    @Query("DELETE FROM download_tasks WHERE id IN (:ids)")
    suspend fun deleteDownloadTasksByIds(ids: List<Long>)

    @Query("SELECT * FROM download_tasks ORDER BY createdAt")
    suspend fun downloadTasks(): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE state = :state ORDER BY createdAt")
    suspend fun downloadTasks(state: String): List<DownloadTaskEntity>

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteDownloadTask(id: Long)

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    suspend fun downloadTask(id: Long): DownloadTaskEntity?

    @Query(
        "SELECT * FROM download_tasks WHERE chapterId = :chapterId " +
            "ORDER BY updatedAt DESC, id DESC LIMIT 1",
    )
    suspend fun downloadTaskForChapter(chapterId: Long): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE bookId = :bookId ORDER BY createdAt, id")
    suspend fun downloadTasksForBook(bookId: Long): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE bookId = :bookId ORDER BY createdAt, id")
    fun downloadTasksForBookFlow(bookId: Long): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks ORDER BY createdAt, id")
    fun downloadTasksFlow(): Flow<List<DownloadTaskEntity>>

    @Query("DELETE FROM download_tasks WHERE bookId = :bookId")
    suspend fun deleteDownloadTasksForBook(bookId: Long)

    @Query("UPDATE download_tasks SET state = :state, error = :error, updatedAt = :at WHERE id = :id")
    suspend fun updateDownloadTaskState(id: Long, state: String, error: String?, at: Long)

    @Query(
        "SELECT DISTINCT bookId FROM download_tasks " +
            "WHERE state IN ('QUEUED', 'RUNNING', 'WAITING_NETWORK')",
    )
    suspend fun booksWithActiveDownloads(): List<Long>

    // --- v4 metadata / covers / tags / local / translation / highlights ---

    @Upsert
    suspend fun upsertBookCover(cover: BookCoverEntity)

    @Query("SELECT * FROM book_covers WHERE bookId = :bookId")
    suspend fun bookCover(bookId: Long): BookCoverEntity?

    @Query("SELECT * FROM book_covers")
    suspend fun bookCovers(): List<BookCoverEntity>

    // Only the requested covers; callers chunk ids below SQLite's bind limit.
    @Query("SELECT * FROM book_covers WHERE bookId IN (:bookIds)")
    suspend fun bookCoversFor(bookIds: List<Long>): List<BookCoverEntity>

    @Query("SELECT * FROM book_covers WHERE retryAt IS NOT NULL AND retryAt <= :at")
    suspend fun coversDue(at: Long): List<BookCoverEntity>

    @Upsert
    suspend fun upsertSourceIcon(icon: SourceIconEntity)

    @Query(
        "SELECT * FROM source_icons WHERE installId = :installId " +
            "AND version = :version AND urlHash = :urlHash",
    )
    suspend fun sourceIcon(installId: String, version: String, urlHash: String): SourceIconEntity?

    @Query("DELETE FROM source_icons WHERE installId = :installId")
    suspend fun deleteSourceIcons(installId: String)

    @Upsert
    suspend fun upsertTag(tag: TagEntity): Long

    @Query("SELECT * FROM tags WHERE normalizedName = :normalizedName")
    suspend fun tagByNormalized(normalizedName: String): TagEntity?

    @Upsert
    suspend fun upsertBookTag(bookTag: BookTagEntity)

    @Upsert
    suspend fun upsertBookTags(bookTags: List<BookTagEntity>)

    @Query(
        "SELECT t.* FROM tags t INNER JOIN book_tags bt ON bt.tagId = t.tagId " +
            "WHERE bt.bookId = :bookId ORDER BY bt.position, t.name",
    )
    suspend fun tagsForBook(bookId: Long): List<TagEntity>

    @Query(
        "SELECT bt.bookId AS bookId, t.name AS name " +
            "FROM book_tags bt INNER JOIN tags t ON t.tagId = bt.tagId " +
            "ORDER BY bt.bookId, bt.position, t.name",
    )
    suspend fun tagsByBook(): List<BookTagNameRow>

    // Same JOIN restricted to the requested books; callers chunk ids below
    // SQLite's bind limit. Missing books simply have no row.
    @Query(
        "SELECT bt.bookId AS bookId, t.name AS name " +
            "FROM book_tags bt INNER JOIN tags t ON t.tagId = bt.tagId " +
            "WHERE bt.bookId IN (:bookIds) " +
            "ORDER BY bt.bookId, bt.position, t.name",
    )
    suspend fun tagsByBookFor(bookIds: List<Long>): List<BookTagNameRow>

    @Query("SELECT bookId FROM book_tags WHERE tagId = :tagId")
    suspend fun bookIdsForTag(tagId: Long): List<Long>

    @Query("DELETE FROM book_tags WHERE bookId = :bookId")
    suspend fun deleteBookTags(bookId: Long)

    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY chapterId, blockIndex, startOffset")
    suspend fun highlights(bookId: Long): List<HighlightEntity>

    @Upsert
    suspend fun upsertHighlight(highlight: HighlightEntity): Long

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteHighlight(id: Long)

    @Upsert
    suspend fun upsertChapterPage(page: ChapterPageEntity)

    @Query("SELECT * FROM chapter_pages WHERE chapterId = :chapterId")
    suspend fun chapterPage(chapterId: Long): ChapterPageEntity?

    @Query("SELECT * FROM chapter_pages WHERE chapterId IN (:chapterIds)")
    suspend fun chapterPagesFor(chapterIds: List<Long>): List<ChapterPageEntity>

    @Upsert
    suspend fun upsertChapterPages(pages: List<ChapterPageEntity>)

    @Query("DELETE FROM chapter_pages WHERE chapterId = :chapterId")
    suspend fun deleteChapterPage(chapterId: Long)

    @Query("DELETE FROM chapter_pages WHERE chapterId IN (:chapterIds)")
    suspend fun deleteChapterPages(chapterIds: List<Long>)

    @Upsert
    suspend fun upsertLocalBookFile(file: LocalBookFileEntity)

    @Query("SELECT * FROM local_book_files WHERE bookId = :bookId")
    suspend fun localBookFile(bookId: Long): LocalBookFileEntity?

    @Query("DELETE FROM local_book_files WHERE bookId = :bookId")
    suspend fun deleteLocalBookFile(bookId: Long)

    // Live local catalogue: match a scanned SAF document URI to the book that
    // was already imported from it. URI is the stable scan identity.
    @Query("SELECT * FROM local_book_files WHERE uri = :uri LIMIT 1")
    suspend fun localBookFileByUri(uri: String): LocalBookFileEntity?

    // Duplicate import detection: the copy path is content-addressed by sha.
    @Query("SELECT * FROM local_book_files WHERE sha256 = :sha256 ORDER BY importedAt LIMIT 1")
    suspend fun localBookFileBySha(sha256: String): LocalBookFileEntity?

    @Query("SELECT COUNT(*) FROM books WHERE installId = :installId AND inLibrary = 1")
    suspend fun libraryBookCount(installId: String): Int

    @Query("SELECT COUNT(*) FROM books WHERE inLibrary = 1")
    suspend fun libraryBookCount(): Int

    @Query("SELECT COUNT(*) FROM books")
    suspend fun bookCount(): Int

    @Query("SELECT COUNT(*) FROM books WHERE inLibrary = 0")
    suspend fun outOfLibraryBookCount(): Int

    @Query("SELECT bookId, url FROM books WHERE inLibrary = 0 ORDER BY bookId")
    suspend fun outOfLibraryBooks(): List<BookUrlRow>

    @Query("SELECT bookId FROM books")
    suspend fun bookIds(): List<Long>

    @Query("SELECT bookId FROM book_covers")
    suspend fun bookCoverIds(): List<Long>

    @Query("SELECT copyPath FROM local_book_files WHERE copyPath IS NOT NULL AND copyPath <> ''")
    suspend fun localBookCopyPaths(): List<String>

    @Query("SELECT url FROM books WHERE inLibrary = 1")
    suspend fun libraryBookUrls(): List<String>

    @Query(
        "SELECT bookId, installId, url FROM books " +
            "WHERE inLibrary = 1 AND url IN (:urls) ORDER BY bookId",
    )
    suspend fun bookKeysByUrls(urls: List<String>): List<BookKeyRow>

    // Delete path: a sourced URL can also be a history-only row, so it must
    // resolve through the same unfiltered lookup as the unsourced path.
    @Query(
        "SELECT bookId, installId, url FROM books " +
            "WHERE url IN (:urls) ORDER BY bookId",
    )
    suspend fun bookKeysByUrlsAll(urls: List<String>): List<BookKeyRow>

    // --- local backup selection ---------------------------------------------

    // Every in-library book the archive carries files for. Plugin novel bodies
    // are downloadable chapter text a user expects offline, so they are packed;
    // manga pages and video files are not. History-only copies stay out, and
    // local-manga books are pruned from the snapshot, so neither is selected.
    // The LEFT JOIN keeps plugin books (no local file row); a local book without
    // that row has no copy to pack and would fail the backup instead.
    @Query(
        """
        SELECT b.bookId AS bookId, b.installId AS installId, b.title AS title,
            b.inLibrary AS inLibrary, l.copyPath AS copyPath,
            COALESCE(l.format, '') AS format,
            c.localPath AS coverLocalPath
        FROM books b
        LEFT JOIN local_book_files l ON l.bookId = b.bookId
        LEFT JOIN book_covers c ON c.bookId = b.bookId
        WHERE b.inLibrary = 1
            AND b.installId <> 'local-manga'
            AND (l.bookId IS NOT NULL OR b.installId <> 'local-books')
        ORDER BY b.bookId
        """,
    )
    suspend fun localBackupBooks(): List<LocalBackupBookRow>

    @Query(
        """
        SELECT c.bookId AS bookId, cb.path AS path
        FROM chapter_bodies cb
        INNER JOIN chapters c ON c.chapterId = cb.chapterId
        INNER JOIN books b ON b.bookId = c.bookId
        WHERE b.inLibrary = 1
            AND b.installId <> 'local-manga'
        """,
    )
    suspend fun localBackupBodyPaths(): List<LocalBackupPathRow>

    // Covers of every in-library book (local and plugin). A user override is
    // always backed up; non-override covers are cache and follow includeImages.
    @Query(
        """
        SELECT b.bookId AS bookId, b.installId AS installId, b.title AS title,
            c.localPath AS coverLocalPath, c.isUserOverride AS isUserOverride
        FROM books b
        INNER JOIN book_covers c ON c.bookId = b.bookId
        WHERE b.inLibrary = 1
            AND c.localPath IS NOT NULL
            AND c.localPath <> ''
        ORDER BY b.bookId
        """,
    )
    suspend fun localBackupCovers(): List<LocalBackupCoverRow>

    // Manga content is not backed up. After restore, rows whose files are gone
    // must stop claiming the chapter is downloaded; the app supplies the
    // filesystem predicate because this module has no File API.
    @Query(
        """
        SELECT cp.chapterId AS chapterId, cp.pagePathsJson AS pagePathsJson
        FROM chapter_pages cp
        INNER JOIN chapters c ON c.chapterId = cp.chapterId
        INNER JOIN books b ON b.bookId = c.bookId
        """,
    )
    suspend fun restorePageRows(): List<RestorePageRow>

    // Bodies are content data: a restored row must not claim a download when
    // the file is absent. This covers plugin books whose bodies older archives
    // did not carry, plus same-device rows whose cache was evicted.
    @Query(
        """
        SELECT cb.chapterId AS chapterId, cb.path AS path
        FROM chapter_bodies cb
        INNER JOIN chapters c ON c.chapterId = cb.chapterId
        INNER JOIN books b ON b.bookId = c.bookId
        """,
    )
    suspend fun restoreBodyRows(): List<RestoreBodyRow>

    @Query(
        """
        SELECT l.bookId AS bookId, l.copyPath AS copyPath
        FROM local_book_files l
        INNER JOIN books b ON b.bookId = l.bookId
        WHERE b.installId = 'local-manga'
        """,
    )
    suspend fun localMangaFileRows(): List<LocalMangaFileRow>

    // Legacy rows without copyPath still own page files. Normal imports derive
    // their valid folder name from copyPath, so parsing every page JSON on each
    // Data-screen refresh is unnecessary.
    @Query(
        """
        SELECT c.bookId AS bookId, cp.pagePathsJson AS pathsJson
        FROM chapter_pages cp
        INNER JOIN chapters c ON c.chapterId = cp.chapterId
        INNER JOIN books b ON b.bookId = c.bookId
        WHERE b.inLibrary = 1
            AND b.installId IN ('local-books', 'local-manga')
            AND NOT EXISTS (
                SELECT 1 FROM local_book_files lf
                WHERE lf.bookId = b.bookId
                    AND lf.copyPath IS NOT NULL
                    AND lf.copyPath <> ''
            )
        """,
    )
    suspend fun localPagePathsWithoutCopy(): List<LocalBackupPageRow>

    @Upsert
    suspend fun upsertImportJob(job: ImportJobEntity): Long

    @Query("SELECT * FROM import_jobs ORDER BY createdAt")
    suspend fun importJobs(): List<ImportJobEntity>

    @Upsert
    suspend fun upsertChapterTranslation(translation: ChapterTranslationEntity): Long

    @Query(
        "SELECT * FROM chapter_translations WHERE chapterId = :chapterId " +
            "AND source = :source AND target = :target AND provider = :provider " +
            "AND promptHash = :promptHash AND sourceHash = :sourceHash " +
            "AND pipelineVersion = :pipelineVersion " +
            "ORDER BY updatedAt DESC LIMIT 1",
    )
    suspend fun chapterTranslation(
        chapterId: Long,
        source: String,
        target: String,
        provider: String,
        promptHash: String,
        sourceHash: String,
        pipelineVersion: String,
    ): ChapterTranslationEntity?

    @Query(
        """
        DELETE FROM chapter_translations
        WHERE chapterId IN (
            SELECT c.chapterId FROM chapters c
            INNER JOIN books b ON b.bookId = c.bookId
            WHERE b.url = :bookUrl AND c.idx IN (:indexes) AND c.removedAt IS NULL
        )
        """,
    )
    suspend fun deleteChapterTranslations(bookUrl: String, indexes: List<Int>): Int

    @Upsert
    suspend fun upsertTranslationBlock(block: TranslationBlockEntity)

    @Query("SELECT * FROM translation_blocks WHERE translationId = :translationId ORDER BY blockIndex")
    suspend fun translationBlocks(translationId: Long): List<TranslationBlockEntity>

    @Query("SELECT * FROM translation_blocks WHERE translationId IN (:translationIds)")
    suspend fun translationBlocksFor(translationIds: List<Long>): List<TranslationBlockEntity>

    @Upsert
    suspend fun upsertTranslationBlocks(blocks: List<TranslationBlockEntity>)

    @Query("DELETE FROM translation_blocks WHERE translationId = :translationId")
    suspend fun deleteTranslationBlocks(translationId: Long)

    /**
     * One transaction for the cache invariant "COMPLETE implies blocks present".
     * A crash between the status upsert and the block inserts must not leave a
     * COMPLETE row with a half-replaced block set.
     */
    @Transaction
    suspend fun saveTranslationWithBlocks(
        translation: ChapterTranslationEntity,
        blocks: List<TranslationBlockEntity>,
    ): Long {
        require(blocks.isNotEmpty()) { "A COMPLETE translation needs blocks" }
        val existing = chapterTranslationAnySource(
            chapterId = translation.chapterId,
            source = translation.source,
            target = translation.target,
            provider = translation.provider,
            promptHash = translation.promptHash,
            pipelineVersion = translation.pipelineVersion,
        )
        val translationId = if (existing != null) {
            upsertChapterTranslation(translation.copy(translationId = existing.translationId))
            existing.translationId
        } else {
            upsertChapterTranslation(translation)
        }
        deleteTranslationBlocks(translationId)
        upsertTranslationBlocks(blocks.map { it.copy(translationId = translationId) })
        return translationId
    }

    /**
     * A failed attempt must not keep the previous COMPLETE block set: the
     * status and its (empty) content have to commit together, otherwise a
     * reader sees FAILED with stale text from an older revision.
     */
    @Transaction
    suspend fun saveTranslationFailure(translation: ChapterTranslationEntity): Long {
        val existing = chapterTranslationAnySource(
            chapterId = translation.chapterId,
            source = translation.source,
            target = translation.target,
            provider = translation.provider,
            promptHash = translation.promptHash,
            pipelineVersion = translation.pipelineVersion,
        )
        val translationId = if (existing != null) {
            upsertChapterTranslation(translation.copy(translationId = existing.translationId))
            existing.translationId
        } else {
            upsertChapterTranslation(translation)
        }
        deleteTranslationBlocks(translationId)
        return translationId
    }

    @Upsert
    suspend fun upsertBookTranslation(translation: BookTranslationEntity): Long

    @Query("SELECT * FROM book_translations WHERE bookId = :bookId")
    suspend fun bookTranslationsForBook(bookId: Long): List<BookTranslationEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookTranslations(translations: List<BookTranslationEntity>): List<Long>

    @Query("DELETE FROM book_translations WHERE translationId IN (:translationIds)")
    suspend fun deleteBookTranslationsByIds(translationIds: List<Long>)

    @Query(
        "SELECT * FROM book_translations WHERE bookId = :bookId AND source = :source " +
            "AND target = :target AND provider = :provider " +
            "AND promptHash = :promptHash AND pipelineVersion = :pipelineVersion " +
            "ORDER BY updatedAt DESC LIMIT 1",
    )
    suspend fun bookTranslation(
        bookId: Long,
        source: String,
        target: String,
        provider: String,
        promptHash: String,
        pipelineVersion: String,
    ): BookTranslationEntity?

    // Batch lane for the library/dashboard overlay: one query per config,
    // never one query per book.
    @Query(
        "SELECT * FROM book_translations WHERE bookId IN (:bookIds) " +
            "AND source = :source AND target = :target " +
            "AND provider = :provider AND promptHash = :promptHash " +
            "AND pipelineVersion = :pipelineVersion " +
            "ORDER BY bookId, updatedAt DESC, translationId DESC",
    )
    suspend fun bookTranslations(
        bookIds: List<Long>,
        source: String,
        target: String,
        provider: String,
        promptHash: String,
        pipelineVersion: String,
    ): List<BookTranslationEntity>

    @Upsert
    suspend fun upsertTtsChunk(chunk: TtsChunkEntity)

    @Query("SELECT * FROM tts_chunks WHERE chapterId = :chapterId ORDER BY blockIndex, sentenceIndex")
    suspend fun ttsChunks(chapterId: Long): List<TtsChunkEntity>

    @Upsert
    suspend fun upsertSessionByKey(session: ReadingSessionEntity): Long

    @Query("SELECT * FROM reading_sessions WHERE sessionKey = :sessionKey LIMIT 1")
    suspend fun sessionByKey(sessionKey: String): ReadingSessionEntity?

    @Upsert
    suspend fun upsertBookMigration(migration: BookMigrationEntity): Long

    @Query("SELECT * FROM book_migrations WHERE batchId = :batchId ORDER BY id DESC LIMIT 1")
    suspend fun bookMigrationByBatch(batchId: String): BookMigrationEntity?

    // Restore journals are implementation rows; never show them as user
    // migrations.
    @Query(
        "SELECT * FROM book_migrations WHERE status != '$RestoreJournalStatus' " +
            "ORDER BY createdAt DESC",
    )
    suspend fun bookMigrations(): List<BookMigrationEntity>

    // History is user-visible metadata only: deleting a group must never touch
    // books, chapters or files, and restore journals stay internal.
    @Query(
        "DELETE FROM book_migrations WHERE batchId = :batchId " +
            "AND status != '$RestoreJournalStatus'",
    )
    suspend fun deleteBookMigrationsForBatch(batchId: String): Int

    @Query(
        "SELECT COUNT(*) FROM book_migrations WHERE id = :id AND batchId = :batchId " +
            "AND status = :status",
    )
    suspend fun restoreJournalCount(id: Long, batchId: String, status: String): Int

    @Query("DELETE FROM book_migrations WHERE id = :id AND status = :status")
    suspend fun deleteRestoreJournal(id: Long, status: String): Int

    @Query("DELETE FROM book_migrations WHERE status = :status")
    suspend fun deleteRestoreJournals(status: String): Int

    @Query("SELECT COUNT(*) FROM book_migrations WHERE status = :status")
    suspend fun restoreJournalStatusCount(status: String): Int

    // --- live backup import --------------------------------------------------

    // Bounded reads from a backup database. The import runs inside one live
    // transaction, so these pages never observe a partially written backup.
    @Query("SELECT * FROM books WHERE bookId > :afterId ORDER BY bookId LIMIT :limit")
    suspend fun backupBooks(afterId: Long, limit: Int): List<BookEntity>

    @Query("SELECT * FROM chapters WHERE chapterId > :afterId ORDER BY chapterId LIMIT :limit")
    suspend fun backupChapters(afterId: Long, limit: Int): List<ChapterEntity>

    @Query("SELECT * FROM progress WHERE bookId > :afterId ORDER BY bookId LIMIT :limit")
    suspend fun backupProgress(afterId: Long, limit: Int): List<ProgressEntity>

    @Query("SELECT * FROM history WHERE bookId > :afterId ORDER BY bookId LIMIT :limit")
    suspend fun backupHistory(afterId: Long, limit: Int): List<HistoryEntity>

    @Query(
        "SELECT * FROM video_progress " +
            "WHERE bookId > :afterBookId " +
            "OR (bookId = :afterBookId AND chapterId > :afterChapterId) " +
            "ORDER BY bookId, chapterId LIMIT :limit",
    )
    suspend fun backupVideoProgress(
        afterBookId: Long,
        afterChapterId: Long,
        limit: Int,
    ): List<VideoProgressEntity>

    @Query("SELECT * FROM reading_sessions WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun backupSessions(afterId: Long, limit: Int): List<ReadingSessionEntity>

    @Query("SELECT * FROM download_tasks WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun backupDownloadTasks(afterId: Long, limit: Int): List<DownloadTaskEntity>

    @Query(
        "SELECT * FROM chapter_bodies WHERE chapterId > :afterId " +
            "ORDER BY chapterId LIMIT :limit",
    )
    suspend fun backupChapterBodies(afterId: Long, limit: Int): List<ChapterBodyEntity>

    @Query(
        "SELECT * FROM chapter_pages WHERE chapterId > :afterId " +
            "ORDER BY chapterId LIMIT :limit",
    )
    suspend fun backupChapterPages(afterId: Long, limit: Int): List<ChapterPageEntity>

    @Query("SELECT * FROM book_covers WHERE bookId > :afterId ORDER BY bookId LIMIT :limit")
    suspend fun backupCovers(afterId: Long, limit: Int): List<BookCoverEntity>

    @Query("SELECT * FROM tags WHERE tagId > :afterId ORDER BY tagId LIMIT :limit")
    suspend fun backupTags(afterId: Long, limit: Int): List<TagEntity>

    @Query(
        "SELECT * FROM book_tags ORDER BY bookId, tagId LIMIT :limit OFFSET :offset",
    )
    suspend fun backupBookTags(offset: Int, limit: Int): List<BookTagEntity>

    @Query(
        "SELECT * FROM local_book_files WHERE bookId > :afterId " +
            "ORDER BY bookId LIMIT :limit",
    )
    suspend fun backupLocalBookFiles(afterId: Long, limit: Int): List<LocalBookFileEntity>

    @Query("SELECT * FROM highlights WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun backupHighlights(afterId: Long, limit: Int): List<HighlightEntity>

    @Query(
        "SELECT * FROM chapter_translations WHERE translationId > :afterId " +
            "ORDER BY translationId LIMIT :limit",
    )
    suspend fun backupChapterTranslations(afterId: Long, limit: Int): List<ChapterTranslationEntity>

    @Query(
        "SELECT * FROM translation_blocks ORDER BY translationId, blockIndex " +
            "LIMIT :limit OFFSET :offset",
    )
    suspend fun backupTranslationBlocks(offset: Int, limit: Int): List<TranslationBlockEntity>

    @Query(
        "SELECT * FROM book_translations WHERE translationId > :afterId " +
            "ORDER BY translationId LIMIT :limit",
    )
    suspend fun backupBookTranslations(afterId: Long, limit: Int): List<BookTranslationEntity>

    @Query(
        "SELECT * FROM tts_chunks ORDER BY chapterId, blockIndex, sentenceIndex " +
            "LIMIT :limit OFFSET :offset",
    )
    suspend fun backupTtsChunks(offset: Int, limit: Int): List<TtsChunkEntity>

    @Query(
        "SELECT * FROM download_tasks WHERE bookId = :bookId AND chapterId = :chapterId " +
            "AND kind = :kind LIMIT 1",
    )
    suspend fun downloadTask(bookId: Long, chapterId: Long, kind: String): DownloadTaskEntity?

    @Query("SELECT DISTINCT bookId FROM local_book_files")
    suspend fun localBookFileIds(): List<Long>

    @Query("UPDATE books SET lastChapterId = :chapterId WHERE bookId = :bookId")
    suspend fun setLastChapterId(bookId: Long, chapterId: Long?)

    @Query(
        "UPDATE books SET lastReadAt = :readAt, lastChapterId = :chapterId, " +
            "lastChapterIndex = :chapterIndex WHERE bookId = :bookId",
    )
    suspend fun setLastRead(
        bookId: Long,
        readAt: Long?,
        chapterId: Long?,
        chapterIndex: Int?,
    )

    @Query(
        "SELECT id FROM highlights WHERE bookId = :bookId AND chapterId = :chapterId " +
            "AND blockIndex = :blockIndex AND startOffset = :startOffset " +
            "AND endOffset = :endOffset AND textKind = :textKind " +
            "AND sourceHash = :sourceHash LIMIT 1",
    )
    suspend fun highlightId(
        bookId: Long,
        chapterId: Long,
        blockIndex: Int,
        startOffset: Int,
        endOffset: Int,
        textKind: String,
        sourceHash: String,
    ): Long?

    // Null-key sessions have no UNIQUE constraint, so repeat restores must
    // dedupe them by their natural identity.
    @Query(
        "SELECT COUNT(*) FROM reading_sessions WHERE bookId = :bookId " +
            "AND chapterId IS :chapterId AND startedAt = :startedAt " +
            "AND durationMs = :durationMs AND kind = :kind",
    )
    suspend fun sessionWithNaturalKeyCount(
        bookId: Long,
        chapterId: Long?,
        startedAt: Long,
        durationMs: Long,
        kind: String,
    ): Int

    // --- storage accounting --------------------------------------------------

    // Counts and sums in one row. Cached chapter bodies/pages are "clearable"
    // only for books without a local_book_files row: imported files cannot be
    // re-downloaded, so their bodies/pages are library content, not cache.
    @Query(
        """
        SELECT
            (SELECT COALESCE(SUM(bytes), 0) FROM chapter_bodies) AS chapterBodyBytes,
            (SELECT COALESCE(SUM(totalBytes), 0) FROM chapter_pages) AS chapterPageBytes,
            (SELECT COALESCE(SUM(bytes), 0) FROM book_covers) AS coverBytes,
            (SELECT COALESCE(SUM(bytes), 0) FROM source_icons) AS sourceIconBytes,
            (SELECT COALESCE(SUM(sizeBytes), 0) FROM local_book_files
                WHERE UPPER(format) != 'CBZ') AS localNovelBytes,
            (SELECT COALESCE(SUM(sizeBytes), 0) FROM local_book_files
                WHERE UPPER(format) = 'CBZ') AS localMangaBytes,
            (SELECT COUNT(*) FROM books) AS bookCount,
            (SELECT COUNT(*) FROM chapters) AS chapterCount,
            (SELECT COUNT(*) FROM book_covers) AS coverCount,
            (SELECT COUNT(*) FROM local_book_files) AS localFileCount,
            (SELECT COUNT(*) FROM chapter_bodies WHERE chapterId IN
                (SELECT c.chapterId FROM chapters c
                    LEFT JOIN local_book_files l ON l.bookId = c.bookId
                    WHERE l.bookId IS NULL)) AS clearableChapterBodyCount,
            (SELECT COUNT(*) FROM chapter_pages WHERE chapterId IN
                (SELECT c.chapterId FROM chapters c
                    LEFT JOIN local_book_files l ON l.bookId = c.bookId
                    WHERE l.bookId IS NULL)) AS clearableChapterPageCount,
            (SELECT COUNT(*) FROM book_covers WHERE isUserOverride = 0
                AND bookId NOT IN (SELECT bookId FROM local_book_files))
                AS clearableCoverCount,
            (SELECT COUNT(*) FROM source_icons) AS sourceIconCount
        """,
    )
    suspend fun storageTotals(): StorageTotals

    @Query("SELECT path, bytes FROM chapter_bodies")
    suspend fun chapterBodyFiles(): List<StoredFileBytes>

    @Query("SELECT pagePathsJson AS pathsJson, totalBytes FROM chapter_pages")
    suspend fun chapterPageFiles(): List<ChapterPageFiles>

    @Query("SELECT localPath AS path, bytes FROM book_covers WHERE localPath IS NOT NULL")
    suspend fun coverFiles(): List<StoredFileBytes>

    @Query("SELECT localPath AS path, bytes FROM source_icons WHERE localPath IS NOT NULL")
    suspend fun sourceIconFiles(): List<StoredFileBytes>

    @Query(
        "SELECT copyPath AS path, sizeBytes AS bytes FROM local_book_files " +
            "WHERE copyPath IS NOT NULL AND UPPER(format) != 'CBZ'",
    )
    suspend fun localNovelFiles(): List<StoredFileBytes>

    @Query(
        "SELECT copyPath AS path, sizeBytes AS bytes FROM local_book_files " +
            "WHERE copyPath IS NOT NULL AND UPPER(format) = 'CBZ'",
    )
    suspend fun localMangaFiles(): List<StoredFileBytes>

    @Query(
        "SELECT path FROM chapter_bodies WHERE chapterId IN " +
            "(SELECT c.chapterId FROM chapters c " +
            "LEFT JOIN local_book_files l ON l.bookId = c.bookId WHERE l.bookId IS NULL)",
    )
    suspend fun removableChapterBodyPaths(): List<String>

    @Query(
        "DELETE FROM chapter_bodies WHERE chapterId IN " +
            "(SELECT c.chapterId FROM chapters c " +
            "LEFT JOIN local_book_files l ON l.bookId = c.bookId WHERE l.bookId IS NULL)",
    )
    suspend fun deleteRemovableChapterBodies()

    // Clearing the cache deletes the payload, so the denormalized chapter flag
    // must stop claiming a download when no body/page row remains.
    @Query(
        "UPDATE chapters SET downloaded = 0 WHERE downloaded = 1 " +
            "AND chapterId NOT IN (SELECT chapterId FROM chapter_bodies) " +
            "AND chapterId NOT IN (SELECT chapterId FROM chapter_pages)",
    )
    suspend fun resetOrphanedDownloadedFlags()

    @Query(
        "UPDATE chapters SET downloaded = 0 WHERE bookId IN (:bookIds) AND downloaded = 1 " +
            "AND chapterId NOT IN (SELECT chapterId FROM chapter_bodies) " +
            "AND chapterId NOT IN (SELECT chapterId FROM chapter_pages)",
    )
    suspend fun resetOrphanedDownloadedFlagsFor(bookIds: List<Long>): Int

    @Query(
        "SELECT pagePathsJson FROM chapter_pages WHERE chapterId IN " +
            "(SELECT c.chapterId FROM chapters c " +
            "LEFT JOIN local_book_files l ON l.bookId = c.bookId WHERE l.bookId IS NULL)",
    )
    suspend fun removableChapterPagePathsJson(): List<String>

    @Query(
        "DELETE FROM chapter_pages WHERE chapterId IN " +
            "(SELECT c.chapterId FROM chapters c " +
            "LEFT JOIN local_book_files l ON l.bookId = c.bookId WHERE l.bookId IS NULL)",
    )
    suspend fun deleteRemovableChapterPages()

    // Covers for imported books are extracted from the local copy at import
    // time and cannot be fetched again, so they stay. User overrides stay too.
    @Query(
        "SELECT localPath FROM book_covers WHERE isUserOverride = 0 " +
            "AND bookId NOT IN (SELECT bookId FROM local_book_files) " +
            "AND localPath IS NOT NULL",
    )
    suspend fun removableCoverPaths(): List<String>

    @Query(
        "DELETE FROM book_covers WHERE isUserOverride = 0 " +
            "AND bookId NOT IN (SELECT bookId FROM local_book_files)",
    )
    suspend fun deleteRemovableCovers()

    @Query("SELECT localPath FROM source_icons WHERE localPath IS NOT NULL")
    suspend fun removableSourceIconPaths(): List<String>

    @Query("DELETE FROM source_icons")
    suspend fun deleteSourceIconsCache()
}

data class DirectBookWrite(val bookId: Long, val created: Boolean)

// Reconcile writes one short transaction per chunk. A cancellation between
// chunks leaves only idempotent upserts; removals and the total are applied
// only after every upsert chunk committed, so a retry finishes the job.
internal suspend fun reconcileChapterRows(
    bookId: Long,
    existing: Map<String, ChapterEntity>,
    seeds: List<ChapterSeed>,
    at: Long,
    upsertChunk: suspend (List<ChapterEntity>) -> Unit,
    markRemovedChunk: suspend (List<Long>, Long) -> Unit,
    updateTotal: suspend (Int) -> Unit,
    readChapters: suspend () -> List<ChapterEntity>,
): List<ChapterEntity> {
    val incoming = seeds.filter { it.url.isNotBlank() }.distinctBy { it.url }
    val rows = incoming.map { seed ->
        val old = existing[seed.url]
        ChapterEntity(
            chapterId = old?.chapterId ?: 0,
            bookId = bookId,
            idx = seed.index,
            url = seed.url,
            title = seed.title,
            hash = seed.hash ?: old?.hash,
            read = old?.read ?: false,
            readAt = old?.readAt,
            downloaded = old?.downloaded ?: false,
            removedAt = null,
            updatedAt = at,
        )
    }
    for (chunk in rows.chunked(ChapterChunkSize)) {
        currentCoroutineContext().ensureActive()
        upsertChunk(chunk)
    }
    currentCoroutineContext().ensureActive()
    val incomingUrls = incoming.mapTo(HashSet()) { it.url }
    val removed = existing.values
        .filter { it.url !in incomingUrls && it.removedAt == null }
        .map { it.chapterId }
    for (chunk in removed.chunked(ChapterChunkSize)) {
        currentCoroutineContext().ensureActive()
        markRemovedChunk(chunk, at)
    }
    updateTotal(incoming.size)
    return readChapters()
}

// A fresh metadata write must never reset local user state: membership, shelf,
// added time, the unseen badge and a valid last-read position stay with the
// stored row; metadata fields take the incoming value when it is meaningful.
internal suspend fun mergeBookRow(
    row: BookEntity,
    incoming: BookEntity,
    dao: LibraryDao,
): BookEntity {
    val incomingChapterValid = incoming.lastChapterId != null &&
        dao.chapterIdForBook(row.bookId, incoming.lastChapterId) != null
    val incomingNotOlder =
        (incoming.lastReadAt ?: Long.MIN_VALUE) >= (row.lastReadAt ?: Long.MIN_VALUE)
    val takeIncomingRead = incomingChapterValid && incomingNotOlder
    val lastChapterId = if (takeIncomingRead) incoming.lastChapterId else row.lastChapterId
    val lastChapterIndex = if (takeIncomingRead) incoming.lastChapterIndex else row.lastChapterIndex
    val incomingChapterDropped = incoming.lastChapterId != null && !incomingChapterValid
    val lastReadAt = if (incomingChapterDropped) {
        row.lastReadAt
    } else {
        listOfNotNull(row.lastReadAt, incoming.lastReadAt).maxOrNull()
    }
    return row.copy(
        installId = row.installId.ifBlank { incoming.installId },
        title = incoming.title.ifBlank { row.title },
        author = incoming.author.ifBlank { row.author },
        coverUrl = incoming.coverUrl ?: row.coverUrl,
        contentType = incoming.contentType.ifBlank { row.contentType },
        rating = if (incoming.rating != 0.0) incoming.rating else row.rating,
        inLibrary = row.inLibrary || incoming.inLibrary,
        addedAt = minOf(row.addedAt, incoming.addedAt),
        addedToLibraryAt = listOfNotNull(row.addedToLibraryAt, incoming.addedToLibraryAt)
            .minOrNull(),
        updatedAt = maxOf(row.updatedAt, incoming.updatedAt),
        lastReadAt = lastReadAt,
        lastChapterId = lastChapterId,
        lastChapterIndex = lastChapterIndex,
        subtitle = incoming.subtitle ?: row.subtitle,
        altTitlesJson = incoming.altTitlesJson.takeIf { it != "[]" } ?: row.altTitlesJson,
        authorRaw = incoming.authorRaw.ifBlank { row.authorRaw },
        authorsJson = incoming.authorsJson.takeIf { it != "[]" } ?: row.authorsJson,
        description = incoming.description.ifBlank { row.description },
        originLanguage = incoming.originLanguage ?: row.originLanguage,
        ratingRaw = incoming.ratingRaw.ifBlank { row.ratingRaw },
        statusRaw = incoming.statusRaw.ifBlank { row.statusRaw },
        statusNormalized = incoming.statusNormalized
            .takeIf { it != "UNKNOWN" } ?: row.statusNormalized,
        lastUpdatedRaw = incoming.lastUpdatedRaw.ifBlank { row.lastUpdatedRaw },
        lastUpdatedAt = incoming.lastUpdatedAt ?: row.lastUpdatedAt,
        genresJson = incoming.genresJson.takeIf { it != "[]" } ?: row.genresJson,
        extrasJson = incoming.extrasJson.takeIf { it != "{}" } ?: row.extrasJson,
        metadataSource = incoming.metadataSource.ifBlank { row.metadataSource },
        metadataUpdatedAt = maxOf(row.metadataUpdatedAt, incoming.metadataUpdatedAt),
        metadataHash = incoming.metadataHash.ifBlank { row.metadataHash },
    )
}

internal suspend fun mergeRepairRow(
    survivor: BookEntity,
    loser: BookEntity,
    dao: LibraryDao,
): BookEntity {
    val merged = mergeBookRow(survivor, loser, dao)
    val inLibrary = survivor.inLibrary || loser.inLibrary
    return merged.copy(
        // The survivor's metadata wins; the other row only fills blanks.
        title = survivor.title.ifBlank { loser.title },
        author = survivor.author.ifBlank { loser.author },
        coverUrl = survivor.coverUrl ?: loser.coverUrl,
        contentType = survivor.contentType.ifBlank { loser.contentType },
        rating = if (survivor.rating != 0.0) survivor.rating else loser.rating,
        description = survivor.description.ifBlank { loser.description },
        subtitle = survivor.subtitle ?: loser.subtitle,
        altTitlesJson = survivor.altTitlesJson.takeIf { it != "[]" } ?: loser.altTitlesJson,
        genresJson = survivor.genresJson.takeIf { it != "[]" } ?: loser.genresJson,
        authorRaw = survivor.authorRaw.ifBlank { loser.authorRaw },
        authorsJson = survivor.authorsJson.takeIf { it != "[]" } ?: loser.authorsJson,
        originLanguage = survivor.originLanguage ?: loser.originLanguage,
        ratingRaw = survivor.ratingRaw.ifBlank { loser.ratingRaw },
        statusRaw = survivor.statusRaw.ifBlank { loser.statusRaw },
        statusNormalized = survivor.statusNormalized
            .takeIf { it != "UNKNOWN" } ?: loser.statusNormalized,
        lastUpdatedRaw = survivor.lastUpdatedRaw.ifBlank { loser.lastUpdatedRaw },
        lastUpdatedAt = survivor.lastUpdatedAt ?: loser.lastUpdatedAt,
        extrasJson = survivor.extrasJson.takeIf { it != "{}" } ?: loser.extrasJson,
        metadataSource = survivor.metadataSource.ifBlank { loser.metadataSource },
        metadataUpdatedAt = maxOf(survivor.metadataUpdatedAt, loser.metadataUpdatedAt),
        metadataHash = survivor.metadataHash.ifBlank { loser.metadataHash },
        addedAt = minOf(survivor.addedAt, loser.addedAt),
        addedToLibraryAt = if (inLibrary) {
            listOfNotNull(survivor.addedToLibraryAt, loser.addedToLibraryAt)
                .ifEmpty { listOf(minOf(survivor.addedAt, loser.addedAt)) }
                .minOrNull()
        } else {
            null
        },
        shelfId = when {
            survivor.inLibrary -> survivor.shelfId
            loser.inLibrary -> loser.shelfId
            else -> survivor.shelfId
        },
        totalChapters = maxOf(survivor.totalChapters, loser.totalChapters),
        newChapters = maxOf(survivor.newChapters, loser.newChapters),
    )
}

private const val ReadingShelfId = "reading"

// Room projection for the grouped membership query.
data class LibraryTitleCount(val title: String, val count: Int)

// Reconcile/delete chunks stay well below SQLite's 999 bind limit while
// keeping each write transaction short.
private const val ChapterChunkSize = 500
