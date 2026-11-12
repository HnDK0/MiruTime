package dev.mirutime.app.features.finder.data

import dev.mirutime.app.features.finder.domain.CatalogBook
import dev.mirutime.app.features.finder.domain.CatalogFacet
import dev.mirutime.app.features.finder.domain.CatalogFacetGroup
import dev.mirutime.app.features.finder.domain.CatalogFilters
import dev.mirutime.app.features.finder.domain.CatalogSection
import dev.mirutime.app.features.finder.domain.CatalogSortOption
import dev.mirutime.app.features.opds.domain.OpdsBook
import dev.mirutime.app.features.opds.domain.OpdsCatalogLink
import dev.mirutime.app.features.opds.domain.OpdsFailure
import dev.mirutime.app.features.opds.domain.OpdsPage
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.opds.OpdsFacet
import dev.mirutime.core.opds.OpdsFacetGroup
import dev.mirutime.core.opds.OpdsFormat
import dev.mirutime.core.opds.OpdsLink
import dev.mirutime.core.opds.OpdsSearchLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OpdsCatalogSessionTest {

    @Test
    fun `root loads once however often the screen asks`() = runTest {
        val harness = Harness(this)
        harness.feeds[null] = feed("https://c.test/root", books = listOf(book("One")))

        harness.session.ensureLoaded()
        advanceUntilIdle()
        harness.session.ensureLoaded()
        advanceUntilIdle()

        assertEquals(listOf<String?>(null), harness.requests)
        assertEquals(listOf("One"), harness.titles)
        assertEquals(1, harness.session.state.value.pagesLoaded)
        assertFalse(harness.session.state.value.canGoBack)
    }

    @Test
    fun `a section pushes a feed and back restores it without a refetch`() = runTest {
        val harness = Harness(this)
        harness.feeds[null] = feed(
            url = "https://c.test/root",
            links = listOf(OpdsCatalogLink("Authors", "https://c.test/authors", subtitle = "12")),
        )
        harness.feeds["https://c.test/authors"] = feed(
            url = "https://c.test/authors",
            books = listOf(book("Writer")),
        )

        harness.session.ensureLoaded()
        advanceUntilIdle()
        assertEquals(
            listOf(CatalogSection("Authors", "12", "https://c.test/authors")),
            harness.session.state.value.sections,
        )

        harness.session.openSection("https://c.test/authors")
        advanceUntilIdle()
        assertEquals(listOf("Writer"), harness.titles)
        assertTrue(harness.session.state.value.canGoBack)
        assertTrue(harness.session.state.value.sections.isEmpty())

        harness.session.goBack()
        advanceUntilIdle()
        assertTrue(harness.titles.isEmpty())
        assertFalse(harness.session.state.value.canGoBack)
        assertEquals(1, harness.session.state.value.sections.size)
        // Back is served from the stack: only the root and the section fetched.
        assertEquals(listOf(null, "https://c.test/authors"), harness.requests)
    }

    @Test
    fun `next page follows the feed's next url once`() = runTest {
        val harness = Harness(this)
        harness.feeds[null] = feed("https://c.test/root", books = listOf(book("One")), next = "https://c.test/2")
        harness.feeds["https://c.test/2"] = feed("https://c.test/2", books = listOf(book("Two")))

        harness.session.ensureLoaded()
        advanceUntilIdle()
        harness.session.requestNext()
        advanceUntilIdle()

        assertEquals(listOf(null, "https://c.test/2"), harness.requests)
        assertEquals(listOf("One", "Two"), harness.titles)
        assertEquals(2, harness.session.state.value.pagesLoaded)
        assertFalse(harness.session.state.value.hasMore)

        // No nextUrl left: no request, no state change.
        harness.session.requestNext()
        advanceUntilIdle()
        assertEquals(2, harness.requests.size)
    }

    @Test
    fun `search pushes a feed and back returns to the feed it started from`() = runTest {
        val harness = Harness(this)
        harness.feeds[null] = feed(
            url = "https://c.test/root",
            books = listOf(book("One")),
            search = OpdsSearchLink("https://c.test/search?q={searchTerms}", "application/atom+xml"),
        )
        harness.searchTemplate = "https://c.test/search?q={searchTerms}"
        harness.feeds["https://c.test/search?q=war+and+peace"] = feed(
            url = "https://c.test/search?q=war+and+peace",
            books = listOf(book("War")),
        )

        harness.session.ensureLoaded()
        advanceUntilIdle()
        assertTrue(harness.session.state.value.searchAvailable)

        // A blank query is the caller's "clear search", answered by goBack.
        harness.session.search("   ")
        advanceUntilIdle()
        assertEquals(1, harness.requests.size)

        harness.session.search("war and peace")
        advanceUntilIdle()
        assertEquals("https://c.test/search?q=war+and+peace", harness.requests.last())
        assertEquals(listOf("War"), harness.titles)
        assertTrue(harness.session.state.value.canGoBack)

        harness.session.goBack()
        advanceUntilIdle()
        assertEquals(listOf("One"), harness.titles)
        assertFalse(harness.session.state.value.canGoBack)
    }

    @Test
    fun `retry reloads the current feed rather than the root`() = runTest {
        val harness = Harness(this)
        harness.feeds[null] = feed(
            url = "https://c.test/root",
            books = listOf(book("Root")),
            links = listOf(OpdsCatalogLink("Authors", "https://c.test/authors")),
        )
        harness.feeds["https://c.test/authors"] = feed(
            url = "https://c.test/authors",
            books = listOf(book("Writer")),
        )

        harness.session.ensureLoaded()
        advanceUntilIdle()
        harness.session.openSection("https://c.test/authors")
        advanceUntilIdle()

        harness.feeds["https://c.test/authors"] = feed(
            url = "https://c.test/authors",
            books = listOf(book("Writer Two")),
        )
        harness.session.retry()
        advanceUntilIdle()

        assertEquals(listOf(null, "https://c.test/authors", "https://c.test/authors"), harness.requests)
        assertEquals(listOf("Writer Two"), harness.titles)
        assertEquals(1, harness.session.state.value.pagesLoaded)
        // The root is still on the stack under the reloaded feed.
        assertTrue(harness.session.state.value.canGoBack)
    }

    @Test
    fun `a failed push keeps the feed the user came from`() = runTest {
        val harness = Harness(this)
        harness.feeds[null] = feed(
            url = "https://c.test/root",
            books = listOf(book("Root")),
        )
        harness.failing += "https://c.test/broken"

        harness.session.ensureLoaded()
        advanceUntilIdle()
        harness.session.openSection("https://c.test/broken")
        advanceUntilIdle()

        assertEquals(listOf("Root"), harness.titles)
        assertNotNull(harness.session.state.value.error)
        assertFalse(harness.session.state.value.canGoBack)
        assertFalse(harness.session.state.value.loading)
    }

    @Test
    fun `retry after a failed push reopens the failed url`() = runTest {
        val harness = Harness(this)
        harness.feeds[null] = feed(
            url = "https://c.test/root",
            books = listOf(book("Root")),
        )
        harness.failing += "https://c.test/broken"

        harness.session.ensureLoaded()
        advanceUntilIdle()
        harness.session.openSection("https://c.test/broken")
        advanceUntilIdle()
        assertNotNull(harness.session.state.value.error)

        // The server recovers: Retry must go back to the failed URL, not
        // reload the parent layer the user already sees.
        harness.failing.clear()
        harness.feeds["https://c.test/broken"] = feed(
            url = "https://c.test/broken",
            books = listOf(book("Recovered")),
        )
        harness.session.retry()
        advanceUntilIdle()

        assertEquals(
            listOf(null, "https://c.test/broken", "https://c.test/broken"),
            harness.requests,
        )
        assertEquals(listOf("Recovered"), harness.titles)
        assertNull(harness.session.state.value.error)
        assertTrue(harness.session.state.value.canGoBack)
    }

    @Test
    fun `publication feed keeps links as books on the last page`() = runTest {
        val harness = Harness(this)
        harness.feeds[null] = feed(
            url = "https://c.test/root",
            links = listOf(OpdsCatalogLink("One", "https://c.test/book/1")),
            next = "https://c.test/2",
        )
        harness.feeds["https://c.test/2"] = feed(
            url = "https://c.test/2",
            links = listOf(OpdsCatalogLink("Two", "https://c.test/book/2")),
            next = null,
        )

        harness.session.ensureLoaded()
        advanceUntilIdle()
        harness.session.requestNext()
        advanceUntilIdle()

        // The last page has no nextUrl: it is still the result list started on
        // page 1, so its links stay books and never turn into sections.
        assertEquals(listOf("One", "Two"), harness.titles)
        assertTrue(harness.session.state.value.sections.isEmpty())
        assertFalse(harness.session.state.value.sectionsOnly)
    }

    @Test
    fun `a publication feed keeps its layer flag on the last page`() = runTest {
        val harness = Harness(this)
        harness.feeds[null] = feed(
            url = "https://c.test/root",
            links = listOf(OpdsCatalogLink("One", "https://c.test/book/1")),
            next = "https://c.test/2",
        )
        harness.feeds["https://c.test/2"] = feed(
            url = "https://c.test/2",
            links = listOf(OpdsCatalogLink("Two", "https://c.test/book/2")),
        )

        harness.session.ensureLoaded()
        advanceUntilIdle()
        harness.session.requestNext()
        advanceUntilIdle()

        // Page 1 has no flag of its own; page 2 inherits the layer's decision,
        // so its links keep caching entries and joining membership.
        assertEquals(
            listOf(null to null, "https://c.test/2" to true),
            harness.modes,
        )
    }

    @Test
    fun `an unpaged feed of opds publications maps its links as books`() = runTest {
        val harness = Harness(this)
        harness.feeds[null] = feed(
            url = "https://c.test/root",
            links = listOf(OpdsCatalogLink("One", "https://c.test/book/1.opds")),
        )

        harness.session.ensureLoaded()
        advanceUntilIdle()

        assertEquals(listOf("One"), harness.titles)
        assertTrue(harness.session.state.value.sections.isEmpty())
        assertFalse(harness.session.state.value.sectionsOnly)
    }

    @Test
    fun `a superseded push failure never hijacks retry`() = runTest {
        val harness = Harness(this)
        harness.feeds[null] = feed(url = "https://c.test/root", books = listOf(book("Root")))
        // Retry reloads the layer by its own url, not the null root alias.
        harness.feeds["https://c.test/root"] = harness.feeds.getValue(null)
        harness.session.ensureLoaded()
        advanceUntilIdle()

        // A push that stays in flight while a newer load starts and fails.
        val gate = CompletableDeferred<Unit>()
        harness.gate = "https://c.test/slow" to gate
        harness.failing += "https://c.test/slow"
        harness.failing += "https://c.test/root"
        harness.session.openSection("https://c.test/slow")
        advanceUntilIdle() // blocks on the gate
        harness.session.refresh()
        gate.complete(Unit)
        advanceUntilIdle()

        assertNotNull(harness.session.state.value.error)
        harness.failing.clear()
        harness.gate = null
        harness.session.retry()
        advanceUntilIdle()

        // Retry repeats the load that actually failed for the user, never the
        // superseded push whose failure arrived too late.
        assertEquals(
            listOf(null, "https://c.test/slow", "https://c.test/root", "https://c.test/root"),
            harness.requests,
        )
        assertEquals(listOf("Root"), harness.titles)
    }

    @Test
    fun `a navigation-only feed reports sections instead of an empty catalogue`() = runTest {
        val harness = Harness(this)
        harness.feeds[null] = feed(
            url = "https://c.test/root",
            links = listOf(OpdsCatalogLink("New", "https://c.test/new", subtitle = "729 new")),
        )

        harness.session.ensureLoaded()
        advanceUntilIdle()

        val state = harness.session.state.value
        assertTrue(state.books.isEmpty())
        assertTrue(state.sectionsOnly)
        assertEquals(listOf("New"), state.sections.map { it.title })
    }

    @Test
    fun `facets, sort links and the search link come from the current feed`() = runTest {
        val harness = Harness(this)
        harness.feeds[null] = feed(
            url = "https://c.test/root",
            books = listOf(book("One")),
            facets = listOf(
                OpdsFacetGroup(
                    label = "Genre",
                    options = listOf(OpdsFacet("Fantasy", "https://c.test/fantasy", active = true, count = 3)),
                ),
            ),
            sortLinks = listOf(
                OpdsLink("http://opds-spec.org/sort/popular", "https://c.test/popular", null, "Popular"),
                OpdsLink("http://opds-spec.org/sort/new", "https://c.test/sort/new", null, null),
            ),
            search = OpdsSearchLink("https://c.test/search?q={searchTerms}", null),
        )

        harness.session.ensureLoaded()
        advanceUntilIdle()

        val state = harness.session.state.value
        assertEquals(
            listOf(CatalogFacetGroup("Genre", listOf(CatalogFacet("Fantasy", "https://c.test/fantasy", true, 3)))),
            state.facets,
        )
        assertEquals(
            listOf("Popular", "new"),
            state.sortOptions.map(CatalogSortOption::label),
        )
        assertTrue(state.searchAvailable)
        assertFalse(state.sectionsOnly)
    }

    @Test
    fun `scroll is kept per stack layer`() = runTest {
        val harness = Harness(this)
        harness.feeds[null] = feed(
            url = "https://c.test/root",
            books = listOf(book("Root"), book("Second")),
            links = listOf(OpdsCatalogLink("Authors", "https://c.test/authors")),
        )
        harness.feeds["https://c.test/authors"] = feed(
            url = "https://c.test/authors",
            books = listOf(book("Writer")),
        )

        harness.session.ensureLoaded()
        advanceUntilIdle()
        harness.session.saveScroll("https://c.test/Second.epub", 40)
        harness.session.openSection("https://c.test/authors")
        advanceUntilIdle()
        harness.session.saveScroll("https://c.test/Writer.epub", 7)

        harness.session.goBack()
        advanceUntilIdle()

        val books = harness.session.state.value.books
        assertEquals(1, harness.session.scrollIndex(books))
        assertEquals(40, harness.session.scrollOffset())
    }

    @Test
    fun `one opds session serves every query of a source`() = runTest {
        val registry = CatalogSessionRegistry(
            page = { _, _, _ -> error("opds sessions never page through the provider") },
            opdsFeed = { _, _, _ -> Outcome.Ok(feed("https://c.test/root")) },
            newScope = { CoroutineScope(coroutineContext + Job()) },
        )

        val first = registry.get("opds:catalog", CatalogFilters())
        val searched = registry.get("opds:catalog", CatalogFilters(query = "war"))

        assertSame(first, searched)
        registry.closeAll()
    }

    private fun book(title: String) = OpdsBook(
        title = title,
        author = null,
        summary = null,
        coverUrl = null,
        format = OpdsFormat.EPUB,
        downloadUrl = "https://c.test/$title.epub",
    )

    private fun feed(
        url: String,
        books: List<OpdsBook> = emptyList(),
        links: List<OpdsCatalogLink> = emptyList(),
        next: String? = null,
        facets: List<OpdsFacetGroup> = emptyList(),
        sortLinks: List<OpdsLink> = emptyList(),
        search: OpdsSearchLink? = null,
    ) = OpdsPage(
        url = url,
        title = "Feed",
        links = links,
        books = books,
        nextUrl = next,
        searchTemplate = search?.href,
        facets = facets,
        sortLinks = sortLinks,
        searchLink = search,
    )

    private class Harness(scope: CoroutineScope) {
        val requests = mutableListOf<String?>()
        val modes = mutableListOf<Pair<String?, Boolean?>>()
        val feeds = mutableMapOf<String?, OpdsPage>()
        val failing = mutableSetOf<String>()
        var searchTemplate: String? = null
        var gate: Pair<String, CompletableDeferred<Unit>>? = null

        val session = OpdsCatalogSession(
            fetchFeed = { url, includeLinks ->
                requests += url
                modes += url to includeLinks
                gate?.let { (gated, deferred) -> if (url == gated) deferred.await() }
                when {
                    url in failing -> Outcome.Err(OpdsFailure.Unknown("failed"))
                    else -> feeds[url]?.let { Outcome.Ok(it) }
                        ?: Outcome.Err(OpdsFailure.Unknown("no-feed"))
                }
            },
            resolveSearchUrl = { _ -> Outcome.Ok(searchTemplate) },
            mapBooks = { page, includeLinks ->
                page.books.map { book ->
                    CatalogBook(
                        bookUrl = book.downloadUrl ?: book.title,
                        title = book.title,
                        rating = 0f,
                        chapters = 0,
                        genres = emptyList(),
                        ongoing = false,
                    )
                } + if (!includeLinks) {
                    emptyList()
                } else {
                    page.links.map { link ->
                        CatalogBook(
                            bookUrl = link.url,
                            title = link.title,
                            rating = 0f,
                            chapters = 0,
                            genres = emptyList(),
                            ongoing = false,
                        )
                    }
                }
            },
            scope = scope,
        )

        val titles: List<String> get() = session.state.value.books.map { it.title }
    }
}
