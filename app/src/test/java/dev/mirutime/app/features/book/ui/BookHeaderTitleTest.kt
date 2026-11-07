package dev.mirutime.app.features.book.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookHeaderTitleTest {

    @Test
    fun `the loaded page title wins over the cached index`() {
        assertEquals("Book", bookHeaderTitle("Book", "1234"))
    }

    @Test
    fun `a missing or blank snapshot keeps the index title`() {
        assertEquals("1234", bookHeaderTitle(null, "1234"))
        assertEquals("1234", bookHeaderTitle("   ", "1234"))
    }

    @Test
    fun `no title anywhere stays null`() {
        assertNull(bookHeaderTitle(null, null))
        assertNull(bookHeaderTitle("", ""))
    }
}
