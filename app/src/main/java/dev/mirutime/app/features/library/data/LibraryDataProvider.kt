package dev.mirutime.app.features.library.data

import dev.mirutime.app.features.library.domain.LibraryBook
import dev.mirutime.app.features.library.domain.LibrarySort
import dev.mirutime.app.features.library.domain.Shelf
import dev.mirutime.app.features.library.domain.SortDirection
import dev.mirutime.core.api.Outcome

// Partial failures stay visible to the caller: succeeded URLs are cleared from
// the selection, failed ones remain selected for a retry.
data class BulkActionResult(
    val succeeded: Set<String>,
    val failed: Set<String>,
)

// Library-shaped slice of the future data layer.
// Shelves (Reading "" + Completed + custom), search across titles/sources,
// chapter states and update dots all come from real tables later; the screen
// depends only on this interface.
interface LibraryDataProvider {
    suspend fun shelves(): List<Shelf>
    // Full-library read for settings/other non-paged callers. The library list
    // itself uses [libraryPage] and never loads every row.
    suspend fun books(): List<LibraryBook>
    suspend fun lastRead(): LibraryBook?
    // One list page: SQL keyset for a blank query, bm25 offset for search.
    // Filters/sort are applied by the provider; the screen only renders.
    suspend fun libraryPage(
        query: String = "",
        cursor: LibraryPageCursor? = null,
        limit: Int = LibraryPageSize,
        filters: LibraryPageFilters = LibraryPageFilters(),
        sort: LibrarySort = LibrarySort.LastRead,
        direction: SortDirection = SortDirection.Desc,
    ): LibraryPageSlice
    suspend fun libraryFacets(): LibraryFacets
    // All URLs matching the active filters/search for select-all.
    suspend fun matchingLibraryUrls(
        query: String = "",
        filters: LibraryPageFilters = LibraryPageFilters(),
    ): List<String>
    // url -> installId for select-all; keeps duplicate-URL deletion targeted.
    suspend fun matchingLibrarySources(
        query: String = "",
        filters: LibraryPageFilters = LibraryPageFilters(),
    ): Map<String, Set<String>> = emptyMap()
    suspend fun shelfSummaries(): List<LibraryShelfSummary>
    // Real full-text search over the library; empty queries return the normal
    // library page. Kept for callers that need a simple offset page.
    suspend fun searchBooks(query: String, limit: Int = 50, offset: Int = 0): List<LibraryBook>
    // Repair a dead/rotated cover from its source. Returns the local file URI
    // (the old one when the forced fetch fails); `Err` carries the typed plugin
    // failure for the screen's error state. Providers without a cover store
    // keep the current URL (Ok(null)).
    suspend fun refreshCover(bookUrl: String): Outcome<String?> = Outcome.Ok(null)
    suspend fun saveShelf(shelf: Shelf)
    suspend fun deleteShelf(id: String)
    suspend fun reorderShelves(shelves: List<Shelf>)
    suspend fun moveBook(bookUrl: String, shelfId: String)
    // Bulk library actions. Each one is a single DB write with no per-book
    // transaction/query; files are collected in the same delete transaction
    // and removed afterwards on IO. Missing URLs are reported as failures.
    suspend fun deleteBooks(
        bookUrls: Set<String>,
        sources: Map<String, Set<String>> = emptyMap(),
    ): BulkActionResult
    suspend fun moveBooks(bookUrls: Set<String>, shelfId: String): BulkActionResult
    suspend fun markBooksRead(bookUrls: Set<String>, read: Boolean): BulkActionResult
    // Real SAF import: copies, hashes and persists every picked file. The
    // fake provider keeps an in-memory approximation for previews/tests.
    suspend fun importFiles(files: List<PickedImportFile>): ImportSummary
}
