package dev.mirutime.app.features.finder.data

import dev.mirutime.app.features.finder.domain.CatalogBook
import dev.mirutime.app.features.finder.domain.CatalogFilters
import dev.mirutime.app.features.finder.domain.CatalogSort
import dev.mirutime.app.features.finder.domain.applyCatalogFilters
import dev.mirutime.app.features.opds.domain.OpdsBook
import dev.mirutime.app.features.opds.domain.OpdsCatalogLink
import dev.mirutime.app.features.opds.domain.OpdsPage
import dev.mirutime.core.opds.OpdsFormat
import dev.mirutime.data.library.libraryBookKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpdsPageMappingTest {

    @Test
    fun `books map format categories and cover`() {
        val page = page(
            books = listOf(
                OpdsBook(
                    title = "Book One",
                    author = "Jane",
                    summary = "Blurb",
                    coverUrl = "https://c.test/1.jpg",
                    format = OpdsFormat.EPUB,
                    downloadUrl = "https://c.test/1.epub",
                    categories = listOf("Fantasy", "Adventure"),
                ),
            ),
        )

        val book = opdsPageToCatalogBooks(SOURCE, page).single()
        assertEquals("https://c.test/1.epub", book.bookUrl)
        assertEquals("Book One", book.title)
        assertEquals(OpdsFormat.EPUB.name, book.localFormat)
        assertEquals(listOf("Fantasy", "Adventure"), book.genres)
        assertEquals("https://c.test/1.jpg", book.coverUrl)
        assertEquals(0f, book.rating)
        assertEquals(0, book.chapters)
        assertTrue(book.notImported)
        // The source id is the card's install id: the card key must decode to
        // `opds:<catalogId>` for the add flow to find the catalog.
        assertEquals(SOURCE, book.installId)
        assertEquals(
            libraryBookKey(SOURCE, "https://c.test/1.epub"),
            book.bookKey,
        )
    }

    @Test
    fun `an entry is keyed by its entry url and carries library membership`() {
        val page = page(
            books = listOf(
                book("Book", "https://c.test/b.epub")
                    .copy(entryUrl = "https://c.test/entry/1"),
            ),
        ).copy(
            membership = mapOf("https://c.test/entry/1" to "miru-book:v1:local:abc"),
        )

        val mapped = opdsPageToCatalogBooks(SOURCE, page).single()

        assertEquals("https://c.test/entry/1", mapped.bookUrl)
        assertEquals(SOURCE, mapped.installId)
        assertEquals("miru-book:v1:local:abc", mapped.libraryBookKey)
    }

    @Test
    fun `navigation links become cards with membership when the feed is paged`() {
        val page = OpdsPage(
            url = "https://c.test/root",
            title = "Catalog",
            links = listOf(OpdsCatalogLink("Indirect", "https://c.test/entry/9")),
            books = emptyList(),
            nextUrl = "https://c.test/root?page=2",
            searchTemplate = null,
            membership = mapOf("https://c.test/entry/9" to "miru-book:v1:local:def"),
        )

        val mapped = opdsPageToCatalogBooks(SOURCE, page).single()

        assertEquals("https://c.test/entry/9", mapped.bookUrl)
        assertEquals(SOURCE, mapped.installId)
        assertEquals("miru-book:v1:local:def", mapped.libraryBookKey)
    }

    @Test
    fun `the last page of a publication feed keeps its links as cards`() {
        val page = OpdsPage(
            url = "https://c.test/root?page=2",
            title = "Catalog",
            links = listOf(OpdsCatalogLink("Indirect", "https://c.test/entry/9")),
            books = emptyList(),
            nextUrl = null,
            searchTemplate = null,
            membership = mapOf("https://c.test/entry/9" to "miru-book:v1:local:def"),
        )

        val mapped = opdsPageToCatalogBooks(SOURCE, page, includeLinks = true).single()

        assertEquals("https://c.test/entry/9", mapped.bookUrl)
        assertEquals("miru-book:v1:local:def", mapped.libraryBookKey)
    }

    @Test
    fun `the last page of a publication feed caches and joins its links`() {
        val page = OpdsPage(
            url = "https://c.test/root?page=2",
            title = "Catalog",
            links = listOf(OpdsCatalogLink("Indirect", "https://c.test/entry/9")),
            books = emptyList(),
            nextUrl = null,
            searchTemplate = null,
        )
        val catalogUrl = "https://c.test/opds"

        val rows = opdsEntryRows(catalogUrl, page, includeLinks = true, now = 7L)

        assertEquals(listOf("https://c.test/entry/9"), rows.map { it.entryUrl })
        // The listing page is what a later resolve re-fetches.
        assertEquals("https://c.test/root?page=2", rows.single().feedUrl)
        assertEquals(listOf("https://c.test/entry/9"), page.entryUrls(includeLinks = true))
        // A navigation feed caches and joins nothing: its links are sections.
        assertTrue(opdsEntryRows(catalogUrl, page, includeLinks = false, now = 7L).isEmpty())
        assertTrue(page.entryUrls(includeLinks = false).isEmpty())
    }


    @Test
    fun `navigation entries are not books`() {
        val page = OpdsPage(
            url = "https://c.test/root",
            title = "Catalog",
            links = listOf(OpdsCatalogLink("Authors", "https://c.test/authors")),
            books = listOf(book("Book", "https://c.test/b.epub")),
            nextUrl = null,
            searchTemplate = null,
        )

        val mapped = opdsPageToCatalogBooks(SOURCE, page)
        assertEquals(listOf("Book"), mapped.map(CatalogBook::title))
    }

    @Test
    fun `entry without a supported format stays visible but not downloadable`() {
        val page = page(
            books = listOf(
                OpdsBook(
                    title = "Pdf Only",
                    author = null,
                    summary = null,
                    coverUrl = "https://c.test/pdf.jpg",
                    format = null,
                    downloadUrl = null,
                ),
            ),
        )

        val book = opdsPageToCatalogBooks(SOURCE, page).single()
        assertTrue(book.notImported)
        assertNull(book.localFormat)
        // No file URL: the cover keeps the card keyed and unique.
        assertEquals("https://c.test/pdf.jpg", book.bookUrl)
    }

    @Test
    fun `feed order is kept and never re-sorted`() {
        val page = page(
            books = listOf(
                book("Zed", "https://c.test/zed.epub"),
                book("alpha", "https://c.test/alpha.epub"),
                book("Mid", "https://c.test/mid.epub"),
            ),
        )

        // The feed declares its order through sort links; a client re-rank
        // would contradict them.
        val mapped = applyCatalogFilters(
            books = opdsPageToCatalogBooks(SOURCE, page),
            filters = CatalogFilters(sort = CatalogSort.Title, descending = false),
            sourceOrderIsLatest = false,
            sortEnabled = false,
        )

        assertEquals(listOf("Zed", "alpha", "Mid"), mapped.map(CatalogBook::title))
    }

    @Test
    fun `a card without a file or cover url stays unique per source`() {
        val page = page(
            books = listOf(
                OpdsBook("Pdf", null, null, null, null, null),
                OpdsBook("Pdf", null, null, null, null, null),
            ),
        )

        val urls = opdsPageToCatalogBooks(SOURCE, page).map(CatalogBook::bookUrl)

        assertEquals(listOf("$SOURCE#https://c.test/root#0", "$SOURCE#https://c.test/root#1"), urls)
    }

    private fun page(books: List<OpdsBook>) = OpdsPage(
        url = "https://c.test/root",
        title = "Catalog",
        links = emptyList(),
        books = books,
        nextUrl = null,
        searchTemplate = null,
    )

    private fun book(title: String, downloadUrl: String) = OpdsBook(
        title = title,
        author = null,
        summary = null,
        coverUrl = null,
        format = OpdsFormat.EPUB,
        downloadUrl = downloadUrl,
    )

    private companion object {
        const val SOURCE = "opds:catalog"
    }
}
