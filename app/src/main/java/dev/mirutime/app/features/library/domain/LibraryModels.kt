package dev.mirutime.app.features.library.domain

enum class ContentType { Novel, Manga, Video }

enum class LibrarySort {
    LastRead,
    Title,
    Unread,
    LastUpdate,
    Added,
}

enum class SortDirection { Asc, Desc }

@kotlinx.serialization.Serializable
enum class CardStyle {
    // Legacy persisted values: kept readable for backup/restore, never shown.
    CoverTitle,
    BelowCover,
    Horizontal,
    Stats,

    // Grid presets + the list identifier.
    Mat,
    Above,
    Share,
    OnCover,
    OnCoverAbove,
    ProgressAbove,
}

data class Shelf(
    val id: String,
    val label: String,
    // Shelves join automatic update checks unless this is set. The real
    // update worker reads it once the data layer owns categories; until then
    // the flag only rides the in-memory shelf (same as the shelf itself).
    val excludeFromUpdates: Boolean = false,
)

// Library UI models. Persistence keys (BOOKS_GRID_COLUMNS,
// LIBRARY_FILTER_READ, LIBRARY_SORT_CONFIG, LIBRARY_CUSTOM_CATEGORIES,
// LIBRARY_SELECTED_*) arrive with the data library;
// the screen below only holds them as in-memory state for now.
data class LibraryBook(
    val bookUrl: String,
    val title: String,
    val source: String,
    // Display name for the source (plugin name); the raw install id remains
    // in [source] for filters/backup. Defaults keep fakes/tests compiling.
    val sourceLabel: String = source,
    val author: String,
    val rating: Float,
    val contentType: ContentType,
    val totalChapters: Int,
    val readChapters: Int,
    val hasUpdate: Boolean,
    val shelfId: String,
    // Visible labels: tags win, genresJson is only the fallback.
    val genres: List<String>,
    // Filter-only union of tags and genresJson so the SQL list, search page
    // and select-all agree even when the visible labels differ. Defaults to
    // [genres] for fakes/fixtures that have no separate JSON source.
    val filterGenres: List<String> = genres,
    // Translated title shown under the strip; null renders blank (08-library).
    // Rating 0 or below means "not parseable" → no rating badge (card rules).
    val translatedTitle: String? = null,
    val lastReadRank: Int,
    val lastUpdateRank: Int,
    val addedRank: Int,
    val lastReadLabel: String,
    val continueLabel: String,
    // Number of newly detected chapters; 0 = no +N label. The library card
    // still uses the update dot; Dashboard Updates uses the accent +N.
    val newChapters: Int = 0,
    // Remote cover for the fake providers; null keeps the initial placeholder.
    val coverUrl: String? = null,
) {
    val unreadChapters: Int get() = (totalChapters - readChapters).coerceAtLeast(0)
    val isFinished: Boolean get() = unreadChapters == 0
}

// Provider rows are unique by installId+URL, so one book URL can arrive from
// two installs. LazyList identity/navigation use bookUrl, so collapse first.
internal fun dedupeLibraryBooks(books: List<LibraryBook>): List<LibraryBook> =
    books.distinctBy { it.bookUrl }
