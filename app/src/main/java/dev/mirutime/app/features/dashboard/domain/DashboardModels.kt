package dev.mirutime.app.features.dashboard.domain

import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.data.library.libraryBookKey

// Dashboard UI models. Chapter-level reading lives behind ReaderDataProvider;
// these are the dashboard's read-only view of it.
data class DashboardBook(
    val bookUrl: String,
    val title: String,
    // Persisted/cold-start translation for the active language pair; null
    // falls back to [title] in the row/rail composables.
    val translatedTitle: String? = null,
    val source: String,
    // Raw installId; [source] may synthesize a host for legacy rows.
    val installId: String = "",
    // Plugin display name; falls back to the install id.
    val sourceLabel: String = source,
    val author: String,
    val rating: Float,
    val totalChapters: Int,
    val readChapters: Int,
    val updatedLabel: String,
    val hasNewChapters: Boolean,
    val coverUrl: String? = null,
    val contentType: ContentType = ContentType.Novel,
) {
    // Derived from the counters, never from the (English) fixture label:
    // the UI localizes "Ch. N" itself.
    val isFinished: Boolean get() = totalChapters > 0 && readChapters >= totalChapters
    val nextChapter: Int get() = (readChapters + 1).coerceAtMost(totalChapters.coerceAtLeast(1))
    val progress: Float get() = readChapters.toFloat() / totalChapters.coerceAtLeast(1)
    val percent: Int get() = (progress * 100).toInt().coerceIn(0, 100)
    val chaptersLeft: Int get() = (totalChapters - readChapters).coerceAtLeast(0)
    val bookKey: String = libraryBookKey(installId, bookUrl)
}

data class ReadingTotals(
    val readMs: Long,
    val listenMs: Long,
) {
    val totalMs: Long get() = readMs + listenMs
}

// Time statistics behind the dashboard's top panel. weekMs is the last 7
// days, oldest first, today LAST (its total equals today.totalMs).
data class ReadingStats(
    val today: ReadingTotals,
    val yesterdayMs: Long,
    val weekMs: List<Long>,
)

data class UpdateItem(
    val book: DashboardBook,
    val newChapters: Int,
)

data class HistoryEntry(
    val book: DashboardBook,
    // Chapter that was open in this session (not the book's current progress).
    val chapter: Int,
    val readLabel: String,
)
