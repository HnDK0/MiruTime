package dev.mirutime.data.library

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LibraryDatabaseSnapshotTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `snapshot includes wal rows and passes integrity check`() {
        val dir = tempDir()
        val source = File(dir, "source.db")
        val target = File(dir, "snapshot.db")

        BundledSQLiteDriver().open(source.absolutePath).use { connection ->
            connection.execute("PRAGMA journal_mode = WAL")
            connection.execute("PRAGMA user_version = $LibrarySchemaVersion")
            connection.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, value TEXT)")
            connection.execute("INSERT INTO items (value) VALUES ('wal-not-checkpointed')")

            // Room keeps its connection open in production; VACUUM INTO must
            // still see the latest committed WAL row through its own snapshot.
            createDatabaseSnapshot(source.absolutePath, target.absolutePath)
        }

        assertTrue(target.isFile)
        assertTrue(target.length() > 0)
        BundledSQLiteDriver().open(target.absolutePath).use { connection ->
            connection.prepare("PRAGMA integrity_check").use { statement ->
                assertTrue(statement.step())
                assertEquals("ok", statement.getText(0))
            }
            connection.prepare("SELECT value FROM items").use { statement ->
                assertTrue(statement.step())
                assertEquals("wal-not-checkpointed", statement.getText(0))
            }
        }
    }

    @Test
    fun `snapshot rejects a database with another schema version`() {
        val dir = tempDir()
        val source = File(dir, "source-version.db")
        val target = File(dir, "snapshot-version.db")

        BundledSQLiteDriver().open(source.absolutePath).use { connection ->
            connection.execute("PRAGMA user_version = ${LibrarySchemaVersion + 1}")
            connection.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        }

        assertFailsWith<IllegalStateException> {
            createDatabaseSnapshot(source.absolutePath, target.absolutePath)
        }
    }

    @Test
    fun `local manga books are pruned from the snapshot`() = runTest {
        val dir = tempDir()
        val live = File(dir, "live.db")
        val snapshot = File(dir, "snapshot-manga.db")
        val db = buildLibraryDatabase(libraryDatabaseBuilder(live))
        try {
            val repository = LibraryRepository(db.libraryDao())
            val novel = repository.saveBook(book("local://novel", "local-books"))
            repository.replaceChapters(
                novel,
                listOf(ChapterSeed(1, "local://novel#1", "One")),
                at = 1L,
            )
            val manga = repository.saveBook(book("local://manga", "local-manga"))
            repository.replaceChapters(
                manga,
                listOf(ChapterSeed(1, "local://manga#1", "One")),
                at = 1L,
            )
        } finally {
            db.close()
        }

        createDatabaseSnapshot(live.absolutePath, snapshot.absolutePath)
        pruneLocalMangaBooks(snapshot.absolutePath)

        BundledSQLiteDriver().open(snapshot.absolutePath).use { connection ->
            assertEquals(1, connection.long("SELECT COUNT(*) FROM books"))
            assertEquals(1, connection.long("SELECT COUNT(*) FROM chapters"))
            assertEquals("local-books", connection.text("SELECT installId FROM books"))
            assertEquals(1, connection.long("SELECT COUNT(*) FROM book_search"))
        }
        // The manifest must describe the snapshot, not the live database.
        assertEquals(1 to 1, snapshotBookCounts(snapshot.absolutePath))
    }

    @Test
    fun `book id map joins the staged snapshot to the live database`() = runTest {
        val dir = tempDir()
        val staged = File(dir, "staged-identity.db")
        val live = File(dir, "live-identity.db")

        val stagedDb = buildLibraryDatabase(libraryDatabaseBuilder(staged))
        try {
            stagedDb.libraryDao().upsertBook(book("https://a/book", "plugin-a"))
        } finally {
            stagedDb.close()
        }
        val liveDb = buildLibraryDatabase(libraryDatabaseBuilder(live))
        try {
            // A different install with the same URL must not match, and an
            // earlier row shifts the target id.
            liveDb.libraryDao().upsertBook(book("https://a/book", "plugin-b"))
            liveDb.libraryDao().upsertBook(book("https://a/book", "plugin-a"))
        } finally {
            liveDb.close()
        }

        val map = restoreBookIdMap(staged.absolutePath, live.absolutePath)

        assertEquals(mapOf(1L to 2L), map)
    }

    private fun tempDir(): File =
        Files.createTempDirectory("mirutime-db-snapshot").toFile().also { tempDirs += it }
}

private fun book(url: String, installId: String) = BookEntity(
    installId = installId,
    url = url,
    title = "Book",
    author = "Author",
    coverUrl = null,
    contentType = "Novel",
    rating = 0.0,
    shelfId = "reading",
    totalChapters = 1,
    inLibrary = true,
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

private fun SQLiteConnection.execute(sql: String) {
    prepare(sql).use { it.step() }
}
