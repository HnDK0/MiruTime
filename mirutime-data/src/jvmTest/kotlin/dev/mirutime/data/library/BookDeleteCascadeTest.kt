package dev.mirutime.data.library

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Hard-delete contract: every row and every file path a book owns must be
 * captured before the row disappears, and the FK cascade must remove all child
 * rows. This test intentionally populates every table that has a book/chapter
 * FK so a new table without CASCADE is caught here.
 */
class BookDeleteCascadeTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `delete book cascades every child row and returns every owned file`() = runTest {
        val file = File(tempDir(), "delete.db")
        val db = buildLibraryDatabase(libraryDatabaseBuilder(file))
        val repository = LibraryRepository(db.libraryDao())
        val book = book("https://delete.test/book", title = "DeleteMe")
        val bookId = repository.saveBook(book)
        val chapterId = repository.replaceChapters(
            bookId,
            listOf(ChapterSeed(1, "https://delete.test/book/1", "One")),
            at = 1L,
        ).single().chapterId

        val coverPath = "covers/$bookId/cover"
        val copyPath = "local_books/local-books/sha.epub"
        val bodyPath = "/tmp/delete-body.html"
        val pagePath = "/tmp/delete-page.jpg"

        repository.saveChapterBody(
            ChapterBodyEntity(chapterId, "text", bodyPath, 10, "sha-body", 1L),
        )
        repository.saveChapterPage(
            ChapterPageEntity(
                chapterId = chapterId,
                format = "images",
                pagePathsJson = "[\"$pagePath\"]",
                totalBytes = 20,
            ),
        )
        repository.saveProgress(ProgressEntity(bookId, chapterId, 1, 40, 2, 0, false, 1L, 2L))
        repository.saveVideoProgress(bookId, chapterId, 5_000, 10_000, 3L)
        repository.saveSession(
            ReadingSessionEntity(
                bookId = bookId,
                chapterId = chapterId,
                startedAt = 100L,
                endedAt = 160L,
                durationMs = 60_000L,
                kind = "reading",
            ),
        )
        repository.touchHistory(bookId, chapterId, 1, 4L)
        repository.saveDownloadTask(
            DownloadTaskEntity(
                bookId = bookId,
                chapterId = chapterId,
                kind = "text",
                state = "DONE",
                createdAt = 1L,
                updatedAt = 2L,
            ),
        )
        repository.saveBookCover(
            BookCoverEntity(
                bookId = bookId,
                remoteUrl = "https://cdn/cover.jpg",
                localPath = coverPath,
                bytes = 30,
            ),
        )
        repository.saveTag(
            TagEntity(name = "DeleteTag", normalizedName = "deletetag", kind = "TAG", createdAt = 1L),
        )
        repository.saveBookTag(
            BookTagEntity(
                bookId = bookId,
                tagId = repository.tagByNormalized("deletetag")!!.tagId,
            ),
        )
        repository.saveHighlight(
            HighlightEntity(
                bookId = bookId,
                chapterId = chapterId,
                blockIndex = 0,
                startOffset = 0,
                endOffset = 5,
                textKind = "ORIGINAL",
                colorArgb = 0xFFFF6D00L,
                sourceHash = "hash",
                parserVersion = 1,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        repository.saveLocalBookFile(
            LocalBookFileEntity(
                bookId = bookId,
                uri = "content://delete-book",
                displayName = "delete.epub",
                format = "EPUB",
                mime = "application/epub+zip",
                sizeBytes = 10,
                sha256 = "sha-delete",
                copyPath = copyPath,
                importedAt = 1L,
            ),
        )
        repository.saveChapterTranslationWithBlocks(
            ChapterTranslationEntity(
                chapterId = chapterId,
                source = "en",
                target = "ru",
                provider = "GEMINI",
                model = "m",
                promptHash = "p",
                sourceHash = "s",
                pipelineVersion = "v",
                status = "COMPLETE",
                updatedAt = 1L,
            ),
            listOf(TranslationBlockEntity(0, 0, "h", "translated")),
        )
        repository.saveBookTranslation(
            BookTranslationEntity(
                bookId = bookId,
                source = "en",
                target = "ru",
                provider = "GEMINI",
                model = null,
                promptHash = "p",
                sourceHash = "s",
                pipelineVersion = "v",
                title = "Translated",
                status = "COMPLETE",
                updatedAt = 1L,
            ),
        )
        repository.saveTtsChunk(
            TtsChunkEntity(
                chapterId = chapterId,
                blockIndex = 0,
                sentenceIndex = 0,
                text = "Hello",
                startOffset = 0,
                endOffset = 5,
            ),
        )
        repository.indexBookWithTags(book.copy(bookId = bookId))

        assertTrue(repository.searchBooks("DeleteMe").any { it.bookId == bookId })

        val result = repository.deleteBookWithFiles(book.url)

        assertEquals(setOf(book.url), result.deleted)
        assertTrue(result.missing.isEmpty())
        val files = result.files.single()
        assertEquals(coverPath, files.coverPath)
        assertEquals(copyPath, files.copyPath)
        assertEquals(listOf(bodyPath), files.bodyPaths)
        assertEquals(listOf("[\"$pagePath\"]"), files.pagePathsJson)
        assertNull(repository.book(bookId))
        assertTrue(repository.searchBooks("DeleteMe").isEmpty())

        // Idempotent repeat: nothing to delete, no files to remove.
        val repeat = repository.deleteBookWithFiles(book.url)
        assertTrue(repeat.deleted.isEmpty())
        assertTrue(repeat.files.isEmpty())

        db.close()
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            listOf(
                "books",
                "chapters",
                "chapter_bodies",
                "chapter_pages",
                "progress",
                "video_progress",
                "reading_sessions",
                "history",
                "download_tasks",
                "book_covers",
                "book_tags",
                "highlights",
                "local_book_files",
                "chapter_translations",
                "translation_blocks",
                "book_translations",
                "tts_chunks",
                "book_search",
            ).forEach { table ->
                assertEquals(0, connection.long("SELECT COUNT(*) FROM $table"), table)
            }
            // Tags are global entities and outlive the book itself.
            assertEquals(1, connection.long("SELECT COUNT(*) FROM tags"))
        }
    }

    @Test
    fun `delete by url removes only one row when two installs share the url`() = runTest {
        val db = buildLibraryDatabase(libraryDatabaseBuilder(File(tempDir(), "dupes.db")))
        val repository = LibraryRepository(db.libraryDao())
        val first = repository.saveBook(
            book("https://same.test/book", title = "First").copy(installId = "install-a"),
        )
        val second = repository.saveBook(
            book("https://same.test/book", title = "Second").copy(installId = "install-b"),
        )
        try {
            val result = repository.deleteBookWithFiles("https://same.test/book")

            assertEquals(1, result.deleted.size)
            val remaining = repository.book("https://same.test/book")
            assertEquals(true, remaining != null)
            val survivors = listOf(first, second).count { repository.book(it) != null }
            assertEquals(1, survivors)
        } finally {
            db.close()
        }
    }

    @Test
    fun `delete with install id removes the selected duplicate`() = runTest {
        val db = buildLibraryDatabase(libraryDatabaseBuilder(File(tempDir(), "dupes-install.db")))
        val repository = LibraryRepository(db.libraryDao())
        val first = repository.saveBook(
            book("https://same.test/book", title = "First").copy(installId = "install-a"),
        )
        val second = repository.saveBook(
            book("https://same.test/book", title = "Second").copy(installId = "install-b"),
        )
        try {
            val result = repository.deleteBookWithFiles(
                "https://same.test/book",
                installId = "install-b",
            )

            assertEquals(setOf("https://same.test/book"), result.deleted)
            assertEquals("install-a", repository.book(first)?.installId)
            assertNull(repository.book(second))
        } finally {
            db.close()
        }
    }

    @Test
    fun `delete cancels scheduled work before removing the row`() = runTest {
        val db = buildLibraryDatabase(libraryDatabaseBuilder(File(tempDir(), "cancel.db")))
        val cancelled = mutableListOf<List<Long>>()
        val repository = LibraryRepository(db.libraryDao()) { cancelled += it }
        val bookId = repository.saveBook(book("https://cancel.test/book", title = "Cancel"))
        try {
            repository.deleteBookWithFiles("https://cancel.test/book")

            assertEquals(listOf(bookId), cancelled.flatten())
            assertNull(repository.book(bookId))
        } finally {
            db.close()
        }
    }

    @Test
    fun `bulk delete with sources keeps the other install`() = runTest {
        val db = buildLibraryDatabase(libraryDatabaseBuilder(File(tempDir(), "bulk-dupes.db")))
        val repository = LibraryRepository(db.libraryDao())
        val first = repository.saveBook(
            book("https://same.test/bulk", title = "First").copy(installId = "install-a"),
        )
        val second = repository.saveBook(
            book("https://same.test/bulk", title = "Second").copy(installId = "install-b"),
        )
        try {
            val result = repository.deleteBooks(
                listOf("https://same.test/bulk"),
                sources = mapOf("https://same.test/bulk" to setOf("install-b")),
            )

            assertEquals(setOf("https://same.test/bulk"), result.deleted)
            assertEquals("install-a", repository.book(first)?.installId)
            assertNull(repository.book(second))
        } finally {
            db.close()
        }
    }

    @Test
    fun `purge out-of-library removes history-only books and keeps the library`() = runTest {
        val db = buildLibraryDatabase(libraryDatabaseBuilder(File(tempDir(), "purge.db")))
        val repository = LibraryRepository(db.libraryDao())
        val libraryId = repository.saveBook(
            book("https://purge.test/library", title = "Library"),
        )
        val historyId = repository.saveBook(
            book("https://purge.test/history", title = "History").copy(inLibrary = false),
        )
        repository.replaceChapters(
            historyId,
            listOf(ChapterSeed(1, "https://purge.test/history/1", "One")),
            at = 1L,
        )
        val coverPath = "covers/$historyId/cover"
        val copyPath = "local_books/local-books/history.epub"
        repository.saveBookCover(
            BookCoverEntity(
                bookId = historyId,
                remoteUrl = "https://cdn/history.jpg",
                localPath = coverPath,
                bytes = 10,
            ),
        )
        repository.saveLocalBookFile(
            LocalBookFileEntity(
                bookId = historyId,
                uri = "content://history",
                displayName = "history.epub",
                format = "EPUB",
                mime = "application/epub+zip",
                sizeBytes = 10,
                sha256 = "sha-history",
                copyPath = copyPath,
                importedAt = 1L,
            ),
        )
        try {
            assertEquals(1, repository.outOfLibraryBookCount())

            val delivered = mutableListOf<LibraryBookFiles>()
            val result = repository.purgeOutOfLibrary { delivered += it }

            assertEquals(setOf("https://purge.test/history"), result.deleted)
            assertNull(repository.book(historyId))
            assertEquals("Library", repository.book(libraryId)?.title)
            assertEquals(0, repository.outOfLibraryBookCount())
            assertTrue(result.files.isEmpty())
            val files = delivered.single()
            assertEquals(coverPath, files.coverPath)
            assertEquals(copyPath, files.copyPath)
            assertTrue(repository.purgeOutOfLibrary().deleted.isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `unknown install id never falls back to url-wide delete`() = runTest {
        val db = buildLibraryDatabase(libraryDatabaseBuilder(File(tempDir(), "dupes-stale.db")))
        val repository = LibraryRepository(db.libraryDao())
        val first = repository.saveBook(
            book("https://same.test/stale", title = "First").copy(installId = "install-a"),
        )
        val second = repository.saveBook(
            book("https://same.test/stale", title = "Second").copy(installId = "install-b"),
        )
        try {
            val result = repository.deleteBookWithFiles(
                "https://same.test/stale",
                installId = "gone-install",
            )

            assertTrue(result.deleted.isEmpty())
            assertEquals(setOf("https://same.test/stale"), result.missing)
            assertNotNull(repository.book(first))
            assertNotNull(repository.book(second))
        } finally {
            db.close()
        }
    }

    @Test
    fun `select all sources deletes both installs sharing the url`() = runTest {
        val db = buildLibraryDatabase(libraryDatabaseBuilder(File(tempDir(), "bulk-all.db")))
        val repository = LibraryRepository(db.libraryDao())
        val first = repository.saveBook(
            book("https://same.test/all", title = "First").copy(installId = "install-a"),
        )
        val second = repository.saveBook(
            book("https://same.test/all", title = "Second").copy(installId = "install-b"),
        )
        try {
            val result = repository.deleteBooks(
                listOf("https://same.test/all"),
                sources = mapOf(
                    "https://same.test/all" to setOf("install-a", "install-b"),
                ),
            )

            assertEquals(setOf("https://same.test/all"), result.deleted)
            assertNull(repository.book(first))
            assertNull(repository.book(second))
        } finally {
            db.close()
        }
    }

    @Test
    fun `book key resolves the exact install when two share a url`() = runTest {
        val db = buildLibraryDatabase(libraryDatabaseBuilder(File(tempDir(), "key.db")))
        val repository = LibraryRepository(db.libraryDao())
        val first = repository.saveBook(
            book("https://same.test/key", title = "First").copy(installId = "install-a"),
        )
        val second = repository.saveBook(
            book("https://same.test/key", title = "Second").copy(installId = "install-b"),
        )
        try {
            val firstKey = libraryBookKey("install-a", "https://same.test/key")
            val secondKey = libraryBookKey("install-b", "https://same.test/key")

            assertEquals(first, repository.book(firstKey)?.bookId)
            assertEquals(second, repository.book(secondKey)?.bookId)
            assertEquals(
                mapOf(firstKey to first, secondKey to second),
                repository.bookIdsByUrls(listOf(firstKey, secondKey)),
            )
            assertEquals(
                mapOf(firstKey to first, secondKey to second),
                repository.uniqueBookIdsByUrls(listOf(firstKey, secondKey)),
            )

            val result = repository.deleteBookWithFiles(firstKey)
            assertEquals(setOf("https://same.test/key"), result.deleted)
            assertNull(repository.book(firstKey))
            assertNotNull(repository.book(secondKey))
        } finally {
            db.close()
        }
    }

    @Test
    fun `bulk move and read by key affect only the selected install`() = runTest {
        val db = buildLibraryDatabase(libraryDatabaseBuilder(File(tempDir(), "bulk-key.db")))
        val repository = LibraryRepository(db.libraryDao())
        val first = repository.saveBook(
            book("https://same.test/actions", title = "First").copy(installId = "install-a"),
        )
        val second = repository.saveBook(
            book("https://same.test/actions", title = "Second").copy(installId = "install-b"),
        )
        repository.replaceChapters(
            first,
            listOf(ChapterSeed(1, "https://same.test/actions/1", "A1")),
            at = 1L,
        )
        repository.replaceChapters(
            second,
            listOf(ChapterSeed(1, "https://same.test/actions/1", "B1")),
            at = 1L,
        )
        try {
            repository.saveShelf(ShelfEntity(id = "archive", label = "Archive", position = 1))
            val key = libraryBookKey("install-a", "https://same.test/actions")
            repository.moveBooks(listOf(key), "archive")
            repository.markBooksRead(listOf(key), read = true, at = 2L)

            assertEquals("archive", repository.book(first)?.shelfId)
            assertEquals("reading", repository.book(second)?.shelfId)
            assertEquals(
                listOf(1),
                repository.readChapterIndexes(first).toList(),
            )
            assertTrue(repository.readChapterIndexes(second).isEmpty())
        } finally {
            db.close()
        }
    }

    private fun tempDir(): File =
        Files.createTempDirectory("mirutime-delete-cascade").toFile().also { tempDirs += it }
}

private fun book(url: String, title: String) = BookEntity(
    installId = "delete",
    url = url,
    title = title,
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
