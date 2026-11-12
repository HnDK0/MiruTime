package dev.mirutime.app.features.book.data

import dev.mirutime.app.features.book.domain.BookChapter
import dev.mirutime.app.features.book.domain.BookDetails
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginError
import dev.mirutime.core.api.VideoStream
import dev.mirutime.core.reader.ChapterContent

// Membership is an explicit target, not a toggle. `fileCleanupFailed` never
// turns a committed DB delete into a failure: the row is gone and only the
// app-private cache cleanup needs a retry/log.
data class MembershipResult(
    val success: Boolean,
    val fileCleanupFailed: Boolean = false,
    val superseded: Boolean = false,
)

// Book-shaped slice of the future data layer. The screen depends only on
// this interface; the real one will resolve bookUrl
// through mirutime-core and the DB tables.
interface BookDataProvider {
    suspend fun book(bookUrl: String): BookDetails?

    /**
     * Drops any in-memory copy so the next [book]/[chapters] call re-reads the
     * store. A live restore replaces rows behind an already-open page.
     */
    fun invalidateBookCache(bookUrl: String) = Unit

    /** Reader/history entry point: creates a history-only row if needed. */
    suspend fun ensureHistoryBook(bookUrl: String)

    // Chapter list on its own so the book screen can paint its metadata
    // before a slow plugin chapters() call finishes. Empty on failure.
    suspend fun chapters(bookUrl: String): List<BookChapter>

    /**
     * Typed chapter load for the book page: a source failure is returned as
     * `Err` so the page can show it instead of a false "No chapters".
     */
    suspend fun chaptersOutcome(bookUrl: String): Outcome<List<BookChapter>> =
        Outcome.Ok(chapters(bookUrl))

    /**
     * Persisted chapters only, no plugin/network. Null means the store has no
     * rows (the screen then falls back to chapters()); used to open a book
     * immediately and refresh it in the background.
     */
    suspend fun cachedChapters(bookUrl: String): List<BookChapter>? = null

    /** Bypass any provider-side chapter cache (library update checks). */
    suspend fun refreshChapters(bookUrl: String): List<BookChapter> = chapters(bookUrl)

    /**
     * Typed explicit refresh for the book page: an `Err` keeps the plugin
     * failure (and the stored rows stay untouched) so the page can explain it;
     * `Ok` carries the rows it should show.
     */
    suspend fun refreshChaptersOutcome(bookUrl: String): Outcome<List<BookChapter>> =
        Outcome.Ok(refreshChapters(bookUrl))

    /**
     * Typed refresh for the library update service: an `Err` keeps the plugin
     * failure (HTTP status/class) so the Updates screen can explain it.
     * `Ok(null)` means no source/chapter rows, not a transport error.
     */
    suspend fun refreshChapterCountOutcome(bookUrl: String): Outcome<Int?> =
        Outcome.Ok(refreshChapters(bookUrl).size.takeIf { it > 0 })

    /** Last typed plugin failure for this book page; null after a success. */
    fun lastPluginError(bookUrl: String): PluginError? = null

    /**
     * Repairs a dead/rotated cover by re-resolving it from the source, then
     * forcing one fetch. Returns the local file URI (the old one when the
     * forced fetch fails); `Err` carries the typed plugin failure so the book
     * screen can show it in the shared error state. Providers without a cover
     * store keep the current URL.
     */
    suspend fun refreshCover(bookUrl: String): Outcome<String?> = Outcome.Ok(null)

    /**
     * Explicit metadata refresh (FR-011/FR-012/FR-013): re-fetches title,
     * author, description, rating and cover from the source and writes the
     * changed fields, preserving membership, shelf, progress and read flags.
     * A field the source no longer returns keeps its stored value (never wiped);
     * `Err` carries the typed plugin failure so the page can explain it.
     */
    suspend fun refreshMetadata(bookUrl: String): Outcome<Unit> = Outcome.Ok(Unit)

    /**
     * Optional cheap update probe (NoveLA getChapterListHash): a plugin that
     * exposes a chapter-list hash lets the library update skip the full list
     * when the hash is unchanged. Null = plugin has no hash; callers fall back
     * to the full chapter list.
     */
    suspend fun chapterListHash(bookUrl: String): String? = null

    // Raw chapter payload for the reader; null for an unknown chapter.
    suspend fun chapterContent(bookUrl: String, chapterIndex: Int): ChapterContent?

    // Image URLs for a remote manga chapter. Empty for text/local books; the
    // download engine stores the bytes through the host image client.
    suspend fun chapterPages(bookUrl: String, chapterIndex: Int): List<String> = emptyList()

    /**
     * Video streams for a plugin video chapter. Unlike [chapterContent], the
     * host never fetches chapter HTML: the source resolves the URL itself.
     * Non-video providers keep the typed error.
     */
    suspend fun videoStreams(
        bookUrl: String,
        chapterIndex: Int,
    ): Outcome<List<VideoStream>> = Outcome.Err(
        PluginError.ScriptError("Video streams are not supported by this provider"),
    )

    /**
     * Download-specific payload getters. Unlike the reader calls they keep the
     * typed source failure (auth/CF/rate limit) instead of returning null, so
     * the queue can fail one chapter without burning worker retries forever.
     * [sourceId] is the persisted `BookEntity.installId` hint; providers that
     * resolve a plugin may use it, the default implementation ignores it.
     */
    suspend fun downloadChapterText(
        bookUrl: String,
        chapterIndex: Int,
        sourceId: String? = null,
    ): String =
        chapterContent(bookUrl, chapterIndex)?.html
            ?: throw ChapterDownloadException(
                ChapterFailureKind.SOURCE,
                retryable = false,
                message = "Chapter content unavailable",
            )

    suspend fun downloadChapterPages(
        bookUrl: String,
        chapterIndex: Int,
        sourceId: String? = null,
    ): List<String> =
        chapterPages(bookUrl, chapterIndex).ifEmpty {
            throw ChapterDownloadException(
                ChapterFailureKind.SOURCE,
                retryable = false,
                message = "Chapter pages unavailable",
            )
        }

    // Fake mutations (shelf move, library toggle, bulk chapter actions).
    // Real ones move into the data lib with the DB.
    suspend fun moveShelf(bookUrl: String, shelfId: String)
    suspend fun setInLibrary(bookUrl: String, inLibrary: Boolean): MembershipResult

    // Cards that know their installId use this overload so a URL shared by two
    // installs deletes the selected row. Providers that cannot distinguish keep
    // the URL-only behavior.
    suspend fun setInLibrary(
        bookUrl: String,
        inLibrary: Boolean,
        installId: String?,
    ): MembershipResult = setInLibrary(bookUrl, inLibrary)
    fun setChapterDownloaded(bookUrl: String, range: IntRange, downloaded: Boolean)
    suspend fun downloadNext100(bookUrl: String)
    suspend fun markChapterRead(bookUrl: String, chapterIndex: Int, read: Boolean)

    /** Deletes persisted chapter translations for the selected indexes. */
    suspend fun deleteChapterTranslations(bookUrl: String, chapterIndexes: Set<Int>): Int = 0

    /**
     * OPDS only: adds a catalogue entry to the library and returns the local
     * book key the reader opens. [optionKey] picks a download option; null takes
     * the entry's default. Providers without a catalogue keep the typed error.
     */
    suspend fun addOpdsBook(bookUrl: String, optionKey: String?): Outcome<String> =
        Outcome.Err(PluginError.ScriptError("OPDS is not supported by this provider"))

    /** Default: loop single writes; DB-backed providers override with one UPDATE. */
    suspend fun markChaptersRead(bookUrl: String, chapterIndexes: Set<Int>, read: Boolean) {
        chapterIndexes.forEach { markChapterRead(bookUrl, it, read) }
    }
}
