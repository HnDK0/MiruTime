package dev.mirutime.app.features.migration.data

import dev.mirutime.app.features.book.data.BookDataProvider
import dev.mirutime.app.features.book.data.MembershipResult
import dev.mirutime.app.features.book.domain.BookChapter
import dev.mirutime.app.features.book.domain.BookDetails
import dev.mirutime.app.features.finder.domain.CatalogSource
import dev.mirutime.app.features.library.domain.ContentType as LibraryContentType
import dev.mirutime.core.reader.ChapterContent
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.ChapterEntity
import dev.mirutime.data.library.LibraryDao
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.migration.BookMigrationPlanner
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookMigrationWiringTest {
    @Test
    fun `app target resolver builds the core target and plans real chapters`() = runTest {
        val provider = FakeBookProvider(targetDetails(), targetChapters())
        val targets = BookMigrationTargets(provider, FakeRepository.nullRows(), now = { 2_000L })

        val target = targets.load(
            sourceId = "newsrc",
            sourceName = "New source",
            bookUrl = "https://new.test/book",
            fallbackTitle = "Book",
        )
        assertNotNull(target)
        assertEquals("newsrc", target!!.target.book.installId)
        assertEquals("https://new.test/book", target.target.book.url)
        assertEquals(0L, target.target.book.bookId)
        assertEquals(2, target.target.chapters.size)
        assertEquals("https://new.test/book/2", target.target.chapters[1].url)

        val oldBook = oldBook()
        val oldChapters = listOf(
            oldChapter(11, 1, "https://old.test/book/1", "Chapter 1"),
            oldChapter(12, 2, "https://old.test/book/2", "Chapter 2"),
        )
        val plan = BookMigrationPlanner().plan(oldBook, oldChapters, target.target)
        assertEquals(2, plan.chaptersMatched)
        assertEquals(2, plan.chaptersTotal)
        assertEquals(listOf(11L, 12L), plan.pairs.map { it.old.chapterId })
    }

    @Test
    fun `candidate list marks unknown sources and skips local imports`() = runTest {
        val dao = FakeRepository.daoWithBooks(
            listOf(
                oldBook(),
                oldBook().copy(installId = "local-books", url = "local://a"),
            ),
        )
        val repository = LibraryRepository(dao)
        val targets = BookMigrationTargets(FakeBookProvider(targetDetails(), targetChapters()), repository)
        val sources = listOf(CatalogSource("newsrc", "New source", "EN", LibraryContentType.Novel, "1", true))

        val candidates = targets.candidates(sources)

        assertEquals(1, candidates.size)
        assertEquals("oldsrc", candidates.single().sourceId)
        assertTrue(candidates.single().unknownSource)
    }

    private fun oldChapter(chapterId: Long, index: Int, url: String, title: String) =
        ChapterEntity(
            chapterId = chapterId,
            bookId = 1,
            idx = index,
            url = url,
            title = title,
            hash = null,
            read = false,
            readAt = null,
            downloaded = false,
            removedAt = null,
            updatedAt = 1L,
        )

    private fun oldBook() = BookEntity(
        installId = "oldsrc",
        url = "https://old.test/book",
        title = "Book",
        author = "Author",
        coverUrl = null,
        contentType = "Novel",
        rating = 0.0,
        shelfId = "reading",
        totalChapters = 2,
        inLibrary = true,
        addedAt = 1L,
        updatedAt = 1L,
        lastReadAt = null,
        lastChapterId = null,
        lastChapterIndex = null,
        chapterListHash = null,
        lastUpdateAt = 1L,
        genresJson = "[]",
    )

    private fun targetDetails() = BookDetails(
        bookUrl = "https://new.test/book",
        title = "Book",
        source = "New source",
        author = "Author",
        originLanguage = "en",
        contentType = LibraryContentType.Novel,
        rating = 0f,
        ongoing = true,
        updatedLabel = "",
        totalChapters = 2,
        readCount = 0,
        readMinutes = 0,
        ttsMinutes = 0,
        inLibrary = false,
        shelfId = "reading",
        genres = listOf("Fantasy"),
        description = "",
        sourceUrl = "https://new.test/book",
        authUrl = null,
        chapters = targetChapters(),
        reviews = emptyList(),
        coverUrl = null,
    )

    private fun targetChapters() = listOf(
        chapter(1, "https://new.test/book/1", "Chapter 1"),
        chapter(2, "https://new.test/book/2", "Chapter 2"),
    )

    private fun chapter(index: Int, url: String, title: String) = BookChapter(
        index = index,
        title = title,
        isRead = false,
        downloaded = false,
        translated = false,
        downloading = false,
        url = url,
    )

    private class FakeBookProvider(
        private val details: BookDetails,
        private val chapters: List<BookChapter>,
    ) : BookDataProvider {
        override suspend fun book(bookUrl: String): BookDetails = details
        override suspend fun ensureHistoryBook(bookUrl: String) = Unit
        override suspend fun chapters(bookUrl: String): List<BookChapter> = chapters
        override suspend fun refreshChapters(bookUrl: String): List<BookChapter> = chapters
        override suspend fun chapterContent(bookUrl: String, chapterIndex: Int): ChapterContent? = null
        override suspend fun moveShelf(bookUrl: String, shelfId: String) = Unit
        override suspend fun setInLibrary(bookUrl: String, inLibrary: Boolean): MembershipResult =
            MembershipResult(success = false)
        override fun setChapterDownloaded(bookUrl: String, range: IntRange, downloaded: Boolean) = Unit
        override suspend fun downloadNext100(bookUrl: String) = Unit
        override suspend fun markChapterRead(bookUrl: String, chapterIndex: Int, read: Boolean) = Unit
    }

    private object FakeRepository {
        fun nullRows(): LibraryRepository = LibraryRepository(daoWithBooks(emptyList()))

        fun daoWithBooks(books: List<BookEntity>): LibraryDao =
            Proxy.newProxyInstance(
                LibraryDao::class.java.classLoader,
                arrayOf(LibraryDao::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "libraryBooks" -> books
                    "bookByInstallAndUrl", "bookByUrl" -> null
                    "hashCode" -> 0
                    "toString" -> "FakeDao"
                    "equals" -> false
                    else -> error("Unexpected DAO call: ${method.name}")
                }
            } as LibraryDao
    }
}
