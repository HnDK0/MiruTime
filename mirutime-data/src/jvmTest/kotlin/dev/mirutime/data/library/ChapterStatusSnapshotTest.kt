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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChapterStatusSnapshotTest {
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

    private fun book(url: String, installId: String = "chikari") = BookEntity(
        installId = installId,
        url = url,
        title = "Batch",
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

    private fun translation(chapterId: Long, status: String) = ChapterTranslationEntity(
        chapterId = chapterId,
        target = "ru",
        provider = "GEMINI",
        model = "m",
        promptHash = "p",
        sourceHash = "s",
        pipelineVersion = "v",
        status = status,
        updatedAt = 10,
    )

    @Test
    fun `batch snapshot maps read downloaded translated last and last-read`() = runTest {
        val url = "https://site/status"
        val bookId = repository.saveBook(book(url))
        val chapters = repository.replaceChapters(
            bookId,
            (1..100).map { ChapterSeed(it, "$url/$it", "Chapter $it") },
            at = 10,
        )

        repository.markChapterRead(bookUrl = url, chapterIndex = 1, read = true, at = 11)
        repository.markChapterRead(bookUrl = url, chapterIndex = 2, read = true, at = 11)
        repository.markChapterRead(bookUrl = url, chapterIndex = 3, read = true, at = 11)

        db.libraryDao().upsertChapter(chapters[4].copy(downloaded = true))
        repository.saveChapterBody(
            ChapterBodyEntity(
                chapterId = chapters[6].chapterId,
                format = "TEXT",
                path = "bodies/7.txt",
                bytes = 10,
                sha256 = "body",
                fetchedAt = 11,
            ),
        )
        repository.saveChapterPage(
            ChapterPageEntity(
                chapterId = chapters[7].chapterId,
                format = "IMAGES",
                pagePathsJson = "[]",
            ),
        )
        repository.saveChapterTranslation(translation(chapters[8].chapterId, "COMPLETE"))
        repository.saveChapterTranslation(translation(chapters[9].chapterId, "FAILED"))

        val progress = ProgressEntity(
            bookId = bookId,
            chapterId = chapters[41].chapterId,
            blockIndex = 0,
            charOffset = 0,
            sentenceIndex = null,
            offsetPx = 0,
            split = false,
            seq = 1,
            updatedAt = 20,
        )
        repository.saveProgress(progress)

        val snapshot = assertNotNull(repository.chapterStatusSnapshot(url))
        assertEquals(bookId, snapshot.bookId)
        assertEquals(100, snapshot.chapterCount)
        assertEquals(setOf(1, 2, 3), snapshot.readIndexes)
        assertEquals(setOf(5, 7, 8), snapshot.downloadedIndexes)
        assertEquals(setOf("$url/5", "$url/7", "$url/8"), snapshot.downloadedUrls)
        assertEquals(setOf(9), snapshot.translatedIndexes)
        assertEquals(setOf("$url/9"), snapshot.translatedUrls)
        assertEquals(100, snapshot.lastIndex)
        assertEquals(chapters.last().chapterId, snapshot.lastChapterId)
        assertEquals(42, snapshot.lastReadIndex)
        assertEquals("$url/42", snapshot.lastReadUrl)
    }

    @Test
    fun `reader-cached body and pages count as downloaded and aggregate size`() = runTest {
        val url = "https://site/reader-cache"
        val bookId = repository.saveBook(book(url))
        val chapters = repository.replaceChapters(
            bookId,
            (1..2).map { ChapterSeed(it, "$url/$it", "Chapter $it") },
            at = 10,
        )

        repository.saveChapterBody(
            ChapterBodyEntity(
                chapterId = chapters[0].chapterId,
                format = "html",
                path = "downloads/1/1.html",
                bytes = 120,
                sha256 = "body",
                fetchedAt = 11,
            ),
        )
        repository.saveChapterPage(
            ChapterPageEntity(
                chapterId = chapters[0].chapterId,
                format = "images",
                pagePathsJson = "[]",
                totalBytes = 380,
            ),
        )

        val snapshot = assertNotNull(repository.chapterStatusSnapshot(url))
        assertEquals(setOf(1), snapshot.downloadedIndexes)
        assertEquals(500L, snapshot.sizeBytes[1])
        assertEquals(0L, snapshot.sizeBytes[2])
    }

    @Test
    fun `last chapter ignores removed rows`() = runTest {
        val url = "https://site/removed-last"
        val bookId = repository.saveBook(book(url))
        val chapters = repository.replaceChapters(
            bookId,
            (1..3).map { ChapterSeed(it, "$url/$it", "Chapter $it") },
            at = 10,
        )

        repository.markChapterRemoved(chapters.last().chapterId, at = 20)
        val snapshot = assertNotNull(repository.chapterStatusSnapshot(bookId))

        assertEquals(2, snapshot.chapterCount)
        assertEquals(2, snapshot.lastIndex)
        assertEquals(chapters[1].chapterId, snapshot.lastChapterId)
    }

    @Test
    fun `direct local snapshot keeps translation and derives last without downloaded`() = runTest {
        val url = "local-file://direct-status"
        val bookId = repository.ensureDirectBook(
            book = book(url, installId = "local-books"),
            file = LocalBookFileEntity(
                bookId = 0,
                uri = "content://tree/direct",
                displayName = "one.epub",
                format = "EPUB",
                mime = null,
                sizeBytes = 10,
                sha256 = "hash",
                copyPath = null,
                parserVersion = 3,
                importedAt = 1,
            ),
            seeds = (1..3).map { ChapterSeed(it, "$url#chapter=$it", "Chapter $it") },
            at = 10,
        )
        val chapters = repository.chapters(bookId)
        // RoomTranslationCache resolves by the request chapter URL, exactly
        // like a direct-local bulk translation request does.
        val lastChapterId = assertNotNull(
            repository.chapterByUrl(bookId, "$url#chapter=3"),
        ).chapterId
        repository.saveChapterTranslation(translation(lastChapterId, "COMPLETE"))

        val snapshot = assertNotNull(repository.chapterStatusSnapshot(url))

        assertTrue(snapshot.downloadedIndexes.isEmpty())
        assertTrue(snapshot.downloadedUrls.isEmpty())
        assertEquals(setOf(3), snapshot.translatedIndexes)
        assertEquals(3, snapshot.lastIndex)
        assertEquals(chapters.last().chapterId, snapshot.lastChapterId)
        assertFalse(3 in snapshot.readIndexes)
    }

    @Test
    fun `last read prefers progress over the book row and falls back to it`() = runTest {
        val url = "https://site/last-read"
        val bookId = repository.saveBook(book(url))
        val chapters = repository.replaceChapters(
            bookId,
            (1..5).map { ChapterSeed(it, "$url/$it", "Chapter $it") },
            at = 10,
        )
        repository.touchHistory(url, chapterIndex = 4, at = 20)

        assertEquals("$url/4", repository.chapterStatusSnapshot(url)?.lastReadUrl)

        repository.saveProgress(
            ProgressEntity(
                bookId = bookId,
                chapterId = chapters[1].chapterId,
                blockIndex = 0,
                charOffset = 0,
                sentenceIndex = null,
                offsetPx = 0,
                split = false,
                seq = 1,
                updatedAt = 30,
            ),
        )

        val snapshot = assertNotNull(repository.chapterStatusSnapshot(url))
        assertEquals(2, snapshot.lastReadIndex)
        assertEquals("$url/2", snapshot.lastReadUrl)
    }
    @Test
    fun `bulk mark read updates only the selected indexes`() = runTest {
        val url = "https://site/bulk-read"
        val bookId = repository.saveBook(book(url))
        repository.replaceChapters(
            bookId,
            (1..5).map { ChapterSeed(it, "$url/$it", "Chapter $it") },
            at = 10,
        )

        repository.markChaptersRead(url, setOf(1, 3, 5), read = true, at = 20)
        assertEquals(setOf(1, 3, 5), repository.readChapterIndexes(url).toSet())

        repository.markChaptersRead(url, setOf(3), read = false, at = 30)
        assertEquals(setOf(1, 5), repository.readChapterIndexes(url).toSet())
    }

    @Test
    fun `delete translations removes only the selected chapter rows`() = runTest {
        val url = "https://site/delete-translations"
        val bookId = repository.saveBook(book(url))
        val chapters = repository.replaceChapters(
            bookId,
            (1..3).map { ChapterSeed(it, "$url/$it", "Chapter $it") },
            at = 10,
        )
        chapters.forEach { repository.saveChapterTranslation(translation(it.chapterId, "COMPLETE")) }

        val removed = repository.deleteChapterTranslations(url, setOf(1, 3))

        assertEquals(2, removed)
        val snapshot = assertNotNull(repository.chapterStatusSnapshot(url))
        assertEquals(setOf(2), snapshot.translatedIndexes)
    }


    @Test
    fun `download tasks join the snapshot and the queue flow`() = runTest {
        val url = "https://site/downloads"
        val bookId = repository.saveBook(book(url))
        val chapters = repository.replaceChapters(
            bookId,
            (1..3).map { ChapterSeed(it, "$url/$it", "Chapter $it") },
            at = 10,
        )
        repository.saveDownloadTask(
            DownloadTaskEntity(
                bookId = bookId,
                chapterId = chapters[1].chapterId,
                kind = "TEXT",
                state = "RUNNING",
                progress = 0.4,
                createdAt = 1,
                updatedAt = 1,
            ),
        )

        val snapshot = assertNotNull(repository.chapterStatusSnapshot(bookId))
        assertEquals("RUNNING", snapshot.downloads[2]?.state)
        assertEquals(0.4, snapshot.downloads[2]?.progress)

        val flowRows = repository.downloadTasksForBookFlow(bookId).first()
        assertEquals(1, flowRows.size)
        assertEquals(chapters[1].chapterId, flowRows.single().chapterId)
    }

    @Test
    fun `clearing downloaded payloads resets the denormalized chapter flag`() = runTest {
        val url = "https://site/clear-download"
        val bookId = repository.saveBook(book(url))
        val chapters = repository.replaceChapters(
            bookId,
            (1..2).map { ChapterSeed(it, "$url/$it", "Chapter $it") },
            at = 10,
        )
        repository.saveChapterBody(
            ChapterBodyEntity(
                chapterId = chapters[0].chapterId,
                format = "html",
                path = "downloads/1.html",
                bytes = 10,
                sha256 = "body",
                fetchedAt = 11,
            ),
        )
        repository.setChapterDownloaded(chapters[0].chapterId, true)
        repository.setChapterDownloaded(chapters[1].chapterId, true)

        repository.clearRemovableChapterBodies()

        val snapshot = assertNotNull(repository.chapterStatusSnapshot(bookId))
        assertTrue(snapshot.downloadedIndexes.isEmpty())
    }
}
