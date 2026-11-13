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

class RemoteAudioBookStoreTest {
    private lateinit var db: LibraryDatabase
    private lateinit var repo: LibraryRepository
    private lateinit var store: LibraryRemoteAudioBookStore

    @BeforeTest
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder<LibraryDatabase>(
            factory = LibraryDatabaseConstructor::initialize,
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        repo = LibraryRepository(db.libraryDao())
        repo.ensureDefaultShelves()
        store = LibraryRemoteAudioBookStore(repo)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `saving writes the book and one chapter per track, no file rows`() = runTest {
        val bookId = store.save(book())

        val row = checkNotNull(repo.book("opds:cat-1", ENTRY))
        assertEquals("Audio", row.contentType)
        assertEquals(3, row.totalChapters)
        assertTrue(row.inLibrary)
        assertEquals(bookId, row.bookId)
        assertTrue(row.extrasJson.contains("\"opdsCatalogName\":\"Archive.org\""))

        val chapters = repo.chapters(bookId)
        assertEquals(listOf(1, 2, 3), chapters.map { it.idx })
        assertEquals(
            listOf("https://a.test/1.mp3", "https://a.test/2.mp3", "https://a.test/3.mp3"),
            chapters.map { it.url },
        )
        assertEquals("02 - Deuxieme", chapters[1].title)
        assertTrue(chapters.all { repo.chapterBody(it.chapterId) == null })
        assertTrue(chapters.all { repo.chapterPage(it.chapterId) == null })
        assertNull(repo.localBookFile(bookId))
        assertTrue(repo.localBookCopyPaths().isEmpty())
    }

    @Test
    fun `saving the same identity again keeps chapter ids and progress`() = runTest {
        val bookId = store.save(book())
        val before = repo.chapters(bookId)
        repo.saveVideoProgress(bookId, before.first().chapterId, 5_000, 60_000, 10L)

        val again = store.save(book(at = 2_000L, title = "La vie des abeilles, 2e ed."))

        val row = checkNotNull(repo.book("opds:cat-1", ENTRY))
        assertEquals(bookId, row.bookId)
        assertEquals(again, bookId)
        assertEquals("La vie des abeilles, 2e ed.", row.title)
        assertEquals(1_000L, row.addedAt)
        assertEquals(before.map { it.chapterId }, repo.chapters(bookId).map { it.chapterId })
        assertEquals(5_000L, repo.videoProgress(bookId, before.first().chapterId)?.positionMs)
        assertTrue(repo.localBookCopyPaths().isEmpty())
    }

    private fun book(at: Long = 1_000L, title: String = "La vie des abeilles") = RemoteAudioBook(
        installId = "opds:cat-1",
        url = ENTRY,
        title = title,
        author = "Maurice Maeterlinck",
        description = "Archive.org",
        coverUrl = "https://a.test/cover.jpg",
        extrasJson = """{"opdsCatalogName":"Archive.org","opdsCatalogUrl":"https://archive.org/services/opds"}""",
        tracks = listOf(
            RemoteAudioTrack("https://a.test/1.mp3", "01 - Premier"),
            RemoteAudioTrack("https://a.test/2.mp3", "02 - Deuxieme"),
            RemoteAudioTrack("https://a.test/3.mp3", "03 - Troisieme"),
        ),
        at = at,
    )

    private companion object {
        const val ENTRY = "https://archive.org/details/vie_des_abeilles_2002_librivox"
    }
}
