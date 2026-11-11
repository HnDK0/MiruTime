package dev.mirutime.app.features.dashboard.data

import android.text.format.DateUtils
import dev.mirutime.app.features.dashboard.domain.DashboardBook
import dev.mirutime.app.features.dashboard.domain.HistoryEntry
import dev.mirutime.app.features.dashboard.domain.ReadingStats
import dev.mirutime.app.features.dashboard.domain.ReadingTotals
import dev.mirutime.app.features.dashboard.domain.UpdateItem
import dev.mirutime.app.features.library.data.BookCoverStore
import dev.mirutime.app.features.library.data.loadTranslatedTitles
import dev.mirutime.app.features.library.data.resolveStoredCover
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.translation.data.MetadataTranslationStore
import dev.mirutime.app.features.translation.data.RoomMetadataTranslationStore
import dev.mirutime.app.features.translation.data.TitleSurface
import dev.mirutime.data.library.BookCoverEntity
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.BookReadStats
import dev.mirutime.data.library.LibraryRepository
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Dashboard/history view over the Room library. Reading-time samples and
// update detection do not have tables yet: stats stay at zero and updates are
// empty until those stores land.
class RoomDashboardDataProvider(
    private val library: LibraryRepository,
    // Same precedence as Library/Book: local cover file, stored remote URL,
    // then the legacy books.coverUrl. Null keeps previews/tests compiling.
    private val coverStore: BookCoverStore? = null,
    private val translations: MetadataTranslationStore? =
        RoomMetadataTranslationStore(library),
    private val now: () -> Long = System::currentTimeMillis,
    private val sourceName: (String) -> String = { it },
) : DashboardDataProvider {
    override suspend fun readingStats(): ReadingStats {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now()).atZone(zone).toLocalDate()
        fun startOf(date: LocalDate): Long =
            date.atStartOfDay(zone).toInstant().toEpochMilli()
        suspend fun total(from: LocalDate, to: LocalDate): Long = runBlockingDuration(
            from = startOf(from),
            to = startOf(to),
        )

        val todayStart = startOf(today)
        val tomorrowStart = startOf(today.plusDays(1))
        val yesterdayStart = startOf(today.minusDays(1))
        val readMs = library.durationBetween(todayStart, tomorrowStart, "reading")
        val listenMs = library.durationBetween(todayStart, tomorrowStart, "tts")
        val yesterdayMs = total(today.minusDays(1), today)
        val weekMs = (6 downTo 0).map { offset ->
            val day = today.minusDays(offset.toLong())
            total(day, day.plusDays(1))
        }
        return ReadingStats(
            today = ReadingTotals(readMs = readMs, listenMs = listenMs),
            yesterdayMs = yesterdayMs,
            weekMs = weekMs,
        )
    }

    private suspend fun runBlockingDuration(from: Long, to: Long): Long =
        library.durationBetween(from, to, "reading") +
            library.durationBetween(from, to, "tts")

    override suspend fun continueReading(): DashboardBook? {
        val timestamp = now()
        val row = library.history(1).firstOrNull() ?: return null
        val batch = batch(listOf(row), TitleSurface.Library)
        return row.toDashboardBook(timestamp, batch)
    }

    override suspend fun nextUp(): List<DashboardBook> {
        val timestamp = now()
        // SQL returns only the unfinished candidates (LIMIT 4); batch stats
        // supply the exact counters for those rows.
        val rows = library.unfinishedLibraryBooks(4)
        if (rows.isEmpty()) return emptyList()
        val batch = batch(rows, TitleSurface.Library)
        return rows.map { it.toDashboardBook(timestamp, batch) }
            .distinctBy { it.bookKey }
    }

    override suspend fun updates(): List<UpdateItem> {
        val timestamp = now()
        val rows = library.booksWithUpdates()
        if (rows.isEmpty()) return emptyList()
        val batch = batch(rows, TitleSurface.Library)
        return rows.map { row ->
            UpdateItem(
                book = row.toDashboardBook(timestamp, batch),
                newChapters = row.newChapters,
            )
        }
    }

    override suspend fun history(limit: Int): List<HistoryEntry> {
        val timestamp = now()
        val rows = library.history(limit)
        if (rows.isEmpty()) return emptyList()
        val batch = batch(rows, TitleSurface.History)
        return rows.map { it.toHistoryEntry(timestamp, batch) }
    }

    override suspend fun fullHistory(): List<HistoryEntry> {
        val timestamp = now()
        // A real cap: the History screen is a list, and an unbounded read grows
        // with the library. 500 covers any practical history.
        val rows = library.history(MaxHistoryRows)
        if (rows.isEmpty()) return emptyList()
        val batch = batch(rows, TitleSurface.History)
        return rows.map { it.toHistoryEntry(timestamp, batch) }
    }

    override suspend fun deleteHistory(bookUrls: List<String>) {
        if (bookUrls.isEmpty()) return
        val ids = library.bookIdsByUrls(bookUrls).values.toList()
        if (ids.isNotEmpty()) library.deleteHistory(ids)
    }

    override suspend fun clearHistory() = library.clearHistory()

    private data class DashboardBatch(
        val stats: Map<Long, BookReadStats>,
        val covers: Map<Long, BookCoverEntity>,
        val translatedTitles: Map<Long, String>,
    )

    private suspend fun batch(rows: List<BookEntity>, surface: TitleSurface): DashboardBatch = DashboardBatch(
        // Only the visible/history rows need counters; never scan every
        // chapter on the dashboard. The repository chunks ids below 999.
        stats = library.readChapterStatsFor(rows.map { it.bookId }),
        covers = library.bookCoversFor(rows.map { it.bookId }),
        translatedTitles = loadTranslatedTitles(rows, translations, surface),
    )

    private fun BookEntity.toDashboardBook(
        timestamp: Long,
        batch: DashboardBatch,
    ): DashboardBook {
        val readCount = batch.stats[bookId]?.readCount ?: 0
        return DashboardBook(
            bookUrl = bookUrl,
            title = title,
            translatedTitle = batch.translatedTitles[bookId],
            source = installId.ifBlank { hostOf(bookUrl) },
            installId = installId,
            sourceLabel = sourceName(installId.ifBlank { hostOf(bookUrl) }),
            author = authorRaw.ifBlank { author },
            rating = rating.toFloat(),
            totalChapters = totalChapters,
            readChapters = readCount,
            updatedLabel = relativeLabel(updatedAt, timestamp),
            hasNewChapters = newChapters > 0,
            coverUrl = dashboardCoverUrl(coverStore, bookId, batch.covers[bookId], coverUrl),
            contentType = runCatching { ContentType.valueOf(contentType) }
                .getOrDefault(ContentType.Novel),
        )
    }

    private fun BookEntity.toHistoryEntry(
        timestamp: Long,
        batch: DashboardBatch,
    ): HistoryEntry = HistoryEntry(
        book = toDashboardBook(timestamp, batch),
        chapter = lastChapterIndex ?: 0,
        readLabel = lastReadAt?.let { relativeLabel(it, timestamp) }.orEmpty(),
    )

    private fun relativeLabel(at: Long, timestamp: Long): String = runCatching {
        DateUtils.getRelativeTimeSpanString(
            at,
            timestamp,
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    }.getOrDefault("")
}

// Dashboard share of the one cover precedence; a seam for JVM tests without
// a Room database (local file, then remote row, then legacy coverUrl).
internal fun dashboardCoverUrl(
    coverStore: BookCoverStore?,
    bookId: Long,
    cover: BookCoverEntity?,
    fallback: String?,
): String? = resolveStoredCover(coverStore, bookId, cover, fallback)

private fun hostOf(url: String): String =
    runCatching { URI(url).host.orEmpty() }.getOrNull().orEmpty()

/** Upper bound for the full History screen; see [RoomDashboardDataProvider.fullHistory]. */
private const val MaxHistoryRows = 500
