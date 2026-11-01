package dev.mirutime.app.features.finder.data

import dev.mirutime.app.features.finder.domain.CatalogBook
import dev.mirutime.app.features.finder.domain.applyLibraryUrls
import dev.mirutime.app.features.finder.domain.setMembership
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinderMembershipMappingTest {

    private fun catalog(url: String, inLibrary: Boolean = false) = CatalogBook(
        bookUrl = url,
        title = url,
        rating = 0f,
        chapters = 0,
        genres = emptyList(),
        ongoing = false,
        inLibrary = inLibrary,
    )

    @Test
    fun `batch library urls map membership true and false`() {
        val books = listOf(
            catalog("https://site/in"),
            catalog("local://book/in"),
            catalog("local-file://abc"),
        )

        val mapped = books.applyLibraryUrls(setOf("https://site/in", "local://book/in"))

        assertEquals(listOf(true, true, false), mapped.map { it.inLibrary })
    }

    @Test
    fun `local-file entry stays false until the library contains its url`() {
        val entry = catalog("local-file://abc")
        assertFalse(entry.inLibrary)
        assertFalse(listOf(entry).applyLibraryUrls(emptySet()).single().inLibrary)
        assertTrue(
            listOf(entry).applyLibraryUrls(setOf("local-file://abc")).single().inLibrary,
        )
    }

    @Test
    fun `membership update touches only the requested url`() {
        val books = listOf(catalog("a"), catalog("b"))

        val updated = books.setMembership("b", true)

        assertEquals(listOf(false, true), updated.map { it.inLibrary })
        assertEquals(listOf("a", "b"), updated.map { it.bookUrl })
    }

    @Test
    fun `fake provider resolves batch membership and persists explicit writes`() = runTest {
        FinderFixtures.inLibrary.clear()
        val provider = FakeFinderDataProvider()
        val book = catalog("fake://site/book")

        assertEquals(emptySet<String>(), provider.libraryUrls(listOf(book.bookUrl)))
        assertTrue(provider.setInLibrary(book.bookUrl, true).success)
        assertEquals(setOf(book.bookUrl), provider.libraryUrls(listOf(book.bookUrl, "missing")))
        assertTrue(provider.setInLibrary(book.bookUrl, false).success)
        assertEquals(emptySet<String>(), provider.libraryUrls(listOf(book.bookUrl)))
        FinderFixtures.inLibrary.clear()
    }
}
