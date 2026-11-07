package dev.mirutime.data.library

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * Regression guard for the translation-cache invariant
 * "COMPLETE implies blocks present". A crash between the status upsert and the
 * block writes used to leave a COMPLETE row with no blocks.
 */
class TranslationCacheAtomicTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `failed block insert rolls back the status and the old block set`() = runTest {
        val file = File(tempDir(), "translation.db")
        var chapterId: Long

        val created = buildLibraryDatabase(libraryDatabaseBuilder(file))
        try {
            val repository = LibraryRepository(created.libraryDao())
            val bookId = repository.saveBook(book("https://atomic.test/book"))
            chapterId = repository.replaceChapters(
                bookId,
                listOf(ChapterSeed(1, "https://atomic.test/book/1", "One")),
                at = 1L,
            ).single().chapterId
            repository.saveChapterTranslationWithBlocks(
                translation(chapterId, sourceHash = "old"),
                listOf(block(0, "old")),
            )
        } finally {
            created.close()
        }

        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            connection.exec(
                """
                CREATE TRIGGER fail_block BEFORE INSERT ON translation_blocks
                WHEN NEW.blockIndex = 999
                BEGIN SELECT RAISE(ABORT, 'boom'); END
                """.trimIndent(),
            )
        }

        val db = buildLibraryDatabase(libraryDatabaseBuilder(file))
        try {
            val repository = LibraryRepository(db.libraryDao())
            assertFailsWith<Exception> {
                repository.saveChapterTranslationWithBlocks(
                    translation(chapterId, sourceHash = "new"),
                    listOf(block(0, "new"), block(999, "boom")),
                )
            }
        } finally {
            db.close()
        }

        val reopened = buildLibraryDatabase(libraryDatabaseBuilder(file))
        try {
            val repository = LibraryRepository(reopened.libraryDao())
            val row = repository.chapterTranslation(
                chapterId = chapterId,
                source = "",
                target = "ru",
                provider = "GEMINI",
                promptHash = "p",
                sourceHash = "old",
                pipelineVersion = "v",
            )
            assertNotNull(row)
            assertEquals("COMPLETE", row.status)
            assertEquals(
                listOf("old"),
                repository.translationBlocks(row.translationId).map { it.translatedText },
            )
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `successful save replaces the block set and stays idempotent`() = runTest {
        val db = buildLibraryDatabase(libraryDatabaseBuilder(File(tempDir(), "ok.db")))
        try {
            val repository = LibraryRepository(db.libraryDao())
            val bookId = repository.saveBook(book("https://atomic.test/ok"))
            val chapterId = repository.replaceChapters(
                bookId,
                listOf(ChapterSeed(1, "https://atomic.test/ok/1", "One")),
                at = 1L,
            ).single().chapterId

            val first = repository.saveChapterTranslationWithBlocks(
                translation(chapterId, sourceHash = "a"),
                listOf(block(0, "a0"), block(1, "a1")),
            )
            val second = repository.saveChapterTranslationWithBlocks(
                translation(chapterId, sourceHash = "b"),
                listOf(block(0, "b0")),
            )

            assertEquals(first, second)
            assertEquals(
                listOf("b0"),
                repository.translationBlocks(second).map { it.translatedText },
            )
            assertNull(
                repository.chapterTranslation(
                    chapterId = chapterId,
                    source = "",
                    target = "ru",
                    provider = "GEMINI",
                    promptHash = "p",
                    sourceHash = "a",
                    pipelineVersion = "v",
                ),
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `empty block set is rejected before writing a COMPLETE row`() = runTest {
        val db = buildLibraryDatabase(libraryDatabaseBuilder(File(tempDir(), "empty.db")))
        try {
            val repository = LibraryRepository(db.libraryDao())
            val bookId = repository.saveBook(book("https://atomic.test/empty"))
            val chapterId = repository.replaceChapters(
                bookId,
                listOf(ChapterSeed(1, "https://atomic.test/empty/1", "One")),
                at = 1L,
            ).single().chapterId

            assertFailsWith<IllegalArgumentException> {
                repository.saveChapterTranslationWithBlocks(
                    translation(chapterId, sourceHash = "empty"),
                    emptyList(),
                )
            }
            assertNull(
                repository.chapterTranslation(
                    chapterId = chapterId,
                    source = "",
                    target = "ru",
                    provider = "GEMINI",
                    promptHash = "p",
                    sourceHash = "empty",
                    pipelineVersion = "v",
                ),
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `failed status drops blocks from the previous attempt`() = runTest {
        val db = buildLibraryDatabase(libraryDatabaseBuilder(File(tempDir(), "failed.db")))
        try {
            val repository = LibraryRepository(db.libraryDao())
            val bookId = repository.saveBook(book("https://atomic.test/failed"))
            val chapterId = repository.replaceChapters(
                bookId,
                listOf(ChapterSeed(1, "https://atomic.test/failed/1", "One")),
                at = 1L,
            ).single().chapterId
            repository.saveChapterTranslationWithBlocks(
                translation(chapterId, sourceHash = "old"),
                listOf(block(0, "stale")),
            )

            repository.saveChapterTranslationFailure(
                translation(chapterId, sourceHash = "old").copy(status = "FAILED"),
            )

            val row = repository.chapterTranslation(
                chapterId = chapterId,
                source = "",
                target = "ru",
                provider = "GEMINI",
                promptHash = "p",
                sourceHash = "old",
                pipelineVersion = "v",
            )
            assertNotNull(row)
            assertEquals("FAILED", row.status)
            assertEquals(emptyList(), repository.translationBlocks(row.translationId))
        } finally {
            db.close()
        }
    }

    private fun tempDir(): File =
        Files.createTempDirectory("mirutime-translation-atomic").toFile().also { tempDirs += it }
}

private fun book(url: String) = BookEntity(
    installId = "atomic",
    url = url,
    title = "Title",
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

private fun translation(chapterId: Long, sourceHash: String) = ChapterTranslationEntity(
    chapterId = chapterId,
    source = "",
    target = "ru",
    provider = "GEMINI",
    model = "m",
    promptHash = "p",
    sourceHash = sourceHash,
    pipelineVersion = "v",
    status = "COMPLETE",
    updatedAt = 1L,
)

private fun block(index: Int, text: String) = TranslationBlockEntity(
    translationId = 0,
    blockIndex = index,
    sourceTextHash = "h-$index",
    translatedText = text,
)

private fun SQLiteConnection.exec(sql: String) {
    prepare(sql).use { it.step() }
}
