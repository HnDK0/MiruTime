package dev.mirutime.data.library

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalBackupSelectionTest {
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
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `only in-library local books and their files are selected`() = runTest {
        val novelId = localNovel()
        val mangaId = localMangaFolder()
        val outOfLibraryId = book("local://out", "local-books", inLibrary = false).let { row ->
            repository.saveBook(row).also { id ->
                repository.saveLocalBookFile(localFile(id, "local_books/local-books/out.epub", "EPUB"))
            }
        }
        val pluginId = book("https://site/book", "plugin-a", inLibrary = true).let { row ->
            repository.saveBook(row).also { id ->
                repository.saveLocalBookFile(localFile(id, "local_books/plugin-a/book.epub", "EPUB"))
            }
        }

        val selected = repository.localBackupBooks()

        assertEquals(setOf(novelId, mangaId), selected.map { it.bookId }.toSet())
        assertTrue(selected.none { it.bookId == outOfLibraryId || it.bookId == pluginId })

        val novel = selected.first { it.bookId == novelId }
        assertEquals("local_books/local-books/novel.epub", novel.copyPath)
        assertEquals("local-books", novel.installId)
        assertEquals("covers/$novelId/cover", novel.coverLocalPath)
        assertEquals(
            listOf("local_books/local-books/novel/chapters/1.html"),
            novel.bodyPaths,
        )
        assertEquals(listOf("""["local_books/local-books/novel/pages/1/00001.jpg"]"""), novel.pagePathsJson)

        val manga = selected.first { it.bookId == mangaId }
        assertEquals("local_books/local-manga/folder", manga.copyPath)
        assertNull(manga.coverLocalPath)
        assertEquals(emptyList(), manga.bodyPaths)
        assertEquals(listOf("""["local_books/local-manga/folder/chapters/1/00001.jpg"]"""), manga.pagePathsJson)
    }

    private suspend fun localNovel(): Long {
        val bookId = repository.saveBook(book("local://novel", "local-books", inLibrary = true))
        val chapter = repository.replaceChapters(
            bookId,
            listOf(ChapterSeed(1, "local://novel#chapter=1", "One")),
            at = 1L,
        ).single()
        repository.saveLocalBookFile(localFile(bookId, "local_books/local-books/novel.epub", "EPUB"))
        repository.saveBookCover(
            BookCoverEntity(
                bookId = bookId,
                remoteUrl = null,
                localPath = "covers/$bookId/cover",
            ),
        )
        repository.saveChapterBody(
            ChapterBodyEntity(
                chapterId = chapter.chapterId,
                format = "html",
                path = "local_books/local-books/novel/chapters/1.html",
                bytes = 10,
                sha256 = "body-sha",
                fetchedAt = 1L,
            ),
        )
        repository.saveChapterPage(
            ChapterPageEntity(
                chapterId = chapter.chapterId,
                format = "images",
                pagePathsJson = """["local_books/local-books/novel/pages/1/00001.jpg"]""",
                totalBytes = 10,
            ),
        )
        return bookId
    }

    private suspend fun localMangaFolder(): Long {
        val bookId = repository.saveBook(book("local://manga", "local-manga", inLibrary = true))
        val chapter = repository.replaceChapters(
            bookId,
            listOf(ChapterSeed(1, "local://manga#chapter=1", "One")),
            at = 1L,
        ).single()
        repository.saveLocalBookFile(localFile(bookId, "local_books/local-manga/folder", "CBZ"))
        repository.saveChapterPage(
            ChapterPageEntity(
                chapterId = chapter.chapterId,
                format = "images",
                pagePathsJson = """["local_books/local-manga/folder/chapters/1/00001.jpg"]""",
                totalBytes = 10,
                parserVersion = 2,
            ),
        )
        return bookId
    }

    private fun localFile(bookId: Long, copyPath: String, format: String) = LocalBookFileEntity(
        bookId = bookId,
        uri = "content://local/$bookId",
        displayName = copyPath.substringAfterLast('/'),
        format = format,
        mime = null,
        sizeBytes = 10,
        sha256 = "sha-$bookId",
        copyPath = copyPath,
        importedAt = 1L,
    )

    private fun book(url: String, installId: String, inLibrary: Boolean) = BookEntity(
        installId = installId,
        url = url,
        title = url,
        author = "Author",
        coverUrl = null,
        contentType = "Novel",
        rating = 0.0,
        shelfId = "reading",
        totalChapters = 0,
        inLibrary = inLibrary,
        addedAt = 1L,
        updatedAt = 1L,
        lastReadAt = null,
        lastChapterId = null,
        lastChapterIndex = null,
        chapterListHash = null,
        lastUpdateAt = null,
        genresJson = "[]",
        firstSeenAt = 1L,
        addedToLibraryAt = 1L,
    )
}
