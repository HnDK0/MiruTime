package dev.mirutime.app.features.finder.data

import dev.mirutime.app.features.finder.domain.CatalogBook
import dev.mirutime.app.features.finder.domain.CatalogFilters
import dev.mirutime.app.features.finder.domain.CatalogPage
import dev.mirutime.app.features.finder.domain.OpdsSources
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSessionTest {

    private fun book(url: String) = CatalogBook(
        bookUrl = url,
        title = url,
        rating = 0f,
        chapters = 1,
        genres = emptyList(),
        ongoing = false,
    )

    private fun page(urls: List<String>, hasMore: Boolean) =
        CatalogPage(books = urls.map(::book), hasMore = hasMore)

    @Test
    fun `first page and the next page append in order`() = runTest {
        val pages = mapOf(
            1 to page(listOf("a", "b"), hasMore = true),
            2 to page(listOf("c"), hasMore = false),
        )
        val session = CatalogSession({ page, _ -> pages.getValue(page) }, this)

        session.ensureLoaded()
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), session.state.value.books.map { it.bookUrl })
        assertEquals(1, session.state.value.pagesLoaded)
        assertTrue(session.state.value.hasMore)

        session.requestNext()
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), session.state.value.books.map { it.bookUrl })
        assertEquals(2, session.state.value.pagesLoaded)
        assertFalse(session.state.value.hasMore)
    }

    @Test
    fun `an in-flight page still lands after deactivate`() = runTest {
        val second = CompletableDeferred<CatalogPage>()
        val session = CatalogSession(
            { page, _ ->
                if (page == 1) page(listOf("a"), hasMore = true) else second.await()
            },
            this,
        )

        session.ensureLoaded()
        advanceUntilIdle()
        session.requestNext()
        runCurrent()
        assertTrue(session.state.value.loading)

        session.deactivate()
        second.complete(page(listOf("b"), hasMore = false))
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), session.state.value.books.map { it.bookUrl })
        assertEquals(2, session.state.value.pagesLoaded)
        assertFalse(session.state.value.loading)
    }

    @Test
    fun `refresh resets the list and asks the loader to rescan`() = runTest {
        val refreshes = mutableListOf<Boolean>()
        val session = CatalogSession(
            { page, refresh ->
                refreshes += refresh
                when (page) {
                    1 -> CatalogPage(listOf(book("a")), hasMore = true)
                    else -> CatalogPage(listOf(book("b")), hasMore = false)
                }
            },
            this,
        )

        session.ensureLoaded()
        advanceUntilIdle()
        session.requestNext()
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), session.state.value.books.map { it.bookUrl })

        session.refresh(force = true)
        advanceUntilIdle()

        assertEquals(listOf(false, false, true), refreshes)
        assertEquals(listOf("a"), session.state.value.books.map { it.bookUrl })
        assertEquals(1, session.state.value.pagesLoaded)
    }

    @Test
    fun `membership rewrite replaces loaded cards and keeps the pager`() = runTest {
        val session = CatalogSession({ _, _ -> page(listOf("a", "b"), hasMore = true) }, this)

        session.ensureLoaded()
        advanceUntilIdle()
        session.updateBooks { books -> books.map { it.copy(inLibrary = true) } }

        assertEquals(listOf("a", "b"), session.state.value.books.map { it.bookUrl })
        assertTrue(session.state.value.books.all { it.inLibrary })
        assertEquals(1, session.state.value.pagesLoaded)
        assertTrue(session.state.value.hasMore)
    }

    @Test
    fun `repeated books are deduped when pages append`() = runTest {
        val session = CatalogSession(
            { page, _ ->
                when (page) {
                    1 -> CatalogPage(listOf(book("a"), book("b")), hasMore = true)
                    else -> CatalogPage(listOf(book("b"), book("c")), hasMore = false)
                }
            },
            this,
        )

        session.ensureLoaded()
        advanceUntilIdle()
        session.requestNext()
        advanceUntilIdle()

        assertEquals(listOf("a", "b", "c"), session.state.value.books.map { it.bookUrl })
    }

    @Test
    fun `a load error is surfaced and retry reloads`() = runTest {
        var fail = true
        val session = CatalogSession(
            { _, _ ->
                if (fail) error("boom") else CatalogPage(listOf(book("a")), hasMore = false)
            },
            this,
        )

        session.ensureLoaded()
        advanceUntilIdle()
        assertEquals("boom", session.state.value.error)

        fail = false
        session.retry()
        advanceUntilIdle()
        assertNull(session.state.value.error)
        assertEquals(listOf("a"), session.state.value.books.map { it.bookUrl })
    }

    @Test
    fun `membership updates are applied in place`() = runTest {
        val session = CatalogSession(
            { _, _ -> CatalogPage(listOf(book("a"), book("b")), hasMore = false) },
            this,
        )
        session.ensureLoaded()
        advanceUntilIdle()

        session.updateBook("a") { it.copy(inLibrary = true, notImported = false) }

        assertEquals(
            listOf(true, false),
            session.state.value.books.map { it.inLibrary },
        )
        assertFalse(session.state.value.books.first().notImported)
    }

    @Test
    fun `evicting one session does not cancel another`() = runTest {
        val registry = CatalogSessionRegistry(
            page = { _, _, pageNumber ->
                CatalogPage(listOf(book("book-$pageNumber")), hasMore = true)
            },
            newScope = { CoroutineScope(coroutineContext + Job()) },
        )
        val first = registry.get("source-1", CatalogFilters())
        val second = registry.get("source-2", CatalogFilters())
        first.ensureLoaded()
        second.ensureLoaded()
        advanceUntilIdle()
        assertEquals(1, first.state.value.pagesLoaded)
        assertEquals(1, second.state.value.pagesLoaded)

        // Push source-1 out of the LRU: only that session may be cancelled.
        for (index in 3..9) {
            registry.get("source-$index", CatalogFilters())
        }
        second.requestNext()
        advanceUntilIdle()

        assertEquals(2, second.state.value.pagesLoaded)
        assertEquals(listOf("book-1", "book-2"), second.state.value.books.map { it.bookUrl })
    }

    @Test
    fun `scroll is restored by book url`() = runTest {
        val session = CatalogSession({ _, _ -> CatalogPage(emptyList(), false) }, this)
        session.saveScroll("b", 40)

        val index = session.scrollIndex(listOf(book("a"), book("b"), book("c")))

        assertEquals(1, index)
        assertEquals(0, session.scrollIndex(listOf(book("a"))))
        assertEquals(40, session.scrollOffset())
    }

    @Test
    fun `deactivate does not clear the loaded list`() = runTest {
        val session = CatalogSession(
            { _, _ -> CatalogPage(listOf(book("a")), hasMore = false) },
            this,
        )

        session.ensureLoaded()
        advanceUntilIdle()
        session.deactivate()

        assertEquals(listOf("a"), session.state.value.books.map { it.bookUrl })
        assertFalse(session.state.value.active)
        session.activate()
        assertTrue(session.state.value.active)
    }

    @Test
    fun `reloading a source drops its other lists and keeps the one on screen`() = runTest {
        val registry = CatalogSessionRegistry(
            page = { _, _, pageNumber ->
                CatalogPage(listOf(book("book-$pageNumber")), hasMore = true)
            },
            newScope = { CoroutineScope(coroutineContext + Job()) },
        )
        val onScreen = registry.get("source-1", CatalogFilters())
        val searched = registry.get("source-1", CatalogFilters(query = "elf"))
        val otherSource = registry.get("source-2", CatalogFilters(query = "elf"))
        listOf(onScreen, searched, otherSource).forEach { it.ensureLoaded() }
        advanceUntilIdle()

        registry.invalidateSource("source-1", keep = CatalogFilters())

        assertSame(onScreen, registry.get("source-1", CatalogFilters()))
        assertNotSame(searched, registry.get("source-1", CatalogFilters(query = "elf")))
        assertSame(otherSource, registry.get("source-2", CatalogFilters(query = "elf")))
    }

    @Test
    fun `a base-url change drops every list of the source and keeps other sources`() = runTest {
        val registry = CatalogSessionRegistry(
            page = { _, _, pageNumber ->
                CatalogPage(listOf(book("book-$pageNumber")), hasMore = true)
            },
            newScope = { CoroutineScope(coroutineContext + Job()) },
        )
        val plain = registry.get("source-1", CatalogFilters())
        val searched = registry.get("source-1", CatalogFilters(query = "elf"))
        val otherSource = registry.get("source-2", CatalogFilters())
        listOf(plain, searched, otherSource).forEach { it.ensureLoaded() }
        advanceUntilIdle()

        registry.invalidateSource("source-1")

        assertNotSame(plain, registry.get("source-1", CatalogFilters()))
        assertNotSame(searched, registry.get("source-1", CatalogFilters(query = "elf")))
        assertSame(otherSource, registry.get("source-2", CatalogFilters()))
    }

    @Test
    fun `reloading a source cancels the loads of the lists it drops`() = runTest {
        val release = CompletableDeferred<CatalogPage>()
        val registry = CatalogSessionRegistry(
            page = { _, filters, _ ->
                if (filters.query.isEmpty()) page(listOf("on-screen"), hasMore = false) else release.await()
            },
            newScope = { CoroutineScope(coroutineContext + Job()) },
        )
        val onScreen = registry.get("source-1", CatalogFilters())
        val searched = registry.get("source-1", CatalogFilters(query = "elf"))
        onScreen.ensureLoaded()
        searched.ensureLoaded()
        runCurrent()
        assertTrue(searched.state.value.loading)

        registry.invalidateSource("source-1", keep = CatalogFilters())
        release.complete(page(listOf("late"), hasMore = false))
        advanceUntilIdle()

        assertEquals(emptyList<String>(), searched.state.value.books.map { it.bookUrl })
        assertEquals(listOf("on-screen"), onScreen.state.value.books.map { it.bookUrl })
    }

    @Test
    fun `a reload asks the source again for the page with the same search and filters`() = runTest {
        val asked = mutableListOf<Pair<CatalogFilters, Int>>()
        val fanfic = CatalogFilters(query = "elf", pluginValues = mapOf("mode" to listOf("fanfic")))
        val registry = CatalogSessionRegistry(
            page = { _, filters, pageNumber ->
                asked += filters to pageNumber
                CatalogPage(listOf(book("call-${asked.size}")), hasMore = true)
            },
            newScope = { CoroutineScope(coroutineContext + Job()) },
        )
        val session = registry.get("source-1", fanfic)
        session.ensureLoaded()
        advanceUntilIdle()

        registry.invalidateSource("source-1", keep = fanfic)
        session.refresh(force = true)
        advanceUntilIdle()

        assertEquals(listOf(fanfic to 1, fanfic to 1), asked)
        assertEquals(listOf("call-2"), session.state.value.books.map { it.bookUrl })
    }

    @Test
    fun `reloading an OPDS source keeps its session whatever query is on screen`() = runTest {
        val registry = CatalogSessionRegistry(
            page = { _, _, _ -> CatalogPage(emptyList(), hasMore = false) },
            newScope = { CoroutineScope(coroutineContext + Job()) },
        )
        val feed = OpdsSources.Prefix + "demo"
        // The feed ignores the query, so both keys name the same session.
        val onScreen = registry.get(feed, CatalogFilters(query = "elf"))

        registry.invalidateSource(feed, keep = CatalogFilters(query = "elf"))

        assertSame(onScreen, registry.get(feed, CatalogFilters(query = "elf")))
        assertSame(onScreen, registry.get(feed, CatalogFilters()))
    }
}
