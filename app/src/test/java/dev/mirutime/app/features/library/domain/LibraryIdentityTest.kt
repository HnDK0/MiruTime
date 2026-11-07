package dev.mirutime.app.features.library.domain

import dev.mirutime.app.core.libraryListKey
import dev.mirutime.app.core.libraryRowKey
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryIdentityTest {

    private fun book(url: String, source: String) = LibraryBook(
        bookUrl = url,
        title = url,
        source = source,
        installId = source,
        author = "author",
        rating = 0f,
        contentType = ContentType.Novel,
        totalChapters = 0,
        readChapters = 0,
        hasUpdate = false,
        shelfId = "reading",
        genres = emptyList(),
        lastReadRank = 0,
        lastUpdateRank = 0,
        addedRank = 0,
        lastReadLabel = "",
        continueLabel = "",
    )

    @Test
    fun `same url from two installs stays two books`() {
        val shown = dedupeLibraryBooks(
            listOf(
                book("https://site/book/1", "install-a"),
                book("https://site/book/1", "install-b"),
                book("https://site/book/2", "install-a"),
            ),
        )

        assertEquals(
            listOf(
                "https://site/book/1",
                "https://site/book/1",
                "https://site/book/2",
            ),
            shown.map { it.bookUrl },
        )
    }

    @Test
    fun `grid rows and list keys are unique after dedupe`() {
        val shown = dedupeLibraryBooks(
            listOf(
                book("https://site/book/1", "install-a"),
                book("https://site/book/1", "install-b"),
                book("https://site/book/2", "install-a"),
                book("https://site/book/3", "install-a"),
            ),
        )

        val gridKeys = shown.chunked(2).map { row -> libraryRowKey(row.first().bookKey) }
        val listKeys = shown.map { libraryListKey(it.bookKey) }

        assertEquals(gridKeys.size, gridKeys.distinct().size)
        assertEquals(listKeys.size, listKeys.distinct().size)
    }
}
