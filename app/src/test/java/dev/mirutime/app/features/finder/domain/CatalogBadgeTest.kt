package dev.mirutime.app.features.finder.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogBadgeTest {
    @Test
    fun `same source is a url match, other source is title only`() {
        val urls = setOf("u1")
        val titles = mapOf("X" to 2)

        assertEquals(LibraryBadgeState(true, 2), libraryBadgeState("u1", "X", urls, titles))
        assertEquals(LibraryBadgeState(false, 2), libraryBadgeState("u2", "X", urls, titles))
        assertNull(libraryBadgeState("u2", "Y", urls, titles))
    }

    @Test
    fun `one url match is at least one source`() {
        assertEquals(
            LibraryBadgeState(true, 1),
            libraryBadgeState("u1", "X", setOf("u1"), emptyMap()),
        )
    }

    @Test
    fun `membership application keeps the two fields in sync`() {
        val books = listOf(
            CatalogBook("u1", "X", 0f, 10, emptyList(), false),
            CatalogBook("u2", "X", 0f, 10, emptyList(), false),
            CatalogBook("u3", "Y", 0f, 10, emptyList(), false),
        )
        val applied = books.applyMembership(setOf("u1"), mapOf("X" to 2))

        assertEquals(true, applied[0].inLibrary)
        assertEquals(LibraryBadgeState(true, 2), applied[0].badge)
        // Title-only is a muted bookmark, not a membership this URL can remove.
        assertEquals(false, applied[1].inLibrary)
        assertEquals(LibraryBadgeState(false, 2), applied[1].badge)
        assertEquals(false, applied[2].inLibrary)
        assertNull(applied[2].badge)
    }

    @Test
    fun `adding from a title-only badge makes it same-source`() {
        val titleOnly = CatalogBook("u2", "X", 0f, 10, emptyList(), false)
            .copy(inLibrary = false, badge = LibraryBadgeState(false, 2))

        val added = listOf(titleOnly).setMembership("u2", true).single()

        assertEquals(true, added.inLibrary)
        assertEquals(LibraryBadgeState(true, 3), added.badge)
    }

    @Test
    fun `removing the last same-source copy leaves no badge`() {
        val one = CatalogBook("u1", "X", 0f, 10, emptyList(), false)
            .copy(inLibrary = true, badge = LibraryBadgeState(true, 1))

        val removed = listOf(one).setMembership("u1", false).single()

        assertEquals(false, removed.inLibrary)
        assertNull(removed.badge)
    }
}
