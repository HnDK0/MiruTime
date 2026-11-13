package dev.mirutime.data.library

import androidx.room3.Embedded
import androidx.room3.RoomRawQuery
import androidx.sqlite.SQLiteStatement

/** Library list sorts. LastUpdate/Added intentionally alias lastRead. */
enum class LibrarySort {
    LastRead,
    LastUpdate,
    Added,
    Title,
    Unread,
}

enum class LibrarySortDirection { Asc, Desc }

/**
 * Stored contentType values are the app's current exact names; an unknown
 * value still counts as Novel in the mapper, but filtering matches the stored
 * string so a filter never silently widens the result.
 */
enum class LibraryContentType(val stored: String) {
    Novel("Novel"),
    Manga("Manga"),
    Video("Video"),
    Audio("Audio"),
    ;

    companion object {
        fun fromStored(raw: String): LibraryContentType =
            entries.firstOrNull { it.stored == raw } ?: Novel
    }
}

/** SQL-level filters; all values are bound, never interpolated. */
data class LibraryPageFilters(
    val shelfId: String? = null,
    val sources: Set<String> = emptySet(),
    val contentType: LibraryContentType? = null,
    val genres: Set<String> = emptySet(),
)

/**
 * Opaque page position. Callers should pass back exactly what the previous
 * page returned; [sort] and [direction] guard against paging after a change.
 */
data class LibraryPageCursor(
    val sort: LibrarySort,
    val direction: LibrarySortDirection,
    val bookId: Long,
    val lastReadAt: Long? = null,
    val addedAt: Long? = null,
    val title: String? = null,
    val unreadCount: Int? = null,
)

data class LibraryPage(
    val books: List<BookEntity>,
    val nextCursor: LibraryPageCursor?,
    val hasMore: Boolean,
    val total: Int,
)

data class LibraryFacets(
    val sources: List<String>,
    val genres: List<String>,
)

data class ShelfSummary(
    val shelfId: String,
    val bookCount: Int,
    val coverUrl: String?,
    val coverTitle: String?,
    val coverBookId: Long? = null,
)

public data class LibraryPageRow(
    @Embedded val book: BookEntity,
    val unreadCount: Int,
)

public data class GenreNameRow(val name: String)

public data class ShelfSummaryRow(
    val shelfId: String,
    val bookCount: Int,
    val coverUrl: String?,
    val coverTitle: String?,
    val coverBookId: Long?,
)

internal data class LibraryQueryPlan(
    val sql: String,
    val args: List<Any?>,
)

internal const val LibraryPageSize = 60

// Timestamps are epoch millis, so -1 sorts before every real value. This keeps
// unread books last in DESC and first in ASC, matching the current UI order.
private const val LastReadKey = "COALESCE(b.lastReadAt, -1)"

private const val UnreadExpr =
    "MAX(b.totalChapters - (SELECT COUNT(*) FROM chapters c " +
        "WHERE c.bookId = b.bookId AND c.read = 1 AND c.removedAt IS NULL), 0)"

/**
 * Keyset page query. The base order is the current visible order per sort:
 * last-read DESC with addedAt/bookId ties (LastUpdate/Added alias it), title
 * ASC, unread ASC. Asc/Desc either keeps or reverses that base order, exactly
 * like LibraryScreen's comparator. Search does not use this path.
 */
internal fun libraryPageQuery(
    limit: Int,
    cursor: LibraryPageCursor?,
    filters: LibraryPageFilters,
    sort: LibrarySort,
    direction: LibrarySortDirection,
): LibraryQueryPlan {
    val pageSize = limit.coerceAtLeast(1)
    val where = WhereParts()
    where.addFilters(filters)
    if (cursor != null && cursor.sort == sort && cursor.direction == direction) {
        where.addCursor(sort, direction, cursor)
    }
    val unreadSelect = if (sort == LibrarySort.Unread) {
        "$UnreadExpr AS unreadCount"
    } else {
        "0 AS unreadCount"
    }
    val sql = "SELECT b.*, $unreadSelect FROM books b WHERE ${where.sql()} " +
        "ORDER BY ${orderBy(sort, direction)} LIMIT ?"
    return LibraryQueryPlan(sql, where.args + (pageSize + 1))
}

internal fun libraryCountQuery(filters: LibraryPageFilters): LibraryQueryPlan {
    val where = WhereParts()
    where.addFilters(filters)
    return LibraryQueryPlan("SELECT COUNT(*) FROM books b WHERE ${where.sql()}", where.args)
}

internal fun libraryUrlsQuery(filters: LibraryPageFilters): LibraryQueryPlan {
    val where = WhereParts()
    where.addFilters(filters)
    return LibraryQueryPlan(
        "SELECT DISTINCT b.url FROM books b WHERE ${where.sql()} ORDER BY b.url",
        where.args,
    )
}

internal fun libraryGenresQuery(): LibraryQueryPlan = LibraryQueryPlan(
    """
    SELECT name FROM (
        SELECT t.name AS name
        FROM book_tags bt
        INNER JOIN tags t ON t.tagId = bt.tagId
        INNER JOIN books b ON b.bookId = bt.bookId
        WHERE b.inLibrary = 1
        UNION
        SELECT je.value AS name
        FROM books b,
            json_each(CASE WHEN json_valid(b.genresJson) THEN b.genresJson ELSE '[]' END) je
        WHERE b.inLibrary = 1
    )
    WHERE name IS NOT NULL AND name != ''
    ORDER BY name
    """.trimIndent(),
    emptyList(),
)

internal fun LibraryQueryPlan.toRawQuery(): RoomRawQuery = RoomRawQuery(sql) { statement ->
    args.forEachIndexed { index, value -> statement.bind(index + 1, value) }
}

internal fun LibraryPageRow.toCursor(
    sort: LibrarySort,
    direction: LibrarySortDirection,
): LibraryPageCursor = when (sort) {
    LibrarySort.LastRead, LibrarySort.LastUpdate, LibrarySort.Added -> LibraryPageCursor(
        sort = sort,
        direction = direction,
        bookId = book.bookId,
        lastReadAt = book.lastReadAt,
        addedAt = book.addedAt,
    )

    LibrarySort.Title -> LibraryPageCursor(
        sort = sort,
        direction = direction,
        bookId = book.bookId,
        title = book.title,
    )

    LibrarySort.Unread -> LibraryPageCursor(
        sort = sort,
        direction = direction,
        bookId = book.bookId,
        unreadCount = unreadCount,
    )
}

private fun SQLiteStatement.bind(index: Int, value: Any?) {
    when (value) {
        null -> bindNull(index)
        is String -> bindText(index, value)
        is Long -> bindLong(index, value)
        is Int -> bindLong(index, value.toLong())
        is Boolean -> bindLong(index, if (value) 1L else 0L)
        else -> error("Unsupported library page bind: ${value::class}")
    }
}

private class WhereParts {
    private val clauses = mutableListOf<String>()
    val args = mutableListOf<Any?>()

    private fun bind(value: Any?): String {
        args += value
        return "?"
    }

    private fun bindAll(values: Collection<Any?>): String = values.joinToString(", ") { bind(it) }

    fun sql(): String = clauses.joinToString(" AND ")

    fun addFilters(filters: LibraryPageFilters) {
        clauses += "b.inLibrary = 1"
        filters.shelfId?.takeIf { it.isNotBlank() }?.let { shelfId ->
            clauses += "b.shelfId = ${bind(shelfId)}"
        }
        if (filters.sources.isNotEmpty()) {
            val sources = filters.sources.sorted()
            clauses += "b.installId IN (${bindAll(sources)})"
        }
        filters.contentType?.let { type ->
            // Matches the mapper: valueOf() failure falls back to Novel, so a
            // row with an unknown stored value is still a Novel filter hit.
            clauses += when (type) {
                LibraryContentType.Novel ->
                    "(b.contentType = 'Novel' OR b.contentType NOT IN ('Manga', 'Video', 'Audio'))"
                LibraryContentType.Manga, LibraryContentType.Video, LibraryContentType.Audio ->
                    "b.contentType = ${bind(type.stored)}"
            }
        }
        if (filters.genres.isNotEmpty()) {
            val genres = filters.genres.sorted()
            // Tags win in the mapper, but the filter accepts either source so
            // a genresJson-only book is not hidden by an empty tag row.
            val tagSlots = bindAll(genres)
            val jsonSlots = bindAll(genres)
            clauses += "(" +
                "EXISTS (SELECT 1 FROM book_tags bt " +
                "INNER JOIN tags t ON t.tagId = bt.tagId " +
                "WHERE bt.bookId = b.bookId AND t.name IN ($tagSlots)) OR " +
                "EXISTS (SELECT 1 FROM json_each(CASE WHEN json_valid(b.genresJson) " +
                "THEN b.genresJson ELSE '[]' END) je WHERE je.value IN ($jsonSlots))" +
                ")"
        }
    }

    fun addCursor(
        sort: LibrarySort,
        direction: LibrarySortDirection,
        cursor: LibraryPageCursor,
    ) {
        val descending = direction == LibrarySortDirection.Desc
        when (sort) {
            LibrarySort.LastRead, LibrarySort.LastUpdate, LibrarySort.Added -> {
                val key = cursor.lastReadAt ?: -1L
                val addedAt = cursor.addedAt ?: 0L
                if (descending) {
                    clauses += "(" +
                        "$LastReadKey < ${bind(key)} OR " +
                        "($LastReadKey = ${bind(key)} AND " +
                        "(b.addedAt < ${bind(addedAt)} OR " +
                        "(b.addedAt = ${bind(addedAt)} AND b.bookId > ${bind(cursor.bookId)})))" +
                        ")"
                } else {
                    clauses += "(" +
                        "$LastReadKey > ${bind(key)} OR " +
                        "($LastReadKey = ${bind(key)} AND " +
                        "(b.addedAt > ${bind(addedAt)} OR " +
                        "(b.addedAt = ${bind(addedAt)} AND b.bookId < ${bind(cursor.bookId)})))" +
                        ")"
                }
            }

            LibrarySort.Title -> {
                val title = cursor.title.orEmpty()
                if (descending) {
                    clauses += "(b.title < ${bind(title)} OR " +
                        "(b.title = ${bind(title)} AND b.bookId < ${bind(cursor.bookId)}))"
                } else {
                    clauses += "(b.title > ${bind(title)} OR " +
                        "(b.title = ${bind(title)} AND b.bookId > ${bind(cursor.bookId)}))"
                }
            }

            LibrarySort.Unread -> {
                val unread = cursor.unreadCount ?: 0
                if (descending) {
                    clauses += "($UnreadExpr < ${bind(unread)} OR " +
                        "($UnreadExpr = ${bind(unread)} AND b.bookId < ${bind(cursor.bookId)}))"
                } else {
                    clauses += "($UnreadExpr > ${bind(unread)} OR " +
                        "($UnreadExpr = ${bind(unread)} AND b.bookId > ${bind(cursor.bookId)}))"
                }
            }
        }
    }
}

private fun orderBy(sort: LibrarySort, direction: LibrarySortDirection): String {
    val descending = direction == LibrarySortDirection.Desc
    return when (sort) {
        LibrarySort.LastRead, LibrarySort.LastUpdate, LibrarySort.Added ->
            if (descending) {
                "$LastReadKey DESC, b.addedAt DESC, b.bookId ASC"
            } else {
                "$LastReadKey ASC, b.addedAt ASC, b.bookId DESC"
            }

        LibrarySort.Title ->
            if (descending) {
                "b.title DESC, b.bookId DESC"
            } else {
                "b.title ASC, b.bookId ASC"
            }

        LibrarySort.Unread ->
            if (descending) {
                "$UnreadExpr DESC, b.bookId DESC"
            } else {
                "$UnreadExpr ASC, b.bookId ASC"
            }
    }
}
