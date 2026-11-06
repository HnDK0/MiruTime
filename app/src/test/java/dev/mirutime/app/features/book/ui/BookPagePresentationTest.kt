package dev.mirutime.app.features.book.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BookPagePresentationTest {

    @Test
    fun `percent keeps honest precision and never fakes progress at zero`() {
        assertEquals("0", formatPercent(0, 1432))
        assertEquals("0.35", formatPercent(5, 1432))
        assertEquals("0.10", formatPercent(1, 1000))
        assertEquals("1.0", formatPercent(1, 100))
        assertEquals("10", formatPercent(1, 10))
        assertEquals("50", formatPercent(1, 2))
    }
}
