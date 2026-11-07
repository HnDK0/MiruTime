package dev.mirutime.data.library

// One persisted file candidate. `bytes` is the size recorded in the DB, used
// as the fallback when the file itself is gone or unreadable.
data class StoredFileBytes(val path: String, val bytes: Long)

// Batch read state for list screens: one GROUP BY query instead of a query per
// book. maxReadIndex preserves the old "lastChapterIndex ?: max(read idx)"
// continue-label fallback.
data class BookReadStats(
    val readCount: Int,
    val maxReadIndex: Int,
)

data class BookReadStatsRow(
    val bookId: Long,
    val readCount: Int,
    val maxReadIndex: Int,
)

// One row per non-removed chapter. `downloaded` is the persisted flag; bodies
// and pages are separate tables, so a real local payload counts even when the
// flag was never written.
data class ChapterStatusRow(
    val chapterId: Long,
    val idx: Int,
    val url: String,
    val read: Boolean,
    val downloaded: Boolean,
    val hasBody: Boolean,
    val hasPages: Boolean,
    // Body bytes + page bytes from the persisted rows; 0 = not stored.
    val sizeBytes: Long,
)

data class TranslatedChapterRow(
    val chapterId: Long,
    val idx: Int,
    val url: String,
)

data class LastReadChapterRow(
    val url: String,
    val idx: Int,
)

/**
 * Batch chapter state for one book: read/downloaded/translated/last plus the
 * last-read chapter. Built from a fixed query set, never one query per row.
 */
data class ChapterStatusSnapshot(
    val bookId: Long,
    val chapterCount: Int,
    val readIndexes: Set<Int>,
    val downloadedIndexes: Set<Int>,
    val downloadedUrls: Set<String>,
    val translatedIndexes: Set<Int>,
    val translatedUrls: Set<String>,
    val lastIndex: Int?,
    val lastChapterId: Long?,
    val lastReadIndex: Int?,
    val lastReadUrl: String?,
    // Active/paused/failed queue rows by chapter index, so the chapter list
    // can show real progress without a second per-row query.
    val downloads: Map<Int, ChapterDownloadState> = emptyMap(),
    // Latest translation status per chapter index (PENDING/FAILED/COMPLETE),
    // so "download + translate" progress is visible without a per-row query.
    val translationStates: Map<Int, String> = emptyMap(),
    // Persisted body + page bytes by chapter index. Missing/0 = unknown.
    val sizeBytes: Map<Int, Long> = emptyMap(),
)

data class ChapterDownloadState(
    val state: String,
    val progress: Double,
)

// One chapter_translations row joined with its chapter. Ordered newest first;
// the first row per chapter is the current queue/translation state.
data class ChapterTranslationQueueRow(
    val translationId: Long,
    val chapterId: Long,
    val idx: Int,
    val url: String,
    val bookId: Long,
    val source: String,
    val target: String,
    val provider: String,
    val promptHash: String,
    val sourceHash: String,
    val pipelineVersion: String,
    val status: String,
    val updatedAt: Long,
)

data class BookTagNameRow(
    val bookId: Long,
    val name: String,
)

// chapter_pages stores its file paths as a JSON array; parsing belongs to the
// caller that can touch the filesystem.
data class ChapterPageFiles(val pathsJson: String, val totalBytes: Long)

// Aggregate of one Room storage snapshot. Every `*Bytes` field comes from a
// COALESCE(SUM(...), 0) query, so an empty database reports 0, not null.
// Paths are returned alongside the sums so the app can prefer real file sizes.
data class LibraryStorageUsage(
    val chapterBodyBytes: Long,
    val chapterPageBytes: Long,
    val coverBytes: Long,
    val sourceIconBytes: Long,
    val localNovelBytes: Long,
    val localMangaBytes: Long,
    val chapterBodyFiles: List<StoredFileBytes>,
    val chapterPageFiles: List<ChapterPageFiles>,
    val coverFiles: List<StoredFileBytes>,
    val sourceIconFiles: List<StoredFileBytes>,
    val localNovelFiles: List<StoredFileBytes>,
    val localMangaFiles: List<StoredFileBytes>,
    val bookCount: Int,
    val chapterCount: Int,
    val coverCount: Int,
    val localFileCount: Int,
    val clearableChapterBodyCount: Int,
    val clearableChapterPageCount: Int,
    val clearableCoverCount: Int,
    val sourceIconCount: Int,
) {
    val localBookBytes: Long get() = localNovelBytes + localMangaBytes
    val clearableImageCount: Int get() = clearableCoverCount + sourceIconCount
}

// One library book that owns local files (EPUB/FB2/CBZ/folder). The backup
// lane must include these even though they are not cache. Only books with
// inLibrary = 1 and a local install id are returned.
data class LocalBackupBook(
    val bookId: Long,
    val installId: String,
    val title: String,
    val inLibrary: Boolean,
    val copyPath: String?,
    val format: String,
    val coverLocalPath: String?,
    val bodyPaths: List<String>,
    val pagePathsJson: List<String>,
)

// Room projections; aliases match the query columns.
data class LocalBackupBookRow(
    val bookId: Long,
    val installId: String,
    val title: String,
    val inLibrary: Boolean,
    val copyPath: String?,
    val format: String,
    val coverLocalPath: String?,
)

data class LocalBackupPathRow(val bookId: Long, val path: String)
// Every in-library cover that has a local file. Unlike LocalBackupBook this
// deliberately includes plugin books: a user-override cover is user data, not
// a cache entry, and must survive a clean restore.
data class LocalBackupCover(
    val bookId: Long,
    val installId: String,
    val title: String,
    val coverLocalPath: String,
    val isUserOverride: Boolean,
)

data class LocalBackupCoverRow(
    val bookId: Long,
    val installId: String,
    val title: String,
    val coverLocalPath: String?,
    val isUserOverride: Boolean,
)


data class LocalBackupPageRow(val bookId: Long, val pathsJson: String)

// One joined row from the pre-delete file snapshot. A book with several
// chapters/bodies/pages yields several rows; the repository groups them.
// pagePathsJson is parsed by the app layer, which owns the filesystem policy.
data class BookFileRow(
    val bookId: Long,
    val coverPath: String?,
    val copyPath: String?,
    val parserVersion: Int?,
    val bodyPath: String?,
    val pagePathsJson: String?,
)

// Grouped files owned by one book, captured before the DB rows are deleted.
data class LibraryBookFiles(
    val bookId: Long,
    val coverPath: String?,
    val copyPath: String?,
    // Folder imports (parserVersion 2) own the whole copy directory; single
    // file imports own the file plus its content-addressed book directory.
    val folderCopy: Boolean,
    val bodyPaths: List<String>,
    val pagePathsJson: List<String>,
)

data class BulkBookResult(
    val succeeded: Set<String>,
    val missing: Set<String>,
)

data class BulkDeleteResult(
    val deleted: Set<String>,
    val missing: Set<String>,
    val files: List<LibraryBookFiles>,
)

// Expected by Room's aggregate projection.
data class StorageTotals(
    val chapterBodyBytes: Long,
    val chapterPageBytes: Long,
    val coverBytes: Long,
    val sourceIconBytes: Long,
    val localNovelBytes: Long,
    val localMangaBytes: Long,
    val bookCount: Int,
    val chapterCount: Int,
    val coverCount: Int,
    val localFileCount: Int,
    val clearableChapterBodyCount: Int,
    val clearableChapterPageCount: Int,
    val clearableCoverCount: Int,
    val sourceIconCount: Int,
)
