package dev.mirutime.data.library

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Builds a real v6 file (all tables come from Room itself, then the two
 * changed tables are downgraded to their v6 shape), inserts duplicate legacy
 * rows and opens it with the v7 builder. That exercises the real migration
 * path including Room's post-migration schema validation.
 */
class LibraryMigrationTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `migration 6 to 7 preserves data and collapses duplicate translation keys`() = runTest {
        val file = File(tempDir(), "migrate.db")
        var bookId: Long
        var chapterId: Long

        val created = buildLibraryDatabase(libraryDatabaseBuilder(file))
        try {
            val repository = LibraryRepository(created.libraryDao())
            bookId = repository.saveBook(book("https://migrate.test/book"))
            chapterId = repository.replaceChapters(
                bookId,
                listOf(ChapterSeed(1, "https://migrate.test/book/1", "One")),
                at = 1L,
            ).single().chapterId
        } finally {
            created.close()
        }

        downgradeToV6(file)

        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            connection.exec(
                """
                INSERT INTO chapter_translations
                    (chapterId, target, provider, model, promptHash, sourceHash,
                     pipelineVersion, status, updatedAt)
                VALUES
                    ($chapterId, 'ru', 'GEMINI', NULL, 'p', 'old', 'v', 'COMPLETE', 100),
                    ($chapterId, 'ru', 'GEMINI', NULL, 'p', 'failed-newer', 'v', 'FAILED', 200),
                    ($chapterId, 'ru', 'GEMINI', NULL, 'p', 'newest', 'v', 'COMPLETE', 300)
                """.trimIndent(),
            )
            val keptTranslationId = connection.long(
                "SELECT translationId FROM chapter_translations " +
                    "WHERE sourceHash = 'newest'",
            )
            val staleTranslationId = connection.long(
                "SELECT translationId FROM chapter_translations " +
                    "WHERE sourceHash = 'old'",
            )
            connection.exec(
                "INSERT INTO translation_blocks VALUES " +
                    "($keptTranslationId, 0, 'h', 'kept', NULL), " +
                    "($staleTranslationId, 0, 'h', 'stale', NULL)",
            )
            connection.exec(
                """
                INSERT INTO book_translations
                    (bookId, target, provider, model, promptHash, sourceHash,
                     pipelineVersion, title, description, altTitlesJson, status, updatedAt)
                VALUES
                    ($bookId, 'ru', 'GEMINI', NULL, 'p', 'old', 'v',
                     'Старое', 'Старое описание', '["old"]', 'COMPLETE', 100),
                    ($bookId, 'ru', 'GEMINI', NULL, 'p', 'new', 'v',
                     'Новое', 'Новое описание', '["new"]', 'COMPLETE', 300)
                """.trimIndent(),
            )
        }

        val migrated = buildLibraryDatabase(libraryDatabaseBuilder(file))
        try {
            val repository = LibraryRepository(migrated.libraryDao())
            assertEquals(12, LibrarySchemaVersion)
            assertEquals("https://migrate.test/book", repository.book(bookId)?.url)
            assertEquals(chapterId, repository.chapters(bookId).single().chapterId)

            val kept = repository.chapterTranslation(
                chapterId = chapterId,
                source = "",
                target = "ru",
                provider = "GEMINI",
                promptHash = "p",
                sourceHash = "newest",
                pipelineVersion = "v",
            )
            assertNotNull(kept)
            assertEquals(
                listOf("kept"),
                repository.translationBlocks(kept.translationId).map { it.translatedText },
            )

            val bookTranslation = repository.bookTranslation(
                bookId = bookId,
                source = "",
                target = "ru",
                provider = "GEMINI",
                promptHash = "p",
                pipelineVersion = "v",
            )
            assertNotNull(bookTranslation)
            assertEquals("Новое", bookTranslation.title)
            assertEquals("Новое описание", bookTranslation.description)
            assertEquals("{}", bookTranslation.chapterTitlesJson)
        } finally {
            migrated.close()
        }

        // Counts after Room closed: exactly one row per identity, and the
        // duplicate's blocks were removed.
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            assertEquals(
                1,
                connection.long("SELECT COUNT(*) FROM chapter_translations").toInt(),
            )
            assertEquals(
                1,
                connection.long("SELECT COUNT(*) FROM book_translations").toInt(),
            )
            assertEquals(
                1,
                connection.long("SELECT COUNT(*) FROM translation_blocks").toInt(),
            )
            assertEquals(
                LibrarySchemaVersion,
                connection.long("PRAGMA user_version").toInt(),
            )
        }
    }

    @Test
    fun `migration 7 to current preserves 5 books and 5003 chapters and adds indexes`() = runTest {
        val file = File(tempDir(), "migrate-v7.db")
        var firstBookId: Long

        val created = buildLibraryDatabase(libraryDatabaseBuilder(file))
        try {
            val repository = LibraryRepository(created.libraryDao())
            val ids = (1..5).map { index ->
                repository.saveBook(book("https://v7.test/book/$index"))
            }
            firstBookId = ids.first()
        } finally {
            created.close()
        }

        // Build the v7 shape from the current file: same rows, no v8 indexes.
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            connection.exec("BEGIN")
            connection.prepare(
                "INSERT INTO chapters " +
                    "(bookId, idx, url, title, read, downloaded, updatedAt) " +
                    "VALUES (?, ?, ?, ?, ?, 0, 1)",
            ).use { statement ->
                (1..5).forEach { bookIndex ->
                    val bookId = firstBookId + bookIndex - 1
                    val chapterCount = if (bookIndex == 5) 1003 else 1000
                    for (index in 1..chapterCount) {
                        statement.bindLong(1, bookId)
                        statement.bindLong(2, index.toLong())
                        statement.bindText(3, "https://v7.test/book/$bookIndex/$index")
                        statement.bindText(4, "Chapter $index")
                        statement.bindLong(5, if (index <= 2) 1 else 0)
                        statement.step()
                        statement.reset()
                    }
                }
            }
            connection.exec(
                """
                INSERT INTO local_book_files
                    (bookId, uri, displayName, format, mime, sizeBytes, sha256,
                     copyPath, parserVersion, importedAt)
                VALUES ($firstBookId, 'content://v7/book', 'book.epub', 'EPUB',
                    NULL, 42, 'sha-v7', NULL, 1, 1)
                """.trimIndent(),
            )
            connection.exec("COMMIT")

            connection.exec("DROP INDEX index_chapters_read_removedAt_bookId_idx")
            connection.exec("DROP INDEX index_local_book_files_uri")
            connection.exec("DROP INDEX index_local_book_files_sha256")
            connection.exec("PRAGMA user_version = 7")
            connection.exec(
                "UPDATE room_master_table SET identity_hash = " +
                    "'0366896254e9012fc6321c1c2f8983ab' WHERE id = 42",
            )
        }

        val migrated = buildLibraryDatabase(libraryDatabaseBuilder(file))
        try {
            val repository = LibraryRepository(migrated.libraryDao())
            assertEquals("https://v7.test/book/1", repository.book(firstBookId)?.url)
            assertEquals(1000, repository.chapters(firstBookId).size)
            assertEquals(
                BookReadStats(readCount = 2, maxReadIndex = 2),
                repository.readChapterStats()[firstBookId],
            )
            assertEquals("content://v7/book", repository.localBookFileByUri("content://v7/book")?.uri)
            assertEquals("sha-v7", repository.localBookFileBySha("sha-v7")?.sha256)
        } finally {
            migrated.close()
        }

        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            assertEquals(12, connection.long("PRAGMA user_version").toInt())
            assertEquals(5, connection.long("SELECT COUNT(*) FROM books").toInt())
            assertEquals(5003, connection.long("SELECT COUNT(*) FROM chapters").toInt())
            listOf(
                "index_chapters_read_removedAt_bookId_idx" to "chapters",
                "index_local_book_files_uri" to "local_book_files",
                "index_local_book_files_sha256" to "local_book_files",
            ).forEach { (index, table) ->
                assertTrue(connection.indexExists(index, table), "missing $index")
            }
        }
    }

    @Test
    fun `backup migrator upgrades a real v6 snapshot and preserves rows`() = runTest {
        val file = File(tempDir(), "backup-v6.db")
        var bookId: Long
        var chapterId: Long

        val created = buildLibraryDatabase(libraryDatabaseBuilder(file))
        try {
            val repository = LibraryRepository(created.libraryDao())
            bookId = repository.saveBook(book("https://backup.test/book"))
            chapterId = repository.replaceChapters(
                bookId,
                listOf(ChapterSeed(1, "https://backup.test/book/1", "One")),
                at = 1L,
            ).single().chapterId
        } finally {
            created.close()
        }

        downgradeToV6(file)
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            connection.exec(
                """
                INSERT INTO chapter_translations
                    (chapterId, target, provider, model, promptHash, sourceHash,
                     pipelineVersion, status, updatedAt)
                VALUES
                    ($chapterId, 'ru', 'GEMINI', NULL, 'p', 'old', 'v', 'COMPLETE', 100),
                    ($chapterId, 'ru', 'GEMINI', NULL, 'p', 'new', 'v', 'COMPLETE', 300)
                """.trimIndent(),
            )
            connection.exec(
                """
                INSERT INTO book_translations
                    (bookId, target, provider, model, promptHash, sourceHash,
                     pipelineVersion, title, description, altTitlesJson, status, updatedAt)
                VALUES
                    ($bookId, 'ru', 'GEMINI', NULL, 'p', 'old', 'v',
                     'Старое', 'Описание', '["old"]', 'COMPLETE', 100),
                    ($bookId, 'ru', 'GEMINI', NULL, 'p', 'new', 'v',
                     'Новое', 'Описание', '["new"]', 'COMPLETE', 300)
                """.trimIndent(),
            )
        }

        migrateBackupDatabase(file.absolutePath, fromVersion = 6)

        // The live import opens the staged file through Room, so the migrator
        // must leave a valid v7 identity hash behind.
        val migrated = buildLibraryDatabase(libraryDatabaseBuilder(file))
        try {
            val repository = LibraryRepository(migrated.libraryDao())
            assertEquals("https://backup.test/book", repository.book(bookId)?.url)
            assertEquals(chapterId, repository.chapters(bookId).single().chapterId)
            val translation = repository.chapterTranslation(
                chapterId = chapterId,
                source = "",
                target = "ru",
                provider = "GEMINI",
                promptHash = "p",
                sourceHash = "new",
                pipelineVersion = "v",
            )
            assertNotNull(translation)
            val bookTranslation = repository.bookTranslation(
                bookId = bookId,
                source = "",
                target = "ru",
                provider = "GEMINI",
                promptHash = "p",
                pipelineVersion = "v",
            )
            assertNotNull(bookTranslation)
            assertEquals("Новое", bookTranslation.title)
            assertEquals("{}", bookTranslation.chapterTitlesJson)
        } finally {
            migrated.close()
        }

        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            assertEquals(
                LibrarySchemaVersion,
                connection.long("PRAGMA user_version").toInt(),
            )
            assertEquals(1, connection.long("SELECT COUNT(*) FROM chapter_translations").toInt())
            assertEquals(1, connection.long("SELECT COUNT(*) FROM book_translations").toInt())
            assertTrue(connection.columns("chapter_translations").contains("source"))
            assertTrue(connection.columns("book_translations").contains("chapterTitlesJson"))
            assertTrue(
                connection.uniqueIndexExists(
                    "index_chapter_translations_chapterId_source_target_provider_promptHash_pipelineVersion",
                    "chapter_translations",
                ),
            )
            assertTrue(
                connection.uniqueIndexExists(
                    "index_book_translations_bookId_source_target_provider_promptHash_pipelineVersion",
                    "book_translations",
                ),
            )
            assertTrue(connection.indexExists("index_chapters_read_removedAt_bookId_idx", "chapters"))
            assertTrue(connection.indexExists("index_local_book_files_uri", "local_book_files"))
            assertTrue(connection.indexExists("index_local_book_files_sha256", "local_book_files"))
        }
    }

    @Test
    fun `backup migrator rejects v5 without touching the file`() {
        val file = File(tempDir(), "backup-v5.db")
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            connection.exec("PRAGMA user_version = 5")
            connection.exec("CREATE TABLE books (bookId INTEGER PRIMARY KEY)")
        }
        val before = file.readBytes()

        assertFailsWith<IllegalArgumentException> {
            migrateBackupDatabase(file.absolutePath, fromVersion = 5)
        }
        assertTrue(before.contentEquals(file.readBytes()))
    }

    @Test
    fun `backup migrator rolls back a failed v6 migration`() {
        val file = File(tempDir(), "backup-corrupt.db")
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            connection.exec("PRAGMA user_version = 6")
            connection.exec("CREATE TABLE books (bookId INTEGER PRIMARY KEY)")
        }

        assertFailsWith<Exception> {
            migrateBackupDatabase(file.absolutePath, fromVersion = 6)
        }

        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            assertEquals(6, connection.long("PRAGMA user_version").toInt())
            assertFalse(connection.columns("books").contains("source"))
        }
    }

    @Test
    fun `migration 8 to 9 preserves rows and adds video progress`() = runTest {
        val file = File(tempDir(), "migrate-v8.db")
        var bookId: Long
        var chapterId: Long

        val created = buildLibraryDatabase(libraryDatabaseBuilder(file))
        try {
            val repository = LibraryRepository(created.libraryDao())
            bookId = repository.saveBook(book("https://v8.test/book"))
            chapterId = repository.replaceChapters(
                bookId,
                listOf(ChapterSeed(1, "https://v8.test/book/1", "One")),
                at = 1L,
            ).single().chapterId
            repository.saveProgress(
                ProgressEntity(bookId, chapterId, 3, 40, 2, 0, false, 7L, 8L),
            )
        } finally {
            created.close()
        }

        downgradeToV8(file)

        val migrated = buildLibraryDatabase(libraryDatabaseBuilder(file))
        try {
            val repository = LibraryRepository(migrated.libraryDao())
            assertEquals("https://v8.test/book", repository.book(bookId)?.url)
            assertEquals(chapterId, repository.chapters(bookId).single().chapterId)
            assertEquals(40, repository.progress(bookId)?.charOffset)
            repository.saveVideoProgress(bookId, chapterId, 5_000, 10_000, 9L)
            assertEquals(
                5_000,
                repository.videoProgress(bookId, chapterId)?.positionMs,
            )
        } finally {
            migrated.close()
        }

        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            assertEquals(12, connection.long("PRAGMA user_version").toInt())
            assertEquals(1, connection.long("SELECT COUNT(*) FROM video_progress").toInt())
            assertTrue(connection.indexExists("index_video_progress_bookId", "video_progress"))
        }
    }

    @Test
    fun `backup migrator upgrades a real v8 snapshot with empty video progress`() = runTest {
        val file = File(tempDir(), "backup-v8.db")
        var bookId: Long
        var chapterId: Long

        val created = buildLibraryDatabase(libraryDatabaseBuilder(file))
        try {
            val repository = LibraryRepository(created.libraryDao())
            bookId = repository.saveBook(book("https://backup-v8.test/book"))
            chapterId = repository.replaceChapters(
                bookId,
                listOf(ChapterSeed(1, "https://backup-v8.test/book/1", "One")),
                at = 1L,
            ).single().chapterId
        } finally {
            created.close()
        }

        downgradeToV8(file)

        migrateBackupDatabase(file.absolutePath, fromVersion = 8)

        val migrated = buildLibraryDatabase(libraryDatabaseBuilder(file))
        try {
            val repository = LibraryRepository(migrated.libraryDao())
            assertEquals("https://backup-v8.test/book", repository.book(bookId)?.url)
            assertEquals(chapterId, repository.chapters(bookId).single().chapterId)
            assertNull(repository.videoProgress(bookId, chapterId))
        } finally {
            migrated.close()
        }

        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            assertEquals(12, connection.long("PRAGMA user_version").toInt())
            assertEquals(0, connection.long("SELECT COUNT(*) FROM video_progress").toInt())
        }
    }

    @Test
    fun `migration 11 to 12 keeps files readable and adds catalogue identity`() = runTest {
        val file = File(tempDir(), "migrate-v11.db")
        var bookId: Long

        val created = buildLibraryDatabase(libraryDatabaseBuilder(file))
        try {
            val repository = LibraryRepository(created.libraryDao())
            bookId = repository.saveBook(book("https://v11.test/book"))
            repository.saveLocalBookFile(
                LocalBookFileEntity(
                    bookId = bookId,
                    uri = "content://v11/book.epub",
                    displayName = "book.epub",
                    format = "EPUB",
                    mime = null,
                    sizeBytes = 42,
                    sha256 = "sha-v11",
                    copyPath = "local_books/local-books/sha-v11.epub",
                    importedAt = 1L,
                ),
            )
        } finally {
            created.close()
        }

        downgradeToV11(file)

        val migrated = buildLibraryDatabase(libraryDatabaseBuilder(file))
        try {
            val repository = LibraryRepository(migrated.libraryDao())
            val original = repository.localBookFile(bookId)
            assertNotNull(original)
            assertEquals("content://v11/book.epub", original?.uri)
            // A pre-v12 file has no provenance: empty, never invented.
            assertEquals("", original?.originCatalogUrl)
            assertEquals("", original?.originEntryUrl)
            assertEquals("", original?.originCatalogName)

            repository.upsertOpdsEntries(
                listOf(
                    OpdsEntryEntity(
                        catalogUrl = "https://opds.test/root",
                        entryUrl = "https://opds.test/book/1",
                        feedUrl = "https://opds.test/root?page=1",
                        title = "Catalogue Book",
                        author = "Jane",
                        description = "Blurb",
                        coverUrl = null,
                        formatsJson = "[]",
                        updatedAt = 1L,
                    ),
                ),
            )
            assertEquals(
                "Catalogue Book",
                repository.opdsEntry("https://opds.test/root", "https://opds.test/book/1")?.title,
            )
            // The row remembers the feed page it was seen in; an old cache row
            // (no feed) reads back as an empty URL.
            assertEquals(
                "https://opds.test/root?page=1",
                repository.opdsEntry("https://opds.test/root", "https://opds.test/book/1")?.feedUrl,
            )
            repository.deleteOpdsEntries("https://opds.test/root")
            assertNull(repository.opdsEntry("https://opds.test/root", "https://opds.test/book/1"))
        } finally {
            migrated.close()
        }

        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            assertEquals(12, connection.long("PRAGMA user_version").toInt())
            assertTrue(connection.columns("local_book_files").contains("origin_catalog_url"))
            assertTrue(connection.columns("opds_entries").contains("feed_url"))
            assertTrue(connection.indexExists("index_opds_entries_catalogUrl_updatedAt", "opds_entries"))
            assertEquals(1, connection.long("SELECT COUNT(*) FROM books").toInt())
        }
    }

    @Test
    fun `backup migrator upgrades a real v11 snapshot and adds the entry cache`() = runTest {
        val file = File(tempDir(), "backup-v11.db")
        var bookId: Long

        val created = buildLibraryDatabase(libraryDatabaseBuilder(file))
        try {
            val repository = LibraryRepository(created.libraryDao())
            bookId = repository.saveBook(book("https://backup-v11.test/book"))
        } finally {
            created.close()
        }

        downgradeToV11(file)

        migrateBackupDatabase(file.absolutePath, fromVersion = 11)

        val migrated = buildLibraryDatabase(libraryDatabaseBuilder(file))
        try {
            val repository = LibraryRepository(migrated.libraryDao())
            assertEquals("https://backup-v11.test/book", repository.book(bookId)?.url)
            assertEquals(emptyList<OpdsEntryEntity>(), repository.opdsEntries("https://x.test", emptyList()).toList())
            assertTrue(
                repository.originMembership(
                    "https://opds.test/root",
                    listOf("https://opds.test/book/1"),
                ).isEmpty(),
            )
        } finally {
            migrated.close()
        }

        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            assertEquals(12, connection.long("PRAGMA user_version").toInt())
            assertEquals(0, connection.long("SELECT COUNT(*) FROM opds_entries").toInt())
        }
    }

    @Test
    fun `migration 9 to 10 backfills independent history and keeps book state`() = runTest {
        val file = File(tempDir(), "migrate-v9.db")
        var bookId: Long
        var chapterId: Long

        val created = buildLibraryDatabase(libraryDatabaseBuilder(file))
        try {
            val repository = LibraryRepository(created.libraryDao())
            bookId = repository.saveBook(book("https://v9.test/book"))
            chapterId = repository.replaceChapters(
                bookId,
                listOf(ChapterSeed(1, "https://v9.test/book/1", "One")),
                at = 1L,
            ).single().chapterId
            repository.touchHistory(bookId, chapterId, 1, at = 5_000L)
            repository.saveProgress(ProgressEntity(bookId, chapterId, 3, 40, 2, 0, false, 7L, 8L))
            repository.markChapterRead(bookId, 1, true, at = 6_000L)
        } finally {
            created.close()
        }

        downgradeToV9(file)

        val migrated = buildLibraryDatabase(libraryDatabaseBuilder(file))
        try {
            val repository = LibraryRepository(migrated.libraryDao())
            // Backfilled from books.lastReadAt: exactly one row, before anything
            // is deleted.
            assertEquals(listOf(bookId), repository.history(10).map { it.bookId })
        } finally {
            migrated.close()
        }

        // The history row itself is not exposed as a BookEntity, so the carried
        // chapter is checked straight from the table.
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            assertEquals(5_000L, connection.long("SELECT readAt FROM history WHERE bookId = $bookId"))
            assertEquals(1L, connection.long("SELECT chapterIndex FROM history WHERE bookId = $bookId"))
            assertEquals(chapterId, connection.long("SELECT chapterId FROM history WHERE bookId = $bookId"))
        }

        val reopened = buildLibraryDatabase(libraryDatabaseBuilder(file))
        try {
            val repository = LibraryRepository(reopened.libraryDao())
            // Deleting history removes only the history row.
            repository.deleteHistory(bookId)
            assertEquals(0, repository.history(10).size)
            assertEquals("https://v9.test/book", repository.book(bookId)?.url)
            assertEquals(5_000L, repository.book(bookId)?.lastReadAt)
            assertEquals(40, repository.progress(bookId)?.charOffset)
            assertEquals(1, repository.readChapterStats()[bookId]?.readCount)
        } finally {
            reopened.close()
        }

        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            assertEquals(12, connection.long("PRAGMA user_version").toInt())
            // The list row was deleted, but the backfill/index remain valid and
            // the book itself is intact.
            assertEquals(0, connection.long("SELECT COUNT(*) FROM history").toInt())
            assertEquals(1, connection.long("SELECT COUNT(*) FROM books").toInt())
            assertTrue(connection.indexExists("index_history_readAt", "history"))
        }
    }

    @Test
    fun `unknown schema version fails loudly instead of wiping the library`() = runTest {
        val file = File(tempDir(), "newer.db")
        val created = buildLibraryDatabase(libraryDatabaseBuilder(file))
        try {
            LibraryRepository(created.libraryDao()).saveBook(book("https://newer.test/book"))
        } finally {
            created.close()
        }
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            connection.exec("PRAGMA user_version = 99")
        }

        val failed = runCatching {
            val db = buildLibraryDatabase(libraryDatabaseBuilder(file))
            try {
                db.libraryDao().shelfCount()
            } finally {
                db.close()
            }
        }
        assertTrue(failed.isFailure, "unknown schema version must fail loudly")

        // The file must still contain the original row: no destructive fallback.
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            assertEquals(99, connection.long("PRAGMA user_version").toInt())
            assertEquals(1, connection.long("SELECT COUNT(*) FROM books").toInt())
        }
    }

    private fun downgradeToV11(file: File) {
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            connection.exec("DROP INDEX index_opds_entries_catalogUrl_updatedAt")
            connection.exec("DROP TABLE opds_entries")
            connection.exec("DROP INDEX index_local_book_files_origin_catalog_url_origin_entry_url")
            connection.exec("ALTER TABLE local_book_files DROP COLUMN origin_catalog_url")
            connection.exec("ALTER TABLE local_book_files DROP COLUMN origin_entry_url")
            connection.exec("ALTER TABLE local_book_files DROP COLUMN origin_catalog_name")
            connection.exec("PRAGMA user_version = 11")
            // A genuine v11 file carried the v11 hash published in 11.json.
            connection.exec(
                "UPDATE room_master_table SET identity_hash = " +
                    "'a2fd3b84ea24a48d3ef9e35e589a2271' WHERE id = 42",
            )
        }
    }

    private fun downgradeToV9(file: File) {
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            connection.exec("DROP INDEX index_history_readAt")
            connection.exec("DROP TABLE history")
            connection.exec("PRAGMA user_version = 9")
            connection.exec(
                "UPDATE room_master_table SET identity_hash = " +
                    "'117dd4519b1602322a7e187aa9956185' WHERE id = 42",
            )
        }
    }

    private fun downgradeToV8(file: File) {
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            connection.exec("DROP INDEX index_video_progress_bookId")
            connection.exec("DROP TABLE video_progress")
            connection.exec("PRAGMA user_version = 8")
            connection.exec(
                "UPDATE room_master_table SET identity_hash = " +
                    "'f86c75a9ad41d8019c00660e05d7ee7d' WHERE id = 42",
            )
        }
    }

    private fun downgradeToV6(file: File) {
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            connection.exec("DROP INDEX index_chapters_read_removedAt_bookId_idx")
            connection.exec("DROP INDEX index_local_book_files_uri")
            connection.exec("DROP INDEX index_local_book_files_sha256")
            connection.exec("DROP INDEX index_chapter_translations_chapterId_source_target_provider_promptHash_pipelineVersion")
            connection.exec(
                "ALTER TABLE chapter_translations DROP COLUMN source",
            )
            connection.exec(
                "CREATE INDEX index_chapter_translations_chapterId " +
                    "ON chapter_translations (chapterId)",
            )

            connection.exec("DROP INDEX index_book_translations_bookId_source_target_provider_promptHash_pipelineVersion")
            connection.exec("ALTER TABLE book_translations DROP COLUMN source")
            connection.exec("ALTER TABLE book_translations DROP COLUMN chapterTitlesJson")
            connection.exec(
                "CREATE INDEX index_book_translations_bookId ON book_translations (bookId)",
            )

            connection.exec("PRAGMA user_version = 6")
            // Room rejects a post-migration identity mismatch; a genuine v6
            // file carried the v6 hash, so use the one published in 6.json.
            connection.exec(
                "UPDATE room_master_table SET identity_hash = " +
                    "'9403073cdde554577f01df02440a66c6' WHERE id = 42",
            )
        }
    }

    private fun tempDir(): File =
        Files.createTempDirectory("mirutime-db-migration").toFile().also { tempDirs += it }
}

private fun book(url: String) = BookEntity(
    installId = "chikari",
    url = url,
    title = "Title",
    author = "Author",
    coverUrl = null,
    contentType = "Novel",
    rating = 4.5,
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

private fun SQLiteConnection.exec(sql: String) {
    prepare(sql).use { it.step() }
}

private fun SQLiteConnection.long(sql: String): Long =
    prepare(sql).use { statement ->
        check(statement.step()) { "no row for: $sql" }
        statement.getLong(0)
    }

private fun SQLiteConnection.columns(table: String): Set<String> =
    prepare("PRAGMA table_info('$table')").use { statement ->
        buildSet {
            while (statement.step()) add(statement.getText(1))
        }
    }

private fun SQLiteConnection.uniqueIndexExists(index: String, table: String): Boolean =
    prepare(
        "SELECT 1 FROM sqlite_master " +
            "WHERE type = 'index' AND name = '$index' AND tbl_name = '$table' " +
            "AND sql LIKE '%UNIQUE%'",
    ).use { it.step() }

private fun SQLiteConnection.indexExists(index: String, table: String): Boolean =
    prepare(
        "SELECT 1 FROM sqlite_master " +
            "WHERE type = 'index' AND name = '$index' AND tbl_name = '$table'",
    ).use { it.step() }
