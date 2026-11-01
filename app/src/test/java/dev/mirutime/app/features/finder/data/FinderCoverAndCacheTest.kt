package dev.mirutime.app.features.finder.data

import dev.mirutime.app.features.finder.domain.CatalogFilters
import dev.mirutime.app.features.finder.domain.CatalogSource
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.core.api.CatalogItem
import dev.mirutime.core.api.CatalogPage as CoreCatalogPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Pure-logic coverage for the finder fixes: cover-URL resolution and the
// navigation-surviving search cache. No Android types involved.
class FinderCoverAndCacheTest {

    @Before
    fun setUp() {
        FinderSearchCache.clear()
    }

    @Test
    fun `absolute cover urls pass through untouched`() {
        assertEquals(
            "https://cdn.site.com/a.jpg",
            resolveCoverUrl("https://cdn.site.com/a.jpg", "https://site.com"),
        )
        assertEquals(
            "http://cdn.site.com/a.jpg",
            resolveCoverUrl("http://cdn.site.com/a.jpg", "https://site.com"),
        )
    }

    @Test
    fun `protocol relative cover uses the base scheme`() {
        assertEquals(
            "https://cdn.site.com/a.jpg",
            resolveCoverUrl("//cdn.site.com/a.jpg", "https://site.com"),
        )
        assertEquals(
            "http://cdn.site.com/a.jpg",
            resolveCoverUrl("//cdn.site.com/a.jpg", "http://site.com"),
        )
    }

    @Test
    fun `protocol relative cover defaults to https without a base`() {
        assertEquals("https://cdn.site.com/a.jpg", resolveCoverUrl("//cdn.site.com/a.jpg", ""))
    }

    @Test
    fun `root and bare relative covers resolve against the base`() {
        assertEquals(
            "https://site.com/img/a.jpg",
            resolveCoverUrl("/img/a.jpg", "https://site.com"),
        )
        assertEquals(
            "https://site.com/image.jpg",
            resolveCoverUrl("image.jpg", "https://site.com/"),
        )
    }

    @Test
    fun `cover url resolution table`() {
        data class Case(val raw: String?, val base: String, val expected: String?)

        val cases = listOf(
            // Absolute, including an uppercase scheme: pass through untouched.
            Case("https://cdn.site.com/a.jpg", "https://s/manga", "https://cdn.site.com/a.jpg"),
            Case("http://cdn.site.com/a.jpg", "https://s/manga", "http://cdn.site.com/a.jpg"),
            Case("HTTPS://CDN.SITE.COM/A.JPG", "https://s/manga", "HTTPS://CDN.SITE.COM/A.JPG"),
            // Protocol-relative uses the base scheme (case-insensitively).
            Case("//cdn.site.com/a.jpg", "https://s/manga", "https://cdn.site.com/a.jpg"),
            Case("//cdn.site.com/a.jpg", "HTTPS://s/manga", "https://cdn.site.com/a.jpg"),
            Case("//cdn.site.com/a.jpg", "", "https://cdn.site.com/a.jpg"),
            // Root-relative drops the base path but keeps the authority.
            Case("/ch/1", "https://s/manga", "https://s/ch/1"),
            Case("/img/a.jpg", "https://s/manga/", "https://s/img/a.jpg"),
            // Bare relative nests under the base path even without a trailing
            // slash on the base.
            Case("ch/1", "https://s/manga", "https://s/manga/ch/1"),
            Case("ch/1", "https://s/manga/", "https://s/manga/ch/1"),
            Case("image.jpg", "https://site.com", "https://site.com/image.jpg"),
            // Query/fragment-only attach to the base itself.
            Case("?p=2", "https://s/manga", "https://s/manga?p=2"),
            Case("#frag", "https://s/manga", "https://s/manga#frag"),
            // Blank cover is null; blank base leaves the raw value untouched.
            Case("   ", "https://s/manga", null),
            Case(null, "https://s/manga", null),
            Case("img/a.jpg", "", "img/a.jpg"),
        )

        cases.forEach { case ->
            assertEquals(
                "raw=${case.raw} base=${case.base}",
                case.expected,
                resolveCoverUrl(case.raw, case.base),
            )
        }
    }

    @Test
    fun `blank cover is null and unresolvable relative passes through`() {
        assertNull(resolveCoverUrl(null, "https://site.com"))
        assertNull(resolveCoverUrl("   ", "https://site.com"))
        assertEquals("img/a.jpg", resolveCoverUrl("img/a.jpg", ""))
    }

    @Test
    fun `mapCatalogPage resolves the cover against the base url`() {
        val page = CoreCatalogPage(
            items = listOf(CatalogItem(title = "T", url = "https://site.com/1", coverUrl = "/c/1.jpg")),
            hasNext = false,
        )

        val books = mapCatalogPage(page, baseUrl = "https://site.com")

        assertEquals("https://site.com/c/1.jpg", books.single().coverUrl)
    }

    @Test
    fun `search cache key is stable and scope aware`() {
        assertEquals(searchCacheKey("dune", null), searchCacheKey("dune", null))
        assertTrue(searchCacheKey("dune", null) != searchCacheKey("dune", "Novel"))
        assertTrue(searchCacheKey("dune", null) != searchCacheKey("other", null))
    }

    @Test
    fun `global snapshot round trips and clears`() {
        val source = CatalogSource(
            id = "alpha",
            name = "Alpha",
            language = "EN",
            type = ContentType.Novel,
            version = "1.0.0",
            pinned = false,
        )
        val snapshot = FinderSearchCache.GlobalSnapshot(
            key = searchCacheKey("dune", null),
            results = listOf(SourceResult(source, emptyList(), failed = false)),
            total = 1,
            done = 1,
            scrollIndex = 3,
            scrollOffset = 7,
        )

        FinderSearchCache.putGlobal(snapshot)

        assertEquals(snapshot, FinderSearchCache.global(snapshot.key))
        assertNull(FinderSearchCache.global(searchCacheKey("other", null)))

        FinderSearchCache.clear()
        assertNull(FinderSearchCache.global(snapshot.key))
    }

    @Test
    fun `catalog snapshot round trips per source and filters`() {
        val key = CatalogCacheKey("alpha", CatalogFilters(query = "q"))
        val snapshot = FinderSearchCache.CatalogSnapshot(
            books = emptyList(),
            pagesLoaded = 2,
            hasMore = true,
            scrollIndex = 5,
            scrollOffset = 0,
        )

        FinderSearchCache.putCatalog(key, snapshot)

        assertEquals(snapshot, FinderSearchCache.catalog(key))
        assertNull(
            FinderSearchCache.catalog(CatalogCacheKey("beta", CatalogFilters(query = "q"))),
        )
    }

    @Test
    fun `mapCatalogPage scales a value per max rating`() {
        val page = CoreCatalogPage(
            items = listOf(
                CatalogItem(title = "A", url = "https://site.com/1", rating = "9.5/10"),
                CatalogItem(title = "B", url = "https://site.com/2", rating = "4.2"),
                CatalogItem(title = "C", url = "https://site.com/3", rating = "n/a"),
            ),
            hasNext = false,
        )

        val books = mapCatalogPage(page)

        assertEquals(4.75f, books[0].rating, 0.001f)
        assertEquals(4.2f, books[1].rating, 0.001f)
        assertEquals(0f, books[2].rating, 0.001f)
    }
}