package dev.mirutime.app.features.finder.domain

import dev.mirutime.data.library.libraryBookKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogMembershipKeyTest {

    private fun book(installId: String) = CatalogBook(
        bookUrl = "https://s/1",
        title = "Book",
        rating = 0f,
        chapters = 10,
        genres = emptyList(),
        ongoing = false,
        installId = installId,
    )

    @Test
    fun `the same url from two installs only matches its own key`() {
        val first = book("install-a")
        val second = book("install-b")
        val keys = setOf(libraryBookKey("install-a", "https://s/1"))

        val applied = listOf(first, second).applyMembership(emptySet(), emptyMap(), keys)

        assertTrue(applied[0].inLibrary)
        assertFalse(applied[1].inLibrary)
    }

    @Test
    fun `a card without installId falls back to the url set`() {
        val local = book("")

        val applied = listOf(local).applyMembership(setOf("https://s/1"), emptyMap())

        assertTrue(applied.single().inLibrary)
    }

    @Test
    fun `optimistic setMembership matches the install key`() {
        val first = book("install-a")
        val second = book("install-b")

        val applied = listOf(first, second).setMembership(
            libraryBookKey("install-b", "https://s/1"),
            true,
        )

        assertFalse(applied[0].inLibrary)
        assertTrue(applied[1].inLibrary)
    }
}
