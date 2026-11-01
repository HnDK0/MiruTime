package dev.mirutime.app.features.migration.domain

import dev.mirutime.app.features.finder.domain.CatalogSource
import dev.mirutime.app.features.library.domain.CardStyle
import dev.mirutime.app.features.library.domain.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationFiltersTest {
    @Test
    fun `source filter narrows by query language and content type`() {
        val sources = listOf(
            source("a", "Alpha Reader", language = "EN", type = ContentType.Novel),
            source("b", "Alpha Manga", language = "RU", type = ContentType.Manga),
            source("c", "Beta Reader", language = "EN", type = ContentType.Novel),
        )

        assertEquals(
            listOf("a", "c"),
            filterMigrationSources(sources, MigrationSourceFilter(languages = setOf("EN")))
                .map { it.id },
        )
        assertEquals(
            listOf("b"),
            filterMigrationSources(sources, MigrationSourceFilter(types = setOf(ContentType.Manga)))
                .map { it.id },
        )
        assertEquals(
            listOf("a", "b"),
            filterMigrationSources(sources, MigrationSourceFilter(query = "alpha"))
                .map { it.id },
        )
        assertTrue(
            filterMigrationSources(
                sources,
                MigrationSourceFilter(
                    query = "alpha",
                    languages = setOf("RU"),
                    types = setOf(ContentType.Manga),
                ),
            ).map { it.id } == listOf("b"),
        )
    }

    @Test
    fun `selection is intersected with installed sources`() {
        val sources = listOf(source("a", "A"), source("b", "B"))

        assertEquals(
            setOf("a"),
            migrationSelectedSourceIds(setOf("a", "removed"), sources),
        )
        assertEquals(setOf("a", "b"), migrationSelectAllSources(sources))
    }

    @Test
    fun `select all adds visible books without dropping an existing selection`() {
        val books = listOf(candidate("https://a"), candidate("https://b"))
        val selected = setOf("https://kept")

        val all = migrationSelectAllBooks(selected, books)

        assertEquals(setOf("https://kept", "https://a", "https://b"), all)
        assertTrue(migrationAllBooksSelected(all, books))
        assertFalse(migrationAllBooksSelected(setOf("https://a"), books))
        assertFalse(migrationAllBooksSelected(emptySet(), emptyList()))
    }

    @Test
    fun `toggling a book is reversible and independent per book`() {
        val selected = migrationToggleBook(emptySet(), "https://a")
        assertEquals(setOf("https://a"), selected)
        assertTrue(migrationToggleBook(selected, "https://a").isEmpty())
    }

    @Test
    fun `list layout pins the horizontal card`() {
        val layout = migrationLibraryLayout(
            listLayout = "list",
            gridColumns = 4,
            cardStyle = CardStyle.Stats,
        )

        assertTrue(layout.list)
        assertTrue(layout.rows)
        assertEquals(CardStyle.Horizontal, layout.style)
    }

    @Test
    fun `grid keeps every card variant and clamps the column count`() {
        val below = migrationLibraryLayout("grid", 1, CardStyle.CoverTitle)
        val above = migrationLibraryLayout("grid", 9, CardStyle.Stats)

        assertFalse(below.list)
        assertEquals(2, below.columns)
        assertEquals(CardStyle.CoverTitle, below.style)
        assertFalse(below.rows)

        assertEquals(6, above.columns)
        assertEquals(CardStyle.Stats, above.style)
        assertFalse(above.rows)
    }

    @Test
    fun `horizontal card in grid renders rows`() {
        val layout = migrationLibraryLayout("grid", 3, CardStyle.Horizontal)

        assertFalse(layout.list)
        assertTrue(layout.rows)
    }

    @Test
    fun `library sources group books and keep one unknown bucket last`() {
        val books = listOf(
            candidate("https://a", sourceId = "plugin-a", sourceName = "Alpha"),
            candidate("https://b", sourceId = "plugin-a", sourceName = "Alpha"),
            candidate("https://c", sourceId = "plugin-b", sourceName = "Beta"),
            candidate(
                "https://d",
                sourceId = "gone",
                sourceName = "gone",
                unknown = true,
            ),
            candidate(
                "https://e",
                sourceId = "also-gone",
                sourceName = "also-gone",
                unknown = true,
            ),
        )
        val sources = listOf(
            source("plugin-b", "Beta", language = "RU"),
            source("plugin-a", "Alpha", language = "EN"),
        )

        val grouped = migrationLibrarySources(books, sources)

        assertEquals(listOf("plugin-a", "plugin-b", UnknownMigrationSourceKey), grouped.map { it.key })
        assertEquals(listOf(2, 1, 2), grouped.map { it.bookCount })
        assertEquals("EN", grouped[0].language)
        assertEquals("RU", grouped[1].language)
        assertTrue(grouped[2].unknown)
        assertEquals(
            listOf("https://a", "https://b"),
            migrationBooksForSource(books, "plugin-a").map { it.bookUrl },
        )
        assertEquals(
            listOf("https://d", "https://e"),
            migrationBooksForSource(books, UnknownMigrationSourceKey).map { it.bookUrl },
        )
        assertEquals(books, migrationBooksForSource(books, null))
    }

    private fun candidate(
        url: String,
        sourceId: String = "plugin",
        sourceName: String = "Source",
        unknown: Boolean = false,
    ) = MigrationCandidateBook(
        bookId = 1,
        bookUrl = url,
        title = "Book",
        author = "Author",
        sourceId = sourceId,
        sourceName = sourceName,
        unknownSource = unknown,
        chapterCount = 3,
    )

    private fun source(
        id: String,
        name: String,
        language: String = "EN",
        type: ContentType = ContentType.Novel,
    ) = CatalogSource(
        id = id,
        name = name,
        language = language,
        type = type,
        version = "1",
        pinned = false,
    )
}
