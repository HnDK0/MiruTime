package dev.mirutime.app.features.dashboard.data

import dev.mirutime.app.features.dashboard.domain.DashboardBook
import dev.mirutime.app.features.dashboard.domain.HistoryEntry
import dev.mirutime.app.features.dashboard.domain.ReadingStats
import dev.mirutime.app.features.dashboard.domain.ReadingTotals
import dev.mirutime.app.features.dashboard.domain.UpdateItem
import dev.mirutime.app.features.library.domain.ContentType
import kotlinx.coroutines.delay

// Fake provider + fixtures: the dashboard is clickable with no libraries.
// Swap this class for the real data-lib implementation later; the screen
// only depends on the DashboardDataProvider interface above.
class FakeDashboardDataProvider : DashboardDataProvider {
    override suspend fun readingStats(): ReadingStats {
        delay(150)
        val today = ReadingTotals(
            readMs = 96 * 60_000L,
            listenMs = 41 * 60_000L,
        )
        return ReadingStats(
            today = today,
            yesterdayMs = 108 * 60_000L,
            // Oldest first, today last (must match today.totalMs).
            weekMs = listOf(52L, 0L, 88L, 141L, 75L, 108L, 137L).map { it * 60_000L },
        )
    }

    override suspend fun continueReading(): DashboardBook? {
        delay(150)
        return DashboardFixtures.books.first()
    }

    override suspend fun nextUp(): List<DashboardBook> {
        delay(150)
        return DashboardFixtures.books.drop(1).take(4)
    }

    override suspend fun updates(): List<UpdateItem> {
        delay(150)
        return DashboardFixtures.updates
    }

    override suspend fun history(limit: Int): List<HistoryEntry> {
        delay(150)
        return DashboardFixtures.history.take(limit)
    }

    override suspend fun fullHistory(): List<HistoryEntry> {
        delay(150)
        return DashboardFixtures.history
    }
}

object DashboardFixtures {
    val books = listOf(
        DashboardBook(
            bookUrl = "fake://asurascans/solo-leveling",
            title = "Solo Leveling",
            source = "Asura Scans",
            contentType = ContentType.Manga,
            author = "Chugong",
            rating = 4.8f,
            totalChapters = 179,
            readChapters = 122,
            updatedLabel = "Updated 2 h ago",
            hasNewChapters = true,
            coverUrl = "https://picsum.photos/seed/asurascans-solo-leveling/300/450",
        ),
        DashboardBook(
            bookUrl = "fake://wtr-lab/omniscient-reader",
            title = "Omniscient Reader",
            source = "WTR Lab",
            author = "Sing-Shong",
            rating = 4.9f,
            totalChapters = 551,
            readChapters = 310,
            updatedLabel = "Updated yesterday",
            hasNewChapters = true,
            coverUrl = "https://picsum.photos/seed/wtr-lab-omniscient-reader/300/450",
        ),
        DashboardBook(
            bookUrl = "fake://local/shadows-house",
            title = "Shadows House",
            source = "Local CBZ",
            contentType = ContentType.Manga,
            author = "So-ma-to",
            rating = 4.5f,
            totalChapters = 42,
            readChapters = 40,
            updatedLabel = "Updated 3 days ago",
            hasNewChapters = false,
            coverUrl = "https://picsum.photos/seed/local-shadows-house/300/450",
        ),
        DashboardBook(
            bookUrl = "fake://asurascans/nano-machine",
            title = "Nano Machine",
            source = "Asura Scans",
            contentType = ContentType.Manga,
            author = "Hanjung Wolya",
            rating = 4.6f,
            totalChapters = 268,
            readChapters = 90,
            updatedLabel = "Updated 5 h ago",
            hasNewChapters = true,
            coverUrl = "https://picsum.photos/seed/asurascans-nano-machine/300/450",
        ),
        DashboardBook(
            bookUrl = "fake://wtr-lab/mother-of-learning",
            title = "Mother of Learning",
            source = "WTR Lab",
            author = "Nobody103",
            rating = 4.7f,
            totalChapters = 108,
            readChapters = 108,
            updatedLabel = "Completed",
            hasNewChapters = false,
            coverUrl = "https://picsum.photos/seed/wtr-lab-mother-of-learning/300/450",
        ),
        DashboardBook(
            bookUrl = "fake://wtr-lab/coiling-dragon",
            title = "Coiling Dragon",
            source = "WTR Lab",
            author = "I Eat Tomatoes",
            rating = 4.5f,
            totalChapters = 808,
            readChapters = 455,
            updatedLabel = "Updated 1 h ago",
            hasNewChapters = true,
            coverUrl = "https://picsum.photos/seed/wtr-lab-coiling-dragon/300/450",
        ),
        DashboardBook(
            bookUrl = "fake://local/berserk",
            title = "Berserk",
            source = "Local CBZ",
            contentType = ContentType.Manga,
            author = "Kentaro Miura",
            rating = 4.9f,
            totalChapters = 375,
            readChapters = 360,
            updatedLabel = "Updated 4 h ago",
            hasNewChapters = true,
            coverUrl = "https://picsum.photos/seed/local-berserk/300/450",
        ),
        // Russian-original fixture: doubles as the bundled-font Cyrillic
        // proof on the dashboard (item: fonts).
        DashboardBook(
            bookUrl = "fake://wtr-lab/midnight-academy",
            title = "Академия полуночи",
            source = "WTR Lab",
            author = "Анна Соколова",
            rating = 4.6f,
            totalChapters = 96,
            readChapters = 41,
            updatedLabel = "Updated 30 min ago",
            hasNewChapters = true,
            coverUrl = "https://picsum.photos/seed/wtr-lab-midnight-academy/300/450",
        ),
    )

    val updates = listOf(
        UpdateItem(book = books[3], newChapters = 3),
        UpdateItem(book = books[0], newChapters = 1),
        UpdateItem(book = books[1], newChapters = 5),
        UpdateItem(book = books[5], newChapters = 4),
        UpdateItem(book = books[6], newChapters = 2),
        UpdateItem(book = books[7], newChapters = 6),
    )

    val history = listOf(
        HistoryEntry(book = books[0], chapter = 122, readLabel = "Today, 14:20"),
        HistoryEntry(book = books[3], chapter = 90, readLabel = "Today, 09:05"),
        HistoryEntry(book = books[1], chapter = 310, readLabel = "Yesterday, 22:41"),
        HistoryEntry(book = books[2], chapter = 40, readLabel = "Yesterday, 18:12"),
        HistoryEntry(book = books[4], chapter = 108, readLabel = "Monday, 21:33"),
        HistoryEntry(book = books[0], chapter = 121, readLabel = "Monday, 08:15"),
        HistoryEntry(book = books[1], chapter = 309, readLabel = "Sunday, 23:02"),
        HistoryEntry(book = books[3], chapter = 89, readLabel = "Sunday, 11:47"),
    )
}
