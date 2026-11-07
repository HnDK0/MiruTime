package dev.mirutime.data.library

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

class LibraryMembershipTest {
    private lateinit var db: LibraryDatabase
    private lateinit var repository: LibraryRepository

    @BeforeTest
    fun setUp() {
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
    fun `url match wins and title count fills the number`() = runTest {
        db.libraryDao().upsertBook(book("u1", "X", inLibrary = true))
        db.libraryDao().upsertBook(book("u2", "X", inLibrary = true))
        db.libraryDao().upsertBook(book("u3", "Y", inLibrary = false))

        val membership = repository.libraryMembership(listOf("u1", "u3"), listOf("X", "Y"))

        assertEquals(setOf("u1"), membership.urls)
        assertEquals(setOf(libraryBookKey("source", "u1")), membership.keys)
        assertEquals(1, membership.titles["X"])
        assertEquals(null, membership.titles["Y"])
    }

    @Test
    fun `title count is distinct sources, not rows`() = runTest {
        db.libraryDao().upsertBook(book("u4", "Z", inLibrary = true, installId = "a"))
        db.libraryDao().upsertBook(book("u5", "Z", inLibrary = true, installId = "b"))
        db.libraryDao().upsertBook(book("u6", "Z", inLibrary = true, installId = "b"))

        val membership = repository.libraryMembership(emptyList(), listOf("Z"))

        assertEquals(2, membership.titles["Z"])
    }

    @Test
    fun `empty input makes no query`() = runTest {
        assertEquals(LibraryMembership(), repository.libraryMembership(emptyList(), emptyList()))
    }

    @Test
    fun `urls and titles chunk at 900`() = runTest {
        val urlChunks = mutableListOf<Int>()
        val titleChunks = mutableListOf<Int>()
        val dao = Proxy.newProxyInstance(
            LibraryDao::class.java.classLoader,
            arrayOf(LibraryDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "bookKeysByUrls" -> {
                    val chunk = (args!!.first() as List<*>).filterIsInstance<String>()
                    urlChunks += chunk.size
                    chunk.map { BookKeyRow(bookId = 0, installId = "source", url = it) }
                }
                "libraryTitleCounts" -> {
                    val chunk = (args!!.first() as List<*>).filterIsInstance<String>()
                    titleChunks += chunk.size
                    chunk.map { LibraryTitleCount(it, 1) }
                }
                else -> throw AssertionError("Unexpected DAO call: ${method.name}")
            }
        } as LibraryDao

        val membership = LibraryRepository(dao).libraryMembership(
            urls = (1..1801).map { "u$it" },
            titles = (1..1801).map { "t$it" },
        )

        assertEquals(listOf(900, 900, 1), urlChunks)
        assertEquals(listOf(900, 900, 1), titleChunks)
        assertEquals(1801, membership.urls.size)
        assertEquals(1801, membership.keys.size)
        assertEquals(1801, membership.titles.size)
    }

    private fun book(
        url: String,
        title: String,
        inLibrary: Boolean,
        installId: String = "source",
    ) = BookEntity(
        installId = installId,
        url = url,
        title = title,
        author = "",
        coverUrl = null,
        contentType = "Novel",
        rating = 4.5,
        shelfId = "reading",
        totalChapters = 100,
        inLibrary = inLibrary,
        addedAt = 1L,
        updatedAt = 1L,
        lastReadAt = null,
        lastChapterId = null,
        lastChapterIndex = null,
        chapterListHash = null,
        lastUpdateAt = null,
        genresJson = "[]",
    )
}
