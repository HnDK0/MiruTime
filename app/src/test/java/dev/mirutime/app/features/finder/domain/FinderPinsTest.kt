package dev.mirutime.app.features.finder.domain

import dev.mirutime.app.features.library.domain.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinderPinsTest {

    @Test
    fun `pinned local sources sort above other pinned and unpinned sources`() {
        val sources = listOf(
            source("plugin-pinned", pinned = true),
            source("local-manga", local = true, pinned = true),
            source("plugin-free", pinned = false),
            source("local-books", local = true, pinned = false),
        )

        val sorted = sources.applyBrowse(BrowseFilters())

        assertEquals(
            listOf("local-manga", "plugin-pinned", "local-books", "plugin-free"),
            sorted.map { it.id },
        )
    }

    @Test
    fun `withPin flips one row and reverting restores it`() {
        val sources = listOf(
            source("local-books", local = true, pinned = true),
            source("plugin", pinned = false),
        )

        val optimistic = sources.withPin("plugin", pinned = true)
        assertTrue(optimistic.first { it.id == "plugin" }.pinned)
        assertTrue(optimistic.first { it.id == "local-books" }.pinned)

        val reverted = optimistic.withPin("plugin", pinned = false)
        assertFalse(reverted.first { it.id == "plugin" }.pinned)
    }

    private fun source(
        id: String,
        local: Boolean = false,
        pinned: Boolean,
    ) = CatalogSource(
        id = id,
        name = id,
        language = "",
        type = ContentType.Novel,
        version = "",
        pinned = pinned,
        local = local,
    )
}
