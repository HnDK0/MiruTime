package dev.mirutime.data.library.migration

import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.ChapterEntity

/** A chapter of the target source, supplied by the caller (no search here). */
data class BookMigrationChapter(
    val index: Int,
    val url: String,
    val title: String,
    val volume: Int? = null,
    val uploadedAt: Long? = null,
    val numberRaw: String? = null,
)

/** Target book metadata + its full chapter list from the new source. */
data class BookMigrationTarget(
    val book: BookEntity,
    val chapters: List<BookMigrationChapter>,
)

/**
 * MiruTime defaults deleteOldBook=false: the old book stays until the user
 * explicitly asks for removal, unlike NoveLA's destructive default.
 */
data class BookMigrationOptions(
    val transferProgress: Boolean = true,
    val transferBodies: Boolean = true,
    val transferTranslations: Boolean = true,
    val deleteOldBook: Boolean = false,
)

data class BookMigrationChapterPair(
    val old: ChapterEntity,
    val new: BookMigrationChapter,
)

/** Read-only result of matching the old chapter list against the target. */
data class BookMigrationPlan(
    val oldBook: BookEntity,
    val target: BookMigrationTarget,
    val pairs: List<BookMigrationChapterPair>,
    val unmatchedOld: List<ChapterEntity>,
    val unmatchedNew: List<BookMigrationChapter>,
    val score: Float,
) {
    val chaptersTotal: Int get() = pairs.size + unmatchedOld.size
    val chaptersMatched: Int get() = pairs.size
}

data class BookMigrationResult(
    val migrationId: Long,
    val oldBookId: Long,
    val newBookId: Long,
    val chaptersTotal: Int,
    val chaptersMatched: Int,
    val chaptersWithProgress: Int,
    val unmatchedOld: List<Long>,
    val status: String,
    val bookCreated: Boolean,
    val oldDeleted: Boolean,
)

internal data class MigrationMatchCandidate(
    val key: String,
    val title: String,
    val volume: Int? = null,
)

internal data class MigrationMatchPair(
    val old: MigrationMatchCandidate,
    val new: MigrationMatchCandidate,
)

internal data class MigrationMatchResult(
    val matched: List<MigrationMatchPair>,
    val unmatchedOld: List<MigrationMatchCandidate>,
    val unmatchedNew: List<MigrationMatchCandidate>,
    val score: Float,
)

/** Platform actual delegates to the single core-reader-model matcher. */
internal expect fun matchMigrationChapters(
    old: List<MigrationMatchCandidate>,
    new: List<MigrationMatchCandidate>,
): MigrationMatchResult

sealed class BookMigrationOutcome<out T> {
    data class Success<T>(val value: T) : BookMigrationOutcome<T>()
    data class Failure(val error: String, val cause: Throwable? = null) :
        BookMigrationOutcome<Nothing>()
}

/** File seam implemented by the app's DownloadFileStore adapter. */
interface BookMigrationFileStore {
    suspend fun readText(path: String): String?
    suspend fun writeText(bookId: Long, chapterId: Long, text: String): MigrationStoredText
    suspend fun readBytes(path: String): ByteArray?
    suspend fun writePage(
        bookId: Long,
        chapterId: Long,
        index: Int,
        sourcePath: String,
        bytes: ByteArray,
    ): String
    suspend fun pagePaths(pagePathsJson: String): List<String>
    suspend fun encodePagePaths(paths: List<String>): String
    suspend fun deleteText(bookId: Long, chapterId: Long)
    suspend fun deletePages(bookId: Long, chapterId: Long)
    suspend fun deleteBookFiles(bookId: Long)
}

data class MigrationStoredText(
    val path: String,
    val bytes: Long,
    val sha256: String,
)

/** Per-novel settings seam; app implementation moves URL-keyed settings. */
interface BookMigrationSettingsStore {
    suspend fun copyNovelSettings(oldBookUrl: String, newBookUrl: String)
    suspend fun deleteNovelSettings(bookUrl: String)
}

/**
 * Pure planner shared by the preflight preview and the coordinator. It never
 * writes to the database; the target book is only created after this returns.
 */
class BookMigrationPlanner {
    fun plan(
        oldBook: BookEntity,
        oldChapters: List<ChapterEntity>,
        target: BookMigrationTarget,
    ): BookMigrationPlan {
        val oldByKey = oldChapters.associateBy { it.chapterId.toString() }
        val newByKey = target.chapters.mapIndexed { index, chapter -> index.toString() to chapter }
            .toMap()

        val result = matchMigrationChapters(
            old = oldChapters.map { chapter ->
                MigrationMatchCandidate(
                    key = chapter.chapterId.toString(),
                    title = chapter.title,
                    volume = chapter.volume?.toIntOrNull(),
                )
            },
            new = target.chapters.mapIndexed { index, chapter ->
                MigrationMatchCandidate(
                    key = index.toString(),
                    title = chapter.title,
                    volume = chapter.volume,
                )
            },
        )

        return BookMigrationPlan(
            oldBook = oldBook,
            target = target,
            pairs = result.matched.mapNotNull { pair ->
                val old = oldByKey[pair.old.key] ?: return@mapNotNull null
                val new = newByKey[pair.new.key] ?: return@mapNotNull null
                BookMigrationChapterPair(old = old, new = new)
            },
            unmatchedOld = result.unmatchedOld.mapNotNull { oldByKey[it.key] },
            unmatchedNew = result.unmatchedNew.mapNotNull { newByKey[it.key] },
            score = result.score,
        )
    }
}

internal const val MigrationWriteChunk = 200
internal const val MigrationIdChunk = 900
internal const val MigrationStatusRunning = "running"
internal const val MigrationStatusCompleted = "completed"
internal const val MigrationStatusPartial = "partial"
internal const val MigrationStatusFailed = "failed"
internal const val MigrationStatusCancelled = "cancelled"
