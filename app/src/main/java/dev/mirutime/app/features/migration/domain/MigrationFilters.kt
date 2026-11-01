package dev.mirutime.app.features.migration.domain

import dev.mirutime.app.features.finder.domain.CatalogSource
import dev.mirutime.app.features.library.domain.CardStyle
import dev.mirutime.app.features.library.domain.ContentType

/**
 * One draft for the migration source sheet: which target sources are on, and
 * how the sheet narrows the list the user sees. The selection is what the
 * search actually uses; query/language/type never change it.
 */
data class MigrationSourceFilter(
    val selected: Set<String> = emptySet(),
    val query: String = "",
    val languages: Set<String> = emptySet(),
    val types: Set<ContentType> = emptySet(),
)

fun filterMigrationSources(
    sources: List<CatalogSource>,
    filter: MigrationSourceFilter,
): List<CatalogSource> {
    val needle = filter.query.trim().lowercase()
    return sources.filter { source ->
        (filter.languages.isEmpty() || source.language in filter.languages) &&
            (filter.types.isEmpty() || source.type in filter.types) &&
            (
                needle.isEmpty() ||
                    source.name.lowercase().contains(needle) ||
                    source.id.lowercase().contains(needle)
                )
    }
}

fun migrationSourceLanguages(sources: List<CatalogSource>): List<String> =
    sources.map { it.language }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()

/** Unknown/removed plugin ids are dropped instead of being sent to a search. */
fun migrationSelectedSourceIds(
    selected: Set<String>,
    sources: List<CatalogSource>,
): Set<String> = selected.intersect(sources.map { it.id }.toSet())

fun migrationSelectAllSources(sources: List<CatalogSource>): Set<String> =
    sources.map { it.id }.toSet()

fun migrationToggleBook(selected: Set<String>, bookUrl: String): Set<String> =
    if (bookUrl in selected) selected - bookUrl else selected + bookUrl

fun migrationSelectAllBooks(
    selected: Set<String>,
    books: List<MigrationCandidateBook>,
): Set<String> = selected + books.map { it.bookUrl }

fun migrationAllBooksSelected(
    selected: Set<String>,
    books: List<MigrationCandidateBook>,
): Boolean = books.isNotEmpty() && books.all { it.bookUrl in selected }

/**
 * Migration is a library surface, so it renders with the same three settings
 * as the library: layout mode, column count and card style. List pins the
 * horizontal card; grid keeps A/B/D and every count is clamped to 2..6.
 */
data class MigrationLibraryLayout(
    val list: Boolean,
    val columns: Int,
    val style: CardStyle,
) {
    val rows: Boolean get() = list || style == CardStyle.Horizontal
}

fun migrationLibraryLayout(
    listLayout: String,
    gridColumns: Int,
    cardStyle: CardStyle,
): MigrationLibraryLayout = MigrationLibraryLayout(
    list = listLayout == "list",
    columns = gridColumns.coerceIn(2, 6),
    style = if (listLayout == "list" && cardStyle != CardStyle.Horizontal) {
        CardStyle.Horizontal
    } else {
        cardStyle
    },
)

const val UnknownMigrationSourceKey = "migration-unknown-source"

/**
 * The old-source step. Library books are grouped by the plugin they came from
 * (and one bucket for books whose plugin is gone), with a real count per row.
 * No book payload is kept: the picker filters the same candidate list by key.
 */
data class MigrationLibrarySource(
    val key: String,
    val name: String,
    val unknown: Boolean,
    val language: String,
    val type: ContentType?,
    val bookCount: Int,
    // The plugin's icon, resolved against its baseUrl; null = monogram.
    val iconUrl: String? = null,
)

fun migrationLibrarySources(
    books: List<MigrationCandidateBook>,
    sources: List<CatalogSource>,
): List<MigrationLibrarySource> {
    val catalog = sources.associateBy { it.id }
    val known = books.filterNot { it.unknownSource }
        .groupBy { it.sourceId }
        .map { (sourceId, group) ->
            val installed = catalog[sourceId]
            MigrationLibrarySource(
                key = sourceId,
                name = installed?.name ?: group.first().sourceName,
                unknown = false,
                language = installed?.language.orEmpty(),
                type = installed?.type,
                bookCount = group.size,
                iconUrl = installed?.iconUrl,
            )
        }
        .sortedBy { it.name.lowercase() }
    val unknownCount = books.count { it.unknownSource }
    val unknown = if (unknownCount > 0) {
        listOf(
            MigrationLibrarySource(
                key = UnknownMigrationSourceKey,
                name = "",
                unknown = true,
                language = "",
                type = null,
                bookCount = unknownCount,
            ),
        )
    } else {
        emptyList()
    }
    return known + unknown
}

fun migrationBooksForSource(
    books: List<MigrationCandidateBook>,
    sourceKey: String?,
): List<MigrationCandidateBook> = when {
    sourceKey == null -> books
    sourceKey == UnknownMigrationSourceKey -> books.filter { it.unknownSource }
    else -> books.filter { !it.unknownSource && it.sourceId == sourceKey }
}
