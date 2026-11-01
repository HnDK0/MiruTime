package dev.mirutime.data.library

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectLocalBookTest {
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
        installId = "local-books",
        url = url,
        title = "Direct",
        author = "Unknown",
        coverUrl = null,
        contentType = "Novel",
        rating = 0.0,
        shelfId = "reading",
        totalChapters = 0,
        inLibrary = false,
        addedAt = 1,
        updatedAt = 1,
        lastReadAt = null,
        lastChapterId = null,
        lastChapterIndex = null,
        chapterListHash = null,
        lastUpdateAt = null,
        genresJson = "[]",
    )

    private fun file(uri: String) = LocalBookFileEntity(
        bookId = 0,
        uri = uri,
        displayName = "one.epub",
        format = "EPUB",
        mime = null,
        sizeBytes = 10,
        sha256 = "hash",
        copyPath = null,
        parserVersion = 3,
        importedAt = 1,
    )

    @Test
    fun `direct file attaches to an existing book row without a file row`() = runTest {
        val url = "local-file://existing"
        val existingId = repository.saveBook(book(url))
        val id = repository.ensureDirectBook(
            book = book(url).copy(bookId = 0),
            file = file("content://existing"),
            seeds = listOf(ChapterSeed(1, "$url#chapter=1", "One")),
            at = 10,
        )

        assertEquals(existingId, id)
        assertEquals(existingId, repository.localBookFile(id)?.bookId)
        assertEquals(1, repository.chapters(id).size)
    }

    @Test
    fun `direct history book and chapters land together without a copy`() = runTest {
        val url = "local-file://content%3A%2F%2Ftree%2Fdoc"
        val id = repository.ensureDirectBook(
            book = book(url),
            file = file("content://tree/doc"),
            seeds = listOf(
                ChapterSeed(1, "$url#chapter=1", "One"),
                ChapterSeed(2, "$url#chapter=2", "Two"),
            ),
            at = 10,
        )

        val row = assertNotNull(repository.book(id))
        assertFalse(row.inLibrary)
        assertNull(row.addedToLibraryAt)
        val local = assertNotNull(repository.localBookFile(id))
        assertNull(local.copyPath)
        assertEquals("content://tree/doc", local.uri)
        assertEquals(2, repository.chapters(id).size)
        assertTrue(repository.libraryBooks().none { it.bookId == id })

        // Explicit add does not create/copy a file; the local file row stays
        // copyPath=null and the book becomes visible in the library.
        repository.addToLibrary(id, 20)
        assertTrue(repository.libraryBooks().any { it.bookId == id })
        assertNull(repository.localBookFile(id)?.copyPath)

        // Removing membership keeps the row, chapters and progress identity.
        repository.setInLibrary(id, false)
        assertNotNull(repository.book(id))
        assertNotNull(repository.localBookFile(id))
        assertEquals(2, repository.chapters(id).size)
    }

    @Test
    fun `second ensure with the same identity does not rewrite rows or lose progress`() = runTest {
        val url = "local-file://same-file"
        val book = book(url)
        val file = file("content://tree/same")
        val seeds = listOf(
            ChapterSeed(1, "$url#chapter=1", "One"),
            ChapterSeed(2, "$url#chapter=2", "Two"),
            ChapterSeed(3, "$url#chapter=3", "Three"),
        )

        val id = repository.ensureDirectBook(book, file, seeds, at = 10)
        val chapters = repository.chapters(id)
        val last = chapters.last()
        repository.markChapterRead(id, 3, true, at = 20)
        repository.touchHistory(url, 3, at = 20)
        val translationId = repository.saveChapterTranslation(
            ChapterTranslationEntity(
                chapterId = last.chapterId,
                target = "ru",
                provider = "GEMINI",
                model = "m",
                promptHash = "p",
                sourceHash = "s",
                pipelineVersion = "v",
                status = "COMPLETE",
                updatedAt = 20,
            ),
        )

        val before = repository.book(id)!!
        val beforeChapters = repository.chapters(id)

        // Same URI + mtime + size hash and same URL sequence: the second
        // reader open must not touch book/file/chapter rows or the FTS.
        val secondId = repository.ensureDirectBook(
            book.copy(bookId = id, title = "Changed Title"),
            file,
            seeds,
            at = 99,
        )

        assertEquals(id, secondId)
        val after = repository.book(id)!!
        assertEquals(before.updatedAt, after.updatedAt)
        assertEquals(before.title, after.title)
        assertEquals(3, after.lastChapterIndex)
        assertEquals(beforeChapters.map { it.chapterId }, repository.chapters(id).map { it.chapterId })
        assertEquals(beforeChapters.map { it.updatedAt }, repository.chapters(id).map { it.updatedAt })
        assertTrue(repository.chapters(id).last().read)
        assertEquals(last.chapterId, repository.chapterByUrl(id, "$url#chapter=3")?.chapterId)
        assertEquals(
            translationId,
            repository.chapterTranslation(last.chapterId, "", "ru", "GEMINI", "p", "s", "v")?.translationId,
        )
    }
}
