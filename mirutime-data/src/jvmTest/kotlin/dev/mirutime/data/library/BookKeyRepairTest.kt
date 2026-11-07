package dev.mirutime.data.library

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookKeyRepairTest {

    private val open = mutableListOf<LibraryDatabase>()

    @AfterTest
    fun tearDown() {
        open.forEach { it.close() }
    }

    private fun openRoom(): LibraryDatabase =
        Room.inMemoryDatabaseBuilder<LibraryDatabase>(
            factory = LibraryDatabaseConstructor::initialize,
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
            .also { open += it }

    private fun book(
        url: String,
        installId: String = "install-a",
        inLibrary: Boolean = false,
        title: String = "Book",
        addedAt: Long = 10L,
    ) = BookEntity(
        installId = installId,
        url = url,
        title = title,
        author = "Author",
        coverUrl = null,
        contentType = "Novel",
        rating = 0.0,
        shelfId = "reading",
        totalChapters = 0,
        inLibrary = inLibrary,
        addedAt = addedAt,
        updatedAt = addedAt,
        lastReadAt = null,
        lastChapterId = null,
        lastChapterIndex = null,
        chapterListHash = null,
        lastUpdateAt = null,
        genresJson = "[]",
    )

    private suspend fun addChapter(dao: LibraryDao, bookId: Long) {
        dao.upsertChapter(
            ChapterEntity(
                chapterId = 0,
                bookId = bookId,
                idx = 1,
                url = "https://s/book/1",
                title = "One",
                updatedAt = 1L,
            ),
        )
    }

    @Test
    fun `a childless key row without a twin is rewritten in place`() = runTest {
        val db = openRoom()
        val dao = db.libraryDao()
        val keyUrl = libraryBookKey("install-a", "https://s/book")
        val id = dao.upsertBook(book(keyUrl, installId = ""))

        val report = LibraryRepository(dao).repairKeyUrls()

        assertEquals(1, report.rewrittenInPlace)
        val row = assertNotNull(dao.book(id))
        assertEquals("install-a", row.installId)
        assertEquals("https://s/book", row.url)
    }

    @Test
    fun `a childless key row merges into a rich twin`() = runTest {
        val db = openRoom()
        val dao = db.libraryDao()
        val keyUrl = libraryBookKey("install-a", "https://s/book")
        val twinId = dao.upsertBook(
            book("https://s/book", inLibrary = true, title = "", addedAt = 5L)
                .copy(shelfId = "completed", addedToLibraryAt = 3L),
        )
        addChapter(dao, twinId)
        dao.upsertHistory(HistoryEntity(bookId = twinId, readAt = 7L))
        dao.upsertBook(book(keyUrl, title = "Key title", addedAt = 20L))

        val rewrites = mutableListOf<Pair<String, String>>()
        val report = LibraryRepository(dao).repairKeyUrls { old, new ->
            rewrites += old to new
        }

        assertEquals(1, report.deletedEmpty)
        assertEquals(listOf(keyUrl to "https://s/book"), rewrites)
        val row = assertNotNull(dao.book(twinId))
        assertEquals("Key title", row.title)
        assertTrue(row.inLibrary)
        assertEquals("completed", row.shelfId)
        assertEquals(5L, row.addedAt)
        assertTrue(dao.chapters(twinId).isNotEmpty())
        assertEquals(listOf(twinId), dao.history(10).map { it.bookId })
        assertNull(dao.bookByInstallAndUrl("install-a", keyUrl))
    }

    @Test
    fun `the surviving row metadata wins over the key row`() = runTest {
        val db = openRoom()
        val dao = db.libraryDao()
        val keyUrl = libraryBookKey("install-a", "https://s/book")
        dao.upsertBook(
            book(keyUrl, title = "Key title").copy(coverUrl = "https://c/key.png"),
        )
        val twinId = dao.upsertBook(
            book("https://s/book", title = "Twin title").copy(coverUrl = "https://c/twin.png"),
        )
        addChapter(dao, twinId)

        LibraryRepository(dao).repairKeyUrls()

        val row = assertNotNull(dao.book(twinId))
        assertEquals("Twin title", row.title)
        assertEquals("https://c/twin.png", row.coverUrl)
    }

    @Test
    fun `a rich key row with a childless twin keeps its children`() = runTest {
        val db = openRoom()
        val dao = db.libraryDao()
        val keyUrl = libraryBookKey("install-a", "https://s/book")
        val keyId = dao.upsertBook(book(keyUrl, inLibrary = true, addedAt = 10L))
        addChapter(dao, keyId)
        dao.upsertHistory(HistoryEntity(bookId = keyId, readAt = 7L))
        val twinId = dao.upsertBook(book("https://s/book", addedAt = 5L))

        val report = LibraryRepository(dao).repairKeyUrls()

        assertEquals(1, report.rewrittenInPlace)
        val row = assertNotNull(dao.book(keyId))
        assertEquals("https://s/book", row.url)
        assertEquals("install-a", row.installId)
        assertTrue(row.inLibrary)
        assertEquals(5L, row.addedAt)
        assertTrue(dao.chapters(keyId).isNotEmpty())
        assertEquals(listOf(keyId), dao.history(10).map { it.bookId })
        assertNull(dao.book(twinId))
    }

    @Test
    fun `children on both sides are reported and left untouched`() = runTest {
        val db = openRoom()
        val dao = db.libraryDao()
        val keyUrl = libraryBookKey("install-a", "https://s/book")
        val keyId = dao.upsertBook(book(keyUrl))
        addChapter(dao, keyId)
        val twinId = dao.upsertBook(book("https://s/book"))
        addChapter(dao, twinId)

        val report = LibraryRepository(dao).repairKeyUrls()

        assertEquals(1, report.ambiguous)
        assertEquals(keyUrl, dao.book(keyId)?.url)
        assertNotNull(dao.book(twinId))
    }

    @Test
    fun `soft-removed chapters are reported separately from a real ambiguity`() = runTest {
        val db = openRoom()
        val dao = db.libraryDao()
        val keyUrl = libraryBookKey("install-a", "https://s/book")
        val keyId = dao.upsertBook(book(keyUrl))
        dao.upsertChapter(
            ChapterEntity(
                chapterId = 0,
                bookId = keyId,
                idx = 1,
                url = "https://s/book/old",
                title = "Old",
                removedAt = 1L,
                updatedAt = 1L,
            ),
        )
        val twinId = dao.upsertBook(book("https://s/book"))
        dao.upsertChapter(
            ChapterEntity(
                chapterId = 0,
                bookId = twinId,
                idx = 1,
                url = "https://s/book/old",
                title = "Old",
                removedAt = 1L,
                updatedAt = 1L,
            ),
        )

        val report = LibraryRepository(dao).repairKeyUrls()

        assertEquals(1, report.ambiguousSoftRemoved)
        assertEquals(0, report.ambiguous)
    }

    @Test
    fun `a blank install twin is adopted`() = runTest {
        val db = openRoom()
        val dao = db.libraryDao()
        val keyUrl = libraryBookKey("install-a", "https://s/book")
        val twinId = dao.upsertBook(book("https://s/book", installId = ""))
        addChapter(dao, twinId)
        dao.upsertBook(book(keyUrl))

        val report = LibraryRepository(dao).repairKeyUrls()

        assertEquals(1, report.deletedEmpty)
        val row = assertNotNull(dao.book(twinId))
        assertEquals("install-a", row.installId)
        assertEquals("https://s/book", row.url)
        assertTrue(dao.chapters(twinId).isNotEmpty())
    }

    @Test
    fun `a rewrite reports the old key and the real url`() = runTest {
        val db = openRoom()
        val dao = db.libraryDao()
        val keyUrl = libraryBookKey("install-a", "https://s/book")
        dao.upsertBook(book(keyUrl))
        val rewrites = mutableListOf<Pair<String, String>>()

        val report = LibraryRepository(dao).repairKeyUrls { old, new ->
            rewrites += old to new
        }

        assertEquals(1, report.rewrittenInPlace)
        assertEquals(listOf(keyUrl to "https://s/book"), rewrites)
    }

    @Test
    fun `an undecodable key is reported and left alone`() = runTest {
        val db = openRoom()
        val dao = db.libraryDao()
        val broken = "miru-book:v1:zz"
        val id = dao.upsertBook(book(broken))

        val report = LibraryRepository(dao).repairKeyUrls()

        assertEquals(1, report.unrepairable)
        assertEquals(broken, dao.book(id)?.url)
    }

    @Test
    fun `a second run changes nothing`() = runTest {
        val db = openRoom()
        val dao = db.libraryDao()
        val keyUrl = libraryBookKey("install-a", "https://s/book")
        val id = dao.upsertBook(book(keyUrl))
        val repository = LibraryRepository(dao)

        assertEquals(1, repository.repairKeyUrls().rewrittenInPlace)
        val second = repository.repairKeyUrls()

        assertEquals(0, second.rewrittenInPlace)
        assertEquals(0, second.deletedEmpty)
        assertEquals(0, second.ambiguous)
        assertEquals("https://s/book", dao.book(id)?.url)
    }
}
