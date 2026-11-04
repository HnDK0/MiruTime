package dev.mirutime.app.features.dashboard.data

import dev.mirutime.app.features.dashboard.domain.DashboardBook
import dev.mirutime.app.features.dashboard.domain.HistoryEntry
import dev.mirutime.app.features.dashboard.domain.ReadingStats
import dev.mirutime.app.features.dashboard.domain.UpdateItem

// Dashboard-shaped slice of ReaderDataProvider.
// The full chapter-level contract (chapters, chapterStates, chapterContent,
// chapterListHash, markChapterRead, saveProgress/loadProgress with
// Progress.Item/Time) is defined here in-app for now; the data library adopts
// these signatures later and screens stay untouched.
interface DashboardDataProvider {
    suspend fun readingStats(): ReadingStats
    suspend fun continueReading(): DashboardBook?
    suspend fun nextUp(): List<DashboardBook>
    suspend fun updates(): List<UpdateItem>
    suspend fun history(limit: Int): List<HistoryEntry>
    suspend fun fullHistory(): List<HistoryEntry>

    /** Removes the selected history records; books and their state are kept (FR-063/FR-065). */
    suspend fun deleteHistory(bookUrls: List<String>)

    /** Clears every history record; books and their state are kept (FR-063/FR-065). */
    suspend fun clearHistory()
}
