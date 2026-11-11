package dev.mirutime.data.library.novela

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

data class NovelaBookRow(
    val url: String,
    val title: String,
    val coverUrl: String,
    val description: String,
    val genres: String,
    val rating: String,
    val status: String,
    val lastUpdateDate: String,
    val category: String,
    val inLibrary: Boolean,
    val contentType: String,
    val completed: Boolean,
    val addedAt: Long,
    val updatedAt: Long,
    val lastReadAt: Long,
    val chaptersListHash: String?,
    val lastReadChapter: String?,
)

data class NovelaChapterRow(
    val url: String,
    val bookUrl: String,
    val title: String,
    val position: Int,
    val read: Boolean,
    /** Sign-encoded: >= 0 is paragraph item index, < 0 is -(sentenceIndex + 1). */
    val lastReadPosition: Int,
    val lastReadOffset: Int,
    /** `Chapter.uploaded`: unix seconds, null when unknown. */
    val uploadedAt: Long?,
    val volume: String?,
)

data class NovelaChapterBodyRow(val chapterUrl: String, val body: String)

/** Thrown when the file is not a NoveLA database. */
class NotANovelaDatabaseException(message: String) : IllegalStateException(message)

/**
 * Read-only view of the `database.sqlite3` file inside a NoveLA backup.
 *
 * Only SELECTs and `PRAGMA table_info` run, so the file is never modified.
 * Older backups predate some columns; a missing column is projected as its
 * default instead of failing the whole import.
 */
class NovelaDatabase private constructor(
    private val connection: SQLiteConnection,
) : AutoCloseable {

    fun books(): List<NovelaBookRow> = connection.query(
        table = "Book",
        columns = BOOK_COLUMNS,
        orderBy = "url",
    ) { statement ->
        NovelaBookRow(
            url = statement.getText(0),
            title = statement.getText(1),
            coverUrl = statement.getText(2),
            description = statement.getText(3),
            genres = statement.getText(4),
            rating = statement.getText(5),
            status = statement.getText(6),
            lastUpdateDate = statement.getText(7),
            category = statement.getText(8),
            inLibrary = statement.getLong(9) != 0L,
            contentType = statement.getText(10),
            completed = statement.getLong(11) != 0L,
            addedAt = statement.getLong(12),
            updatedAt = statement.getLong(13),
            lastReadAt = statement.getLong(14),
            chaptersListHash = statement.textOrNull(15),
            lastReadChapter = statement.textOrNull(16),
        )
    }

    /** Chapters of one book, ordered by position then url. */
    fun chapters(bookUrl: String): List<NovelaChapterRow> = connection.query(
        table = "Chapter",
        columns = CHAPTER_COLUMNS,
        where = "bookUrl = ?",
        orderBy = "position, url",
        bind = { it.bindText(1, bookUrl) },
    ) { statement ->
        NovelaChapterRow(
            url = statement.getText(0),
            bookUrl = statement.getText(1),
            title = statement.getText(2),
            position = statement.getInt(3),
            read = statement.getLong(4) != 0L,
            lastReadPosition = statement.getInt(5),
            lastReadOffset = statement.getInt(6),
            uploadedAt = statement.longOrNull(7),
            volume = statement.textOrNull(8),
        )
    }

    /** Bodies for the given chapter urls; unknown urls are simply absent. */
    fun bodies(chapterUrls: List<String>): List<NovelaChapterBodyRow> {
        val urls = chapterUrls.filter { it.isNotBlank() }
        if (urls.isEmpty()) return emptyList()
        val rows = ArrayList<NovelaChapterBodyRow>(urls.size)
        for (chunk in urls.chunked(ID_CHUNK)) {
            val placeholders = chunk.joinToString(",") { "?" }
            connection.prepare("SELECT url, body FROM ChapterBody WHERE url IN ($placeholders)")
                .use { statement ->
                    chunk.forEachIndexed { index, url -> statement.bindText(index + 1, url) }
                    while (statement.step()) {
                        rows += NovelaChapterBodyRow(
                            chapterUrl = statement.getText(0),
                            body = statement.getText(1),
                        )
                    }
                }
        }
        return rows
    }

    override fun close() = connection.close()

    companion object {
        /**
         * Opens [path] read-only in spirit (SELECTs only). Throws
         * [NotANovelaDatabaseException] when the `Book` or `Chapter` table is
         * missing.
         */
        fun open(path: String): NovelaDatabase {
            val connection = BundledSQLiteDriver().open(path)
            val missing = REQUIRED_TABLES.filterNot { connection.hasTable(it) }
            if (missing.isNotEmpty()) {
                connection.close()
                throw NotANovelaDatabaseException(
                    "Not a NoveLA database, missing tables: ${missing.joinToString()}",
                )
            }
            return NovelaDatabase(connection)
        }
    }
}

/** Column name to the SQL literal used when an old backup lacks it. */
private typealias ColumnSpec = Pair<String, String>

private val BOOK_COLUMNS: List<ColumnSpec> = listOf(
    "url" to "''",
    "title" to "''",
    "coverImageUrl" to "''",
    "description" to "''",
    "genres" to "''",
    "rating" to "''",
    "status" to "''",
    "lastUpdateDate" to "''",
    "category" to "''",
    "inLibrary" to "0",
    "contentType" to "''",
    "completed" to "0",
    "addedToLibraryEpochTimeMilli" to "0",
    "lastUpdateEpochTimeMilli" to "0",
    "lastReadEpochTimeMilli" to "0",
    "chaptersListHash" to "NULL",
    "lastReadChapter" to "NULL",
)

private val CHAPTER_COLUMNS: List<ColumnSpec> = listOf(
    "url" to "''",
    "bookUrl" to "''",
    "title" to "''",
    "position" to "0",
    "read" to "0",
    "lastReadPosition" to "0",
    "lastReadOffset" to "0",
    "uploaded" to "NULL",
    "volume" to "NULL",
)

private val REQUIRED_TABLES = listOf("Book", "Chapter")

/** Mirrors `LibraryRepository.SqliteIdChunk`: bind-limit headroom. */
private const val ID_CHUNK = 900

private fun SQLiteConnection.hasTable(table: String): Boolean =
    prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { statement ->
        statement.bindText(1, table)
        statement.step()
    }

private fun SQLiteConnection.tableColumns(table: String): Set<String> = buildSet {
    prepare("SELECT name FROM pragma_table_info('$table')").use { statement ->
        while (statement.step()) add(statement.getText(0))
    }
}

/**
 * Selects [columns] from [table], substituting the fallback of columns
 * an older schema lacks. [where] and [orderBy] are static SQL; values are bound
 * through [bind].
 */
private inline fun <T> SQLiteConnection.query(
    table: String,
    columns: List<ColumnSpec>,
    where: String? = null,
    orderBy: String? = null,
    bind: (SQLiteStatement) -> Unit = {},
    read: (SQLiteStatement) -> T,
): List<T> {
    val present = tableColumns(table)
    val projection = columns.joinToString(", ") { (name, fallback) ->
        if (name in present) name else "$fallback AS $name"
    }
    val sql = buildString {
        append("SELECT ").append(projection).append(" FROM ").append(table)
        if (where != null) append(" WHERE ").append(where)
        if (orderBy != null) append(" ORDER BY ").append(orderBy)
    }
    return prepare(sql).use { statement ->
        bind(statement)
        val rows = ArrayList<T>()
        while (statement.step()) rows += read(statement)
        rows
    }
}

private fun SQLiteStatement.textOrNull(index: Int): String? =
    if (isNull(index)) null else getText(index)

private fun SQLiteStatement.longOrNull(index: Int): Long? =
    if (isNull(index)) null else getLong(index)
