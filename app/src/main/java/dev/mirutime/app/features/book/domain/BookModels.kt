package dev.mirutime.app.features.book.domain

import dev.mirutime.app.features.library.domain.ContentType

// One chapter of the fake catalog. Flags mirror NoveLA's chapterStates read
// in the book page: read / downloaded / translation ready / download running
// (the animated one). Everything is fixture-generated, no real network.
data class BookChapter(
    // 1-based position from the oldest chapter.
    val index: Int,
    val title: String,
    val isRead: Boolean,
    val downloaded: Boolean,
    val translated: Boolean,
    // A single fixture chapter sits mid-queue to show the animated icon.
    val downloading: Boolean,
    // Persisted queue state for this chapter (QUEUED/RUNNING/PAUSED/FAILED),
    // null when there is no active task. Real progress, not a fake tick.
    val downloadState: String? = null,
    val downloadProgress: Float = 0f,
    // Latest persisted translation queue state (PENDING/FAILED), null when the
    // chapter is untranslated or COMPLETE (then `translated` is true).
    val translationState: String? = null,
    // Absolute URL the plugin serves this chapter at; null for fixtures and
    // for plugins that do not expose one (the reader falls back to a key).
    val url: String? = null,
    // Last non-removed chapter by index (derived in the batch status query,
    // never persisted as a source flag).
    val isLast: Boolean = false,
    // On-device content (direct SAF or imported local), not a cloud download.
    val offline: Boolean = false,
    // The book's actual last-read chapter from progress/books.lastChapterId;
    // separate from the "last chapter" badge.
    val isLastRead: Boolean = false,
    // Persisted body + page bytes. 0 means unknown/not stored locally, so the
    // UI must not invent a remote estimate.
    val sizeBytes: Long = 0,
)

// One reader note (READER NOTES section). Fixture-only for now: no plugin
// has confirmed review parsing yet. The manual translate button is an icon +
// stub toast; FULL translation mode would flip `translated` in the data layer.
data class BookReview(
    val author: String,
    // 0f = the review came without a rating; the stars stay hidden.
    val rating: Float,
    val dateLabel: String,
    val text: String,
    // True shows the "Translated" tag instead of the translate button.
    val translated: Boolean,
)

// The book page works on its own fuller model (synopsis, origins, reading
// time, per-chapter states) — richer than the library slice LibraryBook,
// but the catalog-opening fields (bookUrl, title, source) keep the same
// names so lookups stay one key: the bookUrl.
data class BookDetails(
    val bookUrl: String,
    val title: String,
    val source: String,
    // Raw installId; [source] is the display label and must not be used for
    // membership/delete targeting.
    val installId: String = source,
    val author: String,
    // The plugin's original language, e.g. "English". Null hides the chip
    // (a plugin may not provide it).
    val originLanguage: String?,
    val contentType: ContentType,
    // 0 means "not parseable" → the rating cell shows "—".
    val rating: Float,
    val ongoing: Boolean,
    val updatedLabel: String,
    val totalChapters: Int,
    val readCount: Int,
    // Reading time buckets: manual minutes vs TTS minutes; the stat cell
    // shows both and their total.
    val readMinutes: Int,
    val ttsMinutes: Int,
    val inLibrary: Boolean,
    val shelfId: String,
    // Genres + tags as one chip pool; >2 rows collapse behind "+N".
    val genres: List<String>,
    val description: String,
    val sourceUrl: String,
    // When the plugin advertises a login URL the page shows a second
    // button (WebView flow — stubbed with a toast at this step).
    val authUrl: String?,
    val chapters: List<BookChapter>,
    // Reader notes: empty for books whose plugin offered no reviews
    // (the section shows its empty state).
    val reviews: List<BookReview>,
    val coverUrl: String? = null,
    // Plugin-provided or first alt title; shown under the display title in the
    // shell header. Null means "no second name".
    val subtitle: String? = null,
    // OPDS: the primary action for a catalogue entry that is not a library row.
    // Null keeps every existing book on the chapter/continue logic.
    val action: BookActionKind? = null,
    // OPDS: downloadable formats, default first. Empty for every other source.
    val downloadOptions: List<BookDownloadOption> = emptyList(),
    // OPDS: local book key when this entry is already in the library.
    val libraryBookKey: String? = null,
    // OPDS: catalogue that owns this entry (for labels and re-adds).
    val originCatalogUrl: String? = null,
) {
    val unreadCount: Int get() = (totalChapters - readCount).coerceAtLeast(0)
    // Match ReaderOpenResolver: reopen the saved/last-read chapter while it is
    // still unread (mid-chapter stop), otherwise go to the first unread
    // chapter. A finished book restarts from the last chapter. readCount + 1
    // is wrong for non-contiguous bulk-read flags.
    val continueChapter: BookChapter?
        get() {
            val lastRead = chapters.lastOrNull { it.isLastRead }
            if (lastRead != null && !lastRead.isRead) return lastRead
            return chapters.firstOrNull { !it.isRead } ?: chapters.lastOrNull()
        }

    val continueIndex: Int get() = continueChapter?.index ?: 1
    // Chapter the last-read button lands on. Prefer the real progress marker;
    // fall back to the highest read chapter for rows without progress.
    val lastReadIndex: Int
        get() = chapters.lastOrNull { it.isLastRead }?.index
            ?: chapters.lastOrNull { it.isRead }?.index
            ?: 0
    val progressFraction: Float get() = readCount.toFloat() / totalChapters.coerceAtLeast(1)
}

/**
 * Label source for the Continue/Start CTA. The translated chapter title wins so
 * the button names the chapter exactly like the list does; the raw source title
 * (or the generic "Chapter N") is only a fallback while metadata is in flight.
 */
fun continueChapterTitle(
    savedIndex: Int?,
    continueChapter: BookChapter?,
    chapters: List<BookChapter>,
    translatedTitles: Map<Int, String>,
): String {
    val index = savedIndex ?: continueChapter?.index
    if (index == null) return ""
    translatedTitles[index]?.takeIf { it.isNotBlank() }?.let { return it }
    val raw = if (savedIndex != null) {
        chapters.firstOrNull { it.index == savedIndex }?.title
    } else {
        continueChapter?.title
    }
    return raw?.takeIf { it.isNotBlank() } ?: ""
}

/**
 * What the page's primary action says. The chapter list loads after the
 * metadata, so "Read again"/"Continue · Ch. N" must never be derived from an
 * empty list (which used to fall back to chapter 1).
 */
enum class BookActionKind { LoadingChapters, NoChapters, Start, Continue, ReadAgain, AddToLibrary, OpenLibrary, OpenCatalog, NotDownloadable }

/** One OPDS acquisition the detail page can add; [key] is stable per option. */
data class BookDownloadOption(
    val key: String,
    val format: String,
    val url: String,
    val isDefault: Boolean = false,
    /** Non-null for sample/buy/borrow/subscribe: shown, not downloadable. */
    val unavailableReason: String? = null,
) {
    val isDownloadable: Boolean get() = unavailableReason == null
}

data class BookActionState(
    val kind: BookActionKind,
    val chapterIndex: Int? = null,
    val chapterTitle: String? = null,
) {
    /** Loading and a chapterless book are honest dead ends, not Ch. 1. */
    val enabled: Boolean
        get() = kind != BookActionKind.LoadingChapters &&
            kind != BookActionKind.NoChapters &&
            kind != BookActionKind.NotDownloadable
}

fun bookActionState(
    chaptersLoading: Boolean,
    totalChapters: Int,
    readCount: Int,
    chapterIndex: Int?,
    chapterTitle: String?,
    // A saved reader position is progress even when no chapter is marked
    // read yet (the common case after scrolling but before the read marker).
    hasSavedProgress: Boolean = false,
): BookActionState = when {
    chaptersLoading -> BookActionState(BookActionKind.LoadingChapters)
    // Unknown total (0) is not "nothing read" and never "read again".
    totalChapters <= 0 || chapterIndex == null -> BookActionState(BookActionKind.NoChapters)
    readCount >= totalChapters ->
        BookActionState(BookActionKind.ReadAgain, chapterIndex, chapterTitle)
    readCount <= 0 && !hasSavedProgress ->
        BookActionState(BookActionKind.Start, chapterIndex, chapterTitle)
    else -> BookActionState(BookActionKind.Continue, chapterIndex, chapterTitle)
}

/** The read/total + percent cells only mean something once chapters exist. */
fun bookChaptersCountKnown(chaptersLoading: Boolean, totalChapters: Int): Boolean =
    !chaptersLoading && totalChapters > 0

/**
 * Reading order for the chapter browser. Reverses the visual order only; the
 * persisted [BookChapter.index] (1-based, oldest first) is untouched.
 */
fun List<BookChapter>.inReadingOrder(newestFirst: Boolean): List<BookChapter> =
    if (newestFirst) asReversed() else this
