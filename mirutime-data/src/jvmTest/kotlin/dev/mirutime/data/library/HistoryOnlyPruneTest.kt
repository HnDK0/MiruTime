package dev.mirutime.data.library

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class HistoryOnlyPruneTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `library-only snapshot drops history-only books and their rows`() = runTest {
        val live = File(tempDir(), "live.db")
        val snapshot = File(tempDir(), "snapshot.db")
        val db = buildLibraryDatabase(libraryDatabaseBuilder(live))
        try {
            val repository = LibraryRepository(db.libraryDao())
            val libraryBook = repository.saveBook(book("https://prune.test/library", inLibrary = true))
            val historyBook = repository.saveBook(book("https://prune.test/history", inLibrary = false))
            repository.replaceChapters(
                libraryBook,
                listOf(ChapterSeed(1, "https://prune.test/library/1", "One")),
                at = 1L,
            )
            val historyChapter = repository.replaceChapters(
                historyBook,
                listOf(ChapterSeed(1, "https://prune.test/history/1", "One")),
                at = 1L,
            ).single().chapterId
            repository.saveProgress(
                ProgressEntity(historyBook, historyChapter, 0, 0, null, 0, false, 1L, 1L),
            )
        } finally {
            db.close()
        }

        createDatabaseSnapshot(live.absolutePath, snapshot.absolutePath)
        pruneHistoryOnlyBooks(snapshot.absolutePath)

        BundledSQLiteDriver().open(snapshot.absolutePath).use { connection ->
            assertEquals(1, connection.long("SELECT COUNT(*) FROM books"))
            assertEquals(1, connection.long("SELECT COUNT(*) FROM chapters"))
            assertEquals(0, connection.long("SELECT COUNT(*) FROM progress"))
            assertEquals(1, connection.long("SELECT COUNT(*) FROM book_search"))
            assertEquals(
                "https://prune.test/library",
                connection.text("SELECT url FROM books LIMIT 1"),
            )
        }
    }

    private fun tempDir(): File =
        Files.createTempDirectory("mirutime-prune").toFile().also { tempDirs += it }
}

private fun book(url: String, inLibrary: Boolean) = BookEntity(
    installId = "prune",
    url = url,
    title = "Book",
    author = "Author",
    coverUrl = null,
    contentType = "Novel",
    rating = 0.0,
    shelfId = "reading",
    totalChapters = 1,
    inLibrary = inLibrary,
    addedAt = 1L,
    updatedAt = 1L,
    lastReadAt = null,
    lastChapterId = null,
    lastChapterIndex = null,
    chapterListHash = null,
    lastUpdateAt = null,
    genresJson = "[]",
)

private fun SQLiteConnection.long(sql: String): Long =
    prepare(sql).use { statement ->
        check(statement.step()) { "no row for: $sql" }
        statement.getLong(0)
    }

private fun SQLiteConnection.text(sql: String): String =
    prepare(sql).use { statement ->
        check(statement.step()) { "no row for: $sql" }
        statement.getText(0)
    }
