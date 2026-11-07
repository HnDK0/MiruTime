package dev.mirutime.data.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BookKeyTest {
    @Test
    fun `key round-trips install and url`() {
        val key = libraryBookKey("install-a", "https://site/book?q=1")

        assertEquals(
            BookIdentity("install-a", "https://site/book?q=1"),
            decodeLibraryBookKey(key),
        )
        assertEquals("https://site/book?q=1", bookKeyUrl(key))
        assertEquals("install-a", bookKeyInstallId(key))
    }

    @Test
    fun `plain urls stay plain and are not decoded`() {
        assertNull(decodeLibraryBookKey("https://site/book"))
        assertEquals("https://site/book", bookKeyUrl("https://site/book"))
        assertEquals("https://site/book", libraryBookKey("", "https://site/book"))
    }
}
