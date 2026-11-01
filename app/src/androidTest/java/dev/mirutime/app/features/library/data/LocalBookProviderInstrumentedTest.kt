package dev.mirutime.app.features.library.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mirutime.app.di.host.AndroidFileSystem
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.ChapterBodyEntity
import dev.mirutime.data.library.ChapterPageEntity
import dev.mirutime.data.library.ChapterSeed
import dev.mirutime.data.library.LibraryDatabase
import dev.mirutime.data.library.LibraryDatabaseConstructor
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.LocalBookFileEntity
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalBookProviderInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: LibraryDatabase
    private lateinit var repository: LibraryRepository
    private lateinit var fileSystem: AndroidFileSystem

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder<LibraryDatabase>(
            factory = LibraryDatabaseConstructor::initialize,
        )
            .setDriver(BundledSQLiteDriver())
            .build()
        repository = LibraryRepository(database.libraryDao())
        fileSystem = AndroidFileSystem(context.filesDir)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun chapterContentReadsPersistedBodyWithoutParser() = runBlocking {
        val bookUrl = "local://instrumented-book"
        repository.saveBook(book(bookUrl))
        val bookId = repository.book(bookUrl)!!.bookId
        val chapter = repository.replaceChapters(
            bookId,
            listOf(ChapterSeed(1, "$bookUrl#chapter=1", "Chapter 1")),
            at = 1L,
        ).single()
        val html = "<p>Persisted chapter</p><p><img src=\"file:///image.png\"/></p>"
        val path = "local_books/local-books/${bookId}/chapters/${chapter.chapterId}.html"
        fileSystem.write(path, html.toByteArray())
        repository.saveChapterBody(
            ChapterBodyEntity(
                chapterId = chapter.chapterId,
                format = "html",
                path = path,
                bytes = html.toByteArray().size.toLong(),
                sha256 = sha256(html),
                fetchedAt = 1L,
            ),
        )
        repository.saveLocalBookFile(
            LocalBookFileEntity(
                bookId = bookId,
                uri = "content://instrumented/book.epub",
                displayName = "book.epub",
                format = "EPUB",
                mime = "application/epub+zip",
                sizeBytes = 1L,
                sha256 = "file-sha",
                copyPath = "local_books/local-books/copy.epub",
                importedAt = 1L,
            ),
        )

        val provider = LocalBookProvider(repository, fileSystem, context.filesDir)
        val content = provider.chapterContent(bookUrl, "$bookUrl#chapter=1")

        assertEquals("Chapter 1", content?.title)
        assertEquals(html, content?.html)
    }

    @Test
    fun chapterContentRendersStoredPagesInPathOrder() = runBlocking {
        val bookUrl = "local://instrumented-manga"
        repository.saveBook(
            book(bookUrl, installId = LocalMangaInstallId, contentType = "Manga"),
        )
        val bookId = repository.book(bookUrl)!!.bookId
        val chapter = repository.replaceChapters(
            bookId,
            listOf(ChapterSeed(1, "$bookUrl#chapter=1", "Chapter 1")),
            at = 1L,
        ).single()
        val paths = listOf("page-1.jpg", "page-2.jpg", "page-3.jpg")
        paths.forEachIndexed { index, path ->
            fileSystem.write(path, byteArrayOf(index.toByte()))
        }
        repository.saveChapterPage(
            ChapterPageEntity(
                chapterId = chapter.chapterId,
                format = "images",
                pagePathsJson = JSONArray(paths).toString(),
                totalBytes = paths.size.toLong(),
                parserVersion = FolderParserVersion,
            ),
        )

        val provider = LocalBookProvider(repository, fileSystem, context.filesDir)
        val content = provider.chapterContent(bookUrl, "$bookUrl#chapter=1")

        val html = content!!.html
        assertEquals(3, Regex("""<img\b""").findAll(html).count())
        val srcs = Regex("""src="([^"]+)"""").findAll(html)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(paths.map { File(context.filesDir, it).toURI().toString() }, srcs)
    }

    private fun book(
        url: String,
        installId: String = LocalBooksInstallId,
        contentType: String = "Novel",
    ) = BookEntity(
        installId = installId,
        url = url,
        title = "Instrumented",
        author = "Author",
        coverUrl = null,
        contentType = contentType,
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
        firstSeenAt = 1L,
        addedToLibraryAt = 1L,
    )

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
