package dev.mirutime.data.library

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TranslationQueueRowsTest {
    private lateinit var db: LibraryDatabase
    private lateinit var repository: LibraryRepository

    @BeforeTest
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder<LibraryDatabase>(
            factory = LibraryDatabaseConstructor::initialize,
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        repository = LibraryRepository(db.libraryDao())
        repository.ensureDefaultShelves()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun book(url: String) = BookEntity(
        installId = "chikari",
        url = url,
        title = "Queue",
        author = "Author",
        coverUrl = null,
        contentType = "Novel",
        rating = 0.0,
        shelfId = "reading",
        totalChapters = 0,
        inLibrary = true,
        addedAt = 1,
        updatedAt = 1,
        lastReadAt = null,
        lastChapterId = null,
        lastChapterIndex = null,
        chapterListHash = null,
        lastUpdateAt = null,
        genresJson = "[]",
    )

    private fun translation(
        chapterId: Long,
        status: String,
        sourceHash: String = "PENDING",
        updatedAt: Long = 10,
    ) = ChapterTranslationEntity(
        chapterId = chapterId,
        target = "ru",
        provider = "GOOGLE_FREE",
        model = null,
        promptHash = "prompt",
        sourceHash = sourceHash,
        pipelineVersion = "reader-1",
        status = status,
        updatedAt = updatedAt,
    )

    @Test
    fun `queue rows join chapters and carry the pending status`() = runTest {
        val url = "https://site/queue"
        val bookId = repository.saveBook(book(url))
        val chapters = repository.replaceChapters(
            bookId,
            (1..2).map { ChapterSeed(it, "$url/$it", "Chapter $it") },
            at = 10,
        )
        repository.saveChapterTranslation(translation(chapters[0].chapterId, "PENDING"))
        repository.saveChapterTranslation(translation(chapters[1].chapterId, "FAILED"))

        val rows = repository.chapterTranslationQueueRows(bookId)

        assertEquals(2, rows.size)
        assertEquals(setOf(1, 2), rows.map { it.idx }.toSet())
        assertEquals(bookId, rows.first().bookId)
        assertEquals("PENDING", rows.first { it.idx == 1 }.status)
        assertEquals("FAILED", rows.first { it.idx == 2 }.status)
    }

    @Test
    fun `latest translation ignores source hash`() = runTest {
        val url = "https://site/latest"
        val bookId = repository.saveBook(book(url))
        val chapter = repository.replaceChapters(
            bookId,
            listOf(ChapterSeed(1, "$url/1", "One")),
            at = 10,
        ).single()
        val row = repository.saveChapterTranslation(
            translation(chapter.chapterId, "PENDING", sourceHash = "PENDING"),
        )

        val latest = repository.latestChapterTranslation(
            chapterId = chapter.chapterId,
            source = "",
            target = "ru",
            provider = "GOOGLE_FREE",
            promptHash = "prompt",
            pipelineVersion = "reader-1",
        )

        assertNotNull(latest)
        assertEquals(row, latest.translationId)
        assertEquals("PENDING", latest.sourceHash)
    }

    @Test
    fun `snapshot exposes the latest translation state per chapter`() = runTest {
        val url = "https://site/snapshot"
        val bookId = repository.saveBook(book(url))
        val chapter = repository.replaceChapters(
            bookId,
            listOf(ChapterSeed(1, "$url/1", "One")),
            at = 10,
        ).single()
        repository.saveChapterTranslation(
            translation(chapter.chapterId, "FAILED", updatedAt = 10),
        )
        repository.saveChapterTranslation(
            translation(chapter.chapterId, "PENDING", sourceHash = "other", updatedAt = 20),
        )

        val snapshot = assertNotNull(repository.chapterStatusSnapshot(bookId))

        assertEquals("PENDING", snapshot.translationStates[1])
    }

    @Test
    fun `queue rows flow emits pending work`() = runTest {
        val url = "https://site/flow"
        val bookId = repository.saveBook(book(url))
        val chapter = repository.replaceChapters(
            bookId,
            listOf(ChapterSeed(1, "$url/1", "One")),
            at = 10,
        ).single()
        repository.saveChapterTranslation(translation(chapter.chapterId, "PENDING"))

        val rows = repository.chapterTranslationQueueRowsFlow().first()

        assertEquals(1, rows.size)
        assertEquals("PENDING", rows.single().status)
    }

    @Test
    fun `pending flow keeps the latest-state counts of the full queue flow`() = runTest {
        val url = "https://site/pending"
        val bookId = repository.saveBook(book(url))
        val chapters = repository.replaceChapters(
            bookId,
            (1..3).map { ChapterSeed(it, "$url/$it", "Chapter $it") },
            at = 10,
        )
        // Current state is COMPLETE; the stale FAILED row must not surface.
        repository.saveChapterTranslation(
            translation(chapters[0].chapterId, "FAILED", sourceHash = "old", updatedAt = 10),
        )
        repository.saveChapterTranslation(
            translation(chapters[0].chapterId, "COMPLETE", sourceHash = "new", updatedAt = 20),
        )
        repository.saveChapterTranslation(
            translation(chapters[1].chapterId, "PENDING", updatedAt = 30),
        )
        repository.saveChapterTranslation(
            translation(chapters[2].chapterId, "FAILED", updatedAt = 40),
        )

        val allLatest = repository.chapterTranslationQueueRows(bookId)
            .groupBy { it.chapterId }
            .mapValues { (_, rows) -> rows.first() }
            .values
        val pendingRows = repository.chapterTranslationQueuePendingRowsFlow().first()

        assertEquals(2, pendingRows.size)
        assertEquals(
            allLatest.count { it.status.equals("PENDING", ignoreCase = true) },
            pendingRows.count { it.status.equals("PENDING", ignoreCase = true) },
        )
        assertEquals(
            allLatest.count { it.status.equals("FAILED", ignoreCase = true) },
            pendingRows.count { it.status.equals("FAILED", ignoreCase = true) },
        )
        assertEquals(
            setOf(chapters[1].chapterId, chapters[2].chapterId),
            pendingRows.map { it.chapterId }.toSet(),
        )
    }

    @Test
    fun `delete removes a queue row by id`() = runTest {
        val url = "https://site/delete"
        val bookId = repository.saveBook(book(url))
        val chapter = repository.replaceChapters(
            bookId,
            listOf(ChapterSeed(1, "$url/1", "One")),
            at = 10,
        ).single()
        val id = repository.saveChapterTranslation(translation(chapter.chapterId, "PENDING"))

        repository.deleteChapterTranslation(id)

        assertNull(repository.chapterTranslationQueueRows(bookId).firstOrNull())
    }
}
