package dev.mirutime.data.library

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest

class LatestChapterTranslationTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `latest COMPLETE row wins regardless of source and prompt hash`() = runTest {
        val file = File(tempDir(), "latest.db")
        val db = buildLibraryDatabase(libraryDatabaseBuilder(file))
        try {
            val repository = LibraryRepository(db.libraryDao())
            val bookId = repository.saveBook(latestBook("https://latest.test/book"))
            val chapterId = repository.replaceChapters(
                bookId,
                listOf(ChapterSeed(1, "https://latest.test/book/1", "One")),
                at = 1L,
            ).single().chapterId

            repository.saveChapterTranslationWithBlocks(
                latestTranslation(chapterId, sourceHash = "old", promptHash = "p1", updatedAt = 1L),
                listOf(latestBlock(0, "old")),
            )
            repository.saveChapterTranslationWithBlocks(
                latestTranslation(chapterId, sourceHash = "new", promptHash = "p2", updatedAt = 2L),
                listOf(latestBlock(0, "new")),
            )
            repository.saveChapterTranslationFailure(
                latestTranslation(chapterId, sourceHash = "newest", promptHash = "p3", updatedAt = 3L)
                    .copy(status = "FAILED"),
            )

            val row = repository.latestCompleteChapterTranslation(
                chapterId = chapterId,
                source = "",
                target = "ru",
                provider = "GEMINI",
            )

            assertNotNull(row)
            assertEquals("p2", row.promptHash)
            assertEquals("COMPLETE", row.status)
            assertEquals(
                listOf("new"),
                repository.translationBlocks(row.translationId).map { it.translatedText },
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `a forced failure replaces the same-config row and drops its blocks`() = runTest {
        val file = File(tempDir(), "forced-failure.db")
        val db = buildLibraryDatabase(libraryDatabaseBuilder(file))
        try {
            val repository = LibraryRepository(db.libraryDao())
            val bookId = repository.saveBook(latestBook("https://latest.test/forced"))
            val chapterId = repository.replaceChapters(
                bookId,
                listOf(ChapterSeed(1, "https://latest.test/forced/1", "One")),
                at = 1L,
            ).single().chapterId

            repository.saveChapterTranslationWithBlocks(
                latestTranslation(chapterId, sourceHash = "old", promptHash = "p1", updatedAt = 1L),
                listOf(latestBlock(0, "old")),
            )
            repository.saveChapterTranslationFailure(
                latestTranslation(chapterId, sourceHash = "new", promptHash = "p1", updatedAt = 2L)
                    .copy(status = "FAILED", model = null),
            )

            val sameConfig = repository.latestChapterTranslation(
                chapterId = chapterId,
                source = "",
                target = "ru",
                provider = "GEMINI",
                promptHash = "p1",
                pipelineVersion = "v",
            )
            assertEquals("FAILED", sameConfig?.status)
            assertEquals(emptyList(), repository.translationBlocks(sameConfig!!.translationId))
            assertEquals(
                null,
                repository.latestCompleteChapterTranslation(
                    chapterId = chapterId,
                    source = "",
                    target = "ru",
                    provider = "GEMINI",
                ),
            )
        } finally {
            db.close()
        }
    }

    private fun tempDir(): File =
        Files.createTempDirectory("mirutime-latest-translation").toFile().also { tempDirs += it }
}

private fun latestBook(url: String) = BookEntity(
    installId = "latest",
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

private fun latestTranslation(
    chapterId: Long,
    sourceHash: String,
    promptHash: String,
    updatedAt: Long,
) = ChapterTranslationEntity(
    chapterId = chapterId,
    source = "",
    target = "ru",
    provider = "GEMINI",
    model = "m",
    promptHash = promptHash,
    sourceHash = sourceHash,
    pipelineVersion = "v",
    status = "COMPLETE",
    updatedAt = updatedAt,
)

private fun latestBlock(index: Int, text: String) = TranslationBlockEntity(
    translationId = 0,
    blockIndex = index,
    sourceTextHash = "h-$index",
    translatedText = text,
)
