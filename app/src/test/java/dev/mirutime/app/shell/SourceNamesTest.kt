package dev.mirutime.app.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceNamesTest {
    private val plugins = mapOf(
        "novel-phoenix" to "Novel Phoenix",
    )

    @Test
    fun `local sources use their localized names`() {
        assertEquals(
            "Local books",
            sourceDisplayName("local-books", plugins::get, "Local books", "Local manga"),
        )
        assertEquals(
            "Local manga",
            sourceDisplayName("local-manga", plugins::get, "Local books", "Local manga"),
        )
    }

    @Test
    fun `plugins use display names and unknown ids fall back`() {
        assertEquals(
            "Novel Phoenix",
            sourceDisplayName("novel-phoenix", plugins::get, "Local books", "Local manga"),
        )
        assertEquals(
            "removed-plugin",
            sourceDisplayName("removed-plugin", plugins::get, "Local books", "Local manga"),
        )
    }

    @Test
    fun `opds sources use the saved catalogue name`() {
        assertEquals(
            "Archive.org",
            sourceDisplayName(
                "opds:cat-1",
                plugins::get,
                "Local books",
                "Local manga",
                opdsCatalogName = { id -> if (id == "cat-1") "Archive.org" else null },
            ),
        )
        // A removed catalogue keeps its id rather than showing a plugin name.
        assertEquals(
            "opds:gone",
            sourceDisplayName(
                "opds:gone",
                plugins::get,
                "Local books",
                "Local manga",
                opdsCatalogName = { null },
            ),
        )
    }
}
