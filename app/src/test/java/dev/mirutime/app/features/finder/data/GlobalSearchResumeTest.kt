package dev.mirutime.app.features.finder.data

import dev.mirutime.app.features.finder.domain.CatalogBook
import dev.mirutime.app.features.finder.domain.CatalogSource
import dev.mirutime.app.features.library.domain.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Pure resume decision for the global search cache: which answers can be
// restored, which targets still need to be asked, and when an empty finished
// snapshot is stale.
class GlobalSearchResumeTest {

    private fun source(id: String, local: Boolean = false) = CatalogSource(
        id = id,
        name = id,
        language = "EN",
        type = ContentType.Novel,
        version = "1.0.0",
        pinned = false,
        local = local,
    )

    private fun book(url: String) = CatalogBook(
        bookUrl = url,
        title = url,
        rating = 0f,
        chapters = 0,
        genres = emptyList(),
        ongoing = false,
    )

    private fun snapshot(
        results: List<SourceResult>,
        total: Int,
        done: Int,
    ) = FinderSearchCache.GlobalSnapshot(
        key = searchCacheKey("dune", null),
        results = results,
        total = total,
        done = done,
        scrollIndex = 2,
        scrollOffset = 5,
    )

    @Test
    fun `no cache seeds nothing and asks every target`() {
        val a = source("a")
        val b = source("b")

        val resume = resumeGlobalSearch(null, listOf(a, b))

        assertTrue(resume.results.isEmpty())
        assertEquals(listOf("a", "b"), resume.missing.map { it.id })
        assertEquals(2, resume.total)
        assertEquals(0, resume.done)
        assertFalse(resume.staleEmpty)
    }

    @Test
    fun `partial snapshot keeps answers and asks only pending targets`() {
        val a = source("a")
        val b = source("b")
        val snap = snapshot(
            results = listOf(SourceResult(a, listOf(book("a-1")), failed = false)),
            total = 2,
            done = 1,
        )

        val resume = resumeGlobalSearch(snap, listOf(a, b))

        assertEquals(listOf("a"), resume.results.map { it.source.id })
        assertEquals(listOf("b"), resume.missing.map { it.id })
        assertEquals(2, resume.total)
        assertEquals(1, resume.done)
        assertFalse(resume.staleEmpty)
    }

    @Test
    fun `finished snapshot with books is replayed whole`() {
        val a = source("a")
        val b = source("b")
        val snap = snapshot(
            results = listOf(
                SourceResult(a, listOf(book("a-1")), failed = false),
                SourceResult(b, emptyList(), failed = false),
            ),
            total = 2,
            done = 2,
        )

        val resume = resumeGlobalSearch(snap, listOf(a, b))

        assertEquals(listOf("a", "b"), resume.results.map { it.source.id })
        assertTrue(resume.missing.isEmpty())
        assertEquals(2, resume.total)
        assertEquals(2, resume.done)
        assertFalse(resume.staleEmpty)
    }

    @Test
    fun `failed source is retried while successful answers are kept`() {
        val a = source("a")
        val b = source("b")
        val snap = snapshot(
            results = listOf(
                SourceResult(a, listOf(book("a-1")), failed = false),
                SourceResult(b, emptyList(), failed = true),
            ),
            total = 2,
            done = 2,
        )

        val resume = resumeGlobalSearch(snap, listOf(a, b))

        assertEquals(listOf("a"), resume.results.map { it.source.id })
        assertEquals(listOf("b"), resume.missing.map { it.id })
        assertEquals(2, resume.total)
        assertEquals(1, resume.done)
        assertFalse(resume.staleEmpty)
    }

    @Test
    fun `finished empty snapshot is stale and runs again`() {
        val a = source("a")
        val b = source("b")
        val snap = snapshot(
            results = listOf(
                SourceResult(a, emptyList(), failed = false),
                SourceResult(b, emptyList(), failed = true),
            ),
            total = 2,
            done = 2,
        )
        assertTrue(snap.finishedEmpty)

        val resume = resumeGlobalSearch(snap, listOf(a, b))

        assertTrue(resume.results.isEmpty())
        assertEquals(listOf("a", "b"), resume.missing.map { it.id })
        assertEquals(2, resume.total)
        assertEquals(0, resume.done)
        assertTrue(resume.staleEmpty)
    }

    @Test
    fun `partial empty snapshot is not stale`() {
        val a = source("a")
        val b = source("b")
        val snap = snapshot(
            results = listOf(SourceResult(a, emptyList(), failed = false)),
            total = 2,
            done = 1,
        )
        assertFalse(snap.finishedEmpty)

        val resume = resumeGlobalSearch(snap, listOf(a, b))

        assertEquals(listOf("a"), resume.results.map { it.source.id })
        assertEquals(listOf("b"), resume.missing.map { it.id })
        assertFalse(resume.staleEmpty)
    }

    @Test
    fun `removed source answers are dropped and total is recomputed`() {
        val a = source("a")
        val removed = source("gone")
        val snap = snapshot(
            results = listOf(
                SourceResult(a, listOf(book("a-1")), failed = false),
                SourceResult(removed, listOf(book("gone-1")), failed = false),
            ),
            total = 2,
            done = 2,
        )

        val resume = resumeGlobalSearch(snap, listOf(a))

        assertEquals(listOf("a"), resume.results.map { it.source.id })
        assertTrue(resume.missing.isEmpty())
        assertEquals(1, resume.total)
        assertEquals(1, resume.done)
        assertFalse(resume.staleEmpty)
    }

    @Test
    fun `newly installed target is missing while old answers stay`() {
        val a = source("a")
        val b = source("b")
        val fresh = source("fresh")
        val snap = snapshot(
            results = listOf(
                SourceResult(a, listOf(book("a-1")), failed = false),
                SourceResult(b, emptyList(), failed = false),
            ),
            total = 2,
            done = 2,
        )

        val resume = resumeGlobalSearch(snap, listOf(a, b, fresh))

        assertEquals(listOf("a", "b"), resume.results.map { it.source.id })
        assertEquals(listOf("fresh"), resume.missing.map { it.id })
        assertEquals(3, resume.total)
        assertEquals(2, resume.done)
    }

    @Test
    fun `local answers are never replayed while plugin answers are reused`() {
        val plugin = source("plugin")
        val local = source("local-books", local = true)
        val snap = snapshot(
            results = listOf(
                SourceResult(plugin, listOf(book("plugin-1")), failed = false),
                SourceResult(local, listOf(book("local-1")), failed = false),
            ),
            total = 2,
            done = 2,
        )

        val resume = resumeGlobalSearch(snap, listOf(plugin, local))

        assertEquals(listOf("plugin"), resume.results.map { it.source.id })
        assertTrue(resume.results.none { r -> r.books.any { it.bookUrl == "local-1" } })
        assertEquals(listOf("local-books"), resume.missing.map { it.id })
        assertEquals(2, resume.total)
        assertEquals(1, resume.done)
        assertFalse(resume.staleEmpty)
    }

    @Test
    fun `all-local snapshot is re-queried entirely`() {
        val books = source("local-books", local = true)
        val manga = source("local-manga", local = true)
        val snap = snapshot(
            results = listOf(
                SourceResult(books, listOf(book("local-book")), failed = false),
                SourceResult(manga, listOf(book("local-manga")), failed = false),
            ),
            total = 2,
            done = 2,
        )

        val resume = resumeGlobalSearch(snap, listOf(books, manga))

        assertTrue(resume.results.isEmpty())
        assertEquals(listOf("local-books", "local-manga"), resume.missing.map { it.id })
        assertEquals(2, resume.total)
        assertEquals(0, resume.done)
        assertFalse(resume.staleEmpty)
    }

    @Test
    fun `empty local answer is re-queried`() {
        val plugin = source("plugin")
        val local = source("local-books", local = true)
        val snap = snapshot(
            results = listOf(
                SourceResult(plugin, listOf(book("plugin-1")), failed = false),
                SourceResult(local, emptyList(), failed = false),
            ),
            total = 2,
            done = 2,
        )

        val resume = resumeGlobalSearch(snap, listOf(plugin, local))

        assertEquals(listOf("plugin"), resume.results.map { it.source.id })
        assertEquals(listOf("local-books"), resume.missing.map { it.id })
        assertEquals(2, resume.total)
        assertEquals(1, resume.done)
    }

    @Test
    fun `failed local answer is re-queried and its books are not replayed`() {
        val plugin = source("plugin")
        val local = source("local-books", local = true)
        val snap = snapshot(
            results = listOf(
                SourceResult(plugin, listOf(book("plugin-1")), failed = false),
                SourceResult(local, listOf(book("local-stale")), failed = true),
            ),
            total = 2,
            done = 2,
        )

        val resume = resumeGlobalSearch(snap, listOf(plugin, local))

        assertEquals(listOf("plugin"), resume.results.map { it.source.id })
        assertEquals(listOf("local-books"), resume.missing.map { it.id })
        assertEquals(2, resume.total)
        assertEquals(1, resume.done)
        assertFalse(resume.staleEmpty)
    }
}
