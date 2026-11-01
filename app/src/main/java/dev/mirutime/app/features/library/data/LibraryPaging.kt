package dev.mirutime.app.features.library.data

import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.library.domain.LibraryBook
import dev.mirutime.app.features.library.domain.LibrarySort
import dev.mirutime.app.features.library.domain.SortDirection
import dev.mirutime.data.library.LibraryContentType

/** Opaque page position: SQL keyset for the library, bm25 offset for search. */
sealed interface LibraryPageCursor {
    data class Keyset(val value: dev.mirutime.data.library.LibraryPageCursor) : LibraryPageCursor
    data class Search(val offset: Int) : LibraryPageCursor
}

/** One mapped page; [total] is null for filtered FTS search (no count query). */
data class LibraryPageSlice(
    val books: List<LibraryBook>,
    val nextCursor: LibraryPageCursor?,
    val hasMore: Boolean,
    val total: Int? = null,
)

data class LibraryPageFilters(
    val shelfId: String? = null,
    val sources: Set<String> = emptySet(),
    val contentType: ContentType? = null,
    val genres: Set<String> = emptySet(),
)

data class LibraryFacets(
    val sources: List<String>,
    val genres: List<String>,
)

data class LibraryShelfSummary(
    val shelfId: String,
    val bookCount: Int,
    val coverUrl: String?,
    val coverTitle: String?,
    // Representative book for the cover; lets the provider resolve the same
    // local-file precedence as the library rows.
    val coverBookId: Long? = null,
)

/**
 * The library list sort is SQL-side; FTS search stays bm25 relevance. The UI
 * uses this to hide the sort controls while a query is active, and the fake
 * provider uses it to mirror the same rule.
 */
internal fun sortAppliesToQuery(query: String): Boolean = query.trim().isEmpty()

internal fun LibraryPageFilters.toData(): dev.mirutime.data.library.LibraryPageFilters =
    dev.mirutime.data.library.LibraryPageFilters(
        shelfId = shelfId,
        sources = sources,
        contentType = contentType?.toData(),
        genres = genres,
    )

internal fun ContentType.toData(): LibraryContentType = when (this) {
    ContentType.Novel -> LibraryContentType.Novel
    ContentType.Manga -> LibraryContentType.Manga
    ContentType.Video -> LibraryContentType.Video
}

internal fun LibrarySort.toData(): dev.mirutime.data.library.LibrarySort = when (this) {
    LibrarySort.LastRead -> dev.mirutime.data.library.LibrarySort.LastRead
    LibrarySort.LastUpdate -> dev.mirutime.data.library.LibrarySort.LastUpdate
    LibrarySort.Added -> dev.mirutime.data.library.LibrarySort.Added
    LibrarySort.Title -> dev.mirutime.data.library.LibrarySort.Title
    LibrarySort.Unread -> dev.mirutime.data.library.LibrarySort.Unread
}

internal fun SortDirection.toData(): dev.mirutime.data.library.LibrarySortDirection = when (this) {
    SortDirection.Asc -> dev.mirutime.data.library.LibrarySortDirection.Asc
    SortDirection.Desc -> dev.mirutime.data.library.LibrarySortDirection.Desc
}

/** Library page size used by the screen and the provider default. */
const val LibraryPageSize = 60
