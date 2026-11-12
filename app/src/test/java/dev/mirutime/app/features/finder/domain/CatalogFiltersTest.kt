package dev.mirutime.app.features.finder.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Pure page-local filter/sort matrix shared by plugin and local catalogues.
class CatalogFiltersTest {

    private fun book(
        url: String,
        title: String,
        rating: Float = 0f,
        genres: List<String> = emptyList(),
        ongoing: Boolean = false,
    ) = CatalogBook(
        bookUrl = url,
        title = title,
        rating = rating,
        chapters = 0,
        genres = genres,
        ongoing = ongoing,
    )

    @Test
    fun `query and genre are composed`() {
        val books = listOf(
            book("u1", "Dragon", genres = listOf("Fantasy")),
            book("u2", "Dragon 2", genres = listOf("SciFi")),
            book("u3", "Elf", genres = listOf("Fantasy")),
            book("u4", "Dragon 3"),
        )

        val result = applyCatalogFilters(
            books,
            CatalogFilters(query = "dragon", genres = setOf("fantasy")),
        )

        assertEquals(listOf("Dragon", "Dragon 3"), result.map { it.title })
    }

    @Test
    fun `genre filter is skipped when no book reports genres`() {
        val books = listOf(book("u1", "A"), book("u2", "B"))

        val result = applyCatalogFilters(books, CatalogFilters(genres = setOf("Fantasy")))

        assertEquals(books, result)
    }

    @Test
    fun `genre filter keeps books with unknown genres and matches any selected genre`() {
        val books = listOf(
            book("u1", "A", genres = listOf("Fantasy")),
            book("u2", "B", genres = listOf("SciFi")),
            book("u3", "C"),
        )

        val result = applyCatalogFilters(books, CatalogFilters(genres = setOf("Fantasy", "SciFi")))

        assertEquals(listOf("A", "B", "C"), result.map { it.title })
    }

    @Test
    fun `ongoing only keeps ongoing books`() {
        val books = listOf(book("u1", "A", ongoing = true), book("u2", "B"))

        val result = applyCatalogFilters(books, CatalogFilters(ongoingOnly = true))

        assertEquals(listOf("A"), result.map { it.title })
    }

    @Test
    fun `disabled sort keeps the feed order while still filtering`() {
        val books = listOf(
            book("u1", "Zed", ongoing = true),
            book("u2", "alpha"),
            book("u3", "Mid"),
        )

        val result = applyCatalogFilters(
            books = books,
            filters = CatalogFilters(sort = CatalogSort.Title, descending = false, ongoingOnly = true),
            sortEnabled = false,
        )

        assertEquals(listOf("Zed"), result.map { it.title })
    }

    @Test
    fun `popular preserves provider order descending and reverses ascending`() {
        val books = listOf(book("u1", "A"), book("u2", "B"), book("u3", "C"))

        assertEquals(
            listOf("A", "B", "C"),
            applyCatalogFilters(books, CatalogFilters(descending = true)).map { it.title },
        )
        assertEquals(
            listOf("C", "B", "A"),
            applyCatalogFilters(books, CatalogFilters(descending = false)).map { it.title },
        )
    }

    @Test
    fun `latest uses provider order when it is known and keeps it otherwise`() {
        val books = listOf(book("u1", "A"), book("u2", "B"), book("u3", "C"))
        val filters = CatalogFilters(sort = CatalogSort.Latest, descending = true)

        assertEquals(
            listOf("A", "B", "C"),
            applyCatalogFilters(books, filters, sourceOrderIsLatest = true).map { it.title },
        )
        assertEquals(
            listOf("C", "B", "A"),
            applyCatalogFilters(
                books,
                filters.copy(descending = false),
                sourceOrderIsLatest = true,
            ).map { it.title },
        )
        assertEquals(
            listOf("A", "B", "C"),
            applyCatalogFilters(books, filters, sourceOrderIsLatest = false).map { it.title },
        )
    }

    @Test
    fun `rating sorts both directions with deterministic title and url ties`() {
        val books = listOf(
            book("u3", "beta", rating = 4f),
            book("u2", "Alpha", rating = 4f),
            book("u1", "low", rating = 2f),
            book("u0", "Zed", rating = 5f),
        )

        assertEquals(
            listOf("Zed", "Alpha", "beta", "low"),
            applyCatalogFilters(
                books,
                CatalogFilters(sort = CatalogSort.Rating, descending = true),
            ).map { it.title },
        )
        assertEquals(
            listOf("low", "Alpha", "beta", "Zed"),
            applyCatalogFilters(
                books,
                CatalogFilters(sort = CatalogSort.Rating, descending = false),
            ).map { it.title },
        )
    }

    @Test
    fun `title sorts case insensitively with url tie break`() {
        val books = listOf(
            book("u2", "beta"),
            book("u3", "Alpha"),
            book("u1", "alpha"),
        )

        assertEquals(
            listOf("u1", "u3", "u2"),
            applyCatalogFilters(
                books,
                CatalogFilters(sort = CatalogSort.Title, descending = false),
            ).map { it.bookUrl },
        )
        assertEquals(
            listOf("u2", "u1", "u3"),
            applyCatalogFilters(
                books,
                CatalogFilters(sort = CatalogSort.Title, descending = true),
            ).map { it.bookUrl },
        )
    }

    @Test
    fun `query is not re-applied when the caller already searched`() {
        val books = listOf(book("u1", "A"), book("u2", "B"))

        val result = applyCatalogFilters(
            books,
            CatalogFilters(query = "a"),
            queryAlreadyApplied = true,
        )

        assertEquals(books, result)
    }

    @Test
    fun `plugin values count as active and participate in equality`() {
        val filters = CatalogFilters(
            pluginValues = mapOf(
                "genre_included" to listOf("action", "fantasy"),
                "order_ascending" to listOf("true"),
            ),
        )

        assertEquals(2, filters.activeFilterCount)
        assertEquals(filters, filters.copy())
        assertTrue(filters != CatalogFilters())
    }
}
