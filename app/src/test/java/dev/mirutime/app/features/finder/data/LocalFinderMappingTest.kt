package dev.mirutime.app.features.finder.data

import dev.mirutime.app.features.finder.domain.CatalogFilters
import dev.mirutime.app.features.finder.domain.CatalogSort
import dev.mirutime.app.features.finder.domain.FilterPreset
import dev.mirutime.app.features.finder.domain.LocalBook
import dev.mirutime.app.features.finder.domain.LocalSources
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Local catalogue pages come from DB rows through LocalBook; keep that
// mapping pure so it is testable without Android/Room.
class LocalFinderMappingTest {

    private val books = listOf(
        LocalBook("local://a", "alpha.epub", "Alpha", "EPUB", "1.0 MB", 10),
        LocalBook("local://b", "beta.fb2", "Beta", "FB2", "2.0 MB", 20),
        LocalBook("local://c", "gamma.cbz", "Gamma", "CBZ", "3.0 MB", 30),
    )

    @Test
    fun `page maps local books to catalogue books and paginates`() {
        val first = localCatalogPage(books, CatalogFilters(), page = 1, pageSize = 2)
        assertEquals(listOf("local://a", "local://b"), first.books.map { it.bookUrl })
        assertTrue(first.hasMore)

        val second = localCatalogPage(books, CatalogFilters(), page = 2, pageSize = 2)
        assertEquals(listOf("local://c"), second.books.map { it.bookUrl })
        assertFalse(second.hasMore)
        assertEquals(30, second.books.single().chapters)
    }

    @Test
    fun `query filters by title and original file name`() {
        assertEquals(
            listOf("local://b"),
            localCatalogPage(books, CatalogFilters(query = "bet"), 1).books.map { it.bookUrl },
        )
        assertEquals(
            listOf("local://c"),
            localCatalogPage(books, CatalogFilters(query = "gamma.cbz"), 1).books.map { it.bookUrl },
        )
    }

    @Test
    fun `local books have no plugin genres or status`() {
        assertTrue(localCatalogPage(books, CatalogFilters(genres = setOf("Fantasy")), 1).books.isEmpty())
        assertTrue(localCatalogPage(books, CatalogFilters(ongoingOnly = true), 1).books.isEmpty())
    }

    @Test
    fun `title sort lowercases and search caps the result count`() {
        val sorted = localCatalogPage(
            books,
            CatalogFilters(sort = CatalogSort.Title, descending = false),
            1,
        )
        assertEquals(listOf("Alpha", "Beta", "Gamma"), sorted.books.map { it.title })

        assertEquals(3, localSearch(books, "a").size)
        assertEquals(listOf("local://b"), localSearch(books, "Beta").map { it.bookUrl })

        val many = (1..20).map {
            LocalBook("local://$it", "f$it.epub", "Book $it", "EPUB", "1 MB", 1)
        }
        assertEquals(12, localSearch(many, "").size)
    }

    @Test
    fun `local source rows use stable ids, real counts and persisted pins`() {
        val rows = localSourceRows(
            pinned = setOf(LocalSources.Manga),
            booksCount = 4,
            mangaCount = 2,
        )

        assertEquals(listOf(LocalSources.Books, LocalSources.Manga), rows.map { it.id })
        assertTrue(rows.all { it.local })
        assertEquals(listOf(4, 2), rows.map { it.libraryCount })
        assertEquals(listOf(false, true), rows.map { it.pinned })
    }

    @Test
    fun `preset merge replaces by name case-insensitively and caps the list`() {
        val current = (1..FilterPreset.Max).map { FilterPreset("Preset $it", CatalogFilters()) }

        val replaced = mergePresets(current, FilterPreset("preset 3", CatalogFilters(query = "q")))
        assertEquals(FilterPreset.Max, replaced.size)
        assertEquals("preset 3", replaced.first().name)
        assertEquals("q", replaced.first().filters.query)
        assertEquals(1, replaced.count { it.name.equals("preset 3", ignoreCase = true) })

        val overflow = (1..FilterPreset.Max + 1).map { FilterPreset("P$it", CatalogFilters()) }
        assertEquals(FilterPreset.Max, mergePresets(overflow, FilterPreset("New", CatalogFilters())).size)
    }

    @Test
    fun `install id and human size stay stable`() {
        assertEquals("local-manga", localInstallId("manga"))
        assertEquals("local-books", localInstallId("books"))
        assertEquals("512 B", formatFileSize(512, Locale.US))
        assertEquals("1.0 KB", formatFileSize(1024, Locale.US))
        assertEquals("5.0 MB", formatFileSize(5L * 1024 * 1024, Locale.US))
    }
}
