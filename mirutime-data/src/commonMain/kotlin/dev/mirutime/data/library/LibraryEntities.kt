package dev.mirutime.data.library

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Fts5
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Database v2. Identity is stable: [BookEntity.bookId] and
 * [ChapterEntity.chapterId] survive base-URL/domain moves. URLs are mutable
 * network fields, not keys. Progress, sessions and downloads reference the
 * surrogate ids; rebasing only rewrites URLs.
 */
@Entity(tableName = "plugin_installs")
data class PluginInstallEntity(
    @PrimaryKey val installId: String,
    val pluginId: String,
    val baseUrlDefault: String,
    val baseUrlOverride: String? = null,
    val baseUrlEffective: String,
    val version: String = "",
    val enabled: Boolean = true,
    val installedAt: Long,
    val removedAt: Long? = null,
)

@Entity(tableName = "shelves")
data class ShelfEntity(
    @PrimaryKey val id: String,
    val label: String,
    val excludeFromUpdates: Boolean = false,
    val position: Int = 0,
)

@Entity(
    tableName = "books",
    indices = [
        Index("installId"),
        Index("shelfId"),
        Index("lastReadAt"),
        Index("inLibrary"),
        Index(value = ["installId", "url"], unique = true),
    ],
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val bookId: Long = 0,
    val installId: String,
    val url: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val contentType: String,
    val rating: Double,
    val shelfId: String,
    val totalChapters: Int,
    val inLibrary: Boolean,
    val addedAt: Long,
    val updatedAt: Long,
    val lastReadAt: Long?,
    val lastChapterId: Long?,
    // Denormalized for cheap list rendering; chapterId is the identity.
    val lastChapterIndex: Int?,
    val chapterListHash: String?,
    val lastUpdateAt: Long?,
    val genresJson: String,
    // v4 metadata. Existing fields above stay as compatibility shims until
    // every screen moves to the canonical/raw pair.
    val firstSeenAt: Long = 0,
    val addedToLibraryAt: Long? = null,
    val subtitle: String? = null,
    val altTitlesJson: String = "[]",
    val authorRaw: String = "",
    val authorsJson: String = "[]",
    val description: String = "",
    val originLanguage: String? = null,
    val ratingRaw: String = "",
    val statusRaw: String = "",
    val statusNormalized: String = "UNKNOWN",
    val lastUpdatedRaw: String = "",
    val lastUpdatedAt: Long? = null,
    // Update detection: chapters found above the last known count.
    val newChapters: Int = 0,
    val lastUpdateCheckAt: Long? = null,
    val extrasJson: String = "{}",
    val metadataSource: String = "PLUGIN",
    val metadataUpdatedAt: Long = 0,
    val metadataHash: String = "",
) {
    val bookUrl: String get() = url
}

/**
 * v6 standalone FTS5 index. rowid is [BookEntity.bookId]; the row is written
 * by [LibraryRepository] on book/tag/translation changes, so no external
 * content or sync triggers are needed. Search joins it back to `books` to
 * filter the library and keep the returned rows authoritative.
 */
@Fts5(tokenizer = "unicode61")
@Entity(tableName = "book_search")
data class BookSearchEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val bookId: Long,
    val title: String,
    val author: String,
    val tags: String,
    val description: String,
    val translatedTitle: String,
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["bookId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("bookId"),
        Index(value = ["bookId", "url"], unique = true),
        Index(value = ["bookId", "idx"]),
        // Covers readChapterStats/readChapterStatsFor: read + removedAt filter,
        // then bookId grouping and MAX(idx), without touching the table.
        Index(value = ["read", "removedAt", "bookId", "idx"]),
    ],
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val chapterId: Long = 0,
    val bookId: Long,
    val idx: Int,
    val url: String,
    val title: String,
    val hash: String? = null,
    val read: Boolean = false,
    val readAt: Long? = null,
    val downloaded: Boolean = false,
    val removedAt: Long? = null,
    val updatedAt: Long,
    val volume: String? = null,
    val uploadedAt: Long? = null,
    val numberRaw: String? = null,
)

@Entity(
    tableName = "chapter_bodies",
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["chapterId"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChapterBodyEntity(
    @PrimaryKey val chapterId: Long,
    val format: String,
    val path: String,
    val bytes: Long,
    val sha256: String,
    val fetchedAt: Long,
)

@Entity(
    tableName = "progress",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["bookId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ProgressEntity(
    @PrimaryKey val bookId: Long,
    val chapterId: Long,
    val blockIndex: Int,
    val charOffset: Int,
    val sentenceIndex: Int?,
    val offsetPx: Int,
    val split: Boolean,
    val seq: Long,
    val updatedAt: Long,
)

/**
 * Position inside one video chapter. Kept separate from [ProgressEntity]
 * (text reader state) so a video book never overwrites text progress and the
 * text reader never interprets milliseconds as block offsets.
 */
@Entity(
    tableName = "video_progress",
    primaryKeys = ["bookId", "chapterId"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["bookId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["chapterId"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId")],
)
data class VideoProgressEntity(
    val bookId: Long,
    val chapterId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "reading_sessions",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["bookId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("startedAt"), Index("bookId"), Index("kind"), Index(value = ["sessionKey"], unique = true)],
)
data class ReadingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterId: Long?,
    val startedAt: Long,
    val endedAt: Long?,
    val durationMs: Long,
    val kind: String,
    val pagesRead: Int? = null,
    // Idempotency key for the TTS/reading writer. Nullable for v3 rows and
    // tests; SQLite allows multiple NULLs under a UNIQUE index.
    val sessionKey: String? = null,
)

@Entity(
    tableName = "download_tasks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["bookId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["chapterId"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("state"),
        Index("bookId"),
        Index(value = ["bookId", "chapterId", "kind"], unique = true),
    ],
)
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterId: Long,
    val kind: String,
    val state: String,
    val progress: Double = 0.0,
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)


// --- v4 metadata / local / content / translation / highlight / TTS ----------

@Entity(
    tableName = "book_covers",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["bookId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("retryAt")],
)
data class BookCoverEntity(
    @PrimaryKey val bookId: Long,
    val remoteUrl: String?,
    val localPath: String? = null,
    val sha256: String? = null,
    val bytes: Long = 0,
    val referer: String? = null,
    val isUserOverride: Boolean = false,
    val fetchedAt: Long? = null,
    val checkedAt: Long? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val lastError: String? = null,
    val retryAt: Long? = null,
)

@Entity(
    tableName = "source_icons",
    primaryKeys = ["installId", "version", "urlHash"],
)
data class SourceIconEntity(
    val installId: String,
    val version: String = "",
    val urlHash: String,
    val localPath: String? = null,
    val sha256: String? = null,
    val bytes: Long = 0,
    val fetchedAt: Long? = null,
    val checkedAt: Long? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val lastError: String? = null,
    val retryAt: Long? = null,
)

@Entity(
    tableName = "tags",
    indices = [Index(value = ["normalizedName"], unique = true)],
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val tagId: Long = 0,
    val name: String,
    val normalizedName: String,
    val kind: String,
    val createdAt: Long,
)

@Entity(
    tableName = "book_tags",
    primaryKeys = ["bookId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["bookId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["tagId"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tagId")],
)
data class BookTagEntity(
    val bookId: Long,
    val tagId: Long,
    val source: String = "PLUGIN",
    val position: Int = 0,
)

@Entity(
    tableName = "highlights",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["bookId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["chapterId"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId", "chapterId"), Index("chapterId", "blockIndex")],
)
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterId: Long,
    val blockIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val textKind: String,
    val translationKey: String? = null,
    val colorArgb: Long,
    val note: String? = null,
    val sourceHash: String,
    val parserVersion: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "chapter_pages",
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["chapterId"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChapterPageEntity(
    @PrimaryKey val chapterId: Long,
    val format: String,
    val pagePathsJson: String,
    val totalBytes: Long = 0,
    val quality: String? = null,
    val parserVersion: Int = 1,
)

@Entity(
    tableName = "local_book_files",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["bookId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // Live SAF catalogue lookup by URI and duplicate detection by content hash.
    // The origin pair indexes catalogue membership without scanning the table.
    indices = [
        Index("uri"),
        Index("sha256"),
        Index("origin_catalog_url", "origin_entry_url"),
    ],
)
data class LocalBookFileEntity(
    @PrimaryKey val bookId: Long,
    val uri: String,
    val displayName: String,
    val format: String,
    val mime: String?,
    val sizeBytes: Long,
    val sha256: String,
    val copyPath: String? = null,
    val parserVersion: Int = 1,
    val importedAt: Long,
    // Catalogue provenance for an OPDS import; empty for a device/SAF file.
    // `uri` for such a row is the acquisition URL, not the deleted temp path.
    @ColumnInfo(name = "origin_catalog_url", defaultValue = "''")
    val originCatalogUrl: String = "",
    @ColumnInfo(name = "origin_entry_url", defaultValue = "''")
    val originEntryUrl: String = "",
    @ColumnInfo(name = "origin_catalog_name", defaultValue = "''")
    val originCatalogName: String = "",
)

/**
 * Saved OPDS catalogs. Credentials are deliberately not here: this table is
 * part of the backup export, the login/password pair lives in
 * OpdsCredentialStore under noBackupFilesDir.
 */
@Entity(tableName = "opds_catalogs")
data class OpdsCatalogEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val addedAt: Long,
)

/**
 * One rendered catalogue entry: enough to show the book page and add it without
 * re-fetching the feed. Keyed by the entry URL the catalogue itself uses, so a
 * book re-added from a different feed page still lands on one row.
 */
@Entity(
    tableName = "opds_entries",
    primaryKeys = ["catalogUrl", "entryUrl"],
    indices = [Index("catalogUrl", "updatedAt")],
)
data class OpdsEntryEntity(
    val catalogUrl: String,
    val entryUrl: String,
    /** Feed page this entry was seen in: the only safe place to re-resolve it. */
    @ColumnInfo(name = "feed_url", defaultValue = "''")
    val feedUrl: String = "",
    val title: String,
    val author: String,
    val description: String,
    val coverUrl: String?,
    /** Acquisition options as JSON; see OpdsEntryOptions. */
    val formatsJson: String,
    val updatedAt: Long,
)

/** Query projection: one imported book that came from a catalogue entry. */
data class LocalBookOriginRow(
    val originEntryUrl: String,
    val installId: String,
    val url: String,
)

/** Query projection: the catalogue display name stored on an imported file. */
data class LocalBookOriginNameRow(
    val bookId: Long,
    val originCatalogName: String,
)

@Entity(tableName = "import_jobs")
data class ImportJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val status: String,
    val progress: Double = 0.0,
    val error: String? = null,
    val createdAt: Long,
    val finishedAt: Long? = null,
)

@Entity(
    tableName = "chapter_translations",
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["chapterId"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["chapterId", "source", "target", "provider", "promptHash", "pipelineVersion"],
            unique = true,
        ),
    ],
)
data class ChapterTranslationEntity(
    @PrimaryKey(autoGenerate = true) val translationId: Long = 0,
    val chapterId: Long,
    // v7: the translation identity is language-pair aware. Defaults keep old
    // callers source-less instead of forcing a fake language.
    val source: String = "",
    val target: String,
    val provider: String,
    val model: String?,
    val promptHash: String,
    val sourceHash: String,
    val pipelineVersion: String,
    val status: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "translation_blocks",
    primaryKeys = ["translationId", "blockIndex"],
    foreignKeys = [
        ForeignKey(
            entity = ChapterTranslationEntity::class,
            parentColumns = ["translationId"],
            childColumns = ["translationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TranslationBlockEntity(
    val translationId: Long,
    val blockIndex: Int,
    val sourceTextHash: String,
    val translatedText: String,
    val sentenceSpansJson: String? = null,
)

@Entity(
    tableName = "book_translations",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["bookId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["bookId", "source", "target", "provider", "promptHash", "pipelineVersion"],
            unique = true,
        ),
    ],
)
data class BookTranslationEntity(
    @PrimaryKey(autoGenerate = true) val translationId: Long = 0,
    val bookId: Long,
    val source: String = "",
    val target: String,
    val provider: String,
    val model: String?,
    val promptHash: String,
    val sourceHash: String,
    val pipelineVersion: String,
    val title: String? = null,
    val description: String? = null,
    val altTitlesJson: String = "[]",
    // Persisted `chapterIndex -> translated title` map for the metadata
    // overlay; "{}" when the title batch has not run.
    val chapterTitlesJson: String = "{}",
    val status: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "tts_chunks",
    primaryKeys = ["chapterId", "blockIndex", "sentenceIndex"],
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["chapterId"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("chapterId")],
)
data class TtsChunkEntity(
    val chapterId: Long,
    val blockIndex: Int,
    val sentenceIndex: Int,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val translationKey: String? = null,
)

@Entity(tableName = "book_migrations")
data class BookMigrationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val oldBookId: Long?,
    val newBookId: Long?,
    val oldInstallId: String?,
    val newInstallId: String?,
    val batchId: String?,
    val optionsJson: String = "{}",
    val unmatchedJson: String = "[]",
    val chaptersTotal: Int = 0,
    val chaptersMatched: Int = 0,
    val chaptersWithProgress: Int = 0,
    val status: String,
    val error: String? = null,
    val createdAt: Long,
    val finishedAt: Long? = null,
)

/**
 * Independent reading-history record: exactly one row per book, kept apart from
 * [BookEntity.lastReadAt] so deleting history never changes the library, its
 * sort key, the saved position, read flags or downloads (FR-061/FR-065).
 * `readAt` mirrors the last read time; `chapterIndex`/`chapterId` are the
 * chapter the user was on when it was recorded.
 */
@Entity(
    tableName = "history",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["bookId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("readAt")],
)
data class HistoryEntity(
    @PrimaryKey val bookId: Long,
    val readAt: Long,
    val chapterIndex: Int? = null,
    val chapterId: Long? = null,
)
