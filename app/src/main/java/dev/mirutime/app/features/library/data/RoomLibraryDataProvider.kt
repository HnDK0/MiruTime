package dev.mirutime.app.features.library.data

import android.text.format.DateUtils
import dev.mirutime.app.core.jsonStringArray
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.log.MiruLog
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.library.domain.LibraryBook
import dev.mirutime.app.features.library.domain.LibrarySort
import dev.mirutime.app.features.library.domain.Shelf
import dev.mirutime.app.features.library.domain.SortDirection
import dev.mirutime.app.features.translation.data.MetadataTranslationStore
import dev.mirutime.app.features.translation.data.RoomMetadataTranslationStore
import dev.mirutime.data.library.BookCoverEntity
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.BookReadStats
import dev.mirutime.data.library.LibraryBookFiles
import dev.mirutime.data.library.decodeLibraryBookKey
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.ShelfEntity
import java.net.URI
import kotlinx.coroutines.CancellationException

// Library screen's real data source: shelves and books come from the Room
// library store, read flags from chapter_read. Ranks follow the DAO order
// (last-read desc); the UI compares ranks and reverses for direction.
class RoomLibraryDataProvider(
    private val library: LibraryRepository,
    private val coverStore: BookCoverStore? = null,
    private val importer: LocalBookImporter? = null,
    // Owns the same app-private filesystem as imports; bulk delete hands the
    // pre-collected paths here after the DB commit.
    private val localFiles: LocalBookProvider? = null,
    private val translations: MetadataTranslationStore? =
        RoomMetadataTranslationStore(library),
    private val now: () -> Long = System::currentTimeMillis,
    // installId -> plugin display name; fallback keeps local/unknown ids.
    private val sourceName: (String) -> String = { it },
    // Runs after the delete transaction commits; a cleanup failure must not
    // resurrect rows or turn a successful delete into an error.
    private val onBooksDeleted: suspend (Set<String>) -> Unit = {},
    // Cover repair is owned by the plugin book provider (source re-resolve +
    // forced fetch); this screen-side provider only forwards the request.
    private val coverRefresh: (suspend (String) -> Outcome<String?>)? = null,
) : LibraryDataProvider {
    override suspend fun refreshCover(bookUrl: String): Outcome<String?> =
        coverRefresh?.invoke(bookUrl) ?: Outcome.Ok(null)
    override suspend fun shelves(): List<Shelf> {
        // Seeding is also launched in Application.onCreate, but the first
        // Library render can beat that coroutine; keep the read self-healing.
        library.ensureDefaultShelves()
        return library.shelves().map { shelf ->
            Shelf(
                id = shelf.id,
                label = shelf.label,
                excludeFromUpdates = shelf.excludeFromUpdates,
            )
        }
    }

    override suspend fun books(): List<LibraryBook> {
        val timestamp = now()
        val rows = library.libraryBooks()
        if (rows.isEmpty()) return emptyList()
        val batch = batchReads(rows)
        return rows.mapIndexed { rank, entity ->
            mapLibraryBookRow(
                entity = entity,
                rank = rank,
                timestamp = timestamp,
                stats = batch.stats,
                tags = batch.tags,
                covers = batch.covers,
                translatedTitles = batch.translatedTitles,
                coverStore = coverStore,
                sourceName = sourceName,
            )
        }.distinctBy { it.bookKey }
    }

    override suspend fun lastRead(): LibraryBook? {
        val timestamp = now()
        val row = library.history(1).firstOrNull() ?: return null
        val batch = batchReads(listOf(row))
        return mapLibraryBookRow(
            entity = row,
            rank = 0,
            timestamp = timestamp,
            stats = batch.stats,
            tags = batch.tags,
            covers = batch.covers,
            translatedTitles = batch.translatedTitles,
            coverStore = coverStore,
            sourceName = sourceName,
        )
    }

    override suspend fun libraryPage(
        query: String,
        cursor: LibraryPageCursor?,
        limit: Int,
        filters: LibraryPageFilters,
        sort: LibrarySort,
        direction: SortDirection,
    ): LibraryPageSlice {
        val pageSize = limit.coerceIn(1, 500)
        val text = query.trim()
        if (text.isEmpty()) {
            val keyset = (cursor as? LibraryPageCursor.Keyset)?.value
            val page = library.libraryPage(
                limit = pageSize,
                cursor = keyset,
                filters = filters.toData(),
                sort = sort.toData(),
                direction = direction.toData(),
            )
            return LibraryPageSlice(
                books = mapPageRows(page.books),
                nextCursor = page.nextCursor?.let(LibraryPageCursor::Keyset),
                hasMore = page.hasMore,
                total = page.total,
            )
        }
        return searchPage(text, cursor, pageSize, filters)
    }

    override suspend fun libraryFacets(): LibraryFacets {
        val facets = library.libraryFacets()
        return LibraryFacets(sources = facets.sources, genres = facets.genres)
    }

    override suspend fun matchingLibraryUrls(
        query: String,
        filters: LibraryPageFilters,
    ): List<String> {
        val matchingFilters = library.matchingLibraryUrls(filters.toData()).toSet()
        if (matchingFilters.isEmpty()) return emptyList()
        val text = query.trim()
        if (text.isEmpty()) return matchingFilters.sorted()

        val matched = LinkedHashSet<String>()
        var offset = 0
        while (true) {
            val rows = library.searchBooks(text, limit = SelectAllSearchChunk, offset = offset)
            if (rows.isEmpty()) break
            rows.forEach { row ->
                if (row.url in matchingFilters) matched += row.url
            }
            if (rows.size < SelectAllSearchChunk) break
            offset += rows.size
        }
        return matched.toList()
    }

    override suspend fun matchingLibrarySources(
        query: String,
        filters: LibraryPageFilters,
    ): Map<String, Set<String>> {
        val urls = matchingLibraryUrls(query, filters)
        return library.bookSourcesByUrls(urls)
    }

    override suspend fun shelfSummaries(): List<LibraryShelfSummary> {
        val summaries = library.shelfSummaries()
        if (summaries.isEmpty()) return emptyList()
        // One batch cover read for the representative book of every shelf;
        // the mapper's local-file precedence is reused instead of raw URLs.
        val bookIds = summaries.mapNotNull { it.coverBookId }.distinct()
        val covers = if (coverStore == null || bookIds.isEmpty()) {
            emptyMap()
        } else {
            library.bookCoversFor(bookIds)
        }
        return summaries.map { summary ->
            val coverBookId = summary.coverBookId
            LibraryShelfSummary(
                shelfId = summary.shelfId,
                bookCount = summary.bookCount,
                coverUrl = if (coverBookId == null) {
                    summary.coverUrl
                } else {
                    resolveStoredCover(
                        coverStore = coverStore,
                        bookId = coverBookId,
                        cover = covers[coverBookId],
                        fallback = summary.coverUrl,
                    )
                },
                coverTitle = summary.coverTitle,
                coverBookId = coverBookId,
            )
        }
    }

    /**
     * FTS page with the active SQL filters applied in memory, because the
     * bm25 query has no filter arguments. Each fetch is exactly the remaining
     * page size, so a skipped row never shifts the offset.
     */
    private suspend fun searchPage(
        query: String,
        cursor: LibraryPageCursor?,
        pageSize: Int,
        filters: LibraryPageFilters,
    ): LibraryPageSlice {
        var offset = ((cursor as? LibraryPageCursor.Search)?.offset ?: 0).coerceAtLeast(0)
        val collected = mutableListOf<LibraryBook>()
        var hasMore = false
        while (collected.size < pageSize) {
            val need = pageSize - collected.size
            val rows = library.searchBooks(query, limit = need, offset = offset)
            if (rows.isEmpty()) {
                hasMore = false
                break
            }
            offset += rows.size
            collected += mapPageRows(rows).filter { it.matches(filters) }
            hasMore = rows.size == need
            if (!hasMore) break
        }
        return LibraryPageSlice(
            books = collected,
            nextCursor = if (hasMore) LibraryPageCursor.Search(offset) else null,
            hasMore = hasMore,
            total = null,
        )
    }

    private suspend fun mapPageRows(rows: List<BookEntity>): List<LibraryBook> {
        if (rows.isEmpty()) return emptyList()
        val timestamp = now()
        val batch = batchReads(rows)
        return rows.mapIndexed { index, entity ->
            mapLibraryBookRow(
                entity = entity,
                rank = index,
                timestamp = timestamp,
                stats = batch.stats,
                tags = batch.tags,
                covers = batch.covers,
                translatedTitles = batch.translatedTitles,
                coverStore = coverStore,
                sourceName = sourceName,
            )
        }
    }

    override suspend fun searchBooks(
        query: String,
        limit: Int,
        offset: Int,
    ): List<LibraryBook> {
        val timestamp = now()
        val rows = library.searchBooks(query, limit = limit, offset = offset)
        if (rows.isEmpty()) return emptyList()
        val batch = batchReads(rows)
        return rows.mapIndexed { index, entity ->
            mapLibraryBookRow(
                entity = entity,
                rank = offset + index,
                timestamp = timestamp,
                stats = batch.stats,
                tags = batch.tags,
                covers = batch.covers,
                translatedTitles = batch.translatedTitles,
                coverStore = coverStore,
                sourceName = sourceName,
            )
        }.distinctBy { it.bookKey }
    }

    override suspend fun saveShelf(shelf: Shelf) {
        val current = library.shelves()
        val position = current.firstOrNull { it.id == shelf.id }?.position
            ?: ((current.maxOfOrNull { it.position } ?: -1) + 1)
        library.saveShelf(
            ShelfEntity(
                id = shelf.id,
                label = shelf.label,
                excludeFromUpdates = shelf.excludeFromUpdates,
                position = position,
            ),
        )
    }

    override suspend fun deleteShelf(id: String) = library.deleteShelf(id)

    override suspend fun reorderShelves(shelves: List<Shelf>) {
        library.reorderShelves(
            shelves.map { shelf ->
                ShelfEntity(
                    id = shelf.id,
                    label = shelf.label,
                    excludeFromUpdates = shelf.excludeFromUpdates,
                )
            },
        )
    }

    override suspend fun moveBook(bookUrl: String, shelfId: String) =
        library.setShelf(bookUrl, shelfId)

    override suspend fun deleteBooks(
        bookUrls: Set<String>,
        sources: Map<String, Set<String>>,
    ): BulkActionResult =
        bulk(bookUrls) {
            // Cards carry an opaque BookKey. The repository deletes real
            // URLs; map the result back to the keys the UI selected.
            val identities = bookUrls.associateWith(::decodeLibraryBookKey)
            val urls = bookUrls.mapTo(LinkedHashSet()) { key ->
                identities[key]?.url ?: key
            }
            val decodedSources = identities.values
                .filterNotNull()
                .groupBy({ it.url }, { it.installId })
                .mapValues { (_, installs) -> installs.toSet() }
                .toMutableMap()
            sources.forEach { (url, installs) -> decodedSources[url] = installs }
            val result = library.deleteBooks(urls.toList(), decodedSources) { files ->
                removeFilesSafely(files)
            }
            val deletedKeys = bookUrls.filterTo(LinkedHashSet()) { key ->
                identities[key]?.url?.let { it in result.deleted } ?: (key in result.deleted)
            }
            if (result.deleted.isNotEmpty()) {
                try {
                    onBooksDeleted(result.deleted)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    MiruLog.w("library", "Post-delete cleanup failed", e)
                }
            }
            BulkActionResult(deletedKeys, bookUrls - deletedKeys)
        }

    private suspend fun removeFilesSafely(files: List<LibraryBookFiles>) {
        if (files.isEmpty()) return
        try {
            localFiles?.removeFiles(files)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // DB commit already happened; file cleanup must not turn a
            // successful delete into a reported failure.
            MiruLog.w("library", "Bulk file cleanup failed", e)
        }
    }

    override suspend fun moveBooks(bookUrls: Set<String>, shelfId: String): BulkActionResult =
        bulk(bookUrls) {
            val result = library.moveBooks(bookUrls.toList(), shelfId)
            BulkActionResult(result.succeeded, result.missing)
        }

    override suspend fun markBooksRead(
        bookUrls: Set<String>,
        read: Boolean,
    ): BulkActionResult = bulk(bookUrls) {
        val result = library.markBooksRead(bookUrls.toList(), read, now())
        BulkActionResult(result.succeeded, result.missing)
    }

    private suspend fun bulk(
        bookUrls: Set<String>,
        action: suspend () -> BulkActionResult,
    ): BulkActionResult = try {
        action()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        MiruLog.w("library", "Bulk library action failed", e)
        BulkActionResult(emptySet(), bookUrls)
    }

    // SAF import: the local importer owns copy/hash/parse/book rows; this
    // provider only aggregates the per-file outcomes for the UI toast.
    override suspend fun importFiles(files: List<PickedImportFile>): ImportSummary {
        val importer = importer ?: return ImportSummary(failures = files.size)
        return summarizeImports(files.map { importer.import(it.documentUri()) })
    }

    private data class LibraryBatch(
        val stats: Map<Long, BookReadStats>,
        val tags: Map<Long, List<String>>,
        val covers: Map<Long, BookCoverEntity>,
        val translatedTitles: Map<Long, String>,
    )

    // One batch query per table for the requested rows only: a single-row
    // lastRead must not scan every chapter/cover/tag in the library.
    private suspend fun batchReads(rows: List<BookEntity>): LibraryBatch {
        val bookIds = rows.map { it.bookId }
        return LibraryBatch(
            stats = library.readChapterStatsFor(bookIds),
            // Tag read failure must not blank the whole shelf; genresJson still
            // supplies the fallback, matching the old per-book runCatching.
            tags = runCatching { library.tagsByBookFor(bookIds) }.getOrDefault(emptyMap()),
            covers = library.bookCoversFor(bookIds),
            translatedTitles = loadTranslatedTitles(rows, translations),
        )
    }

}

/**
 * Pure list mapper: all read/tag/cover data arrives in batch maps, so mapping
 * 100+ rows cannot trigger a per-book repository call.
 */
internal fun mapLibraryBookRow(
    entity: BookEntity,
    rank: Int,
    timestamp: Long,
    stats: Map<Long, BookReadStats>,
    tags: Map<Long, List<String>>,
    covers: Map<Long, BookCoverEntity>,
    translatedTitles: Map<Long, String> = emptyMap(),
    coverStore: BookCoverStore?,
    sourceName: (String) -> String = { it },
): LibraryBook {
    val rawSource = entity.installId.ifBlank { hostOf(entity.bookUrl) }
    val read = stats[entity.bookId]
    val readCount = read?.readCount ?: 0
    val lastIndex = entity.lastChapterIndex ?: read?.maxReadIndex ?: 0
    val names = tags[entity.bookId].orEmpty()
    val fallbackGenres = parseGenres(entity.genresJson)
    // Local file first; the remote URL is only a fallback for the first
    // paint before the backfill has downloaded anything.
    val resolvedCover = resolveStoredCover(
        coverStore,
        entity.bookId,
        covers[entity.bookId],
        entity.coverUrl,
    )
    return LibraryBook(
        bookUrl = entity.bookUrl,
        title = entity.title,
        source = rawSource,
        installId = entity.installId,
        sourceLabel = sourceName(rawSource),
        author = entity.authorRaw.ifBlank { entity.author },
        rating = entity.rating.toFloat(),
        contentType = runCatching { ContentType.valueOf(entity.contentType) }
            .getOrDefault(ContentType.Novel),
        totalChapters = entity.totalChapters,
        readChapters = readCount,
        progressChapter = lastIndex,
        hasUpdate = entity.newChapters > 0,
        newChapters = entity.newChapters,
        shelfId = entity.shelfId,
        genres = names.ifEmpty { fallbackGenres },
        filterGenres = (names + fallbackGenres).distinct(),
        translatedTitle = translatedTitles[entity.bookId],
        lastReadRank = rank,
        lastUpdateRank = rank,
        addedRank = rank,
        lastReadLabel = entity.lastReadAt?.let { at ->
            runCatching {
                DateUtils.getRelativeTimeSpanString(
                    at,
                    timestamp,
                    DateUtils.MINUTE_IN_MILLIS,
                ).toString()
            }.getOrDefault("")
        }.orEmpty(),
        // The card label must match Continue's saved/last-read target, not
        // readCount + 1. The arrow opens the book page, whose Continue button
        // resolves the exact chapter (saved-unread, else first unread).
        continueLabel = if (lastIndex > 0) "Chapter $lastIndex" else "",
        coverUrl = resolvedCover,
    )
}

private fun LibraryBook.matches(filters: LibraryPageFilters): Boolean {
    if (!filters.shelfId.isNullOrBlank() && shelfId != filters.shelfId) return false
    if (filters.sources.isNotEmpty() && source !in filters.sources) return false
    if (filters.contentType != null && contentType != filters.contentType) return false
    if (filters.genres.isNotEmpty() && filterGenres.none { it in filters.genres }) return false
    return true
}

private const val SelectAllSearchChunk = 500

private fun parseGenres(raw: String): List<String> =
    jsonStringArray(raw).filter { it.isNotBlank() }

private fun hostOf(url: String): String =
    runCatching { URI(url).host.orEmpty() }.getOrNull().orEmpty()
