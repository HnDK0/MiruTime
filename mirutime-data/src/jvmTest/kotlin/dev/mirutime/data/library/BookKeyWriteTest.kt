package dev.mirutime.data.library

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookKeyWriteTest {

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

    private fun book(url: String) = BookEntity(
        installId = "install-a",
        url = url,
        title = "Book",
        author = "Author",
        coverUrl = null,
        contentType = "Novel",
        rating = 0.0,
        shelfId = "reading",
        totalChapters = 0,
        inLibrary = false,
        addedAt = 1L,
        updatedAt = 1L,
        lastReadAt = null,
        lastChapterId = null,
        lastChapterIndex = null,
        chapterListHash = null,
        lastUpdateAt = null,
        genresJson = "[]",
    )

    @Test
    fun `identity merge keeps local membership shelf and badge`() = runTest {
        val db = openRoom()
        val repository = LibraryRepository(db.libraryDao())

        val id = repository.saveBook(
            book("https://s/b").copy(
                inLibrary = true,
                shelfId = "completed",
                newChapters = 3,
                addedAt = 10L,
            ),
        )
        val again = repository.saveBook(
            book("https://s/b").copy(
                title = "New",
                inLibrary = false,
                shelfId = "reading",
                newChapters = 0,
                addedAt = 99L,
            ),
        )

        assertEquals(id, again)
        val row = db.libraryDao().book(id)!!
        assertEquals("New", row.title)
        assertTrue(row.inLibrary)
        assertEquals("completed", row.shelfId)
        assertEquals(3, row.newChapters)
        assertEquals(10L, row.addedAt)
    }

    @Test
    fun `a legacy blank install row is adopted by the next write`() = runTest {
        val db = openRoom()
        val repository = LibraryRepository(db.libraryDao())

        val legacy = db.libraryDao().upsertBook(
            book("https://s/b").copy(installId = "", inLibrary = true, addedAt = 5L),
        )
        val saved = repository.saveBook(book("https://s/b").copy(addedAt = 10L))

        assertEquals(legacy, saved)
        val row = db.libraryDao().book(legacy)!!
        assertEquals("install-a", row.installId)
        assertTrue(row.inLibrary)
        assertEquals(5L, row.addedAt)
    }

    @Test
    fun `new rows get a positive id`() = runTest {
        val db = openRoom()
        val repository = LibraryRepository(db.libraryDao())

        val first = repository.saveBook(book("https://s/a"))
        val second = repository.saveBook(book("https://s/b"))

        assertTrue(first > 0L)
        assertTrue(second > 0L)
        assertEquals(2, db.libraryDao().bookCount())
    }

    @Test
    fun `the resolver reports created only for a new identity`() = runTest {
        val db = openRoom()
        val dao = db.libraryDao()

        val first = dao.upsertBookByIdentity(book("https://s/a"))
        val again = dao.upsertBookByIdentity(book("https://s/a").copy(title = "Updated"))

        assertTrue(first.created)
        assertTrue(first.id > 0L)
        assertEquals(false, again.created)
        assertEquals(first.id, again.id)
        assertEquals("Updated", dao.book(first.id)?.title)
    }
}
