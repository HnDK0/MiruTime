package dev.mirutime.app.features.reader.data

import dev.mirutime.app.features.book.domain.BookDetails
import dev.mirutime.app.features.library.domain.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderBookMapperTest {
    private fun details(contentType: ContentType) = BookDetails(
        bookUrl = "https://plugin.example/book",
        title = "Book",
        source = "Plugin",
        author = "Author",
        originLanguage = null,
        contentType = contentType,
        rating = 0f,
        ongoing = false,
        updatedLabel = "",
        totalChapters = 0,
        readCount = 0,
        readMinutes = 0,
        ttsMinutes = 0,
        inLibrary = false,
        shelfId = "",
        genres = emptyList(),
        description = "",
        sourceUrl = "https://plugin.example/book",
        authUrl = null,
        chapters = emptyList(),
        reviews = emptyList(),
    )

    @Test
    fun `manga content type becomes a manga reader book`() {
        assertTrue(details(ContentType.Manga).toReaderBook().isManga)
    }

    @Test
    fun `novel content type stays a text reader book`() {
        assertFalse(details(ContentType.Novel).toReaderBook().isManga)
    }

    @Test
    fun `cover travels to the reader for notification artwork`() {
        val book = details(ContentType.Novel).copy(coverUrl = "file:///covers/1")

        assertEquals("file:///covers/1", book.toReaderBook().coverUrl)
    }
}
