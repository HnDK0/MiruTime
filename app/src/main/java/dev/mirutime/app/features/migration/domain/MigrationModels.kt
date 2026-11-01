package dev.mirutime.app.features.migration.domain

import dev.mirutime.app.features.book.domain.BookChapter
import dev.mirutime.app.features.finder.domain.CatalogBook
import dev.mirutime.app.features.finder.domain.CatalogSource
import dev.mirutime.app.features.reader.document.chapterKey
import dev.mirutime.data.library.BookMigrationEntity
import dev.mirutime.data.library.migration.BookMigrationChapter
import dev.mirutime.data.library.migration.BookMigrationTarget

/** One search answer paired with the source that produced it. */
data class MigrationSearchHit(
    val sourceId: String,
    val sourceName: String,
    val book: CatalogBook,
)

data class MigrationSearchResult(
    val hits: List<MigrationSearchHit>,
    val failedSources: List<String>,
)

/** Search answers for one installed plugin, in the order the search returned them. */
data class MigrationSourceHits(
    val sourceId: String,
    val sourceName: String,
    val hits: List<MigrationSearchHit>,
)

/** Book-page sheet searches for the library title, not a user-typed query. */
fun autoMigrationSearchQuery(candidate: MigrationCandidateBook): String =
    candidate.title.trim()

/** Book-page sheet searches every installed target source. */
fun allMigrationSourceIds(sources: List<CatalogSource>): Set<String> =
    sources.map { it.id }.toSet()

/** Manual source toggle; an empty set stays empty so Search can stay disabled. */
fun toggleMigrationSource(selected: Set<String>, sourceId: String): Set<String> =
    if (sourceId in selected) selected - sourceId else selected + sourceId

/** Manual search runs only on the sources the user kept selected. */
fun selectedMigrationSources(
    sources: List<CatalogSource>,
    selected: Set<String>,
): List<CatalogSource> = sources.filter { it.id in selected }

fun allMigrationSourcesSelected(
    selected: Set<String>,
    sources: List<CatalogSource>,
): Boolean = sources.isNotEmpty() && selected.containsAll(sources.map { it.id })

/**
 * A search card has no verified chapter count: CatalogBook.chapters is often
 * 0 or stale. Only the chosen target's refreshed chapter list is real, so the
 * card shows no number until that target loads.
 */
fun migrationVerifiedChapterCount(
    hit: MigrationSearchHit,
    selectedBookUrl: String?,
    selectedChapterCount: Int?,
): Int? = if (hit.book.bookUrl == selectedBookUrl) selectedChapterCount else null

/**
 * Groups hits under their source without changing the provider's order. Empty
 * input returns an empty list: an empty group would render a fake source row.
 */
fun groupMigrationHits(hits: List<MigrationSearchHit>): List<MigrationSourceHits> =
    hits.groupBy { it.sourceId }.map { (sourceId, sourceHits) ->
        MigrationSourceHits(
            sourceId = sourceId,
            sourceName = sourceHits.first().sourceName,
            hits = sourceHits,
        )
    }

/**
 * Target metadata + chapters fetched from the plugin, already paired with the
 * `BookEntity` the coordinator needs. No row is written before migrate().
 */
data class MigrationTargetDetails(
    val sourceId: String,
    val sourceName: String,
    val bookUrl: String,
    val title: String,
    val chapters: List<BookChapter>,
    val target: BookMigrationTarget,
)

data class MigrationHistoryGroup(
    val batchKey: String,
    val latest: BookMigrationEntity,
    val entries: List<BookMigrationEntity>,
)

/**
 * The plugin chapter list has no uploadedAt/numberRaw/volume, so only stable
 * fields are mapped. The URL falls back to the same key the reader persists,
 * which is what keeps the old/new chapter identity comparable.
 */
fun migrationChapters(bookUrl: String, chapters: List<BookChapter>): List<BookMigrationChapter> =
    chapters.map { chapter ->
        BookMigrationChapter(
            index = chapter.index,
            url = chapter.url ?: chapterKey(bookUrl, chapter.index),
            title = chapter.title,
        )
    }

/** Local EPUB/CBZ sources cannot be migration targets: they are not plugins. */
fun availableMigrationTargets(sources: List<CatalogSource>): List<CatalogSource> =
    sources.filter { !it.local }

/**
 * Mass migration match: exact normalized title wins, then a title containing
 * the other, then the provider's first hit. No synthetic matches.
 */
fun bestMigrationHit(
    hits: List<MigrationSearchHit>,
    title: String,
): MigrationSearchHit? {
    if (hits.isEmpty()) return null
    val wanted = normalizeMigrationTitle(title)
    hits.firstOrNull { normalizeMigrationTitle(it.book.title) == wanted }?.let { return it }
    return hits.firstOrNull {
        val candidate = normalizeMigrationTitle(it.book.title)
        wanted.isNotBlank() && candidate.isNotBlank() &&
            (candidate.contains(wanted) || wanted.contains(candidate))
    } ?: hits.first()
}

internal fun normalizeMigrationTitle(value: String): String =
    value.lowercase().replace(Regex("\\s+"), " ").trim()

/**
 * One row per batch (old book + target identity). A retry updates the same
 * row, so the history never shows duplicate finished runs for one batch.
 */
fun migrationHistoryGroups(rows: List<BookMigrationEntity>): List<MigrationHistoryGroup> =
    rows.groupBy { it.batchId ?: "id:${it.id}" }
        .map { (key, entries) ->
            MigrationHistoryGroup(
                batchKey = key,
                latest = entries.maxWithOrNull(compareBy({ it.createdAt }, { it.id })) ?: entries.first(),
                entries = entries,
            )
        }
        .sortedByDescending { it.latest.createdAt }

/** Single-book picker filter: title or source id, case-insensitive. */
fun filterMigrationBooks(
    books: List<MigrationCandidateBook>,
    query: String,
): List<MigrationCandidateBook> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return books
    return books.filter {
        it.title.lowercase().contains(needle) ||
            it.author.lowercase().contains(needle) ||
            it.sourceName.lowercase().contains(needle)
    }
}

/** Library row with the source name resolved for the picker. */
data class MigrationCandidateBook(
    val bookId: Long,
    val bookUrl: String,
    val title: String,
    val author: String,
    val sourceId: String,
    val sourceName: String,
    val unknownSource: Boolean,
    val chapterCount: Int,
    val coverUrl: String? = null,
    // Library-card data (same fields the library card reads). Migration is a
    // library surface, so it follows AppSettings layout/style and LibraryCard.
    val contentType: String = "Novel",
    val rating: Float = 0f,
    val readChapters: Int = 0,
    val hasUpdate: Boolean = false,
    val shelfId: String = "reading",
)
