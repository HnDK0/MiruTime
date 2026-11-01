package dev.mirutime.data.library.migration

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.ChapterBodyEntity
import dev.mirutime.data.library.ChapterPageEntity
import dev.mirutime.data.library.ChapterTranslationEntity
import dev.mirutime.data.library.DownloadTaskEntity
import dev.mirutime.data.library.LibraryDatabase
import dev.mirutime.data.library.LibraryDatabaseConstructor
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.ProgressEntity
import dev.mirutime.data.library.TranslationBlockEntity
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class BookMigrationCoordinatorTest {
    private lateinit var db: LibraryDatabase
    private lateinit var repository: LibraryRepository
    private lateinit var files: FakeMigrationFiles
    private lateinit var settings: FakeMigrationSettings
    private lateinit var coordinator: BookMigrationCoordinator

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<LibraryDatabase>(
            factory = LibraryDatabaseConstructor::initialize,
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        repository = LibraryRepository(db.libraryDao())
        files = FakeMigrationFiles()
        settings = FakeMigrationSettings()
        coordinator = BookMigrationCoordinator(
            dao = db.libraryDao(),
            repository = repository,
            files = files,
            settings = settings,
            now = { 1_000L },
        )
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `plan reports pairs unmatched chapters and score`() = runTest {
        val old = oldBook("https://old.test/book")
        val oldId = repository.saveBook(old)
        repository.replaceChapters(
            oldId,
            listOf(
                seed(1, "https://old.test/book/1", "Chapter 1"),
                seed(2, "https://old.test/book/2", "Chapter 2"),
                seed(3, "https://old.test/book/bonus", "Bonus Chapter 3"),
            ),
            at = 1L,
        )

        val outcome = coordinator.plan(oldId, target("Chapter 1", "Chapter 2", "Chapter 9"))
        val plan = (outcome as BookMigrationOutcome.Success).value

        assertEquals(2, plan.pairs.size)
        assertEquals(2, plan.chaptersMatched)
        assertEquals(3, plan.chaptersTotal)
        assertEquals(listOf("Bonus Chapter 3"), plan.unmatchedOld.map { it.title })
        assertEquals(listOf("Chapter 9"), plan.unmatchedNew.map { it.title })
        assertEquals(2f / 3f, plan.score)
    }

    @Test
    fun `migration copies metadata progress bodies pages translations tasks and settings`() = runTest {
        val old = oldBook("https://old.test/book")
        val oldId = repository.saveBook(old)
        val oldChapters = repository.replaceChapters(
            oldId,
            listOf(
                seed(1, "https://old.test/book/1", "Chapter 1"),
                seed(2, "https://old.test/book/2", "Chapter 2"),
            ),
            at = 1L,
        )
        repository.markChapterRead(oldId, 1, true, at = 20L)
        val first = oldChapters[0]
        val second = oldChapters[1]

        db.libraryDao().upsertProgress(
            ProgressEntity(
                bookId = oldId,
                chapterId = first.chapterId,
                blockIndex = 4,
                charOffset = 12,
                sentenceIndex = 2,
                offsetPx = 7,
                split = true,
                seq = 9,
                updatedAt = 21L,
            ),
        )
        db.libraryDao().touchHistory(oldId, first.chapterId, 1, 30L)

        val bodyText = "<p>old body</p>"
        files.putText(oldId, first.chapterId, bodyText)
        db.libraryDao().upsertChapterBody(
            ChapterBodyEntity(
                chapterId = first.chapterId,
                format = "html",
                path = files.textPath(oldId, first.chapterId),
                bytes = bodyText.toByteArray().size.toLong(),
                sha256 = "body-sha",
                fetchedAt = 2L,
            ),
        )

        val firstPage = "manga/$oldId/${first.chapterId}/0.jpg"
        files.pages[firstPage] = byteArrayOf(1, 2, 3)
        db.libraryDao().upsertChapterPage(
            ChapterPageEntity(
                chapterId = first.chapterId,
                format = "images",
                pagePathsJson = files.encodePagePaths(listOf(firstPage)),
                totalBytes = 3,
                quality = "HQ",
            ),
        )

        val translationId = db.libraryDao().upsertChapterTranslation(
            ChapterTranslationEntity(
                chapterId = first.chapterId,
                source = "en",
                target = "ru",
                provider = "GEMINI",
                model = "m",
                promptHash = "p",
                sourceHash = "s",
                pipelineVersion = "v",
                status = "COMPLETE",
                updatedAt = 5L,
            ),
        )
        db.libraryDao().upsertTranslationBlock(
            TranslationBlockEntity(
                translationId = translationId,
                blockIndex = 0,
                sourceTextHash = "sh",
                translatedText = "Перевод",
            ),
        )
        db.libraryDao().upsertDownloadTask(
            DownloadTaskEntity(
                bookId = oldId,
                chapterId = second.chapterId,
                kind = "chapter",
                state = "DONE",
                progress = 1.0,
                createdAt = 3L,
                updatedAt = 4L,
            ),
        )

        val outcome = coordinator.migrate(oldId, target("Chapter 1", "Chapter 2"))
        val result = (outcome as BookMigrationOutcome.Success).value

        assertEquals("completed", result.status)
        assertTrue(result.bookCreated)
        assertTrue(!result.oldDeleted)
        assertEquals(2, result.chaptersMatched)
        assertEquals(1, result.chaptersWithProgress)

        val newBook = assertNotNull(repository.book("other", "https://new.test/book"))
        assertEquals("New Title", newBook.title)
        assertEquals("Author", newBook.author)
        assertEquals("reading", newBook.shelfId)
        assertEquals(2, newBook.totalChapters)
        assertEquals(1, newBook.lastChapterIndex)
        assertEquals(30L, newBook.lastReadAt)
        assertNotNull(newBook.lastChapterId)

        val newChapters = repository.chapters(newBook.bookId)
        assertEquals(listOf("Chapter 1", "Chapter 2"), newChapters.map { it.title })
        assertEquals(1, newChapters[0].idx)
        assertEquals(2, newChapters[1].idx)
        assertEquals(true, newChapters[0].read)
        assertEquals(true, newChapters[0].downloaded)

        val newBody = assertNotNull(db.libraryDao().chapterBody(newChapters[0].chapterId))
        assertEquals(files.textPath(newBook.bookId, newChapters[0].chapterId), newBody.path)
        assertEquals(bodyText, files.texts[newBody.path])
        assertTrue(newBody.path.contains("/${newBook.bookId}/"))

        val newPage = assertNotNull(db.libraryDao().chapterPage(newChapters[0].chapterId))
        val pagePaths = files.pagePaths(newPage.pagePathsJson)
        assertEquals(1, pagePaths.size)
        assertTrue(pagePaths.single().contains("/${newBook.bookId}/"))
        assertTrue(files.pages.containsKey(pagePaths.single()))
        assertEquals(3L, newPage.totalBytes)

        val progress = assertNotNull(db.libraryDao().progress(newBook.bookId))
        assertEquals(newChapters[0].chapterId, progress.chapterId)
        assertEquals(9L, progress.seq)

        val translations = db.libraryDao().chapterTranslationsFor(listOf(newChapters[0].chapterId))
        assertEquals(1, translations.size)
        val blocks = db.libraryDao().translationBlocks(translations.single().translationId)
        assertEquals(listOf("Перевод"), blocks.map { it.translatedText })

        val tasks = db.libraryDao().downloadTasksForBook(newBook.bookId)
        assertEquals(1, tasks.size)
        assertEquals(newChapters[1].chapterId, tasks.single().chapterId)
        assertEquals("DONE", tasks.single().state)

        assertEquals(listOf(old.url to newBook.url), settings.copied)
        assertNotNull(repository.book(oldId))
    }

    @Test
    fun `cancellation removes a new book and keeps the old one`() = runTest {
        val old = oldBook("https://old.test/book")
        val oldId = repository.saveBook(old)
        val oldChapters = repository.replaceChapters(
            oldId,
            listOf(
                seed(1, "https://old.test/book/1", "Chapter 1"),
                seed(2, "https://old.test/book/2", "Chapter 2"),
            ),
            at = 1L,
        )
        files.putText(oldId, oldChapters[0].chapterId, "body")
        db.libraryDao().upsertChapterBody(
            ChapterBodyEntity(
                chapterId = oldChapters[0].chapterId,
                format = "html",
                path = files.textPath(oldId, oldChapters[0].chapterId),
                bytes = 4,
                sha256 = "sha",
                fetchedAt = 1L,
            ),
        )

        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        files.pauseOnWrite = {
            started.complete(Unit)
            release.await()
        }
        val job = launch {
            coordinator.migrate(
                oldId,
                target("Chapter 1", "Chapter 2"),
                BookMigrationOptions(deleteOldBook = true),
            )
        }
        started.await()
        job.cancel()
        job.join()
        release.complete(Unit)

        if (!job.isCancelled) {
            // The coordinator rethrows cancellation; reaching here would mean
            // the pause was ignored.
            error("Migration was not cancelled")
        }
        assertNull(repository.book("other", "https://new.test/book"))
        assertNotNull(repository.book(oldId))
        assertEquals("body", files.texts[files.textPath(oldId, oldChapters[0].chapterId)])
        assertTrue(files.texts.keys.none { it.contains("/2/") })
        val record = db.libraryDao().bookMigrations().single()
        assertEquals("cancelled", record.status)
        assertNull(record.newBookId)
        assertTrue(settings.deleted.isEmpty())
    }

    @Test
    fun `failed attempt cleans up and a retry completes idempotently`() = runTest {
        val old = oldBook("https://old.test/book")
        val oldId = repository.saveBook(old)
        val oldChapters = repository.replaceChapters(
            oldId,
            listOf(
                seed(1, "https://old.test/book/1", "Chapter 1"),
                seed(2, "https://old.test/book/2", "Chapter 2"),
            ),
            at = 1L,
        )
        files.putText(oldId, oldChapters[0].chapterId, "body")
        db.libraryDao().upsertChapterBody(
            ChapterBodyEntity(
                chapterId = oldChapters[0].chapterId,
                format = "html",
                path = files.textPath(oldId, oldChapters[0].chapterId),
                bytes = 4,
                sha256 = "sha",
                fetchedAt = 1L,
            ),
        )

        files.failOnWrite = 1
        val first = coordinator.migrate(oldId, target("Chapter 1", "Chapter 2"))
        assertTrue(first is BookMigrationOutcome.Failure)
        assertNull(repository.book("other", "https://new.test/book"))
        assertNotNull(repository.book(oldId))
        assertEquals("failed", db.libraryDao().bookMigrations().single().status)

        files.failOnWrite = 0
        val second = coordinator.migrate(oldId, target("Chapter 1", "Chapter 2"))
        assertTrue(second is BookMigrationOutcome.Success)
        val newBook = assertNotNull(repository.book("other", "https://new.test/book"))
        assertEquals(2, repository.chapters(newBook.bookId).size)
        val records = db.libraryDao().bookMigrations()
        assertEquals(1, records.size)
        assertEquals("completed", records.single().status)
        assertEquals(newBook.bookId, records.single().newBookId)

        // A completed migration is a no-op, not a duplicate book/record.
        val third = coordinator.migrate(oldId, target("Chapter 1", "Chapter 2"))
        assertTrue(third is BookMigrationOutcome.Success)
        assertEquals(1, db.libraryDao().bookMigrations().size)
        assertEquals(1, repository.libraryBooks().count { it.url == "https://new.test/book" })
    }

    @Test
    fun `delete old book runs only after the new state commits`() = runTest {
        val old = oldBook("https://old.test/book")
        val oldId = repository.saveBook(old)
        repository.replaceChapters(
            oldId,
            listOf(
                seed(1, "https://old.test/book/1", "Chapter 1"),
                seed(2, "https://old.test/book/2", "Chapter 2"),
            ),
            at = 1L,
        )

        val outcome = coordinator.migrate(
            oldId,
            target("Chapter 1", "Chapter 2"),
            BookMigrationOptions(deleteOldBook = true),
        )
        val result = (outcome as BookMigrationOutcome.Success).value

        assertTrue(result.oldDeleted)
        assertNull(repository.book(oldId))
        val newBook = assertNotNull(repository.book("other", "https://new.test/book"))
        assertEquals(2, repository.chapters(newBook.bookId).size)
        assertEquals(listOf(old.url), settings.deleted)
        assertTrue(files.deletedBooks.contains(oldId))
    }

    @Test
    fun `large chapter lists cross write chunks and stay idempotent`() = runTest {
        val old = oldBook("https://old.test/book")
        val oldId = repository.saveBook(old)
        val count = 205
        repository.replaceChapters(
            oldId,
            (1..count).map {
                seed(it, "https://old.test/book/$it", "Chapter $it")
            },
            at = 1L,
        )

        val first = coordinator.migrate(
            oldId,
            target(*(1..count).map { "Chapter $it" }.toTypedArray()),
        )
        assertTrue(first is BookMigrationOutcome.Success)
        val newBook = assertNotNull(repository.book("other", "https://new.test/book"))
        assertEquals(count, repository.chapters(newBook.bookId).size)
        assertEquals(count, first.value.chaptersMatched)

        val second = coordinator.migrate(
            oldId,
            target(*(1..count).map { "Chapter $it" }.toTypedArray()),
        )
        assertTrue(second is BookMigrationOutcome.Success)
        assertEquals(count, repository.chapters(newBook.bookId).size)
        assertEquals(1, db.libraryDao().bookMigrations().size)
    }

    private suspend fun oldBook(url: String): BookEntity = BookEntity(
        installId = "old",
        url = url,
        title = "Old Title",
        author = "Author",
        coverUrl = null,
        contentType = "Novel",
        rating = 4.5,
        shelfId = "reading",
        totalChapters = 100,
        inLibrary = true,
        addedAt = 1L,
        updatedAt = 1L,
        lastReadAt = null,
        lastChapterId = null,
        lastChapterIndex = null,
        chapterListHash = null,
        lastUpdateAt = null,
        genresJson = "[]",
        description = "Old description",
    )

    private fun target(vararg titles: String): BookMigrationTarget = BookMigrationTarget(
        book = BookEntity(
            installId = "other",
            url = "https://new.test/book",
            title = "New Title",
            author = "",
            coverUrl = null,
            contentType = "Novel",
            rating = 0.0,
            shelfId = "reading",
            totalChapters = titles.size,
            inLibrary = false,
            addedAt = 0L,
            updatedAt = 0L,
            lastReadAt = null,
            lastChapterId = null,
            lastChapterIndex = null,
            chapterListHash = null,
            lastUpdateAt = null,
            genresJson = "[]",
        ),
        chapters = titles.mapIndexed { index, title ->
            BookMigrationChapter(
                index = index + 1,
                url = "https://new.test/book/${index + 1}",
                title = title,
            )
        },
    )

    private fun seed(index: Int, url: String, title: String) =
        dev.mirutime.data.library.ChapterSeed(index = index, url = url, title = title)

    private class FakeMigrationSettings : BookMigrationSettingsStore {
        val copied = mutableListOf<Pair<String, String>>()
        val deleted = mutableListOf<String>()

        override suspend fun copyNovelSettings(oldBookUrl: String, newBookUrl: String) {
            copied += oldBookUrl to newBookUrl
        }

        override suspend fun deleteNovelSettings(bookUrl: String) {
            deleted += bookUrl
        }
    }

    private class FakeMigrationFiles : BookMigrationFileStore {
        val texts = LinkedHashMap<String, String>()
        val pages = LinkedHashMap<String, ByteArray>()
        val deletedBooks = mutableListOf<Long>()
        var failOnWrite = 0
        var pauseOnWrite: (suspend () -> Unit)? = null
        private var writes = 0

        fun textPath(bookId: Long, chapterId: Long) = "text/$bookId/$chapterId.html"

        fun putText(bookId: Long, chapterId: Long, text: String) {
            texts[textPath(bookId, chapterId)] = text
        }

        override suspend fun readText(path: String): String? = texts[path]

        override suspend fun writeText(
            bookId: Long,
            chapterId: Long,
            text: String,
        ): MigrationStoredText {
            beforeWrite()
            val path = textPath(bookId, chapterId)
            texts[path] = text
            return MigrationStoredText(
                path = path,
                bytes = text.toByteArray().size.toLong(),
                sha256 = "sha-$chapterId",
            )
        }

        override suspend fun readBytes(path: String): ByteArray? = pages[path]

        override suspend fun writePage(
            bookId: Long,
            chapterId: Long,
            index: Int,
            sourcePath: String,
            bytes: ByteArray,
        ): String {
            beforeWrite()
            val path = "manga/$bookId/$chapterId/$index.jpg"
            pages[path] = bytes
            return path
        }

        override suspend fun pagePaths(pagePathsJson: String): List<String> =
            if (pagePathsJson.isBlank()) emptyList() else pagePathsJson.split('|')

        override suspend fun encodePagePaths(paths: List<String>): String = paths.joinToString("|")

        override suspend fun deleteText(bookId: Long, chapterId: Long) {
            texts.remove(textPath(bookId, chapterId))
        }

        override suspend fun deletePages(bookId: Long, chapterId: Long) {
            val prefix = "manga/$bookId/$chapterId/"
            pages.keys.filter { it.startsWith(prefix) }.forEach { pages.remove(it) }
        }

        override suspend fun deleteBookFiles(bookId: Long) {
            deletedBooks += bookId
            texts.keys.filter { it.startsWith("text/$bookId/") }.forEach { texts.remove(it) }
            pages.keys.filter { it.startsWith("manga/$bookId/") }.forEach { pages.remove(it) }
        }

        private suspend fun beforeWrite() {
            writes++
            if (failOnWrite == writes) throw IOException("write failed")
            pauseOnWrite?.invoke()
        }
    }
}
