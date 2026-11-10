package dev.mirutime.data.library

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `only in-library novel books are selected for file backup`() = runTest {
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

        assertEquals(setOf(novelId), selected.map { it.bookId }.toSet())
        assertTrue(
            selected.none {
                it.bookId == mangaId || it.bookId == outOfLibraryId || it.bookId == pluginId
            },
        )

        val novel = selected.first { it.bookId == novelId }
        assertEquals("local_books/local-books/novel.epub", novel.copyPath)
        assertEquals("local-books", novel.installId)
        assertEquals("covers/$novelId/cover", novel.coverLocalPath)
        assertEquals(
            listOf("local_books/local-books/novel/chapters/1.html"),
            novel.bodyPaths,
        )
    }

    @Test
    fun `restore prune queries separate pages from manga-only rows`() = runTest {
        val novelId = localNovel()
        val mangaId = localMangaFolder()
        val novelChapter = db.libraryDao().chapters(novelId).single().chapterId
        val mangaChapter = db.libraryDao().chapters(mangaId).single().chapterId
        repository.saveChapterBody(
            ChapterBodyEntity(
                chapterId = mangaChapter,
                format = "html",
                path = "local_books/local-manga/folder/chapters/1/00001.html",
                bytes = 5,
                sha256 = "manga-body",
                fetchedAt = 1L,
            ),
        )

        val pages = db.libraryDao().restorePageRows()
        assertEquals(setOf(novelChapter, mangaChapter), pages.map { it.chapterId }.toSet())
        assertEquals(
            listOf(mangaChapter),
            db.libraryDao().localMangaBodyRows().map { it.chapterId },
        )
        assertEquals(listOf(mangaId), db.libraryDao().localMangaFileRows().map { it.bookId })
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
