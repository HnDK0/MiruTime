package dev.mirutime.app.features.migration.domain

import dev.mirutime.app.features.book.domain.BookChapter
import dev.mirutime.app.features.finder.domain.CatalogBook
import dev.mirutime.app.features.finder.domain.CatalogSource
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.data.library.BookMigrationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationStateHelpersTest {
    @Test
    fun `chapter mapping falls back to the reader chapter key`() {
        val chapters = migrationChapters(
            "https://old.test/book",
            listOf(chapter(2, "Chapter 2", url = null), chapter(3, "Chapter 3", "https://old/x")),
        )

        assertEquals("https://old.test/book#chapter=2", chapters[0].url)
        assertEquals("https://old/x", chapters[1].url)
        assertEquals(listOf(2, 3), chapters.map { it.index })
    }

    @Test
    fun `local sources are never migration targets`() {
        val sources = listOf(
            source("local-books", "EPUB", local = true),
            source("plugin-a", "Source A"),
        )

        assertEquals(listOf("plugin-a"), availableMigrationTargets(sources).map { it.id })
    }

    @Test
    fun `best hit prefers exact title then containment then first`() {
        val exact = hit("plugin", "Book Title")
        val partial = hit("plugin", "Book Title Extra")
        val unrelated = hit("plugin", "Other")

        assertEquals(exact, bestMigrationHit(listOf(partial, exact), "book title"))
        assertEquals(partial, bestMigrationHit(listOf(partial, unrelated), "Book Title"))
        assertEquals(unrelated, bestMigrationHit(listOf(unrelated), "Book Title"))
        assertNull(bestMigrationHit(emptyList(), "Book Title"))
    }

    @Test
    fun `history groups by batch and shows the newest row`() {
        val older = row(id = 1, batch = "a", createdAt = 10L, status = "failed")
        val newer = row(id = 2, batch = "a", createdAt = 20L, status = "partial")
        val other = row(id = 3, batch = "b", createdAt = 15L, status = "completed")
        val legacy = row(id = 4, batch = null, createdAt = 5L, status = "completed")

        val groups = migrationHistoryGroups(listOf(older, newer, other, legacy))

        assertEquals(3, groups.size)
        assertEquals("a", groups.first().batchKey)
        assertEquals(2L, groups.first().latest.id)
        assertEquals(listOf(1L, 2L), groups.first().entries.map { it.id }.sorted())
        assertEquals("id:4", groups.last().batchKey)
    }

    @Test
    fun `book filter matches title author and source`() {
        val books = listOf(
            candidate("https://a", "Alpha", "Ann", "Source A"),
            candidate("https://b", "Beta", "Bob", "Source B"),
        )

        assertEquals(listOf("Alpha"), filterMigrationBooks(books, "ann").map { it.title })
        assertEquals(listOf("Beta"), filterMigrationBooks(books, "source b").map { it.title })
        assertTrue(filterMigrationBooks(books, "").size == 2)
        assertFalse(filterMigrationBooks(books, "missing").isNotEmpty())
    }

    @Test
    fun `auto search targets the trimmed library title`() {
        val book = candidate("https://a", "  Book Title  ", "Ann", "Source A")

        assertEquals("Book Title", autoMigrationSearchQuery(book))
    }

    @Test
    fun `auto search selects every installed source`() {
        val sources = listOf(
            source("plugin-a", "Source A"),
            source("plugin-b", "Source B"),
        )

        assertEquals(
            setOf("plugin-a", "plugin-b"),
            allMigrationSourceIds(sources),
        )
    }

    @Test
    fun `search card chapter count is unknown until the target details load`() {
        val hit = hit("plugin-a", "Book")

        assertNull(migrationVerifiedChapterCount(hit, selectedBookUrl = null, selectedChapterCount = 12))
        assertNull(migrationVerifiedChapterCount(hit, selectedBookUrl = "https://other", selectedChapterCount = 12))
        assertNull(migrationVerifiedChapterCount(hit, selectedBookUrl = hit.book.bookUrl, selectedChapterCount = null))
    }

    @Test
    fun `search card shows the verified target chapter count`() {
        val hit = hit("plugin-a", "Book")

        assertEquals(
            12,
            migrationVerifiedChapterCount(
                hit = hit,
                selectedBookUrl = hit.book.bookUrl,
                selectedChapterCount = 12,
            ),
        )
    }

    @Test
    fun `manual source selection toggles individual sources`() {
        val selected = setOf("plugin-a", "plugin-b")

        assertEquals(setOf("plugin-b"), toggleMigrationSource(selected, "plugin-a"))
        assertEquals(selected, toggleMigrationSource(setOf("plugin-b"), "plugin-a"))
    }

    @Test
    fun `manual search keeps only selected sources while auto search takes all`() {
        val sources = listOf(
            source("plugin-a", "Source A"),
            source("plugin-b", "Source B"),
        )
        val auto = allMigrationSourceIds(sources)
        val manual = toggleMigrationSource(auto, "plugin-b")

        assertTrue(allMigrationSourcesSelected(auto, sources))
        assertFalse(allMigrationSourcesSelected(manual, sources))
        assertEquals(listOf("plugin-a"), selectedMigrationSources(sources, manual).map { it.id })
        assertEquals(listOf("plugin-a", "plugin-b"), selectedMigrationSources(sources, auto).map { it.id })
    }

    @Test
    fun `manual source selection can be empty`() {
        val sources = listOf(source("plugin-a", "Source A"))

        assertTrue(toggleMigrationSource(setOf("plugin-a"), "plugin-a").isEmpty())
        assertFalse(allMigrationSourcesSelected(emptySet(), sources))
        assertTrue(selectedMigrationSources(sources, emptySet()).isEmpty())
    }

    @Test
    fun `hits group by source without dropping order or empty groups`() {
        val first = hit("plugin-a", "First", "Source A")
        val second = hit("plugin-b", "Second", "Source B")
        val third = hit("plugin-a", "Third", "Source A")

        val groups = groupMigrationHits(listOf(first, second, third))

        assertEquals(listOf("plugin-a", "plugin-b"), groups.map { it.sourceId })
        assertEquals(listOf("First", "Third"), groups[0].hits.map { it.book.title })
        assertEquals("Source A", groups[0].sourceName)
        assertEquals(listOf("Second"), groups[1].hits.map { it.book.title })
        assertTrue(groupMigrationHits(emptyList()).isEmpty())
    }

    private fun chapter(index: Int, title: String, url: String?) = BookChapter(
        index = index,
        title = title,
        isRead = false,
        downloaded = false,
        translated = false,
        downloading = false,
        url = url,
    )

    private fun source(id: String, name: String, local: Boolean = false) =
        CatalogSource(
            id = id,
            name = name,
            language = "EN",
            type = ContentType.Novel,
            version = "1",
            pinned = false,
            local = local,
        )

    private fun hit(
        sourceId: String,
        title: String,
        sourceName: String = "Source",
    ) = MigrationSearchHit(
        sourceId = sourceId,
        sourceName = sourceName,
        book = CatalogBook(
            bookUrl = "https://$title",
            title = title,
            rating = 0f,
            chapters = 3,
            genres = emptyList(),
            ongoing = true,
        ),
    )

    private fun candidate(url: String, title: String, author: String, source: String) =
        MigrationCandidateBook(
            bookId = 1,
            bookUrl = url,
            title = title,
            author = author,
            sourceId = source.lowercase().replace(" ", "-"),
            sourceName = source,
            unknownSource = false,
            chapterCount = 3,
        )

    private fun row(id: Long, batch: String?, createdAt: Long, status: String) =
        BookMigrationEntity(
            id = id,
            oldBookId = 1,
            newBookId = 2,
            oldInstallId = "old",
            newInstallId = "new",
            batchId = batch,
            status = status,
            createdAt = createdAt,
        )
}
