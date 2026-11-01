package dev.mirutime.app.features.library.data

import dev.mirutime.app.features.library.domain.LibrarySort
import dev.mirutime.app.features.library.domain.SortDirection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeLibraryPagingTest {

    private val provider = FakeLibraryDataProvider()

    @Test
    fun `fake pages by offset with total and cursor`() = runTest {
        val urls = mutableListOf<String>()
        var cursor: LibraryPageCursor? = null
        var pages = 0
        do {
            val page = provider.libraryPage(
                query = "",
                cursor = cursor,
                limit = 1,
                filters = LibraryPageFilters(),
                sort = LibrarySort.LastRead,
                direction = SortDirection.Desc,
            )
            urls += page.books.map { it.bookUrl }
            assertEquals(3, page.total)
            cursor = page.nextCursor
            pages++
        } while (page.hasMore)

        assertEquals(3, urls.distinct().size)
        assertEquals(3, pages)
        // LastRead Desc = most recent fixture first.
        assertEquals("fake://toonsource/mage-ten-thousand-rebirths", urls.first())
    }

    @Test
    fun `fake filters match shelf source type and genres`() = runTest {
        val reading = provider.libraryPage(
            query = "",
            cursor = null,
            limit = 60,
            filters = LibraryPageFilters(shelfId = "reading"),
            sort = LibrarySort.LastRead,
            direction = SortDirection.Desc,
        )
        assertEquals(2, reading.total)
        assertTrue(reading.books.all { it.shelfId == "reading" })

        val source = provider.libraryPage(
            query = "",
            cursor = null,
            limit = 60,
            filters = LibraryPageFilters(sources = setOf("NovelNest")),
            sort = LibrarySort.LastRead,
            direction = SortDirection.Desc,
        )
        assertEquals(listOf("NovelNest"), source.books.map { it.source })

        val genre = provider.libraryPage(
            query = "",
            cursor = null,
            limit = 60,
            filters = LibraryPageFilters(genres = setOf("Cultivation")),
            sort = LibrarySort.LastRead,
            direction = SortDirection.Desc,
        )
        assertEquals(1, genre.books.size)
        assertEquals("fake://wuxia-archive/iron-lantern", genre.books.single().bookUrl)
    }

    @Test
    fun `fake search keeps the query and filters`() = runTest {
        val found = provider.libraryPage(
            query = "Iron",
            cursor = null,
            limit = 60,
            filters = LibraryPageFilters(),
            sort = LibrarySort.LastRead,
            direction = SortDirection.Desc,
        )
        assertEquals(listOf("fake://wuxia-archive/iron-lantern"), found.books.map { it.bookUrl })
        assertFalse(found.hasMore)

        val filteredOut = provider.libraryPage(
            query = "Iron",
            cursor = null,
            limit = 60,
            filters = LibraryPageFilters(shelfId = "reading"),
            sort = LibrarySort.LastRead,
            direction = SortDirection.Desc,
        )
        assertTrue(filteredOut.books.isEmpty())
        assertEquals(0, filteredOut.total)
    }

    @Test
    fun `sort applies only to the unsearched library`() {
        assertTrue(sortAppliesToQuery(""))
        assertTrue(sortAppliesToQuery("   "))
        assertFalse(sortAppliesToQuery("Iron"))
    }

    @Test
    fun `fake search ignores sort and direction`() = runTest {
        val byLastRead = provider.libraryPage(
            query = "e",
            cursor = null,
            limit = 60,
            filters = LibraryPageFilters(),
            sort = LibrarySort.LastRead,
            direction = SortDirection.Desc,
        )
        val byTitle = provider.libraryPage(
            query = "e",
            cursor = null,
            limit = 60,
            filters = LibraryPageFilters(),
            sort = LibrarySort.Title,
            direction = SortDirection.Asc,
        )

        assertTrue(byLastRead.books.size > 1)
        assertEquals(
            byLastRead.books.map { it.bookUrl },
            byTitle.books.map { it.bookUrl },
        )
    }

    @Test
    fun `fake facets summaries and select all come from the same rows`() = runTest {
        val facets = provider.libraryFacets()
        assertEquals(listOf("NovelNest", "ToonSource", "Wuxia Archive"), facets.sources)
        assertTrue("Action" in facets.genres)

        val summaries = provider.shelfSummaries().associateBy { it.shelfId }
        assertEquals(2, summaries.getValue("reading").bookCount)
        assertEquals(1, summaries.getValue("wuxia").bookCount)

        val readingUrls = provider.matchingLibraryUrls(
            query = "",
            filters = LibraryPageFilters(shelfId = "reading"),
        )
        assertEquals(2, readingUrls.size)
        assertEquals(readingUrls.distinct(), readingUrls)

        val searchUrls = provider.matchingLibraryUrls(
            query = "NovelNest",
            filters = LibraryPageFilters(shelfId = "reading"),
        )
        assertEquals(listOf("fake://novelnest/chronicles-seven-kingdoms"), searchUrls)
        assertNotNull(summaries.getValue("reading").coverTitle)
    }
}
