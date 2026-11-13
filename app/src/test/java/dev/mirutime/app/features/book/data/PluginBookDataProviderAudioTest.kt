package dev.mirutime.app.features.book.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.mirutime.app.features.book.domain.BookActionKind
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.opds.domain.OpdsAddPort
import dev.mirutime.app.features.opds.domain.OpdsBookOption
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginError
import dev.mirutime.core.api.VideoStream
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.ChapterSeed
import dev.mirutime.data.library.LibraryDatabase
import dev.mirutime.data.library.LibraryDatabaseConstructor
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.OpdsCatalogEntity
import dev.mirutime.data.library.libraryBookKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audio detail page and streams come from the stored row, not a plugin. */
class PluginBookDataProviderAudioTest {
    private val catalogId = "cat-1"
    private val catalogUrl = "https://archive.org/services/opds"
    private val entryUrl = "https://archive.org/details/vie_des_abeilles_2002_librivox"
    private val installId = "opds:$catalogId"
    private val bookKey = libraryBookKey(installId, entryUrl)

    private val db: LibraryDatabase = Room.inMemoryDatabaseBuilder<LibraryDatabase>(
        factory = LibraryDatabaseConstructor::initialize,
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    private val repo = LibraryRepository(db.libraryDao())

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `video streams return the stored track with the catalogue basic auth`() = runTest {
        seedBook()
        val provider = provider(headers = mapOf("Authorization" to "Basic bG9naW46cGFzcw=="))

        val result = provider.videoStreams(bookKey, 2)

        assertTrue(result is Outcome.Ok)
        assertEquals(
            listOf(
                VideoStream(
                    url = "https://archive.org/download/x/a_02.mp3",
                    headers = mapOf("Authorization" to "Basic bG9naW46cGFzcw=="),
                    mime = "audio/mpeg",
                ),
            ),
            (result as Outcome.Ok).value,
        )
    }

    @Test
    fun `an audio page is built from the row and opens the library`() = runTest {
        val bookId = seedBook()
        val provider = provider()

        val details = checkNotNull(provider.book(bookKey))

        assertEquals(ContentType.Audio, details.contentType)
        assertEquals("La vie des abeilles", details.title)
        assertEquals("Maurice Maeterlinck", details.author)
        assertEquals(2, details.totalChapters)
        assertEquals(2, details.chapters.size)
        assertEquals(BookActionKind.OpenLibrary, details.action)
        assertEquals(bookKey, details.libraryBookKey)

        repo.setInLibrary(bookId, false)
        provider.invalidateBookCache(bookKey)
        assertEquals(BookActionKind.AddToLibrary, checkNotNull(provider.book(bookKey)).action)
    }

    @Test
    fun `an audio page opens after its catalogue was deleted`() = runTest {
        seedBook(withCatalog = false)
        val provider = provider()

        val details = checkNotNull(provider.book(bookKey))

        // Name and URL come from books.extrasJson, not from a catalogue row.
        assertEquals("Archive.org", details.source)
        assertEquals("https://archive.org/services/opds", details.originCatalogUrl)
        assertEquals(ContentType.Audio, details.contentType)
        assertEquals(2, details.chapters.size)
        assertEquals(BookActionKind.OpenLibrary, details.action)
    }

    @Test
    fun `chapters for an audio book are served from the DB`() = runTest {
        seedBook()
        val provider = provider()

        val chapters = provider.chapters(bookKey)

        assertEquals(listOf(1, 2), chapters.map { it.index })
        assertEquals("02 - Deuxieme", chapters[1].title)
        assertEquals("https://archive.org/download/x/a_02.mp3", chapters[1].url)
    }

    @Test
    fun `chapter content stays unsupported for audio`() = runTest {
        seedBook()
        val provider = provider()

        val failure = runCatching { provider.chapterContent(bookKey, 1) }.exceptionOrNull()

        assertTrue(failure is ChapterDownloadException)
    }

    private suspend fun seedBook(withCatalog: Boolean = true): Long {
        if (withCatalog) {
            repo.saveOpdsCatalog(
                OpdsCatalogEntity(id = catalogId, name = "Archive.org", url = catalogUrl, addedAt = 1L),
            )
        }
        val bookId = repo.saveBook(
            BookEntity(
                installId = installId,
                url = entryUrl,
                title = "La vie des abeilles",
                author = "Maurice Maeterlinck",
                coverUrl = "https://archive.org/download/x/__ia_thumb.jpg",
                contentType = ContentType.Audio.name,
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
                lastUpdateAt = null,
                genresJson = "[]",
                description = "Archive.org",
                extrasJson = """{"opdsCatalogName":"Archive.org","opdsCatalogUrl":"https://archive.org/services/opds"}""",
            ),
        )
        repo.replaceChapters(
            bookId = bookId,
            seeds = listOf(
                ChapterSeed(1, "https://archive.org/download/x/a_01.mp3", "01 - Premier"),
                ChapterSeed(2, "https://archive.org/download/x/a_02.mp3", "02 - Deuxieme"),
            ),
            at = 1L,
        )
        return bookId
    }

    private fun provider(headers: Map<String, String> = emptyMap()) = PluginBookDataProvider(
        library = repo,
        opds = FakeAddPort(headers),
    )

    private class FakeAddPort(
        private val headers: Map<String, String>,
    ) : OpdsAddPort {
        override suspend fun addEntry(
            catalogUrl: String,
            entryUrl: String,
            optionKey: String?,
            catalogName: String,
        ): Outcome<String> = Outcome.Err(PluginError.ScriptError("unused"))

        override suspend fun resolveEntry(
            catalogUrl: String,
            entryUrl: String,
        ): Outcome<List<OpdsBookOption>> = Outcome.Ok(emptyList())

        override suspend fun streamHeaders(
            catalogUrl: String,
            targetUrl: String,
        ): Map<String, String> = headers
    }
}
