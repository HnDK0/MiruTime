package dev.mirutime.app.features.book.data

import dev.mirutime.app.features.book.domain.BookDetails
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.core.api.BookMeta
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.libraryBookKey
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginBookEntityUrlTest {

    private val realUrl = "https://site/book/1?q=2"
    private val installId = "fake-source"
    private val key = libraryBookKey(installId, realUrl)

    private fun details(title: String) = BookDetails(
        bookUrl = key,
        title = title,
        source = "Fake",
        installId = installId,
        author = "Author",
        originLanguage = null,
        contentType = ContentType.Novel,
        rating = 0f,
        ongoing = false,
        updatedLabel = "",
        totalChapters = 0,
        readCount = 0,
        readMinutes = 0,
        ttsMinutes = 0,
        inLibrary = false,
        shelfId = "reading",
        genres = emptyList(),
        description = "",
        sourceUrl = realUrl,
        authUrl = null,
        chapters = emptyList(),
        reviews = emptyList(),
    )

    private fun existing(title: String = "Stored") = BookEntity(
        bookId = 4,
        installId = installId,
        url = realUrl,
        title = title,
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
    fun `persisted url decodes the opaque key`() {
        assertEquals(realUrl, persistedBookUrl(key))
    }

    @Test
    fun `row builder never stores the key as the book url`() {
        val entity = PluginBookDataProvider().buildEntity(
            bookUrl = key,
            sourceId = installId,
            built = details("Book"),
            meta = BookMeta(title = "Book", genres = listOf("a", "a")),
            existing = null,
            timestamp = 1L,
        )

        assertEquals(realUrl, entity.url)
        assertEquals("Book", entity.title)
        assertEquals("[\"a\"]", entity.genresJson)
        assertEquals("{}", entity.extrasJson)
    }

    @Test
    fun `row builder keeps a stored title when the plugin sends blank`() {
        val entity = PluginBookDataProvider().buildEntity(
            bookUrl = key,
            sourceId = installId,
            built = details(""),
            meta = BookMeta(title = ""),
            existing = existing(),
            timestamp = 2L,
        )

        assertEquals("Stored", entity.title)
    }

    @Test
    fun `row builder preserves existing addedToLibraryAt on refresh`() {
        val existingBook = existing().copy(inLibrary = true, addedAt = 50L, addedToLibraryAt = 100L)
        val entity = PluginBookDataProvider().buildEntity(
            bookUrl = key,
            sourceId = installId,
            built = details("Book").copy(inLibrary = true),
            meta = BookMeta(title = "Book"),
            existing = existingBook,
            timestamp = 999L,
        )

        assertEquals(100L, entity.addedToLibraryAt)
        assertEquals(50L, entity.addedAt)
    }

    @Test
    fun `row builder preserves legacy addedAt when addedToLibraryAt is null on refresh`() {
        val legacyBook = existing().copy(inLibrary = true, addedAt = 50L, addedToLibraryAt = null)
        val entity = PluginBookDataProvider().buildEntity(
            bookUrl = key,
            sourceId = installId,
            built = details("Book").copy(inLibrary = true),
            meta = BookMeta(title = "Book"),
            existing = legacyBook,
            timestamp = 999L,
        )

        // Must keep 50L, never reset to 999L (now) on refresh!
        assertEquals(50L, entity.addedToLibraryAt)
        assertEquals(50L, entity.addedAt)
    }

    @Test
    fun `row builder keeps a stored status when the plugin sends blank`() {
        val stored = existing().copy(statusRaw = "连载中", statusNormalized = "UNKNOWN")
        val entity = PluginBookDataProvider().buildEntity(
            bookUrl = key,
            sourceId = installId,
            built = details("Book"),
            meta = BookMeta(title = "Book"),
            existing = stored,
            timestamp = 3L,
        )

        assertEquals("连载中", entity.statusRaw)
        assertEquals("UNKNOWN", entity.statusNormalized)
    }

    @Test
    fun `row builder prefers a fresh status over the stored one`() {
        val stored = existing().copy(statusRaw = "连载中", statusNormalized = "ONGOING")
        val entity = PluginBookDataProvider().buildEntity(
            bookUrl = key,
            sourceId = installId,
            built = details("Book"),
            meta = BookMeta(title = "Book", status = "已完结"),
            existing = stored,
            timestamp = 3L,
        )

        assertEquals("已完结", entity.statusRaw)
    }

    @Test
    fun `row builder stamps current timestamp when newly added to library`() {
        val freshEntity = PluginBookDataProvider().buildEntity(
            bookUrl = key,
            sourceId = installId,
            built = details("Book").copy(inLibrary = true),
            meta = BookMeta(title = "Book"),
            existing = null,
            timestamp = 999L,
        )

        assertEquals(999L, freshEntity.addedToLibraryAt)
    }
}
